package com.pelleplutt.jumac.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;

import com.pelleplutt.jumac.Jumac;

public abstract class JumacSocketConfig extends Jumac.DefaultAbstractConfig {
  Socket socket;
  InputStream in;
  OutputStream out;
  Jumac u;
  
  public JumacSocketConfig(Jumac umac) {
    super(umac);
    this.u = umac;
  }
  
  public void connect(int port) throws UnknownHostException, IOException {
    socket = new Socket("localhost", port);
    in = socket.getInputStream();
    out = socket.getOutputStream();
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          int c;
          while ((c = in.read()) != -1) {
            u.report((byte)c);
          }
        }
        catch (Throwable t) {t.printStackTrace();}
        finally {
          close();
        }
      }
    });
    t.setDaemon(true);
    t.start();
  }
  
  public void close() {
    try {in.close();} catch (Throwable t) {}
    try {out.close();} catch (Throwable t) {}
    try {socket.close();} catch (Throwable t) {}
  }

  @Override
  public void tx(byte b) {
    try {
      out.write((int)b & 0xff);
    } catch (IOException e) {}
  }

  @Override
  public void tx(byte[] buf, int len) {
    try {
      out.write(buf, 0, len);
    } catch (IOException e) {}
  }

}
