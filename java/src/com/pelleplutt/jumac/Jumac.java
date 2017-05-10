package com.pelleplutt.jumac;

public class Jumac {
  public static final int UMAC_MAX_PAK_LEN = 769;
  public static final int _UMAC_ERR_BASE = -70000;
  public static final int UMAC_OK                       = 0;
  public static final int UMAC_ERR_BUSY                 = (_UMAC_ERR_BASE-1);
  public static final int UMAC_ERR_STATE                = (_UMAC_ERR_BASE-2);
  public static final int UMAC_ERR_TOO_LONG             = (_UMAC_ERR_BASE-3);
  public static final int UMAC_NACK_ERR_NOT_PREAMBLE = 0x01;
  public static final int UMAC_NACK_ERR_BAD_CRC = 0x02;
  public static final int UMAC_NACK_ERR_RX_TIMEOUT = 0x03;
  public static final int UMAC_NACK_ERR_NOT_READY = 0x04;

  private static final int UMAC_INIT_CRC = 0xffff;
  private static final int UMAC_PREAMBLE = 0xfd;
  private static final int UMAC_PKT_NREQ_ACK = 0;
  private static final int UMAC_PKT_REQ_ACK = 1;
  private static final int UMAC_PKT_ACK = 2;
  private static final int UMAC_PKT_NACK = 3;
  private static final int UMST_RX_EXP_PREAMBLE = 0;
  private static final int UMST_RX_NOT_PREAMBLE = 1;
  private static final int UMST_RX_EXP_HDR_HI = 2;
  private static final int UMST_RX_EXP_HDR_LO = 3;
  private static final int UMST_RX_DATA = 4;
  private static final int UMST_RX_CRC_HI = 5;
  private static final int UMST_RX_CRC_LO = 6;

  Config cfg;
  volatile int rxState;
  byte tmp[] = new byte[8];
  int rxDataCnt;
  boolean rxUserAcked;
  volatile char rxSeqno;
  volatile byte rxData[] = new byte[UMAC_MAX_PAK_LEN];
  volatile int rxDataLen;
  volatile int rxPktType;
  int rxRemoteCrc;
  int rxLocalCrc;
  volatile char txSeqno;
  volatile byte txData[] = new byte[UMAC_MAX_PAK_LEN];
  volatile int txDataLen;
  volatile char ackSeqno;
  volatile byte ackData[] = new byte[UMAC_MAX_PAK_LEN];
  volatile int ackDataLen;
  volatile boolean awaitAck;
  int retryCnt;
  volatile boolean timerEnabled;
  volatile boolean timerAckEnabled;
  volatile long timerAckDelta;
  volatile long timerAckStartTick;
  volatile boolean timerRxEnabled;
  volatile long timerRxDelta;
  volatile long timerRxStartTick;
  
  final long cfgUmacRxTimeout;
  final boolean cfgUmacNackGarbage;
  final int cfgUmacRetries;
  public String _dbg_name = null;

  public boolean _dbg = false;
  
  public Jumac(Config cfg, long cfgUmacRxTimeout, boolean cfgUmacNackGarbage, int cfgUmacRetries) {
    this.cfg = cfg;
    this.txSeqno = 1;
    this.cfgUmacRxTimeout = cfgUmacRxTimeout;
    this.cfgUmacNackGarbage = cfgUmacNackGarbage;
    this.cfgUmacRetries = cfgUmacRetries;
  }
  
  void dbg(String format, Object ...args) {
    if (_dbg) {
      if (_dbg_name != null) System.out.print(_dbg_name);
      System.out.format(format, args);
    }
  }
  
  public void report(byte c) {
    parseChar(c);
  }
  
  public void report(byte[] buf, int offs, int len) {
    for (int i = offs; i < offs+len; i++) {
      report(buf[i]);
    }
  }
  
  public void report(byte[] buf) {
    report(buf, 0, buf.length);
  }
  
