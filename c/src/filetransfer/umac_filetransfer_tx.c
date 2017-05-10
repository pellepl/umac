/*
 * umac_filetransfer_tx.c
 *
 *  Created on: May 4, 2017
 *      Author: petera
 */

#include "filetransfer/umac_filetransfer.h"

int umft_sender_tx_file(umac *u, umft *uft, umft_stream *str,
                         uint32_t filelen, const char *filename,
                         uint16_t mtu, uint32_t dt_min, uint32_t dt_max, uint32_t ddt,
                         umft_request_call_fn timer_fn, umft_status_fn status_fn) {
  memset(uft, 0, sizeof(umft));
  UMFT_ASSOCIATE_WITH_UMAC(u, uft);
  uft->tx.u = u;
  uft->tx.length = filelen;
  uft->tx.ddt = ddt;
  uft->tx.req_pkt_seqno = 0xff;
  uft->tx.stream = str;
  uft->tx.timer_fn = timer_fn;
  uft->tx.status_fn = status_fn;

  uint8_t buf[17+256];
  buf[0] = UMFT_CMD_SEND_FILE;
  UMFT_FROM_32BIT(filelen, &buf[1]);
  UMFT_FROM_16BIT(mtu, &buf[5]);
  UMFT_FROM_32BIT(dt_min, &buf[7]);
  UMFT_FROM_32BIT(dt_max, &buf[11]);
  buf[15] = strlen(filename);
  memcpy(&buf[16], filename, buf[15]);
  int res = umac_tx_pkt(u, 1, buf, 17 + buf[15]);
  if (res > 0) {
    uft->tx.req_pkt_seqno = res;
    return UMAC_OK;
  } else {
    return res;
  }
}

int umft_sender_rx_pkt_ack(umac *u, uint8_t seqno, uint8_t *data, uint16_t len) {
  umft *uft = UMFT_GET(u);
  if (seqno != uft->tx.req_pkt_seqno || data[0] != UMFT_CMD_SEND_FILE || len != 14) {
    return 0;
  }
  if (uft->tx.running) {
    // swallow and ignore
    return 1;
  }
  uft->tx.req_pkt_seqno = 0xff;
  uint8_t rxsta = data[1];
  uint16_t rxmtu = UMFT_TO_16BIT(&data[2]);
  uint32_t rxdt_min = UMFT_TO_32BIT(&data[4]);
  uint32_t rxdt_max = UMFT_TO_32BIT(&data[8]);
  uint16_t sess_id = UMFT_TO_16BIT(&data[10]);

  switch (rxsta) {
  case UMFT_STA_OK:
    uft->tx.running = 1;
    break;
  case UMFT_STA_ABORT:
  default:
    uft->tx.running = 0;
    if (uft->tx.status_fn)  uft->tx.status_fn(uft, UMFT_STA_ABORT);
    break;
  }
  if (uft->tx.running) {
    uft->tx.mtu = rxmtu;
    uft->tx.session = sess_id;
    uft->tx.dt_min = rxdt_min;
    uft->tx.dt_max = rxdt_max;
    uft->tx.dt = (rxdt_max - rxdt_min)/4 + rxdt_min;
    uft->tx.timer_fn(uft, uft->tx.dt);
  }
  return 1;
}

static int umft_send_next_chunk(umac *u, umft *uft) {
  int i;
  int res = UMAC_OK;
  uint16_t mtu = uft->tx.mtu;
  for (i = 0; i < 32; i++) {
    if ((uft->tx.txbitmask & (1<<i)) == 0) {
      uint32_t mtu_offset = i + uft->tx.tx_offset;
      uint8_t buf[1+2+4+mtu];
      buf[0] = UMFT_CMD_DATA_CHUNK;
      UMFT_FROM_16BIT(uft->tx.session, &buf[1]);
      UMFT_FROM_32BIT(mtu_offset, &buf[3]);
      uint32_t file_offs = mtu_offset * mtu;
      if (file_offs > uft->tx.length) {
        break;
      }
      uint16_t chunk_len = uft->tx.length - file_offs < mtu ? uft->tx.length - file_offs : mtu;
      uft->tx.stream->read_data_fn(uft->tx.stream, file_offs, &buf[7], chunk_len);
      res = umac_tx_pkt(u, 0, buf, 1+2+4+chunk_len);
      if (res == UMAC_OK) {
        uft->tx.txbitmask |= (1<<i);
      }
      break;
    }
  }
  return res;
}

