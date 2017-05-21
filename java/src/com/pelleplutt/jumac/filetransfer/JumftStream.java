package com.pelleplutt.jumac.filetransfer;

public interface JumftStream {
  void readData(int offs, byte[] dst, int dstOffs, int len);
  boolean createData(int len, String name);
  boolean writeData(int offs, byte[] src, int srcOffs, int len);
}
