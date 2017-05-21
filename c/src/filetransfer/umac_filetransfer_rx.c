/*
 * umac_filetransfer_rx.c
 *
 *  Created on: May 5, 2017
 *      Author: petera
 */

#include "filetransfer/umac_filetransfer.h"

#ifdef UMFT_DEF_GEN_SESSION_ID
static uint32_t __p_sess_id, __p_sess_id_c; // for default generation of session ids
#endif

void umft_reccer_init(umac *u, umft *uft, umft_stream *str,
                     uint16_t mtu, uint32_t dt_min, uint32_t dt_max,
                     umft_request_future_tick_fn timer_fn,  umft_cancel_future_tick_fn cancel_timer_fn,
                     umft_status_fn status_fn) {
  memset(uft, 0, sizeof(umft));
  UMFT_ASSOCIATE_WITH_UMAC(u, uft);
  uft->u = u;
  uft->rx.sta_pkt_seqno = 0xff;
  uft->stream = str;
  uft->timer_fn = timer_fn;
  uft->cancel_timer_fn = cancel_timer_fn;
  uft->status_fn = status_fn;
  uft->dt_min = dt_min;
  uft->dt_max = dt_max;
  uft->mtu = mtu;
}

static void umft_reccer_send_sta(umac *u, umft *uft) {
  uint8_t buf[12];
  buf[0] = UMFT_CMD_STATUS;
  UMFT_FROM_16BIT(uft->session, &buf[1]);
  buf[3] = uft->running
      ? (uft->acked_bytes >= uft->length ? UMFT_STA_FIN : UMFT_STA_OK)
      : UMFT_STA_ABORT;
  UMFT_FROM_32BIT(uft->rx.rx_offset, &buf[4]);
  UMFT_FROM_32BIT(uft->rx.rxbitmask, &buf[8]);

  int res = umac_tx_pkt(u, 1, buf, 12);
  if (res > 0) {
    uft->rx.sta_pkt_seqno = res;
  } else {
    uft->rx.sta_pkt_seqno = 0xff;
  }
}

int umft_reccer_rx_pkt(umac *u, uint8_t seqno, uint8_t *data, uint16_t len, uint8_t req_ack) {
  umft *uft = UMFT_GET(u);
  if (len >= 17 && data[0] == UMFT_CMD_SEND_FILE && req_ack) {
    uft->length = UMFT_TO_32BIT(&data[1]);
    uint16_t txmtu = UMFT_TO_16BIT(&data[5]);
    uint32_t txdt_min = UMFT_TO_32BIT(&data[7]);
    uint32_t txdt_max = UMFT_TO_32BIT(&data[11]);
    uint8_t filenamelen = data[15];

    uint16_t mtu;
    uint32_t dt_min, dt_max;
    if (txdt_min == 0)            dt_min = uft->dt_min;
    else if (uft->dt_min == 0)    dt_min = txdt_min;
    else                          dt_min = uft->dt_min > txdt_min ? uft->dt_min : txdt_min;
    if (dt_min == 0)              dt_min = UMFT_DEF_DT_MIN;
    if (txdt_max == 0)            dt_max = uft->dt_max;
    else if (uft->dt_max == 0)    dt_max = txdt_max;
    else                          dt_max = uft->dt_max < txdt_max ? uft->dt_max : txdt_max;
    if (dt_max == 0)              dt_max = UMFT_DEF_DT_MAX;
    if (txmtu == 0)               mtu = uft->mtu;
    else if (uft->mtu == 0)       mtu = txmtu;
    else                          mtu = uft->mtu < txmtu ? uft->mtu : txmtu;
    if (mtu == 0)                 mtu = UMFT_DEF_MTU;
    uft->mtu = mtu;

    int create = uft->stream->create_data_fn(uft->stream, uft->length,
                                                (const char *)&data[16], filenamelen);
    uft->session = UMFT_GENERATE_SESSION_ID(u);
    uint8_t ack[14];
    ack[0] = UMFT_CMD_SEND_FILE;
    ack[1] = create == 0 ? UMFT_STA_OK : UMFT_STA_ABORT;
    UMFT_FROM_16BIT(mtu, &ack[2]);
    UMFT_FROM_32BIT(dt_min, &ack[4]);
    UMFT_FROM_32BIT(dt_max, &ack[8]);
    UMFT_FROM_16BIT(uft->session, &ack[12]);

    umac_tx_reply_ack(u, ack, 14);

    uft->running = create == 0;

    return 1;
  } else if (len >= 7 && data[0] == UMFT_CMD_DATA_CHUNK && !req_ack) {
    uft->rx.rec_chunks++;
    uint16_t session = UMFT_TO_16BIT(&data[1]);
    if (session != uft->session) {
      return 0; // not for me
    }
    uint32_t offset_mtu = UMFT_TO_32BIT(&data[3]);
    do {
      if (!uft->running)
        break;
      if (offset_mtu < uft->rx.rx_offset || offset_mtu >= uft->rx.rx_offset + 32)
        // outside window
        break;
      if ((uft->rx.rxbitmask & (1 << (offset_mtu - uft->rx.rx_offset))) != 0)
        // already received
        break;
      // try save
      int save_res = uft->stream->write_data_fn(
          uft->stream,
          offset_mtu * uft->mtu,
          &data[7],
          len-7);
      if (save_res == 0) {
        // saved ok, mark it
        uft->acked_bytes += len-7;
        uint32_t rx_offset = uft->rx.rx_offset;
        uint32_t rxbitmask = uft->rx.rxbitmask;
        if (rx_offset == offset_mtu) {
          do {
            rx_offset++;
            rxbitmask >>= 1;
          } while (rxbitmask & 1);
        } else {
          rxbitmask |= (1 << (offset_mtu - rx_offset));
        }
        uft->rx.rx_offset = rx_offset;
        uft->rx.rxbitmask = rxbitmask;
      }
    } while (0);

    if (uft->running) {
      uft->cancel_timer_fn(uft);
      if (uft->acked_bytes >= uft->length) {
        uft->running = 0;
        umft_reccer_send_sta(u, uft);
        if (uft->status_fn)  uft->status_fn(uft, UMFT_STA_FIN);
      } else {
        if (uft->rx.rec_chunks > 16) {
          uft->rx.rec_chunks = 0;
          umft_reccer_send_sta(u, uft);
        }
        uft->timer_fn(uft, uft->rx.dt * 16);
      }
    }
    return 1;
  } else {
    return 0;
  }
}

int umft_reccer_rx_pkt_ack(umac *u, uint8_t seqno, uint8_t *data, uint16_t len) {
  umft *uft = UMFT_GET(u);
  if (seqno != uft->rx.sta_pkt_seqno || data[0] != UMFT_CMD_STATUS || len != 12) {
    return 0;
  }
  if (!uft->running) {
    // swallow and ignore
    return 1;
  }

  uint8_t sta = data[1];
  uint32_t dt = UMFT_TO_32BIT(&data[2]);

  if (sta != UMFT_STA_OK) {
    uft->running = 0;
    uft->cancel_timer_fn(uft);
    if (uft->status_fn)  uft->status_fn(uft, sta);
  }
  uft->rx.dt = dt;
  return 1;
}

void umft_reccer_tick(umft *uft) {
  if (uft->running && uft->acked_bytes < uft->length) {
    umft_reccer_send_sta(uft->u, uft);
    uft->timer_fn(uft, uft->rx.dt * 16);
  }
}
