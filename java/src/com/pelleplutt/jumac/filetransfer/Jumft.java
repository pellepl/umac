package com.pelleplutt.jumac.filetransfer;

abstract class Jumft {
  public static final int UMFT_CMD_SEND_FILE   = 0x10;
  public static final int UMFT_CMD_DATA_CHUNK  = 0x11;
  public static final int UMFT_CMD_STATUS = 0x11;
  public static final int UMFT_STA_OK          = 0x00;
  public static final int UMFT_STA_FIN         = 0x01;
  public static final int UMFT_STA_ABORT       = 0x02;

  public static final int UMFT_DEF_DT_MIN      = 7;
  public static final int UMFT_DEF_DT_MAX      = 10;
  public static final int UMFT_DEF_MTU         = 250;
  
  long to32bit(byte[] buf, int offs) {
    return 
        ((buf[offs++] & 0xff) << 24) |
        ((buf[offs++] & 0xff) << 16) |
        ((buf[offs++] & 0xff) << 8)  |
        ((buf[offs] & 0xff));
  }
  
  void from32bit(long d, byte[] buf, int offs) {
    buf[offs++] = (byte)(d >>> 24);
    buf[offs++] = (byte)(d >>> 16);
    buf[offs++] = (byte)(d >>> 8);
    buf[offs++] = (byte)(d);
  }

  int to16bit(byte[] buf, int offs) {
    return 
        ((buf[offs++] & 0xff) << 8)  |
        ((buf[offs] & 0xff));
  }
  
  void from16bit(int d, byte[] buf, int offs) {
    buf[offs++] = (byte)(d >>> 8);
    buf[offs++] = (byte)(d);
  }

}