  public void tick() {
    long now = cfg.nowTick();
    timersUpdate(now);
    timerEnabled = false;
    if (timerRxEnabled && timerRxDelta == 0) {
      timerRxEnabled = false;
      timerTrigRx();
      if (timerAckEnabled && timerAckDelta > 0) {
        // recalc lingering ack timer
        requestAckTimer(timerAckDelta);
      }
    }
    if (timerAckEnabled && timerAckDelta == 0) {
      timerAckEnabled = false;
      timerTrigAck();
      if (timerRxEnabled && timerRxDelta > 0) {
        // recalc lingering rx timer
        requestRxTimer(timerRxDelta);
      }
    }
  }

  public int txPacket(boolean ack, byte[] buf, short len) {
    if (awaitAck && ack) {
      dbg("TX: ERR user send sync while BUSY\n");
      return UMAC_ERR_BUSY;
    }
    if (len > UMAC_MAX_PAK_LEN) {
      dbg("TX: ERR too long\n");
      return UMAC_ERR_TOO_LONG;
    }
    int pktType = ack ? UMAC_PKT_REQ_ACK : UMAC_PKT_NREQ_ACK;
    if (ack) {
      System.arraycopy(buf, 0, txData, 0, len);
      txDataLen = len;
    }
    char seqno = ack ? txSeqno : 0;
    txInitial(buf, seqno, pktType, len);
    return seqno;
  }

  public int ackReply(byte[] buf, short len) {
    if (rxPktType != UMAC_PKT_REQ_ACK) {
      dbg("TX: ERR user send ack wrong state\n");
      return UMAC_ERR_STATE;
    }
    if (len > UMAC_MAX_PAK_LEN) {
      dbg("TX: ERR too long\n");
      return UMAC_ERR_TOO_LONG;
    }
    rxUserAcked = true;
    ackSeqno = rxSeqno;
    System.arraycopy(buf, 0, ackData, 0, len);
    ackDataLen = len;
    txInitial(ackData, ackSeqno, UMAC_PKT_ACK, len);
    return UMAC_OK;
  }
  
  void parseChar(byte b) {
    char c = (char)(b & 0xff);
    switch (rxState) {
    case UMST_RX_EXP_PREAMBLE:
    if (c == UMAC_PREAMBLE) {
      rxSeqno = 0;
      requestRxTimer(cfgUmacRxTimeout);
      rxState = UMST_RX_EXP_HDR_HI;
    } else {
      rxState = UMST_RX_NOT_PREAMBLE;
      cfg.garbage(b);
    }
    break;
    case UMST_RX_NOT_PREAMBLE:
      if (c == UMAC_PREAMBLE) {
        if (cfgUmacNackGarbage) {
          txNack(UMAC_NACK_ERR_NOT_PREAMBLE, (char)0x0);
        }
        rxSeqno = 0;
        requestRxTimer(cfgUmacRxTimeout);
        rxState = UMST_RX_EXP_HDR_HI;
      } else {
        cfg.garbage(b);
      }
      break;
    case UMST_RX_EXP_HDR_HI:
      rxPktType = (c >>> 6) & 0x3;
      rxSeqno = (char)((c >>> 2) & 0xf);
      rxDataLen = c & 0x3;
      rxLocalCrc = _crc_ccitt_16(UMAC_INIT_CRC, c);
      rxState = rxDataLen == 0 ? UMST_RX_CRC_HI : UMST_RX_EXP_HDR_LO;
      break;
    case UMST_RX_EXP_HDR_LO:
      rxDataLen = ((rxDataLen - 1) << 8) | (c + 1);
      rxLocalCrc = _crc_ccitt_16(rxLocalCrc, c);
      rxDataCnt = 0;
      rxState = UMST_RX_DATA;
      break;
    case UMST_RX_DATA:
      rxData[rxDataCnt++] = b;
      rxLocalCrc = _crc_ccitt_16(rxLocalCrc, c);
      if (rxDataCnt >= rxDataLen) {
        rxState = UMST_RX_CRC_HI;
      }
      break;
      
    case UMST_RX_CRC_HI:
      rxRemoteCrc = (c<<8);
      rxState = UMST_RX_CRC_LO;
      break;
    case UMST_RX_CRC_LO:
      cancelRxtimer();
      rxRemoteCrc |= c;
      if ((rxRemoteCrc & 0xffff) != (rxLocalCrc & 0xffff)) {
        txNack(UMAC_NACK_ERR_BAD_CRC, rxSeqno);
      } else {
        trigRxPkt();
      }
      rxState = UMST_RX_EXP_PREAMBLE;
      break;
    } // switch
  }
  
