package com.pelleplutt.jumac.filetransfer;

import com.pelleplutt.jumac.Jumac;
import com.pelleplutt.jumac.JumacTickable;

public abstract class JumftTx extends Jumft implements JumacTickable {
  Jumac u;
  volatile char req_pkt_seqno;
  JumftStream str;
  int dt, dt_min, dt_max, mtu, ddt;
  short session;
  volatile boolean running;
  long acked_bytes;
  long length;
  volatile long p_rxoffset;
  volatile int p_rxbitmask;
  volatile long tx_offset;
  volatile int txbitmask;
  
  
  public JumftTx(Jumac u,  long filelength, 
      int mtu, int dt_min, int dt_max, int ddt) {
    req_pkt_seqno = 0xff;
    this.u = u;
    this.dt_min = dt_min;
    this.dt_max = dt_max;
    this.ddt = ddt;
    this.mtu = mtu;
    this.length = filelength;
    this.dt = (dt_max - dt_min) / 4 + dt_min;
  }
  
  public int tx(JumftStream str, String filename) {
    req_pkt_seqno = 0xff;
    this.str = str;
    
    byte[] buf = new byte[17+256];
    buf[0] = UMFT_CMD_SEND_FILE;
    from32bit(length, buf, 1);
    from16bit(mtu, buf, 5);
    from32bit(dt_min, buf, 7);
    from32bit(dt_max, buf, 11);
    buf[15] = (byte)Math.min(255, filename.length());
    System.arraycopy(filename.getBytes(), 0, buf, 16, Math.min(255, filename.length()));
    
    int res = u.txPacket(true, buf, (short)(17 + Math.min(255, filename.length())));
    if (res > 0) {
      req_pkt_seqno = (char)res;
    } else {
      req_pkt_seqno = 0xff;
    }
    return res;
  }
  
  int sendNextChunk() {
    int res = Jumac.UMAC_OK;
    long ltx_offset = tx_offset;
    // TODO here, check if nothing is sent - txbitmask is left unchanged
    //      if this happens x times, the txbitmask could be reset to last
    //      received rxbitmask
    System.out.format("[TXER] [TX] send next chunk tx_offset:%04x tx_bitmask:%08x\n", ltx_offset, txbitmask);
    for (int i = 0; i < 32; i++) {
      if ((txbitmask & (1<<i)) == 0) {
        long mtu_offset = i + ltx_offset;
        System.out.format("[TXER] [TX] send next chunk select bit %02x, chunk %04x\n", i, (int)mtu_offset);
        byte[] buf = new byte[1+2+4+mtu];
        buf[0] = (byte)UMFT_CMD_DATA_CHUNK;
        from16bit(session, buf, 1);
        from32bit(mtu_offset, buf, 3);
        long file_offs = mtu_offset * mtu;
        if (file_offs >= length) {
          break;
        }
        long chunk_len = length - file_offs < mtu ? length - file_offs : mtu;
        str.readData((int)file_offs, buf, 7, (int)chunk_len);
        res = u.txPacket(false, buf, (short)(1+2+4+chunk_len));
        if (res == Jumac.UMAC_OK) {
          txbitmask |= (1<<i);
        }
        break;
      }
    }
    return res;
  }
  
  
  int _count_bits(long iv) {
    int count = 0;
    for(int i = 0; i < 32; ++i){
      if ((iv & (1<<i)) != 0) count++;
    }
    return count;
  }

