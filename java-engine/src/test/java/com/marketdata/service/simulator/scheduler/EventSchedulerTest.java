package com.marketdata.service.simulator.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventSchedulerTest {

  private final EventScheduler scheduler = new EventScheduler();
  private final List<String> ran = new ArrayList<>();

  @Test
  void startsAtTimeZero() {
    assertEquals(0, scheduler.now());
  }

  @Test
  void eventsRunInTimeOrderNotTheOrderTheyWereAdded() {
    scheduler.schedule(300, () -> ran.add("third"));
    scheduler.schedule(100, () -> ran.add("first"));
    scheduler.schedule(200, () -> ran.add("second"));

    scheduler.runUntil(1_000);

    assertEquals(List.of("first", "second", "third"), ran);
  }

  /** Enough events at one time that a queue sorted by time alone would mix them up. */
  @Test
  void eventsAtTheSameTimeRunInTheOrderTheyWereScheduled() {
    List<Integer> order = new ArrayList<>();
    List<Integer> expected = new ArrayList<>();

    for (int i = 0; i < 100; i++) {
      int number = i;
      expected.add(number);
      scheduler.schedule(500, () -> order.add(number));
    }

    scheduler.runUntil(500);

    assertEquals(expected, order);
  }

  @Test
  void nowIsTheTimeOfTheRunningEvent() {
    List<Long> times = new ArrayList<>();
    scheduler.schedule(100, () -> times.add(scheduler.now()));
    scheduler.schedule(250, () -> times.add(scheduler.now()));

    scheduler.runUntil(1_000);

    assertEquals(List.of(100L, 250L), times);
  }

  /** How agent turns will work: an event schedules the next one, counted from its own time. */
  @Test
  void anEventCanScheduleAnotherCountedFromItsOwnTime() {
    List<Long> times = new ArrayList<>();
    scheduler.schedule(
        100,
        () -> {
          times.add(scheduler.now());
          scheduler.schedule(50, () -> times.add(scheduler.now()));
        });

    scheduler.runUntil(1_000);

    assertEquals(List.of(100L, 150L), times);
  }

  @Test
  void eventWithNoDelayRunsInTheSameRun() {
    scheduler.schedule(100, () -> scheduler.schedule(0, () -> ran.add("no delay")));

    scheduler.runUntil(100);

    assertEquals(List.of("no delay"), ran);
  }

  @Test
  void eventExactlyAtTheEndTimeRuns() {
    scheduler.schedule(500, () -> ran.add("at the end"));

    scheduler.runUntil(500);

    assertEquals(List.of("at the end"), ran);
  }

  @Test
  void laterEventsWaitForTheNextRun() {
    scheduler.schedule(100, () -> ran.add("early"));
    scheduler.schedule(900, () -> ran.add("late"));

    scheduler.runUntil(500);
    assertEquals(List.of("early"), ran);
    assertEquals(500, scheduler.now());

    scheduler.runUntil(1_000);
    assertEquals(List.of("early", "late"), ran);
  }

  @Test
  void timeReachesTheEndEvenWithNothingToRun() {
    scheduler.runUntil(500);

    assertEquals(500, scheduler.now());
  }

  @Test
  void timeNeverGoesBackwards() {
    scheduler.runUntil(500);
    scheduler.runUntil(200);

    assertEquals(500, scheduler.now());
  }

  @Test
  void delaysAreCountedFromTheCurrentTime() {
    scheduler.runUntil(500);
    scheduler.schedule(10, () -> ran.add("at 510"));

    scheduler.runUntil(509);
    assertTrue(ran.isEmpty());

    scheduler.runUntil(510);
    assertEquals(List.of("at 510"), ran);
  }

  @Test
  void negativeDelayIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(-1, () -> {}));
  }

  @Test
  void exceptionFromAnEventReachesTheCaller() {
    scheduler.schedule(
        100,
        () -> {
          throw new IllegalStateException("broken");
        });

    assertThrows(IllegalStateException.class, () -> scheduler.runUntil(1_000));
  }

  @Test
  void pacerIsAskedToWaitBeforeEachEvent() {
    List<String> steps = new ArrayList<>();
    EventScheduler paced = new EventScheduler(time -> steps.add("wait " + time));
    paced.schedule(100, () -> steps.add("run"));
    paced.schedule(200, () -> steps.add("run"));

    paced.runUntil(1_000);

    assertEquals(List.of("wait 100", "run", "wait 200", "run"), steps);
  }

  /** The event that was being waited for must not run, and must still be there afterwards. */
  @Test
  void interruptedWaitStopsTheRunAndKeepsTheEvent() {
    boolean[] interruptNext = {true};
    EventScheduler paced =
        new EventScheduler(
            time -> {
              if (interruptNext[0]) {
                interruptNext[0] = false;
                throw new InterruptedException();
              }
            });
    paced.schedule(100, () -> ran.add("event"));

    paced.runUntil(1_000);

    assertTrue(Thread.interrupted(), "The interrupt flag must be restored (this also clears it)");
    assertTrue(ran.isEmpty());
    assertEquals(0, paced.now());

    paced.runUntil(1_000);
    assertEquals(List.of("event"), ran);
  }
}
