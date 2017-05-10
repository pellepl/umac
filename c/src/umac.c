/*
 * umac.c
 *
 *  Created on: Feb 15, 2016
 *      Author: petera
 */

#include "umac.h"

#define UMAC_INIT_CRC      0xffff

static void _umac_request_rx_timer(umac *u, umtick delta);
static void _umac_request_ack_timer(umac *u, umtick delta);

static inline unsigned short _crc_ccitt_16(unsigned short crc, unsigned char data) {
  crc  = (unsigned char)(crc >> 8) | (crc << 8);
  crc ^= data;
  crc ^= (unsigned char)(crc & 0xff) >> 4;
  crc ^= (crc << 8) << 4;
  crc ^= ((crc & 0xff) << 4) << 1;
  return crc;
}

static uint16_t _crc_buf(uint16_t initial, uint8_t *data, uint16_t len) {
  uint16_t crc = initial;
  while (len--) {
    crc = _crc_ccitt_16(crc, *data++);
  }
  return crc;
}

// starts system timer
static void _umac_hal_set_timer(umac *u, umtick delta) {
  if (u->timer_enabled) {
    u->cfg.cancel_timer_fn(u);
  }
  u->timer_enabled = 1;
  u->cfg.timer_fn(u, delta);
}

// stops system timer
static void _umac_hal_disable_timer(umac *u) {
  if (u->timer_enabled) {
    u->cfg.cancel_timer_fn(u);
  }
  u->timer_enabled = 0;
}

// increases tx seqno
static void _umac_inc_tx_seqno(umac *u) {
  u->tx_seqno++;
  if (u->tx_seqno > 0xf) u->tx_seqno = 1;
}

// transmit a NACK with error code
static void _umac_tx_nack(umac *u, uint8_t err, uint8_t seqno) {
  CFG_UMAC_DBG(u, "RX: NACK seq %x: err %i\n", seqno, err);
  u->tmp[0] = UMAC_PREAMBLE;
  u->tmp[1] = (UMAC_PKT_NACK << 6) | ((seqno & 0xf) << 2) | (1);
  u->tmp[2] = 0x00;
  u->tmp[3] = err;
  uint16_t crc = _crc_buf(UMAC_INIT_CRC, &u->tmp[1], 3);
  u->tmp[4] = crc >> 8;
  u->tmp[5] = crc;
  u->cfg.tx_buf_fn(u, u->tmp, 6);
}

// transmit a general packet only
static void _umac_tx(umac *u, umac_pkt *pkt) {
  CFG_UMAC_DBG(u, "TX: seq %x, %s\n", pkt->seqno, pkt->pkt_type == UMAC_PKT_REQ_ACK ? "sync" : "unsync");
  uint16_t crc;
  u->tmp[0] = UMAC_PREAMBLE;
  uint16_t hlen = pkt->length == 0 ? 0 : (((((pkt->length-1)>>8) + 1) << 8) | (pkt->length - 1));
  u->tmp[1] = (pkt->pkt_type << 6) | ((pkt->seqno & 0xf) << 2) | (hlen >> 8);
  if (hlen == 0) {
    crc = _crc_buf(UMAC_INIT_CRC, &u->tmp[1], 1);
    u->tmp[2] = crc >> 8;
    u->tmp[3] = crc;
    u->cfg.tx_buf_fn(u, u->tmp, 4);
  } else {
    u->tmp[2] = hlen & 0xff;
    crc = _crc_buf(UMAC_INIT_CRC, &u->tmp[1], 2);
    u->cfg.tx_buf_fn(u, u->tmp, 3);
    crc = _crc_buf(crc, pkt->data, pkt->length);
    u->cfg.tx_buf_fn(u, pkt->data, pkt->length);
    u->tmp[0] = crc >> 8;
    u->tmp[1] = crc;
    u->cfg.tx_buf_fn(u, u->tmp, 2);
  }
}

