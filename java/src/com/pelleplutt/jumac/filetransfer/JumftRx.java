package com.pelleplutt.jumac.filetransfer;

import com.pelleplutt.jumac.Jumac;
import com.pelleplutt.jumac.JumacTickable;

public abstract class JumftRx extends Jumft implements JumacTickable{
  Jumac u;
  volatile char sta_pkt_seqno;
  JumftStream str;
  int dt_min, dt_max, mtu;
  volatile long dt;
  short session;
  volatile boolean running;
  long acked_bytes;
  long length;
  volatile long rx_offset;
  volatile int rxbitmask;
  int rec_chunks;
  
  
  public JumftRx(Jumac u, JumftStream str, int dt_min, int dt_max, int mtu) {
    sta_pkt_seqno = 0xff;
    this.u = u;
    this.str = str;
    this.dt_min = dt_min;
    this.dt_max = dt_max;
    this.mtu = mtu;
  }
  
  void sendSta() {
    byte[] buf = new byte[12];
    buf[0] = UMFT_CMD_STATUS;
    from16bit(session, buf, 1);
    buf[3] = (byte)(running 
        ? (acked_bytes >= length ? UMFT_STA_FIN : UMFT_STA_OK)
        : UMFT_STA_ABORT);
    from32bit(rx_offset, buf, 4);
    from32bit(rxbitmask, buf, 8);
    int res = u.txPacket(true, buf, (short)12);
    if (res > 0) {
      sta_pkt_seqno = (char)res;
    } else {
      sta_pkt_seqno = 0xff;
    }
  }
  
//  boolean[] dbgWritten;
//  void dbgMarkWritten(long mtu) {
//    if (dbgWritten[(int)mtu]) throw new Error("writing twice at mtu 0x" + Integer.toHexString((int)mtu));
//    dbgWritten[(int)mtu] = true;
//  }
//  
//  void dbgDumpWritten() {
//    StringBuilder sb = new StringBuilder();
//    int cnt = 0;
//    for (int i = 0; i < dbgWritten.length; i++) {
//      sb.append(dbgWritten[i] ? 'X' : '-');
//      if (dbgWritten[i]) cnt++;
//    }
//    System.out.println("[RXER] [WR] " + sb.toString() + " " + cnt);
//  }
//
//  void dbgDumpRxStats() {
//    StringBuilder sb = new StringBuilder();
//    for (int i = 0; i < rx_offset; i++) {
//      sb.append('_');
//    }
//    for (int i = 0; i < 32; i++) {
//      sb.append((rxbitmask & (1<<i)) != 0 ? '|' : '.');
//    }
//    System.out.println("[RXER] [ST] " + sb.toString());
//  }

  
  