  public boolean rxPak(char seqno, byte[] data, int len, boolean req_ack) {
    if (len != 12 || data[0] != UMFT_CMD_STATUS || !req_ack) {
      return false; // not for me
    }
    short sess_id = (short)to16bit(data, 1);
    if (sess_id != this.session) {
      return false; // not for me
    }
    
    
    int rxsta = (int)data[3] & 0xff;
    int rxoffset = (int)to32bit(data, 4);
    int rxbitmask = (int)to32bit(data, 8);
    
    System.out.format("[TXER] [ST] got status sta:%d offs:%04x bitm:%08x\n",  rxsta, rxoffset, rxbitmask);

    int ack_res;
    if (!this.running) {
      ack_res = UMFT_STA_ABORT;
    } else {
      switch (rxsta) {
      case UMFT_STA_OK:
        ack_res = UMFT_STA_OK;
        break;
      case UMFT_STA_FIN:
        ack_res = UMFT_STA_FIN;
        this.running = false;
        status(UMFT_STA_FIN);
        break;
      case UMFT_STA_ABORT:
      default:
        ack_res = UMFT_STA_ABORT;
        this.running = false;
        status(UMFT_STA_ABORT);
        break;
      }
    }
    // calculate packets that got acked from previous call
    int lp_rxbitmask = this.p_rxbitmask;
    long d_offs = 0;
    int acked_pkts = 0;
    
    if (rxoffset > this.p_rxoffset) {
      // offset shift, see what got acked in there
      d_offs = rxoffset - this.p_rxoffset;
      int adj_rxbitmask = rxbitmask << d_offs;
      // mark all shifted out as received
      adj_rxbitmask |= ((1<<d_offs)-1);
      // count all zeroes that became one
//      System.out.format("<<<<< TXER  offset shift:%04x bit(%08x & %08x = %08x) = %d\n",  d_offs,
//          adj_rxbitmask, (~lp_rxbitmask), adj_rxbitmask & (~lp_rxbitmask), _count_bits( adj_rxbitmask & (~lp_rxbitmask) ));
      acked_pkts += _count_bits( adj_rxbitmask & (~lp_rxbitmask) );
      // now shift away the delta from previous bitmask
      lp_rxbitmask >>>= d_offs;
    } else {
      // count all zeroes that became one
      acked_pkts += _count_bits(rxbitmask & (~lp_rxbitmask));
    }
    this.acked_bytes += acked_pkts * this.mtu;
//    System.out.format("<<<<< TXER                 bit(%08x & %08x = %08x) = %d [acked %d]\n",  
//        rxbitmask, (~lp_rxbitmask), rxbitmask & (~lp_rxbitmask), _count_bits( rxbitmask & (~lp_rxbitmask) ), 
//        acked_bytes/this.mtu);
    
    // adjust delta time
    if (acked_pkts <= 8) {
      // dropping a lot of packets, increase delta => lower bandwidth
      int ndt = this.dt + this.ddt;
      this.dt = ndt > this.dt_max ? this.dt_max : ndt;
    } else {
      // try to decrease delta => higher bandwidth
      int ndt = this.dt > this.ddt ? (this.dt - this.ddt) : this.dt_min;
      this.dt = ndt < this.dt_min ? this.dt_min : ndt;
    }
  
    this.p_rxoffset = rxoffset;
    this.tx_offset = rxoffset;
    this.p_rxbitmask = rxbitmask;
    this.txbitmask = rxbitmask;
    
    System.out.format("[TXER] [UP] update tx stats tx_offset:%04x tx_bitmask:%08x\n", tx_offset, txbitmask);

    
    byte[] buf = new byte[6];
    buf[0] = UMFT_CMD_STATUS;
    buf[1] = (byte)ack_res;
    from32bit((int)this.dt, buf, 2);
  
    u.ackReply(buf, (short)6);

    return true;
  }
  
  public boolean rxAck(char seqno, byte[] data, int len) {
    if (seqno != this.req_pkt_seqno || data[0] != UMFT_CMD_SEND_FILE || len != 14) {
      return false;
    }
    if (running) {
      return false;
    }
    this.req_pkt_seqno = 0xff;
    int rxsta = (int)data[1] & 0xff;
    int rxmtu = to16bit(data, 2);
    long rxdt_min = to32bit(data, 4);
    long rxdt_max = to32bit(data, 8);
    int sess_id = to16bit(data, 12);

    switch (rxsta) {
    case UMFT_STA_OK:
      this.running = true;
      break;
    case UMFT_STA_ABORT:
    default:
      this.running = false;
      status(UMFT_STA_ABORT);
      break;
    }
    if (this.running) {
      this.mtu = rxmtu;
      this.session = (short)sess_id;
      this.dt_min = (int)rxdt_min;
      this.dt_max = (int)rxdt_max;
      this.dt = (int)((rxdt_max - rxdt_min)/4 + rxdt_min);
      requestFutureTick(this.dt);
    }
    return true;
  }
  
  public void tick() {
    if (running) {
      sendNextChunk();
      if (this.acked_bytes < this.length){
        requestFutureTick(this.dt);
      }
    }
  }
  
  public abstract void requestFutureTick(long delta);
  public abstract void status(int result);
}