  void trigRxPkt() {
    switch (rxPktType) {
    case UMAC_PKT_ACK:
      if (awaitAck && txSeqno == rxSeqno) {
        dbg("RX: ACK seq %x\n", (int)rxSeqno);
        cancelAckTimer();
        awaitAck = false;
        incTxSeqno();
        cfg.rxAck(rxSeqno, rxData, rxDataLen);
      } else {
        dbg("RX: ACK unkn seq %x\n", (int)txSeqno, (int)rxSeqno);
      }
      break;
    case UMAC_PKT_NACK:
      if (awaitAck && txSeqno == rxSeqno) {
        if (rxData[0] == UMAC_NACK_ERR_BAD_CRC || 
            rxData[0] == UMAC_NACK_ERR_RX_TIMEOUT) {
          dbg("RX: NACK seq %x, err %d, reTX direct\n", (int)txSeqno, rxData[0]);
          cancelAckTimer();
          txInitial(txData, txSeqno, UMAC_PKT_REQ_ACK, txDataLen);
        } else {
          dbg("RX: NACK seq %x, err %d\n", rxData[0]);
        }
      } else {
        dbg("RX: NACK unkn seq %x, err %d\n", (int)rxSeqno, rxData[0]);
      }
      break;
    case UMAC_PKT_NREQ_ACK:
    case UMAC_PKT_REQ_ACK:
      boolean expAck = rxPktType == UMAC_PKT_REQ_ACK;
      dbg("RX: seq %x, %s (ackseq %x)\n", (int)rxSeqno, expAck ? "sync" : "unsync", (int)ackSeqno);
      char orxSeqno = rxSeqno;
      if (expAck && ackSeqno == rxSeqno) {
        dbg("RX: reACK seq %x, len %d\n", (int)rxSeqno, ackDataLen);
        tx(ackData, ackSeqno, UMAC_PKT_ACK, ackDataLen);
      } else {
        rxUserAcked = false;
        cfg.rxPak(rxSeqno, rxData, rxDataLen, rxPktType == UMAC_PKT_REQ_ACK);
        if (expAck && !rxUserAcked) {
          txAckEmpty(orxSeqno);
        }
      }
      break;
    }
  }
  
  void timerTrigRx() {
    dbg("RX: pkt TMO\n");
    txNack(UMAC_NACK_ERR_RX_TIMEOUT, rxSeqno);
    rxState = UMST_RX_EXP_PREAMBLE;
  }
  
  void timerTrigAck() {
    if (awaitAck) {
      retryCnt++;
      if (retryCnt > cfgUmacRetries) {
        dbg("TX: noACK, TMO seq %x\n", (int)txSeqno);
        char otxSeqno = txSeqno;
        incTxSeqno();
        retryCnt = 0;
        awaitAck = false;
        cfg.tmo(otxSeqno);
      } else {
        dbg("TX: noACK, reTX seq %x, #%d\n", (int)txSeqno, retryCnt);
        tx(txData, txSeqno, UMAC_PKT_REQ_ACK, txDataLen);
        requestAckTimer(cfg.retryDelta(retryCnt));
      }
    }
  }
  
  void cancelAckTimer() {
    boolean wasEna = timerAckEnabled;
    timerAckEnabled = false;
    long now = cfg.nowTick();
    timersUpdate(now);
    if (wasEna) {
      if (timerRxEnabled) {
        if (timerRxDelta > timerAckDelta) {
          halSetTimer(timerRxDelta);
        }
      } else {
        halDisableTimer();
      }
    }
  }
  