// transmit an empty ACK
static void _umac_tx_ack_empty(umac *u, uint8_t seqno) {
  CFG_UMAC_DBG(u, "RX: autoACK seq %x\n", seqno);
  u->ack_pkt.pkt_type = UMAC_PKT_ACK;
  u->ack_pkt.length = 0;
  u->ack_pkt.seqno = seqno;
  _umac_tx(u, &u->ack_pkt);
}

// transmit a general packet and request ack timer if necessary
static void _umac_tx_initial(umac *u, umac_pkt *pkt) {
  _umac_tx(u, pkt);
  if (pkt->pkt_type == UMAC_PKT_REQ_ACK) {
    u->retry_ctr = 0;
    u->await_ack = 1;
    _umac_request_ack_timer(u, CFG_UMAC_RETRY_DELTA(u->retry_ctr));
  }
}

// adjust active timers from now
static void _umac_timers_update(umac *u, umtick now) {
  if (u->timer_rx_enabled) {
    umtick d = now - u->timer_rx_start_tick;
    u->timer_rx_start_tick = now;
    if (d > u->timer_rx_delta) {
      u->timer_rx_delta = 0;
    } else {
      u->timer_rx_delta -= d;
    }
  }
  if (u->timer_ack_enabled) {
    umtick d = now - u->timer_ack_start_tick;
    u->timer_ack_start_tick = now;
    if (d >= u->timer_ack_delta) {
      u->timer_ack_delta = 0;
    } else {
      u->timer_ack_delta -= d;
    }
  }
}

// request the rx timer
static void _umac_request_rx_timer(umac *u, umtick delta) {
  umtick now = u->cfg.now_fn(u);
  _umac_timers_update(u, now);
  u->timer_rx_delta = delta;
  u->timer_rx_start_tick = now;
  u->timer_rx_enabled = 1;
  if (u->timer_ack_enabled) {
    if (u->timer_ack_delta <= delta) {
      // ack timer already registered and occurs before,
      // no need to start timer
    } else {
      // new delta before other timer, reset timer
      _umac_hal_set_timer(u, delta);
    }
  } else {
    _umac_hal_set_timer(u, delta);
  }
}

// request the ack timer
static void _umac_request_ack_timer(umac *u, umtick delta) {
  umtick now = u->cfg.now_fn(u);
  _umac_timers_update(u, now);
  u->timer_ack_delta = delta;
  u->timer_ack_start_tick = now;
  u->timer_ack_enabled = 1;
  if (u->timer_rx_enabled) {
    if (u->timer_rx_delta <= delta) {
      // rx timer already registered and occurs before,
      // no need to start timer
    } else {
      // new delta before other timer, reset timer
      _umac_hal_set_timer(u, delta);
    }
  } else {
    _umac_hal_set_timer(u, delta);
  }
}

// cancel the rx timer
static void _umac_cancel_rx_timer(umac *u) {
  uint8_t was_ena = u->timer_rx_enabled;
  u->timer_rx_enabled = 0;
  umtick now = u->cfg.now_fn(u);
  _umac_timers_update(u, now);
  if (was_ena) {
    if (u->timer_ack_enabled) {
      if (u->timer_ack_delta > u->timer_rx_delta) {
        // timer rx cancelled, but ack requested and happens after, recalc callback tick
        _umac_hal_set_timer(u, u->timer_ack_delta);
      }
    } else {
      _umac_hal_disable_timer(u);
    }
  }
}

// cancel the ack timer
static void _umac_cancel_ack_timer(umac *u) {
  uint8_t was_ena = u->timer_ack_enabled;
  u->timer_ack_enabled = 0;
  umtick now = u->cfg.now_fn(u);
  _umac_timers_update(u, now);
  if (was_ena) {
    if (u->timer_rx_enabled) {
      if (u->timer_rx_delta > u->timer_ack_delta) {
        // timer ack cancelled, but rx requested and happens after, recalc callback tick
        _umac_hal_set_timer(u, u->timer_rx_delta);
      }
    } else {
      _umac_hal_disable_timer(u);
    }
  }
}