  public boolean rxPak(char seqno, byte[] data, int len, boolean req_ack) {
    if (len >=17 && data[0] == (byte)UMFT_CMD_SEND_FILE && req_ack) {
      length = to32bit(data, 1);
      int txmtu = to16bit(data, 5);
      long txdt_min = to32bit(data, 7);
      long txdt_max = to32bit(data, 11);
      int filenamelen = (int)(data[15] & 0xff);
      
      int mtu;
      long dt_min, dt_max;
      if (txdt_min == 0)            dt_min = this.dt_min;
      else if (this.dt_min == 0)    dt_min = txdt_min;
      else                          dt_min = this.dt_min > txdt_min ? this.dt_min : txdt_min;
      if (dt_min == 0)              dt_min = UMFT_DEF_DT_MIN;
      if (txdt_max == 0)            dt_max = this.dt_max;
      else if (this.dt_max == 0)    dt_max = txdt_max;
      else                          dt_max = this.dt_max < txdt_max ? this.dt_max : txdt_max;
      if (dt_max == 0)              dt_max = UMFT_DEF_DT_MAX;
      if (txmtu == 0)               mtu = this.mtu;
      else if (this.mtu == 0)       mtu = txmtu;
      else                          mtu = this.mtu < txmtu ? this.mtu : txmtu;
      if (mtu == 0)                 mtu = UMFT_DEF_MTU;
      this.mtu = mtu;
      
      boolean create = str.createData((int)length, filenamelen == 0 ? null : new String(data, 16, filenamelen));
      
      session = (short)(Math.random() * 0x10000);
      byte ack[] = new byte[14];
      ack[0] = UMFT_CMD_SEND_FILE;
      ack[1] = (byte)(create ? UMFT_STA_OK : UMFT_STA_ABORT);
      from16bit(mtu, ack, 2);
      from32bit(dt_min, ack, 4);
      from32bit(dt_max, ack, 8);
      from16bit(session, ack, 12);
      
      u.ackReply(ack, (short)14);

      this.dt = (dt_max - dt_min) / 4 + dt_min;

      requestFutureTick(dt_max * 16);
      
//      dbgWritten = new boolean[(int)length/mtu];

      running = create;
      
      return true;
    } else if (len >= 7 && data[0] == (byte)UMFT_CMD_DATA_CHUNK && !req_ack) {
      rec_chunks++;
      short session = (short)to16bit(data, 1);
      if (session != this.session) {
        return false;
      }
      long offset_mtu = to32bit(data, 3);
      do {
        if (!running)
          break;
        System.out.format("[RXER] [RX] chunk at offs:%04x, rx_offset:%04x, rx_bitmask:%08x, stored chunks:%04x\n", 
            offset_mtu, rx_offset, rxbitmask, acked_bytes / this.mtu);
//        dbgDumpRxStats();
        if (offset_mtu < rx_offset || offset_mtu >= rx_offset + 32) {
          System.out.format("[RXER] [RX] oob chunk at offs: %04x beyond %04x--%04x\n", 
              offset_mtu, rx_offset, rx_offset+32);
          break;
        }
        if ((rxbitmask & (1 << (offset_mtu - rx_offset))) != 0) {
          System.out.format("[RXER] [RX] dupl chunk at offs: %04x\n", offset_mtu);
          break;
        }
        boolean save_res = str.writeData(
            (int)(offset_mtu * this.mtu), 
            data,
            7,
            len - 7);
        if (save_res) {
          acked_bytes += len-7;
          long lrx_offset = this.rx_offset;
          int lrxbitmask = this.rxbitmask;
          if (lrx_offset == offset_mtu) {
            do {
              lrx_offset++;
              lrxbitmask >>>= 1;
            } while ((lrxbitmask & 1) != 0);
          } else {
            lrxbitmask |= (1 << (offset_mtu - lrx_offset));
          }
          this.rx_offset = lrx_offset;
          this.rxbitmask = lrxbitmask;
//          dbgMarkWritten(offset_mtu);
        }
//        dbgDumpWritten();

      } while (false);
      
      if (running) {
        cancelFutureTick();
        if (acked_bytes >= length) {
          running = false;
          sendSta();
          status(UMFT_STA_FIN);
        } else {
          if (rec_chunks >= 16) {
            rec_chunks = 0;
            sendSta();
          }
          requestFutureTick(dt * 16);
        }
      }
      return true;
    } else {
      return false;
    }
  }
  
  public boolean rxAck(char seqno, byte[] data, int len) {
    if (seqno != sta_pkt_seqno || data[0] != (byte)UMFT_CMD_STATUS || len != 12) {
      return false;
    }
    if (!running) {
      return true;
    }
    
    int sta = (int)(data[1]&0xff);
    long dt = to32bit(data, 2);
    
    if (sta != UMFT_STA_OK) {
      running = false;
      cancelFutureTick();
      status(sta);
    }
    this.dt = dt;
    return true;
  }

  public void tick() {
    if (running && acked_bytes < length) {
      sendSta();
      requestFutureTick(dt * 16);
    }
  }
  
  
  public abstract void cancelFutureTick();
  public abstract void requestFutureTick(long delta);
  public abstract void status(int result);
}
