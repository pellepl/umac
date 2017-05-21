/*
 * umac_filetransfer.h
 *
 *  Created on: May 4, 2017
 *      Author: petera
 */

#ifndef _UMAC_FILETRANSFER_H_
#define _UMAC_FILETRANSFER_H_

#include "umac.h"

/**
 * TX   sender -> reccer
 * |--0--|-1-2-3-4-|-5-6-|-7..10--|-11-14--|-----15------|--..end---|
 * | CMD | FILELEN | MTU | DT_MIN | DT_MAX | FILENAMELEN | FILENAME |
 *
 * ACK  sender <- reccer
 * |--0--|--1--|-2-3-|--4..7--|-8..11--|-12-13--|
 * | CMD | STA | MTU | DT_MIN | DT_MAX | SESSID |
 */
#define UMFT_CMD_SEND_FILE   0x10
/**
 * TX   sender -> reccer
 * |--0--|--1-2---|-3-4-5-6-|-..end-|
 * | CMD | SESSID | OFFSET | DATA  |
 */
#define UMFT_CMD_DATA_CHUNK  0x11
/**
 * TX   sender <- reccer
 * |--0--|--1-2---|--3--|-4-5-6-7-|--8..11--|
 * | CMD | SESSID | STA |  OFFSET | BITMASK |
 * ACK  sender -> reccer
 * |--0--|--1--|--2-5--|
 * | CMD | STA |  DT   |
 */
#define UMFT_CMD_STATUS      0x12

#define UMFT_STA_OK          0x00
#define UMFT_STA_FIN         0x01
#define UMFT_STA_ABORT       0x02

#define UMFT_DEF_DT_MIN      7
#define UMFT_DEF_DT_MAX      10
#define UMFT_DEF_MTU         250

typedef struct umft_stream_s {
  /** For sender */
  void (* read_data_fn)(struct umft_stream_s *s, uint32_t offs, uint8_t *dst, uint32_t len);
  /** For receiver: if returning zero, the file is accepted, else the transfer is aborted */
  int (* create_data_fn)(struct umft_stream_s *s, uint32_t len, const char *name, uint8_t namelen);
  /** For receiver: if returning zero, the received data is considered written, else the transfer is aborted */
  int (* write_data_fn)(struct umft_stream_s *s, uint32_t offs, uint8_t *src, uint32_t len);
  void *user;
} umft_stream;

struct umft_s;

typedef void (* umft_request_future_tick_fn)(struct umft_s *uft, umtick delta);
typedef void (* umft_cancel_future_tick_fn)(struct umft_s *uft);
typedef void (* umft_status_fn)(struct umft_s *uft, uint8_t res);

typedef struct umft_s {
  union {
    umac *u;
    umft_stream *stream;
    uint16_t session;
    uint32_t length;
    uint32_t acked_bytes;
    uint16_t mtu;
    uint32_t dt_min;
    uint32_t dt_max;
    umft_request_future_tick_fn timer_fn;
    umft_cancel_future_tick_fn cancel_timer_fn;
    umft_status_fn status_fn;
    uint8_t running;
    struct {
      uint32_t dt;
      uint32_t ddt;
      uint32_t p_rxoffset; // in mtu units
      uint32_t p_rxbitmask; // in mtu units
      volatile uint32_t tx_offset; // in mtu units
      volatile uint32_t txbitmask; // in mtu units
      uint8_t req_pkt_seqno;
    } tx;
    struct {
      uint8_t rec_chunks;
      uint32_t dt;
      volatile uint32_t rx_offset; // in mtu units
      volatile uint32_t rxbitmask; // in mtu units
      uint8_t sta_pkt_seqno;
    } rx;
  };
  void *user;
} umft;

#ifndef UMFT_ASSOCIATE_WITH_UMAC
#define UMFT_ASSOCIATE_WITH_UMAC(__u, __umft) (__u)->user = (__umft)
#endif
#ifndef UMFT_GET
#define UMFT_GET(__u) (umft *)(__u)->user
#endif
#ifndef UMFT_GENERATE_SESSION_ID
#define UMFT_DEF_GEN_SESSION_ID
#define UMFT_GENERATE_SESSION_ID(__u) \
  (uint16_t)(__p_sess_id = __p_sess_id ^ (++__p_sess_id_c + (__u)->cfg.now_fn(__u) * 0xaa7f8ea9UL))
#endif

#define UMFT_TO_32BIT(d) \
  (((d)[0] << 24) | ((d)[1] << 16) | ((d)[2] << 8) | ((d)[3]))
#define UMFT_FROM_32BIT(i, d) do { \
  ((d)[0])=i>>24; ((d)[1])=i>>16; ((d)[2])=i>>8; ((d)[3])=i; \
} while (0)
#define UMFT_TO_16BIT(d) \
  (((d)[0] << 8) | ((d)[1]))
#define UMFT_FROM_16BIT(i, d) do { \
  ((d)[0])=i>>8; ((d)[1])=i; \
} while (0)

/**
 * Start transferring a file over umac. The umft_stream *str must be populated
 * with functions. mtu, dt_min, and dt_max are suggestions - the receiver will
 * decide. These may be zero.
 */
int umft_sender_tx_file(umac *u, umft *uft, umft_stream *str,
                        uint32_t filelen, const char *filename,
                        uint16_t mtu, uint32_t dt_min, uint32_t dt_max, uint32_t ddt,
                        umft_request_future_tick_fn timer_fn, umft_status_fn status_fn);

/**
 * Call when timer has timed out, as requested by umft_request_call_fn timer_fn
 * in umft_sender_tx_file.
 */
void umft_sender_tick(umft *uft);

/**
 * Call when packet is received over umac. Returns 1 if this was a file
 * transfer packet and handled, 0 otherwise.
 */
int umft_sender_rx_pkt(umac *u, uint8_t seqno, uint8_t *data, uint16_t len, uint8_t req_ack);

/**
 * Call when ack is received over umac. Returns 1 if this was a file
 * transfer ack and handled, 0 otherwise.
 */
int umft_sender_rx_pkt_ack(umac *u, uint8_t seqno, uint8_t *data, uint16_t len);



/**
 * Initilialize file receiver over umac. The umft_stream *str must be populated
 * with functions. mtu, dt_min and dt_max may be zero, in which case the senders
 * settings will be used.
 */
void umft_reccer_init(umac *u, umft *uft, umft_stream *str,
                     uint16_t mtu, uint32_t dt_min, uint32_t dt_max,
                     umft_request_future_tick_fn timer_fn,  umft_cancel_future_tick_fn cancel_timer_fn,
                     umft_status_fn status_fn);

/**
 * Call when timer has timed out, as requested by umft_request_call_fn timer_fn
 * in umft_reccer_init.
 */
void umft_reccer_tick(umft *uft);

/**
 * Call when packet is received over umac. Returns 1 if this was a file
 * transfer packet and handled, 0 otherwise.
 */
int umft_reccer_rx_pkt(umac *u, uint8_t seqno, uint8_t *data, uint16_t len, uint8_t req_ack);

/**
 * Call when ack is received over umac. Returns 1 if this was a file
 * transfer ack and handled, 0 otherwise.
 */
int umft_reccer_rx_pkt_ack(umac *u, uint8_t seqno, uint8_t *data, uint16_t len);

#endif /* _UMAC_FILETRANSFER_H_ */