  void cancelRxtimer() {
    boolean wasEna = timerRxEnabled;
    timerRxEnabled = false;
    long now = cfg.nowTick();
    timersUpdate(now);
    if (wasEna) {
      if (timerAckEnabled) {
        if (timerAckDelta > timerRxDelta) {
          halSetTimer(timerAckDelta);
        }
      } else {
        halDisableTimer();
      }
    }
  }
  
  void requestAckTimer(long delta) {
    long now = cfg.nowTick();
    timersUpdate(now);
    timerAckDelta = delta;
    timerAckStartTick = now;
    timerAckEnabled = true;
    if (timerRxEnabled) {
      if (timerRxDelta <= delta) {
        // rx timer already registered and occurs before,
        // no need to start timer
      } else {
        // new delta before other timer, reset timer
        halSetTimer(delta);
      }
    } else {
      halSetTimer(delta);
    }
  }
  
  void requestRxTimer(long delta) {
    long now = cfg.nowTick();
    timersUpdate(now);
    timerRxDelta = delta;
    timerRxStartTick = now;
    timerRxEnabled = true;
    if (timerAckEnabled) {
      if (timerAckDelta <= delta) {
        // ack timer already registered and occurs before,
        // no need to start timer
      } else {
        // new delta before other timer, reset timer
        halSetTimer(delta);
      }
    } else {
      halSetTimer(delta);
    }
  }
  
//adjust active timers from now
  void timersUpdate(long now) {
    if (timerRxEnabled) {
      long d = now - timerRxStartTick;
      timerRxStartTick = now;
      if (d > timerRxDelta) {
        timerRxDelta = 0;
      } else {
        timerRxDelta -= d;
      }
    }
    if (timerAckEnabled) {
      long d = now - timerAckStartTick;
      timerAckStartTick = now;
      if (d >= timerAckDelta) {
        timerAckDelta = 0;
      } else {
        timerAckDelta -= d;
      }
    }
  }
  
  void halDisableTimer() {
    if (timerEnabled) {
      cfg.cancelFutureTick();
    }
    timerEnabled = false;
  }
  
  void halSetTimer(long delta) {
    if (timerEnabled) {
      cfg.cancelFutureTick();
    }
    timerEnabled = true;
    cfg.requestFutureTick(delta);
  }
  
  void incTxSeqno() {
    txSeqno++;
    if (txSeqno > 0xf) txSeqno = 1;
  }
  
  void txNack(int err, char seqno) {
    dbg("RX: NACK seq %x: err %d\n", (int)seqno, err);
    tmp[0] = (byte)(UMAC_PREAMBLE);
    tmp[1] = (byte)((UMAC_PKT_NACK << 6) | ((seqno & 0xf) << 2) | (1));
    tmp[2] = 0x00;
    tmp[3] = (byte)(err);
    int crc = _crc_buf(UMAC_INIT_CRC,tmp, 1, 3);
    tmp[4] = (byte)(crc >> 8);
    tmp[5] = (byte)(crc);
    cfg.tx(tmp, 6);
  }
  
  void txAckEmpty(char seqno) {
    dbg("RX: autoACK seq %x\n", (int)seqno);
    tmp[0] = (byte)(UMAC_PREAMBLE);
    tmp[1] = (byte)((UMAC_PKT_ACK << 6) | ((seqno & 0xf) << 2) | (0));
    int crc = _crc_buf(UMAC_INIT_CRC,tmp, 1, 1);
    tmp[2] = (byte)(crc >> 8);
    tmp[3] = (byte)(crc);
    dbg("TX: seq %x, %s\n", (int)txSeqno, "unsync");
    ackSeqno = seqno;
    cfg.tx(tmp, 4);
  }
  
  void txInitial(byte[] txData, char txSeqno, int pktType, int txDataLen) {
    tx(txData, txSeqno, pktType, txDataLen);
    if (pktType == UMAC_PKT_REQ_ACK) {
      retryCnt = 0;
      awaitAck = true;
      requestAckTimer(cfg.retryDelta(retryCnt));
    }
  }