// timer ack triggered
static void _umac_timer_trig_ack(umac *u) {
  if (u->await_ack) {
    u->retry_ctr++;
    if (u->retry_ctr > CFG_UMAC_RETRIES) {
      CFG_UMAC_DBG(u, "TX: noACK, TMO seq %x\n", u->tx_seqno);
      _umac_inc_tx_seqno(u);
      u->retry_ctr = 0;
      u->await_ack = 0;
      u->cfg.timeout_fn(u, &u->tx_pkt);
    } else {
      CFG_UMAC_DBG(u, "TX: noACK, reTX seq %x, #%i\n", u->tx_seqno, u->retry_ctr);
      u->tx_pkt.data = u->tx_pkt_data;
      u->tx_pkt.seqno = u->tx_seqno;
      u->tx_pkt.pkt_type = UMAC_PKT_REQ_ACK;
      u->tx_pkt.length = u->tx_pkt_length;
      _umac_tx(u, &u->tx_pkt);
      _umac_request_ack_timer(u, CFG_UMAC_RETRY_DELTA(u->retry_ctr));
    }
  }
}

// timer rx triggered
static void _umac_timer_trig_rx(umac *u) {
  CFG_UMAC_DBG(u, "RX: pkt TMO\n");
  _umac_tx_nack(u, UMAC_NACK_ERR_RX_TIMEOUT, u->rx_pkt.seqno);
  u->rx_state = UMST_RX_EXP_PREAMBLE;
}

// trigger packet reception, auto ack if required and user didn't call reply in callback
static void _umac_trig_rx_pkt(umac *u) {
  switch (u->rx_pkt.pkt_type) {
  // got an ACKnowledge
  case UMAC_PKT_ACK:
    if (u->await_ack && u->tx_seqno == u->rx_pkt.seqno) {
      CFG_UMAC_DBG(u, "RX: ACK seq %x\n", u->rx_pkt.seqno);
      _umac_cancel_ack_timer(u);
      u->await_ack = 0;
      _umac_inc_tx_seqno(u);
      u->cfg.rx_pkt_ack_fn(u, u->rx_pkt.seqno, u->rx_pkt.data, u->rx_pkt.length);
    } else {
      CFG_UMAC_DBG(u, "RX: ACK unkn seq %x\n", u->rx_pkt.seqno);
    }
    break;
  // got an Negative ACKnowledge
  case UMAC_PKT_NACK:
    if (u->await_ack && u->tx_seqno == u->rx_pkt.seqno) {
      if (u->rx_pkt.data[0] == UMAC_NACK_ERR_BAD_CRC ||
          u->rx_pkt.data[0] == UMAC_NACK_ERR_RX_TIMEOUT) {
        CFG_UMAC_DBG(u, "RX: NACK seq %x, err %i, reTX direct\n", u->rx_pkt.seqno, u->rx_pkt.data[0]);
        _umac_cancel_ack_timer(u);
        u->tx_pkt.data = u->tx_pkt_data;
        u->tx_pkt.seqno = u->tx_seqno;
        u->tx_pkt.pkt_type = UMAC_PKT_REQ_ACK;
        u->tx_pkt.length = u->tx_pkt_length;
        _umac_tx_initial(u, &u->tx_pkt);
      } else {
        CFG_UMAC_DBG(u, "RX: NACK seq %x, err %i\n", u->rx_pkt.seqno, u->rx_pkt.data[0]);
      }
    } else {
      CFG_UMAC_DBG(u, "RX: NACK unkn seq %x, err %i\n", u->rx_pkt.seqno, u->rx_pkt.data[0]);
    }
    break;
  // got a plain packet, synced or not
  case UMAC_PKT_NREQ_ACK:
  case UMAC_PKT_REQ_ACK:
  {
    uint8_t exp_ack = u->rx_pkt.pkt_type == UMAC_PKT_REQ_ACK;
    CFG_UMAC_DBG(u, "RX: seq %x, %s (ackseq %x)\n", u->rx_pkt.seqno, exp_ack ? "sync" : "unsync", u->ack_pkt.seqno);
    uint8_t rx_seqno = u->rx_pkt.seqno;
    // see if this packet was already received and acked
    if (exp_ack && u->ack_pkt.seqno == u->rx_pkt.seqno) {
      // already received and acked, ack again with saved data
      CFG_UMAC_DBG(u, "RX: reACK seq %x, len %d\n", u->rx_pkt.seqno, u->ack_pkt.length);
      _umac_tx(u, &u->ack_pkt);
    } else {
      // new pkt, so call user
      u->rx_user_acked = 0;
      u->cfg.rx_pkt_fn(u, u->rx_pkt.seqno, u->rx_pkt.data, u->rx_pkt.length,
          u->rx_pkt.pkt_type == UMAC_PKT_REQ_ACK);
      // see if user acked it, otherwise empty ack
      if (exp_ack && !u->rx_user_acked) {
        // auto ack
        _umac_tx_ack_empty(u, rx_seqno);
      }
    }
    break;
  }
  }
}

