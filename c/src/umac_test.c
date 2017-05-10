/*
 * main.c
 *
 *  Created on: May 3, 2017
 *      Author: petera
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include "umac.h"

#define COMM_BUF_SZ       32768

#define STRBEGIN(s1, s2) \
  strstr((s1),(s2))==(s1)
#define STREND(s1, s2) \
  strstr((s1),(s2))==(s1)+strlen(s1)-strlen(s2)-1
#define QUOTED(s1) \
  (uint8_t *)(strchr((s1),'"')==0 ? 0 : (strchr((s1),'"')+1))
#define QUOTED_LEN(s1) \
  (uint16_t)(strchr((s1),'"')==0 ? 0 : (strchr(strchr((s1),'"')+1,'"')) - strchr((s1),'"') - 1)

#define UNDEF_EXP   1000
#define ANY_EXP     0xffff

#define DBG(fmt, ...) do { \
  if (verbose) { \
    printf(fmt, ## __VA_ARGS__); \
  } \
} while (0)

static umac ua, ub;
static umac_cfg ucfg;
static uint8_t uarx[769];
static uint8_t uatx[769];
static uint8_t uaack[769];
static uint8_t ubrx[769];
static uint8_t ubtx[769];
static uint8_t uback[769];

typedef struct {
  const char *name;
  umtick time;
  uint8_t alarm_on;
  umtick alarm;
  uint8_t rx[COMM_BUF_SZ];
  uint32_t rx_in;
  uint32_t rx_out;
  uint8_t tx_stopped;
  uint32_t rx_per_tick;
  uint8_t exp_rx[769];
  uint16_t exp_rx_len;
  uint8_t exp_ack[769];
  uint16_t exp_ack_len;
  uint8_t nxt_ack[769];
  uint16_t nxt_ack_len;
  uint8_t exp_tmo;
  int exp_err;
} uctx;

static uctx uactx, ubctx;

static uint8_t verbose = 0;

static void _test_cancel_timer(umac *u) {
  uctx *uc = (uctx *)u->user;
  if (uc->alarm_on == 0) {
    printf("%s multiple timer disable\n", uc->name);
    exit(EXIT_FAILURE);
  }
  uc->alarm_on = 0;
}

static umtick _test_now(umac *u) {
  uctx *uc = (uctx *)u->user;
  return uc->time;
}

static void _test_timer(umac *u, umtick delta) {
  uctx *uc = (uctx *)u->user;
  if (uc->alarm_on) {
    printf("%s multiple timer enable\n", uc->name);
    exit(EXIT_FAILURE);
  }
  uc->alarm = uc->time + delta;
  uc->alarm_on = 1;
}


static void _test_txc(umac *me, uint8_t c) {
  uctx *ucme = (uctx *)me->user;
  if (ucme->tx_stopped) return;
  umac *udst = me == &ua ? &ub : &ua;
  uctx *uc = (uctx *)udst->user;
  uc->rx[uc->rx_in++] = c;
  if (uc->rx_in >= COMM_BUF_SZ) {
    uc->rx_in = 0;
  }
  if (uc->rx_in == uc->rx_out) {
    printf("%s rx overflow\n", uc->name);
    exit(EXIT_FAILURE);
  }
}

static void _test_tx(umac *me, uint8_t *b, uint16_t l) {
  //uctx *uc = (uctx *)me->user;
  //DBG("[%s] tx  %d bytes %s\n", uc->name, l, uc->tx_stopped ? "BLOCKED":"");
  while (l--) _test_txc(me, *b++);
}

static void test_ctx_tick(umac *u) {
  uctx *uc = (uctx *)u->user;
  uc->time++;
  if (uc->alarm_on && uc->time >= uc->alarm) {
    uc->alarm_on = 0;
    umac_tick(u);
  }
}

static void test_ctx_rx(umac *u) {
  uctx *uc = (uctx *)u->user;
  uint32_t rx_per_tick = uc->rx_per_tick == 0 ? 1 : uc->rx_per_tick;
  while (uc->rx_out != uc->rx_in && rx_per_tick) {
    umac_report_rx_byte(u, uc->rx[uc->rx_out++]);
    if (uc->rx_out >= COMM_BUF_SZ) {
      uc->rx_out = 0;
    }
    if (uc->rx_per_tick) rx_per_tick--;
  }
}

static void test_tick(umac *u) {
  test_ctx_rx(u);
  test_ctx_tick(u);
}

static void test_tick_all(void) {
  test_tick(&ua);
  test_tick(&ub);
}

static void test_pkt_rx(umac *u, uint8_t seqno, uint8_t *data, uint16_t len,
    uint8_t req_ack) {
  uctx *uc = (uctx *)u->user;
  data[len] = 0;
  DBG("[%s] RX%c %1x %s\n", uc->name, req_ack ? '*' : ' ', seqno,
             len == 0 ? "" : (char *)data);
  if (uc->exp_rx_len != ANY_EXP) {
    if (!(uc->exp_rx_len == len && (uc->exp_rx_len == 0 || strncmp((char *)data, (char *)uc->exp_rx, uc->exp_rx_len) == 0))) {
      printf("RX mismatch, got \"%s\", expected \"%s\"\n", data, uc->exp_rx);
      exit(EXIT_FAILURE);
    }
    uc->exp_rx_len = UNDEF_EXP;
    memset(uc->exp_rx, 0x00, 769);
  }
  if (req_ack && uc->nxt_ack_len) {
    //DBG("[%s] RPL %1x %s\n", uc->name, p->seqno, uc->nxt_ack_len == 0 ? "" : (char *)uc->nxt_ack);
    umac_tx_reply_ack(u, uc->nxt_ack, uc->nxt_ack_len);
    uc->nxt_ack_len = 0;
  }

}

static void test_pkt_ack(umac *u, uint8_t seqno, uint8_t *data, uint16_t len) {
  uctx *uc = (uctx *)u->user;
  data[len] = 0;
  DBG("[%s] ACK %1x %s\n", uc->name, seqno, len == 0 ? "" : (char *)data);
  if (uc->exp_ack_len != ANY_EXP) {
    if (uc->exp_ack_len != len ||
        (len != 0 && strncmp((char *)data, (char *)uc->exp_ack, uc->exp_ack_len) != 0)) {
      printf("ACK mismatch, got \"%s\", expected \"%s\"\n", data, uc->exp_ack);
      exit(EXIT_FAILURE);
    }
    uc->exp_ack_len = UNDEF_EXP;
    memset(uc->exp_ack, 0x00, 769);
  }
}

static void test_pkt_tmo(umac *u, umac_pkt *p) {
  uctx *uc = (uctx *)u->user;
  DBG("[%s] TMO %1x\n", uc->name, p->seqno);
  if (uc->exp_tmo) {
    uc->exp_tmo = 0;
  } else {
    printf("TMO unexpected\n");
    exit(EXIT_FAILURE);
  }
}

static off_t loop_offs;
static uint32_t loop_count;

static void handle_line(FILE *fd, char *line, uint32_t len) {

  /////////// tranceiver specific

  if (STRBEGIN(line, "A.") || STRBEGIN(line, "B.")) {
    umac *u = line[0] == 'A' ? &ua : &ub;
    uctx *uc = (uctx *)u->user;
    line += 2;

    /////////// TX

    if (STRBEGIN(line, "tx ")) {
      uint8_t ack = STREND(line, "ack");
      int err = umac_tx_pkt(u, ack, QUOTED(line), QUOTED_LEN(line));
      if (err < 0) {
        if (uc->exp_err == err) {
          uc->exp_err = UMAC_OK;
          DBG("[%s] exp err received, clearerr\n", uc->name);
        } else {
          printf("Unexpected error %d\n", err);
          exit(EXIT_FAILURE);
        }
      }
    }

    /////////// RX

    else if (STRBEGIN(line, "rx ")) {
      if (STREND(line, "*")) {
        uc->exp_rx_len = ANY_EXP;
      } else {
        uc->exp_rx_len = QUOTED_LEN(line);
        if (QUOTED(line)) {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wnonnull"
          memcpy(uc->exp_rx, QUOTED(line), uc->exp_rx_len);
#pragma GCC diagnostic pop
        }
      }
    }

    /////////// EXPECT ACK

    else if (STRBEGIN(line, "ack ")) {
      if (STREND(line, "*")) {
        uc->exp_ack_len = ANY_EXP;
      } else {
        uc->exp_ack_len = QUOTED_LEN(line);
        if (QUOTED(line)) {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wnonnull"
          memcpy(uc->exp_ack, QUOTED(line), uc->exp_ack_len);
#pragma GCC diagnostic pop
        }
      }
    }

    /////////// DEFINE NEXT ACK

    else if (STRBEGIN(line, "nextack ")) {
      uc->nxt_ack_len = QUOTED_LEN(line);
      if (QUOTED(line)) {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wnonnull"
        memcpy(uc->nxt_ack, QUOTED(line), uc->nxt_ack_len);
#pragma GCC diagnostic pop
      }
    }

    /////////// EXPECT TMO

    else if (STRBEGIN(line, "tmo")) {
      uc->exp_tmo = 1;
    }

    /////////// EXPECT ERR

    else if (STRBEGIN(line, "err")) {
      uc->exp_err = atoi(strrchr(line, ' ') + 1);
    }

    /////////// TICK

    else if (STRBEGIN(line, "tick")) {
      if (len <= 7) {
        test_tick(u);
      } else {
        int ticks = atoi(strrchr(line, ' ') + 1);
        while (ticks--) test_tick(u);
      }
    }

    /////////// TX BLOCK

    else if (STRBEGIN(line, "txblock")) {
      if (STREND(line,"1") || STREND(line,"on")) {
        uc->tx_stopped = 1;
      }
      else if (STREND(line,"0") || STREND(line,"off")) {
        uc->tx_stopped = 0;
      }
      else {
        printf("Unknown test script argument for txblock:\n%s\n", line);
        exit(EXIT_FAILURE);
      }
    }

    /////////// RX THRUPUT

    else if (STRBEGIN(line, "rxspeed")) {
      if (STREND(line,"full") || STREND(line,"0")) {
        uc->rx_per_tick = 0;
      }
      else {
        uc->rx_per_tick = atoi(strrchr(line, ' ') + 1);
      }
    }

  /////////// general

  } else if (STRBEGIN(line, "tick")) {
    if (len <= 5) {
      test_tick_all();
    } else {
      int ticks = atoi(strrchr(line, ' ') + 1);
      while (ticks--) test_tick_all();
    }
  } else if (STRBEGIN(line, "loop")) {
    loop_count = atoi(strrchr(line, ' ') + 1);
    loop_offs = ftell(fd);
  } else if (STRBEGIN(line, "endloop")) {
    if (loop_count) {
      loop_count--;
      fseek(fd, loop_offs, SEEK_SET);
    }
  } else if (len < 2 || line[0] == '#') {
  } else {
    printf("Unknown test script line:\n%s\n", line);
    exit(EXIT_FAILURE);
  }
}

static int check_ctx(uctx *uc) {
  int err = 0;
  if (uc->exp_rx_len != UNDEF_EXP && uc->exp_rx_len != ANY_EXP) {
    printf("%s unmatched RX %04x: %s\n", uc->name, uc->exp_rx_len, uc->exp_rx);
    err = 1;
  }
  if (uc->exp_ack_len != UNDEF_EXP && uc->exp_ack_len != ANY_EXP) {
    printf("%s unmatched ACK %04x: %s\n", uc->name, uc->exp_ack_len, uc->exp_ack);
    err = 1;
  }
  if (uc->nxt_ack_len != 0) {
    printf("%s unsent ACK %04x: %s\n", uc->name, uc->nxt_ack_len, uc->nxt_ack);
    err = 1;
  }
  if (uc->exp_tmo) {
    printf("%s unmatched TMO\n", uc->name);
    err = 1;
  }
  if (uc->exp_err) {
    printf("%s unmatched ERR %d\n", uc->name, uc->exp_err);
    err = 1;
  }
  return err;
}

int main(int argc, char **argv) {
  ucfg.cancel_timer_fn = _test_cancel_timer;
  ucfg.nonprotocol_data_fn = 0;
  ucfg.now_fn = _test_now;
  ucfg.timer_fn = _test_timer;
  ucfg.tx_byte_fn = _test_txc;
  ucfg.tx_buf_fn = _test_tx;
  ucfg.rx_pkt_fn = test_pkt_rx;
  ucfg.rx_pkt_ack_fn = test_pkt_ack;
  ucfg.timeout_fn = test_pkt_tmo;
  umac_init(&ua, &ucfg, uarx, uatx, uaack);
  ua.user = &uactx;
  umac_init(&ub, &ucfg, ubrx, ubtx, uback);
  ub.user = &ubctx;
  memset(&uactx, 0, sizeof(uctx));
  memset(&ubctx, 0, sizeof(uctx));
  uactx.name = ANSI_COLOR_GREEN  "ADAM" ANSI_COLOR_RESET;
  ubctx.name = ANSI_COLOR_YELLOW "BESS" ANSI_COLOR_RESET;
  ua._dbg_name = "umac ADAM ";
  ub._dbg_name = "umac BESS ";

  if (STRBEGIN(argv[2], "verbose=")) {
    verbose = argv[2][strlen(argv[2]) - 1] == '1';
  }

  FILE *fd = fopen(argv[1], "r");
  if (fd == 0) {
    printf("Could not open %s\n", argv[1]);
    exit(EXIT_FAILURE);
  }

  char *line = 0;
  size_t len;
  ssize_t read;
  while ((read = getline(&line, &len, fd)) > 0) {
    DBG(ANSI_COLOR_MAGENTA "%s" ANSI_COLOR_RESET, line);
    handle_line(fd, line, read);
  }
  DBG("\n");

  fclose(fd);
  free(line);

  if (check_ctx(&uactx) | check_ctx(&ubctx)) {
    exit(EXIT_FAILURE);
  }

  return 0;
}
