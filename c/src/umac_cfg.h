/*
 * umac_cfg.h
 *
 *  Created on: May 3, 2017
 *      Author: petera
 */

#ifndef _UMAC_CFG_H_
#define _UMAC_CFG_H_

#include <stdint.h>
#include <string.h>
#include <stdio.h>

#define ANSI_COLOR_GREEN   "\x1b[32m"
#define ANSI_COLOR_YELLOW  "\x1b[33m"
#define ANSI_COLOR_RED     "\x1b[31m"
#define ANSI_COLOR_BLUE    "\x1b[34m"
#define ANSI_COLOR_MAGENTA "\x1b[35m"
#define ANSI_COLOR_RESET   "\x1b[0m"


#define CFG_UMAC_RETRIES             3
#define CFG_UMAC_RETRY_DELTA(try)    3
#define CFG_UMAC_RX_TIMEOUT          6

//#define CFG_UMAC_DBG(u, fmt, ...) printf(ANSI_COLOR_RED "%s" fmt ANSI_COLOR_RESET, (u)->_dbg_name ? (u)->_dbg_name : "" , ##  __VA_ARGS__)

#endif /* _UMAC_CFG_H_ */
