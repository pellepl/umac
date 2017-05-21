package com.pelleplutt.jumac;


public class JumacTicker {
  JumacTickable tickme;
  private final Object LOCK = new Object();
  private volatile long alarm;
  
  public JumacTicker() {
  }
  public void start(JumacTickable tickme) {
    this.tickme = tickme;
    Thread t = new Thread(ticker, "jumac-ticker");
    t.setDaemon(true);
    t.start();
  }
  public long nowTick() {
    return System.currentTimeMillis();
  }
  public void requestFutureTick(long delta) {
    synchronized (LOCK) {
      alarm = nowTick() + delta;
      LOCK.notifyAll();
    }
  }
  public void cancelFutureTick() {
    synchronized (LOCK) {
      alarm = 0;
      LOCK.notifyAll();
    }
  }
  public long ticksToMs(long ticks) {
    return ticks;
  }
  public int ticksToNanoRemainder(long ticks) {
    return 0;
  }

  private Runnable ticker = new Runnable() {
    @Override
    public void run() {
      while (true) {
        boolean trig = false;
        synchronized (LOCK) {
          long now = 0; 
          while (alarm == 0 || (now = nowTick()) < alarm) {
            try {
              if (alarm > 0) {
                if (alarm - now > 0) {
                  LOCK.wait(ticksToMs(alarm - now), ticksToNanoRemainder(alarm - now));
                }
              } else {
                LOCK.wait();
              }
            } catch (InterruptedException e) {
              return;
            }
          }
          now = nowTick();
          if (alarm != 0 && now >= alarm) {
            trig = true;
            alarm = 0;
          }
        } // sync LOCK
        if (trig) {
          tickme.tick();
        }
      } // while forever
    }
  };
}
