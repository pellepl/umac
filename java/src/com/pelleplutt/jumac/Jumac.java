package com.pelleplutt.jumac;

/**
 * Jumac - micro mac for Java
 *
 * Simple protocol stack for transmitting/receiving packets, sort of MAC-like.
 * Packets can either be synchronized (needing an ack) or unsynchronized (not needing ack).
 * The payload length can vary from 0 to 769 bytes.
 *
 * The stack handles retransmits automatically. Unless user acks packets herself, the
 * stack auto-acks if necessary. Acks can be piggybacked with payload data.
 *
 * Only one synchronized packed can be in the air at a time. It is legal to send unsynched
 * packets while a synchronized is not yet acked, though.
 *
 *  Created on: Feb 15, 2016
 *      Author: petera
 */
public class Jumac implements JumacTickable {
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
  
  /**
   * Construct an umac instance with given settings.
   * @param cfgUmacRxTimeout    Ticks for a packet to timeout 
   * @param cfgUmacNackGarbage  Whether non-protocol data should be nacked or not
   * @param cfgUmacRetries      How many times a packet should be resent if no ack before failing
   */
  public Jumac(long cfgUmacRxTimeout, boolean cfgUmacNackGarbage, int cfgUmacRetries) {
    this.txSeqno = 1;
    this.cfgUmacRxTimeout = cfgUmacRxTimeout;
    this.cfgUmacNackGarbage = cfgUmacNackGarbage;
    this.cfgUmacRetries = cfgUmacRetries;
  }

  public void setConfig(Config cfg) {
    this.cfg = cfg;
  }
  
  void dbg(String format, Object ...args) {
    if (_dbg) {
      if (_dbg_name != null) System.out.print(_dbg_name);
      System.out.format(format, args);
    }
  }
  
  /**
   * Report from stack that a byte was received from PHY
   * @param c the byte
   */
  public void report(byte c) {
    parseChar(c);
  }
  
  /**
   * Report from stack that many bytes were received from PHY
   * @param buf   the data
   * @param offs  offset
   * @param len   length
   */
  public void report(byte[] buf, int offs, int len) {
    for (int i = offs; i < offs+len; i++) {
      report(buf[i]);
    }
  }
  
  /**
   * Report from stack that many bytes were received from PHY
   * @param buf   the data
   */
  public void report(byte[] buf) {
    report(buf, 0, buf.length);
  }
  
  /**
   * Call tick when a requested timer times out. See
   * requestFutureTick in config object.
   */
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

  /**
   * Transmits a packet. Returns sequence number of txed packet,
   * or 0 if packet does not need ack.
   * @param ack 0 if packet does not need ack, else packet needs ack
   * @param buf packet data
   * @param len packet data length
   * @return sequence number on acked packet, 0 otherwise, negative on error
   */
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

  /**
   * When a synchronous packet is received, rxPak function
   * in config object is called. In this call, user may ack with
   * piggybacked data if wanted. If not, the stack autoacks with an
   * empty ack.
   * @param buf ack data
   * @param len ack data length
   * @return 0 if ok, negative on error
   */
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
  
  // adjust active timers from now
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
  
  /**
   * UMAC configuration/HAL interface
   */
  public static interface Config {
    /** handle reception of a packet */
    void rxPak(char seqno, byte[] data, int len, boolean req_ack);
    /** handle acknowledge of a synchronized sent packet */
    void rxAck(char seqno, byte[] data, int len);
    /** handle timeout of a synchronized sent packet, no ack */
    void tmo(char seqno);
    /** handle non UMAC data */
    void garbage(byte b);
    /** returns current system time */
    long nowTick();
    /** request a call to tick function in given ticks */
    void requestFutureTick(long delta);
    /** cancel request to call tick function */
    void cancelFutureTick();
    /** transmit one byte */
    void tx(byte b);
    /** transmit multiple bytes */
    void tx(byte[] buf, int len);
    /** return delta ticks before trying to send an unacked packet again */
    long retryDelta(int tries);
  }
  
  /**
   * Utility implementation of UMAC configuration/HAL interface.
   * Ignores non-umac data unless garbage function is overridden.
   * Implements a timer, based on System.currentTimeMillis and thread/wait.
   * Be aware of granularity of this timer.
   */
  public static abstract class DefaultAbstractConfig implements Config {
    Jumac umac;
    JumacTicker ticker;
    
    public DefaultAbstractConfig(Jumac umac) {
      this.umac = umac;
      this.ticker = new JumacTicker();
      ticker.start(umac);
    }
    public void garbage(byte b) {}
    public long nowTick() {
      return ticker.nowTick();
    }
    public void requestFutureTick(long delta) {
      ticker.requestFutureTick(delta);
    }
    public void cancelFutureTick() {
      ticker.cancelFutureTick();
    }
  }
}
