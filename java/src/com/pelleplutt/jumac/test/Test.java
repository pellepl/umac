package com.pelleplutt.jumac.test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;

import com.pelleplutt.jumac.Jumac;

public class Test {
  static final int COMM_BUF_SZ = 32768;
  static final short UNDEF_EXP   = 1000;
  static final short ANY_EXP     = (short)0xffff;

  Jumac ua, ub;
  Jumac.Config cua, cub;
  Juctx uactx, ubctx;
  boolean verbose;
  
  void dbg(String s) {
    if (verbose) System.out.println(s);
  }

  public static void main(String[] args) {
    Test t = new Test();
    t.uactx = new Juctx();
    t.ubctx = new Juctx();
    t.cua = t.makeConfig(t.uactx);
    t.cub = t.makeConfig(t.ubctx);
    t.ua = new Jumac(6, false, 3);
    t.ua.setConfig(t.cua);
    t.ub = new Jumac(6, false, 3);
    t.ub.setConfig(t.cub);
    t.uactx.name = "ADAM";
    t.ubctx.name = "BESS";
    t.ua._dbg_name = "umac ADAM ";
    t.ub._dbg_name = "umac BESS ";
    //t.ua._dbg = true;
    //t.ub._dbg = true;
    
    if (args.length > 1 && args[1].startsWith("verbose=")) {
      t.verbose = args[1].endsWith("1");
    }
    
    RandomAccessFile raf = null;
    try {
      raf = new RandomAccessFile(args[0], "r");
      String line;
      while ((line = raf.readLine()) != null) {
        t.dbg(line);
        t.handleLine(raf, line);
      }
      raf.close();
      if (t.check_ctx(t.uactx) != 0 || t.check_ctx(t.ubctx) != 0) {
        System.exit(1);
      }
    } catch (IOException e) {
      e.printStackTrace();
      System.exit(1);
    }
  }
  
  int check_ctx(Juctx uc) {
    int err = 0;
    
    if (uc.exp_rx_len != UNDEF_EXP && uc.exp_rx_len != ANY_EXP) {
      System.out.println(uc.name + " unmacthed RX " + new String(uc.exp_rx, 0, uc.exp_rx_len));
      err = 1;
    }
    if (uc.exp_ack_len != UNDEF_EXP && uc.exp_ack_len != ANY_EXP) {
      System.out.println(uc.name + " unmacthed ACK " + new String(uc.exp_ack, 0, uc.exp_ack_len));
      err = 1;
    }
    if (uc.nxt_ack_len != 0) {
      System.out.println(uc.name + " unsent ACK " + new String(uc.nxt_ack, 0, uc.nxt_ack_len));
      err = 1;
    }
    if (uc.exp_tmo) {
      System.out.println(uc.name + " unmatched TMO");
      err = 1;
    }
    if (uc.exp_err != 0) {
      System.out.println(uc.name + " unmatched ERR " + uc.exp_err);
      err = 1;
    }
    
    return err;
  }
  