// parse an rx char
static void _umac_parse_char(umac *u, uint8_t c) {
  switch (u->rx_state) {
  case UMST_RX_EXP_PREAMBLE:
    if (c == UMAC_PREAMBLE) {
      u->rx_pkt.seqno = 0;
      _umac_request_rx_timer(u, CFG_UMAC_RX_TIMEOUT);
      u->rx_state = UMST_RX_EXP_HDR_HI;
    } else {
      u->rx_state = UMST_RX_NOT_PREAMBLE;
      if (u->cfg.nonprotocol_data_fn) {
        u->cfg.nonprotocol_data_fn(u, c);
      }
    }
    break;
  case UMST_RX_NOT_PREAMBLE:
    if (c == UMAC_PREAMBLE) {
#ifdef CFG_UMAC_NACK_GARBAGE
      _umac_tx_nack(u, UMAC_NACK_ERR_NOT_PREAMBLE, 0x0);
#endif
      u->rx_pkt.seqno = 0;
      _umac_request_rx_timer(u, CFG_UMAC_RX_TIMEOUT);
      u->rx_state = UMST_RX_EXP_HDR_HI;
    } else {
      if (u->cfg.nonprotocol_data_fn) {
        u->cfg.nonprotocol_data_fn(u, c);
      }
    }
    break;
  case UMST_RX_EXP_HDR_HI:
    u->rx_pkt.pkt_type = (c >> 6) & 0x3;
    u->rx_pkt.seqno = (c >> 2) & 0xf;
    u->rx_pkt.length = c & 0x3;
    u->rx_local_crc = _crc_ccitt_16(UMAC_INIT_CRC, c);
    u->rx_state = u->rx_pkt.length == 0 ? UMST_RX_CRC_HI : UMST_RX_EXP_HDR_LO;
    break;
  case UMST_RX_EXP_HDR_LO:
    u->rx_pkt.length = ((u->rx_pkt.length - 1) << 8) | (c + 1);
    u->rx_local_crc = _crc_ccitt_16(u->rx_local_crc, c);
    u->rx_data_cnt = 0;
    u->rx_state = UMST_RX_DATA;
    break;
  case UMST_RX_DATA:
    u->rx_pkt.data[u->rx_data_cnt++] = c;
    u->rx_local_crc = _crc_ccitt_16(u->rx_local_crc, c);
    if (u->rx_data_cnt >= u->rx_pkt.length) {
      u->rx_state = UMST_RX_CRC_HI;
    }
    break;
  case UMST_RX_CRC_HI:
    u->rx_pkt.crc = (c<<8);
    u->rx_state = UMST_RX_CRC_LO;
    break;
  case UMST_RX_CRC_LO:
    _umac_cancel_rx_timer(u);
    u->rx_pkt.crc |= c;
    if (u->rx_pkt.crc != u->rx_local_crc) {
      _umac_tx_nack(u, UMAC_NACK_ERR_BAD_CRC, u->rx_pkt.seqno);
    } else {
      _umac_trig_rx_pkt(u);
    }
    u->rx_state = UMST_RX_EXP_PREAMBLE;
    break;
  }
}


