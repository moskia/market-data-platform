package com.marketdata.service.simulator.scheduler;

/** Decides how long the scheduler waits before running an event due at a simulated time. */
public interface Pacer {
  void waitUntil(long simulatedMs) throws InterruptedException;
}