static uint32_t _count_bits(uint32_t v) {
  // courtesy of https://graphics.stanford.edu/~seander/bithacks.html
  v = v - ((v >> 1) & 0x55555555);                    // reuse input as temporary
  v = (v & 0x33333333) + ((v >> 2) & 0x33333333);     // temp
  return ((v + ((v >> 4) & 0xF0F0F0F)) * 0x1010101) >> 24; // count
}

int umft_sender_rx_pkt(umac *u, uint8_t seqno, uint8_t *data, uint16_t len, uint8_t req_ack) {
  if (len != 12 || data[0] != UMFT_CMD_STATUS || !req_ack) {
    return 0; // not for me
  }
  umft *uft = UMFT_GET(u);

  uint16_t sess_id =  UMFT_TO_32BIT(&data[2]);
  if (sess_id != uft->tx.session) {
    return 0; // not for me
  }

  uint8_t rxsta = data[1];
  uint32_t rxoffset = UMFT_TO_32BIT(&data[4]);
  uint32_t rxbitmask = UMFT_TO_32BIT(&data[8]);


  uint8_t ack_res;
  if (!uft->tx.running) {
    // swallow and ignore
    ack_res = UMFT_STA_ABORT;
  } else {
    switch (rxsta) {
    case UMFT_STA_OK:
      ack_res = UMFT_STA_OK;
      break;
    case UMFT_STA_FIN:
      ack_res = UMFT_STA_FIN;
      uft->tx.running = 0;
      if (uft->tx.status_fn)  uft->tx.status_fn(uft, UMFT_STA_FIN);
      break;
    case UMFT_STA_ABORT:
    default:
      ack_res = UMFT_STA_ABORT;
      uft->tx.running = 0;
      if (uft->tx.status_fn)  uft->tx.status_fn(uft, UMFT_STA_ABORT);
      break;
    }
  }

  // calculate packets that got acked from previous call
  uint32_t p_rxbitmask = uft->tx.p_rxbitmask;
  uint32_t d_offs = 0;
  uint32_t acked_pkts = 0;
  if (rxoffset > uft->tx.p_rxoffset) {
    // offset shift, see what got acked in there
    d_offs = rxoffset - uft->tx.p_rxoffset;
    uint32_t adj_rxbitmask = rxbitmask << d_offs;
    // mark all shifted out as received
    adj_rxbitmask |= ((1<<d_offs)-1);
    // count all zeroes that became one
    acked_pkts += _count_bits( adj_rxbitmask & (~p_rxbitmask) );
    // now shift away the delta from previous bitmask
    p_rxbitmask >>= d_offs;
  }
  // count all zeroes that became one
  acked_pkts += _count_bits(rxbitmask & (~p_rxbitmask));

  uft->tx.acked_bytes += acked_pkts * uft->tx.mtu;

  // adjust delta time
  if (acked_pkts <= 8) {
    // dropping a lot of packets, increase delta => lower bandwidth
    uint32_t ndt = uft->tx.dt + uft->tx.ddt;
    uft->tx.dt = ndt > uft->tx.dt_max ? uft->tx.dt_max : ndt;
  } else {
    // try to decrease delta => higher bandwidth
    uint32_t ndt = uft->tx.dt > uft->tx.ddt ? (uft->tx.dt - uft->tx.ddt) : uft->tx.dt_min;
    uft->tx.dt = ndt < uft->tx.dt_min ? uft->tx.dt_min : ndt;
  }

  uft->tx.p_rxoffset = rxoffset;
  uft->tx.tx_offset = rxoffset;
  uft->tx.p_rxbitmask = rxbitmask;
  uft->tx.txbitmask = rxbitmask;

  uint8_t buf[6];
  buf[0] = UMFT_CMD_STATUS;
  buf[1] = ack_res;
  UMFT_FROM_32BIT(uft->tx.dt, &buf[2]);

  umac_tx_reply_ack(u, buf, 6);

  return 1;
}

void umft_sender_timer(umft *uft) {
  if (uft->tx.running) {
    umft_send_next_chunk(uft->tx.u, uft);
    if (uft->tx.acked_bytes < uft->tx.length) {
      uft->tx.timer_fn(uft, uft->tx.dt);
    }
  }
}