  long loop_offs;
  int loop_count;
  void handleLine(RandomAccessFile raf, String line) {
    if (line.startsWith("A.") || line.startsWith("B.")) {
      Jumac u = line.charAt(0) == 'A' ? ua : ub;
      Juctx uc = line.charAt(0) == 'A' ? uactx : ubctx;
      line = line.substring(2);
      
      if (line.startsWith("tx ")) {
        boolean ack = line.endsWith("ack");
        int err = u.txPacket(ack, quoted(line), quotedLen(line));
        if (err < 0) {
          if (uc.exp_err == err) {
            dbg("[" + uc.name + "] exp err receieved, clearerr");
            uc.exp_err = Jumac.UMAC_OK;
          } else {
            System.out.println("Unexpected error " + err);
            System.exit(1);
          }
        }
      }
      else if (line.startsWith("rx ")) {
        if (line.endsWith("*")) {
          uc.exp_rx_len = (short)ANY_EXP;
        } else {
          uc.exp_rx_len = quotedLen(line);
          uc.exp_rx = quoted(line);
        }
      }
      else if (line.startsWith("ack ")) {
        if (line.endsWith("*")) {
          uc.exp_ack_len = (short)ANY_EXP;
        } else {
          uc.exp_ack_len = quotedLen(line);
          uc.exp_ack = quoted(line);
        }
      }
      else if (line.startsWith("nextack ")) {
        uc.nxt_ack_len = quotedLen(line);
        uc.nxt_ack = quoted(line);
      }
      else if (line.startsWith("tmo")) {
        uc.exp_tmo = true;
      }
      else if (line.startsWith("err ")) {
        uc.exp_err = Integer.parseInt(line.substring(line.lastIndexOf(' ')).trim());
      }
      else if (line.startsWith("tick")) {
        if (line.length() <= 5) {
          test_tick(u, uc);
        } else {
          int ticks = Integer.parseInt(line.substring(line.lastIndexOf(' ')).trim());
          while (ticks-- > 0) test_tick(u, uc);
        }
      }
      else if (line.startsWith("txblock ")) {
        if (line.endsWith("1") || line.endsWith("on")) {
          uc.tx_stopped = true;
        } else if (line.endsWith("0") || line.endsWith("off")) {
          uc.tx_stopped = false;
        } else {
          System.out.println("Unknown test script argument for txblock:\n" + line);
          System.exit(1);
        }
      }
      else if (line.startsWith("rxspeed")) {
        if (line.endsWith("full") || line.endsWith("0")) {
          uc.rx_per_tick = 0;
        } else {
          uc.rx_per_tick = Integer.parseInt(line.substring(line.lastIndexOf(' ')).trim());
        }
      }
    }
    
    else if (line.startsWith("tick")) {
      if (line.length() <= 5) {
        test_tick_all();
      } else {
        int ticks = Integer.parseInt(line.substring(line.lastIndexOf(' ')).trim());
        while (ticks-- > 0) test_tick_all();
      }
    }
    else if (line.startsWith("loop ")) {
      try {
        loop_offs = raf.getFilePointer();
      } catch (IOException e) {
        e.printStackTrace();
        System.exit(1);
      }
      loop_count = Integer.parseInt(line.substring(line.lastIndexOf(' ')).trim());
    } else if (line.startsWith("endloop")) {
      if (loop_count > 0) {
        loop_count--;
        try {
          raf.seek(loop_offs);
        } catch (IOException e) {
          e.printStackTrace();
          System.exit(1);
        }
      }
    } else if (line.length() < 2 || line.charAt(0) == '#') {
    }
    else {
      System.out.println("Unknown test script line:\n" + line);
      System.exit(1);
    }
  }
  
  byte[] quoted(String s) {
    if (s.indexOf('"') == -1) return null;
    String q = s.substring(s.indexOf('"')+1, s.lastIndexOf('"'));
    try {
      return q.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      return null;
    }
  }

  short quotedLen(String s) {
    byte[] quoted = quoted(s);
    return (short)(quoted == null ? 0 : quoted.length);
  }

  void test_ctx_tick(Jumac u, Juctx uc) {
    uc.time++;
    if (uc.alarm_on && uc.time >= uc.alarm) {
      uc.alarm_on = false;
      u.tick();
    }
  }
  
  void test_ctx_rx(Jumac u, Juctx uc) {
    int rx_per_tick = uc.rx_per_tick == 0 ? 1 : uc.rx_per_tick;
    while (uc.rx_out != uc.rx_in && rx_per_tick > 0) {
      u.report(uc.rx[uc.rx_out++]);
      if (uc.rx_out >= COMM_BUF_SZ) {
        uc.rx_out = 0;
      }
      if (uc.rx_per_tick > 0) rx_per_tick--;
    }
  }
  
  void test_tick(Jumac u, Juctx uc) {
    test_ctx_rx(u, uc);
    test_ctx_tick(u, uc);
  }
  
