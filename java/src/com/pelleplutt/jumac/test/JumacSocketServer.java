package com.pelleplutt.jumac.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Vector;

public class JumacSocketServer {
  int port;
  float errProb;
  ServerSocket serverSocket;
  Vector<Socket> sockets = new Vector<Socket>();
  
  public JumacSocketServer(int port) {
    this(port, 0);
  }
  
  public JumacSocketServer(int port, float errProb) {
    this.port = port;
    this.errProb = errProb;
  }
  
  public void start() throws IOException{
      serverSocket = new ServerSocket(port);
      try {
        System.out.println("accepting clients on port " + port);
        while (true) {
          Socket clientSocket = serverSocket.accept();
          startClient(clientSocket);
        }
      }
      catch (Throwable ignore) {}
      finally {
        for (Socket s : sockets) {
          try {
            s.close();
          } catch (Throwable ignore) {}
        }
        close();
        System.out.println("server closed");
      }
  }
  
  public void close() {
    try {
      serverSocket.close();
    } catch (Throwable ignore) {}
  }
  
  void startClient(final Socket client) {
    Runnable r = createClientRunnable(client);
    Thread t = new Thread(r);
    t.setDaemon(true);
    t.start();
    sockets.addElement(client);
    System.out.println("client accepted");
  }
  
  Runnable createClientRunnable(final Socket client) {
    Runnable r = new Runnable() {
      @Override
      public void run() {
        InputStream is = null;
        int c;
        try {
          is = client.getInputStream();
          while ((c = is.read()) != -1) {
            for (Socket s : sockets) {
              if (s == client) continue;
              if (errProb > 0) {
                if (Math.random() <= errProb) {
                  c ^= (int)(Math.random() * 0x100);
                }
              }
              s.getOutputStream().write(c);
            }
          }
        }
        catch (Throwable ignore) {}
        finally {
          System.out.println("client closed");
          try {
            is.close();
          } catch (Throwable ignore) {}
          sockets.remove(client);
        }
      }
    };
    return r;
  }

  public static void main(String[] args) throws Throwable {
    int port;
    if (args.length < 1) {
      port = 10000;
    } else {
      port = Integer.parseInt(args[0]);
    }
    JumacSocketServer s = new JumacSocketServer(port);
    s.start();
  }
  
}
