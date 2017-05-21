/*
 * umac.h
 *
 * micro mac
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

#ifndef _UMAC_H_
#define _UMAC_H_

/*
  DATA LINK FORMAT
    PKT header
    8 bits : 0xfd       preamble
    2 bits : 0,1,2,3    0:PKT_NREQ  1:PKT_REQ  2:ACK  3:NACK
                          PKT_NREQ : packet not requiring ack
                          PKT_REQ  : packet requiring ack
                          ACK      : acknowledge of good packet
                          NACK     : acknowledge of bad packet, length 1 with error code
    4 bits : seqno      sequence nbr
    2 bits : len_def    0: no data
                        1: data bytes = datlen + 1
                        2: data bytes = datlen + 1 + 256
                        3: data bytes = datlen + 1 + 512
   (8 bits : datlen     data length in bytes) if len_def > 0
   ( ..      payload    data                ) if len_def bytes > 0
    16 bits: checksum   CRC-CCITT16 of whole packet excluding preamble

     76543210  76543210   76543210  data   76543210  76543210
    [PREAMBLE][TySeqnLd]([DatLen  ][]..[])[CrcHi   ][CrcLo   ]

    For packets requiring ack (PKT_REQ), the seqno increments from 1..15.
    For packets not requiring ack (PKT_NREQ), the seqno is 0.
    An ACK or NACK keeps the seqno of the packet being acked or nacked, if known.
    If unknown, 0x0 is used as seqno.

    NACK error codes:
      0x01 - not preamble
      0x02 - bad crc
      0x03 - rx timeout
      0x04 - not ready

 */

#include "umac_cfg.h"

#ifndef CFG_UMAC_DBG
#define CFG_UMAC_DBG(...)
#endif

#ifndef CFG_UMAC_RETRIES
#define CFG_UMAC_RETRIES              10
#endif

#ifndef CFG_UMAC_RETRY_DELTA
#define CFG_UMAC_RETRY_DELTA(try)     40
#endif

#ifndef CFG_UMAC_RX_TIMEOUT
#define CFG_UMAC_RX_TIMEOUT           2*CFG_UMAC_RETRY_DELTA(1)*CFG_UMAC_RETRIES
#endif

#ifndef CFG_UMAC_TICK_TYPE
#define CFG_UMAC_TICK_TYPE            uint32_t
#endif

#ifndef UMAC_MAX_LEN
#define UMAC_MAX_LEN                  769
#endif

#if UMAC_MAX_LEN > 769
#error UMAC_MAX_LEN must not exceed 769 bytes
#endif

#define UMAC_PREAMBLE                 0xfd

#define UMAC_NACK_ERR_NOT_PREAMBLE    0x01
#define UMAC_NACK_ERR_BAD_CRC         0x02
#define UMAC_NACK_ERR_RX_TIMEOUT      0x03
#define UMAC_NACK_ERR_NOT_READY       0x04

#ifndef _UMAC_ERR_BASE
#define _UMAC_ERR_BASE                (-70000)
#endif
#define UMAC_OK                       0
#define UMAC_ERR_BUSY                 (_UMAC_ERR_BASE-1)
#define UMAC_ERR_STATE                (_UMAC_ERR_BASE-2)
#define UMAC_ERR_TOO_LONG             (_UMAC_ERR_BASE-3)

typedef CFG_UMAC_TICK_TYPE umtick;

/**
 * UMAC packet type enum
 */
typedef enum {
  UMAC_PKT_NREQ_ACK = 0, /* tx, not needing ack */
  UMAC_PKT_REQ_ACK, /* tx, needing ack */
  UMAC_PKT_ACK, /* rx, ack */
  UMAC_PKT_NACK /* rx, nack */
} umac_pkt_type;

/**
 * UMAC rx parser state enum
 */
typedef enum {
  UMST_RX_EXP_PREAMBLE = 0,
  UMST_RX_NOT_PREAMBLE,
  UMST_RX_EXP_HDR_HI,
  UMST_RX_EXP_HDR_LO,
  UMST_RX_DATA,
  UMST_RX_CRC_HI,
  UMST_RX_CRC_LO,
} umac_rx_state;

/**
 * UMAC packet struct
 */
typedef struct {
  /** type of packet */
  umac_pkt_type pkt_type;
  /** packet sequence nbr */
  uint8_t seqno;
  /** packet data */
  uint8_t *data;
  /** packet data length */
  uint16_t length;
  /** packet crc */
  uint16_t crc;
} umac_pkt;

struct umac_s;

/** request a call to umac_tick in given ticks */
typedef void (* umac_request_future_tick)(struct umac_s *u, umtick delta_tick);
/** cancel request to call umac_tick */
typedef void (* umac_cancel_future_tick)(struct umac_s *u);
/** returns current system time */
typedef umtick (* umac_now_tick)(struct umac_s *u);
/** transmit one byte */
typedef void (* umac_tx_byte)(struct umac_s *u, uint8_t c);
/** transmit multiple bytes */
typedef void (* umac_tx_buf)(struct umac_s *u, uint8_t *c, uint16_t len);
/** handle reception of a packet */
typedef void (* umac_rx_pkt)(struct umac_s *u, uint8_t seqno, uint8_t *data, uint16_t len,
    uint8_t req_ack);