  void test_tick_all() {
    test_tick(ua, uactx);
    test_tick(ub, ubctx);
  }
  
  
  Jumac.Config makeConfig(final Juctx uc) {
    return new Jumac.Config() {
      @Override
      public void rxPak(char seqno, byte[] data, int len, boolean req_ack) {
        dbg(String.format("[%s] RX%c %1x %s", uc.name, req_ack ? '*' : ' ', (int)seqno,
            len == 0 ? "" : new String(data, 0, len)));
        if (uc.exp_rx_len != ANY_EXP) {
          if (!(uc.exp_rx_len == len && (uc.exp_rx_len == 0 || strncmp(data, uc.exp_rx, uc.exp_rx_len) == 0))) {
            String sgot = len == UNDEF_EXP ? "<UNDEF>" : new String(data, 0, len);
            String sexp = uc.exp_rx_len == UNDEF_EXP ? "<UNDEF>" : new String(uc.exp_rx, 0, uc.exp_rx_len);
            System.out.println("RX mismatch, got \"" + sgot + "\", expected \"" + sexp + "\"");
            System.exit(1);
          }
          uc.exp_rx_len = UNDEF_EXP;
        }
        if (req_ack && uc.nxt_ack_len > 0) {
          Jumac u = uc == uactx ? ua : ub;
          u.ackReply(uc.nxt_ack, uc.nxt_ack_len);
          uc.nxt_ack_len = 0;
        }
      }

      @Override
      public void rxAck(char seqno, byte[] data, int len) {
        dbg(String.format("[%s] ACK %1x %s", uc.name, (int)seqno, len == 0 ? "" : new String(data, 0, len)));
        if (uc.exp_ack_len != ANY_EXP) {
          if (uc.exp_ack_len != len || 
              (len != 0 && strncmp(data, uc.exp_ack, uc.exp_ack_len) != 0)) {
            System.out.println("ACK mismatch, got \"" + new String(data, 0, len) +
                "\", expected \"" +new String(uc.exp_ack, 0, uc.exp_ack_len)+ "\"");
            System.exit(1);
          }
          uc.exp_ack_len = UNDEF_EXP;
        }
      }

      @Override
      public void tmo(char seqno) {
        dbg(String.format("[%s] TMO %1x",uc.name , (int)seqno));
        if (uc.exp_tmo) {
          uc.exp_tmo = false;
        } else {
          System.out.println("TMO unexpected");
          System.exit(1);
        }
      }

      @Override
      public void garbage(byte b) {
      }

      @Override
      public long nowTick() {
        return uc.time;
      }

      @Override
      public void requestFutureTick(long delta) {
        if (uc.alarm_on) {
          System.out.println(uc.name + " multiple timer enable");
          System.exit(1);
        }
        uc.alarm = uc.time + delta;
        uc.alarm_on = true;
      }

      @Override
      public void cancelFutureTick() {
        if (!uc.alarm_on) {
          System.out.println(uc.name + " multiple timer disable");
          System.exit(1);
        }
        uc.alarm_on = false;
      }

      @Override
      public void tx(byte b) {
        Juctx ucme = uc;
        if (ucme.tx_stopped) return;
        //Jumac udst = uc == uactx ? ub : ua;
        Juctx ucdst = uc == uactx ? ubctx : uactx;
        ucdst.rx[ucdst.rx_in++] = b;
        if (ucdst.rx_in >= COMM_BUF_SZ) {
          uc.rx_in = 0;
        }
        if (ucdst.rx_in == ucdst.rx_out) {
          System.out.println(ucdst.name + " rx overflow");
          System.exit(1);
        }
      }

      @Override
      public void tx(byte[] buf, int len) {
        for (int i = 0; i < len; i++) {
          this.tx(buf[i]);
        }
      }

      @Override
      public long retryDelta(int tries) {
        return 3;
      }
      
    };
  }
  
  static int strncmp(byte[] a, byte[] b, int len) {
    if (len == 0 && (a == null || a.length == 0) && (b == null || b.length == 0)) return 1;
    if (len != 0 && (a == null || b == null)) return 0;
    if (a.length < len || b.length < len) return 1;
    for (int i = 0; i < len; i++) {
      if (a[i] != b[i]) return 2;
    }
    return 0;
  }
  
  static class Juctx {
    String name;
    long time;
    boolean alarm_on;
    long alarm;
    byte rx[] = new byte[COMM_BUF_SZ];
    int rx_in;
    int rx_out;
    boolean tx_stopped;
    int rx_per_tick;
    byte exp_rx[] = new byte[Jumac.UMAC_MAX_PAK_LEN];
    short exp_rx_len;
    byte exp_ack[] = new byte[Jumac.UMAC_MAX_PAK_LEN];
    short exp_ack_len;
    byte nxt_ack[] = new byte[Jumac.UMAC_MAX_PAK_LEN];
    short nxt_ack_len;
    boolean exp_tmo;
    int exp_err;
  }

}
