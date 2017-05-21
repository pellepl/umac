package com.pelleplutt.jumac.test;

import java.io.IOException;

import com.pelleplutt.jumac.Jumac;
import com.pelleplutt.jumac.JumacTicker;
import com.pelleplutt.jumac.filetransfer.JumftRx;
import com.pelleplutt.jumac.filetransfer.JumftStream;
import com.pelleplutt.jumac.filetransfer.JumftTx;

public class JumftTest {

  public JumftTest() {
  }
  
  public static void main(String[] args) throws Throwable
  {
    final JumacSocketServer server = new JumacSocketServer(10000, 0.0001f);
    Thread t = new Thread(new Runnable() {

      @Override
      public void run() {
        try {
          server.start();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    });
    t.setDaemon(true);
    t.start();
    
    final byte[] txbuf = new byte[1024*32];
    final byte[] rxbuf = new byte[1024*32];
    for (int i = 0; i < txbuf.length; i++) {
      txbuf[i] = (byte)(Math.random() * 255);
    }
    
    Jumac utx = new Jumac(1000, false, 5);
    Jumac urx = new Jumac(1000, false, 5);
    
    JumftStream strtx = new JumftStream() {
      @Override
      public void readData(int offs, byte[] dst, int dstOffs, int len) {
        if (offs >= txbuf.length) throw new Error("read beyond buffer " + offs + " > " + txbuf.length);
        System.arraycopy(txbuf, offs, dst, dstOffs, len);
      }
      @Override
      public boolean createData(int len, String name) {
        return false;
      }
      @Override
      public boolean writeData(int offs, byte[] src, int srcOffs, int len) {
        return false;
      }
    };
    
    JumftStream strrx = new JumftStream() {
      @Override
      public void readData(int offs, byte[] dst, int dstOffs, int len) {
      }
      @Override
      public boolean createData(int len, String name) {
        System.out.println("rxstream: creating file " + name + " " + len + " bytes");
        return true;
      }
      @Override
      public boolean writeData(int offs, byte[] src, int srcOffs, int len) {
        if (offs >= txbuf.length) throw new Error("write beyond buffer " + offs + " > " + rxbuf.length);
        System.out.format("                                                 *** write %08x:%d\n", offs, len);
        System.arraycopy(src, srcOffs, rxbuf, offs, len);
        return true;
      }
    };
    
    final int MTU = 7;
    final int DT_MIN = 5;
    final int DT_MAX = 15;
    final int DDT = 1;
    
    final JumacTicker tickerfttx = new JumacTicker();
    final JumacTicker tickerftrx = new JumacTicker();
    
    final JumftTx ufttx = new JumftTx(utx, txbuf.length, MTU, DT_MIN, DT_MAX, DDT) {
      @Override
      public void status(int result) {
        System.out.println("jumfttx: status " + result);
        if (result == UMFT_STA_FIN) {
          System.out.println("TRANSMITTED ALL");
        }
        else if (result == UMFT_STA_ABORT) {
          System.out.println("TRANSMITTER ABORT");
        }
      }
      @Override
      public void requestFutureTick(long delta) {
        tickerfttx.requestFutureTick(delta);
      }
    };
    
    final JumftRx uftrx = new JumftRx(urx, strrx, DT_MIN, DT_MAX, MTU) {
      boolean finished = false;
      @Override
      public void status(int result) {
        System.out.println("jumftrx: status " + result);
        if (result == UMFT_STA_FIN) {
          System.out.println("RECEIVED ALL");
          //uftrx.close(); // TODO
          if (!finished) {
            finished = true;
            boolean error = false;
            for (int i = 0; i < txbuf.length; i++) {
              if (rxbuf[i] != txbuf[i]) {
                error = true;
                System.out.format("MISMATCH @ INDEX %08x (mtu %04x): %02x != %02x\n", i, i / MTU, rxbuf[i], txbuf[i]);
              }
            }
            if (!error) {
              System.out.println("RX = TX - ALL OK");
            }
          }
        }
        else if (result == UMFT_STA_FIN) {
          System.out.println("RECEIVER ABORT");
        }
      }
      @Override
      public void requestFutureTick(long delta) {
        tickerftrx.requestFutureTick(delta);
      }
      @Override
      public void cancelFutureTick() {
        tickerftrx.cancelFutureTick();
      }
    };
    
    JumacSocketConfig ucfgtx = new JumacSocketConfig(utx) {
      @Override
      public void tmo(char seqno) {
        System.out.println("txer: TMO");
      }
      @Override
      public void rxPak(char seqno, byte[] data, int len, boolean req_ack) {
        ufttx.rxPak(seqno, data, len, req_ack);
      }
      @Override
      public void rxAck(char seqno, byte[] data, int len) {
        ufttx.rxAck(seqno, data, len);
      }
      @Override
      public long retryDelta(int tries) {
        return 50;
      }
    };
    utx.setConfig(ucfgtx);
    
    JumacSocketConfig ucfgrx = new JumacSocketConfig(urx) {
      @Override
      public void tmo(char seqno) {
        System.out.println("rxer: TMO");
      }
      @Override
      public void rxPak(char seqno, byte[] data, int len, boolean req_ack) {
        uftrx.rxPak(seqno, data, len, req_ack);
      }
      @Override
      public void rxAck(char seqno, byte[] data, int len) {
        uftrx.rxAck(seqno, data, len);
      }
      @Override
      public long retryDelta(int tries) {
        return 50;
      }
    };
    urx.setConfig(ucfgrx);
    
    ucfgtx.connect(10000);
    ucfgrx.connect(10000);
    
    tickerfttx.start(ufttx);
    tickerftrx.start(uftrx);
    
    ufttx.tx(strtx, "tester.file");
    
    t.join();
    //Thread.sleep(4000);
    
    System.out.println("finished");
  }
}