  void tx(byte[] txData, char txSeqno, int pktType, int txDataLen) {
    dbg("TX: seq %x, %s\n", (int)txSeqno, pktType == UMAC_PKT_REQ_ACK ? "sync" : "unsync");
    int crc;
    tmp[0] = (byte)UMAC_PREAMBLE;
    char hlen = (char)(txDataLen == 0 ? 0 : (((((txDataLen-1)>>>8) + 1) << 8) | (txDataLen - 1)));
    tmp[1] = (byte)((pktType << 6) | ((txSeqno & 0xf) << 2) | (hlen >>> 8));
    if (hlen == 0) {
      crc = _crc_buf(UMAC_INIT_CRC, tmp, 1, 1);
      tmp[2] = (byte)(crc >>> 8);
      tmp[3] = (byte)crc;
      cfg.tx(tmp, 4);
    } else {
      tmp[2] = (byte)hlen;
      crc = _crc_buf(UMAC_INIT_CRC, tmp, 1, 2);
      cfg.tx(tmp, 3);
      crc = _crc_buf(crc, txData, 0, txDataLen);
      cfg.tx(txData, txDataLen);
      tmp[0] = (byte)(crc >> 8);
      tmp[1] = (byte)crc;
      cfg.tx(tmp, 2);
    }
  }
  
  static int _crc_ccitt_16(int crc, char data) {
    crc  = (char)(crc >>> 8) | (crc << 8);
    crc ^= data;
    crc ^= (char)(crc & 0xff) >>> 4;
    crc ^= (crc << 8) << 4;
    crc ^= ((crc & 0xff) << 4) << 1;
    return crc;
  }

  static int _crc_buf(int initial, byte[] buf, int offs, int len) {
    int crc = initial;
    while (len-- > 0) {
      crc = _crc_ccitt_16(crc, (char)((buf[offs++])&0xff));
    }
    return crc;
  }
  
  public static interface Config {
    void rxPak(char seqno, byte[] data, int len, boolean req_ack);
    void rxAck(char seqno, byte[] data, int len);
    void tmo(char seqno);
    void garbage(byte b);
    long nowTick();
    void requestFutureTick(long delta);
    void cancelFutureTick();
    void tx(byte b);
    void tx(byte[] buf, int len);
    long retryDelta(int tries);
  }
  
  public static abstract class DefaultConfig implements Config {
    Jumac umac;
    private final Object LOCK = new Object();
    private volatile long alarm;
    
    public DefaultConfig(Jumac umac) {
      this.umac = umac;
      Thread t = new Thread(ticker, "jumac-ticker");
      t.setDaemon(true);
      t.start();
    }
    public abstract void rxPak(char seqno, byte[] data, int len, boolean req_ack);
    public abstract void rxAck(char seqno, byte[] data, int len);
    public abstract void tmo(char seqno);
    public abstract long retryDelta(int tries);
    public abstract void tx(byte b);
    public abstract void tx(byte[] buf, int len);
    public void garbage(byte b) {}
    public long nowTick() {
      return System.currentTimeMillis();
    }
    public void requestFutureTick(long delta) {
      synchronized (LOCK) {
        alarm = System.currentTimeMillis() + delta;
        LOCK.notifyAll();
      }
    }
    public void cancelFutureTick() {
      synchronized (LOCK) {
        alarm = 0;
        LOCK.notifyAll();
      }
    }

    private Runnable ticker = new Runnable() {
      @Override
      public void run() {
        while (true) {
          boolean trig = false;
          synchronized (LOCK) {
            long now = 0; 
            while (alarm == 0 || (now = System.currentTimeMillis()) < alarm) {
              try {
                if (alarm > 0) {
                  if (alarm - now > 0) {
                    LOCK.wait(alarm - now);
                  }
                } else {
                  LOCK.wait();
                }
              } catch (InterruptedException e) {
                return;
              }
            }
            if (alarm != 0 && now >= alarm) {
              trig = true;
              alarm = 0;
            }
          } // sync LOCK
          if (trig) {
            umac.tick();
          }
        } // while forever
      }
    };
  }
}