/** handle acknowledge of a synchronized sent packet */
typedef void (* umac_tx_pkt_acked)(struct umac_s *u, uint8_t seqno, uint8_t *data, uint16_t len);
/** handle timeout of a synchronized sent packet, no ack */
typedef void (* umac_timeout)(struct umac_s *u, umac_pkt *pkt);
/** handle non UMAC data */
typedef void (* umac_nonprotocol_data)(struct umac_s *u, uint8_t c);

/**
 * UMAC configuration struct
 */
typedef struct {
  /** Requests that umac_tick is to be called within given ticks */
  umac_request_future_tick timer_fn;
  /** Cancel any previous request to call umac_tick */
  umac_cancel_future_tick cancel_timer_fn;
  /** Returns current system tick */
  umac_now_tick now_fn;
  /** Transmits one byte over the PHY layer */
  umac_tx_byte tx_byte_fn;
  /** Transmits a buffer over the PHY layer */
  umac_tx_buf tx_buf_fn;
  /** Called when a packet is received */
  umac_rx_pkt rx_pkt_fn;
  /** Called when a synchronous packet is acked from other side */
  umac_tx_pkt_acked rx_pkt_ack_fn;
  /** Called if a synchronous packet is not acked within timeout */
  umac_timeout timeout_fn;
  /** Called if non protocol data is received */
  umac_nonprotocol_data nonprotocol_data_fn;
} umac_cfg;

/**
 * UMAC stack struct
 */
typedef struct umac_s {
  /** umac config */
  umac_cfg cfg;
  /** parse state */
  umac_rx_state rx_state;
  /** temporary buffer for small packets*/
  uint8_t tmp[8];
  /** rx packet struct */
  umac_pkt rx_pkt;
  /** parser rx data count */
  uint16_t rx_data_cnt;
  /** parser rx data local crc calculation */
  uint16_t rx_local_crc;
  /** if rxed packet is acked by user or not */
  uint8_t rx_user_acked;
  /** current tx packet sequence nbr */
  uint8_t tx_seqno;
  /** current tx packet */
  umac_pkt tx_pkt;
  /** tx packet data for resending */
  uint8_t *tx_pkt_data;
  /** tx packet length for resending */
  uint16_t tx_pkt_length;
  /** ack packet struct */
  umac_pkt ack_pkt;
  /** if we have a packet awaiting ack in air */
  uint8_t await_ack;
  /** how many times current tx packet needing ack have been sent */
  uint8_t retry_ctr;
  /** if system timer is running */
  uint8_t timer_enabled;
  /** if ack timer is running */
  uint8_t timer_ack_enabled;
  /** time until the ack time will trigger, or zero */
  umtick timer_ack_delta;
  /** time when ack timer was requested */
  umtick timer_ack_start_tick;
  /** if rx timer is running */
  uint8_t timer_rx_enabled;
  /** time until the rx time will trigger, or zero */
  umtick timer_rx_delta;
  /** time when rx timer was requested */
  umtick timer_rx_start_tick;
  /** user data */
  void *user;
  /** dbg data */
  const char *_dbg_name;
} umac;

/**
 * Initiates protocol stack with given configuration and
 * given rx, tx and ack buffer. The buffers should be 769 bytes each.
 * @param u           stack struct
 * @param cfg         stack configuration
 * @param rx_buffer   rx buffer
 * @param tx_buffer   tx buffer
 * @param ack_buffer  ack buffer
 */
void umac_init(umac *u, umac_cfg *cfg,
               uint8_t *rx_buffer, uint8_t *tx_buffer, uint8_t *ack_buffer);

/**
 * Call umac_tick when a requested timer times out. See
 * umac_request_future_tick timer_fn in config struct.
 * @param u   stack struct
 */
void umac_tick(umac *u);

/**
 * Transmits a packet. Returns sequence number of txed packet,
 * or 0 if packet does not need ack.
 * @param u   stack struct
 * @param ack 0 if packet does not need ack, else packet needs ack
 * @param buf packet data
 * @param len packet data length
 * @return sequence number on acked packet, 0 otherwise, negative on error
 */
int umac_tx_pkt(umac *u, uint8_t ack, uint8_t *buf, uint16_t len);

/**
 * When a synchronous packet is received, umac_rx_pkt rx_pkt_fn
 * in config struct is called. In this call, user may ack with
 * piggybacked data if wanted. If not, the stack autoacks with an
 * empty ack.
 * @param u   stack struct
 * @param buf ack data
 * @param len ack data length
 * @return 0 if ok, negative on error
 */
int umac_tx_reply_ack(umac *u, uint8_t *buf, uint16_t len);

/**
 * Report to stack that a byte was received from PHY.
 */
void umac_report_rx_byte(umac *u, uint8_t c);

/**
 * Report to stack that a buffer was received from PHY.
 */
void umac_report_rx_buf(umac *u, uint8_t *buf, uint16_t len);

#endif /* _UMAC_H_ */
