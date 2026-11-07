package com.marketdata.service.simulator.scheduler;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * A to-do list sorted by simulated time, run on one thread. Events due at the same time run in the
 * order they were scheduled.
 */
public class EventScheduler {

  private record ScheduledEvent(long timeMs, long arrivalNumber, Runnable action) {}

  private final PriorityQueue<ScheduledEvent> events =
      new PriorityQueue<>(
          Comparator.comparingLong(ScheduledEvent::timeMs)
              .thenComparingLong(ScheduledEvent::arrivalNumber));

  private final Pacer pacer;
  private long nowMs = 0;
  private long nextArrivalNumber = 0;

  /** No waiting: simulated time jumps straight to the next event. */
  public EventScheduler() {
    this(simulatedMs -> {});
  }

  public EventScheduler(Pacer pacer) {
    this.pacer = pacer;
  }

  public long now() {
    return nowMs;
  }

  public void schedule(long delayMs, Runnable action) {
    if (delayMs < 0) {
      throw new IllegalArgumentException("delayMs must not be negative");
    }

    events.add(new ScheduledEvent(nowMs + delayMs, nextArrivalNumber++, action));
  }

  /** Runs every event due at or before endMs. Later events stay on the list for the next run. */
  public void runUntil(long endMs) {
    while (!events.isEmpty() && events.peek().timeMs() <= endMs) {
      try {
        pacer.waitUntil(events.peek().timeMs());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }

      ScheduledEvent event = events.poll();
      nowMs = event.timeMs();
      event.action().run();
    }

    nowMs = Math.max(nowMs, endMs);
  }
}
