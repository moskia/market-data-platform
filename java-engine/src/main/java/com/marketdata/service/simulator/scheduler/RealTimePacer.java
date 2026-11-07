package com.marketdata.service.simulator.scheduler;

import java.util.concurrent.TimeUnit;

/**
 * Makes simulated time follow the real clock: simulated time 0 is the moment this pacer is
 * created, so create it right before starting the run. If the simulation falls behind, events run
 * straight away until it catches up.
 */
public class RealTimePacer implements Pacer {
  private final long startNanos = System.nanoTime();

  @Override
  public void waitUntil(long simulatedMs) throws InterruptedException {
    long dueNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(simulatedMs);
    long waitNanos = dueNanos - System.nanoTime();

    if (waitNanos > 0) {
      TimeUnit.NANOSECONDS.sleep(waitNanos);
    }
  }
}
