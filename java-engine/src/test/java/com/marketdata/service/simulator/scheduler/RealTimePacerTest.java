package com.marketdata.service.simulator.scheduler;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RealTimePacerTest {

  @Test
  void waitsUntilTheSimulatedTimeIsReached() throws InterruptedException {
    long start = System.nanoTime();
    RealTimePacer pacer = new RealTimePacer();

    pacer.waitUntil(200);

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMs >= 200, "Returned after only " + elapsedMs + " ms");
  }

  /** Times are counted from when the pacer was created, not from the last wait. */
  @Test
  void waitsAreCountedFromTheStartNotAddedUp() throws InterruptedException {
    long start = System.nanoTime();
    RealTimePacer pacer = new RealTimePacer();

    pacer.waitUntil(100);
    pacer.waitUntil(150);

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMs >= 150, "Returned after only " + elapsedMs + " ms");
    assertTrue(elapsedMs < 240, "Waited " + elapsedMs + " ms: the waits were added up");
  }

  @Test
  void aTimeAlreadyPassedDoesNotWait() throws InterruptedException {
    RealTimePacer pacer = new RealTimePacer();
    Thread.sleep(50);

    long start = System.nanoTime();
    pacer.waitUntil(10);

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMs < 20, "Waited " + elapsedMs + " ms for a time already passed");
  }

  @Test
  void anInterruptStopsTheWait() {
    RealTimePacer pacer = new RealTimePacer();
    Thread.currentThread().interrupt();

    assertThrows(InterruptedException.class, () -> pacer.waitUntil(10_000));
  }

  /** With the scheduler: 3 events over 300 simulated ms take about 300 real ms. */
  @Test
  void schedulerFollowsRealTime() {
    long start = System.nanoTime();
    EventScheduler scheduler = new EventScheduler(new RealTimePacer());
    scheduler.schedule(100, () -> {});
    scheduler.schedule(200, () -> {});
    scheduler.schedule(300, () -> {});

    scheduler.runUntil(300);

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMs >= 300, "Finished after only " + elapsedMs + " ms");
  }
}