void umac_init(umac *u, umac_cfg *cfg, uint8_t *rx_buffer, uint8_t *tx_buffer, uint8_t *ack_buffer) {
  memset(u, 0, sizeof(umac));
  memcpy(&u->cfg, cfg, sizeof(umac_cfg));
  u->rx_pkt.data = rx_buffer;
  u->tx_pkt_data = tx_buffer;
  u->ack_pkt.data = ack_buffer;
  u->tx_seqno = 1;
}

void umac_tick(umac *u) {
  umtick now = u->cfg.now_fn(u);
  _umac_timers_update(u, now);
  u->timer_enabled = 0;

  if (u->timer_rx_enabled && u->timer_rx_delta == 0) {
    u->timer_rx_enabled = 0;
    _umac_timer_trig_rx(u);
    if (u->timer_ack_enabled && u->timer_ack_delta > 0) {
      // recalc lingering ack timer
      _umac_request_ack_timer(u, u->timer_ack_delta);
    }
  }
  if (u->timer_ack_enabled && u->timer_ack_delta == 0) {
    u->timer_ack_enabled = 0;
    _umac_timer_trig_ack(u);
    if (u->timer_rx_enabled && u->timer_rx_delta > 0) {
      // recalc lingering rx timer
      _umac_request_rx_timer(u, u->timer_rx_delta);
    }
  }
}

int umac_tx_pkt(umac *u, uint8_t ack, uint8_t *buf, uint16_t len) {
  if (u->await_ack && ack) {
    CFG_UMAC_DBG(u, "TX: ERR user send sync while BUSY\n");
    return UMAC_ERR_BUSY;
  }
  if (len > 769) {
    CFG_UMAC_DBG(u, "TX: ERR too long\n");
    return UMAC_ERR_TOO_LONG;
  }
  umac_pkt *pkt = &u->tx_pkt;
  pkt->pkt_type = ack ? UMAC_PKT_REQ_ACK : UMAC_PKT_NREQ_ACK;
  if (ack) {
    memcpy(u->tx_pkt_data, buf, len);
    u->tx_pkt_length = len;
    pkt->data = u->tx_pkt_data;
  } else {
    pkt->data = buf;
  }
  pkt->length = len;
  pkt->seqno = ack ? u->tx_seqno : 0;
  _umac_tx_initial(u, pkt);
  return pkt->seqno;
}

int umac_tx_reply_ack(umac *u, uint8_t *buf, uint16_t len) {
  if (u->rx_pkt.pkt_type != UMAC_PKT_REQ_ACK) {
    CFG_UMAC_DBG(u, "TX: ERR user send ack wrong state\n");
    return UMAC_ERR_STATE;
  }
  if (len > 769) {
    CFG_UMAC_DBG(u, "TX: ERR too long\n");
    return UMAC_ERR_TOO_LONG;
  }
  u->rx_user_acked = 1;
  u->ack_pkt.pkt_type = UMAC_PKT_ACK;
  memcpy(u->ack_pkt.data, buf, len);
  u->ack_pkt.length = len;
  u->ack_pkt.seqno = u->rx_pkt.seqno;
  _umac_tx_initial(u, &u->ack_pkt);
  return UMAC_OK;
}

void umac_report_rx_byte(umac *u, uint8_t c) {
  _umac_parse_char(u, c);
}

void umac_report_rx_buf(umac *u, uint8_t *buf, uint16_t len) {
  while (len--) {
    _umac_parse_char(u, *buf++);
  }
}
