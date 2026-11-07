package com.marketdata.service.simulator.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Sends 10,000 messages, one per millisecond (message n at time n - 1), and looks at what comes out. */
class UnreliableFeedTest {

  private static final Instant SENT_AT = Instant.parse("2020-01-01T00:00:00Z");
  private static final int MESSAGES = 10_000;

  private final EventScheduler scheduler = new EventScheduler();
  private final List<RawQuoteEvent> received = new ArrayList<>();
  private final List<Long> delays = new ArrayList<>();
  private final UnreliableFeed feed =
      new UnreliableFeed(
          scheduler,
          new Random(1),
          quote -> {
            received.add(quote);
            delays.add(scheduler.now() - (quote.sequenceNumber() - 1));
          });

  private void sendAll() {
    for (int i = 1; i <= MESSAGES; i++) {
      RawQuoteEvent quote = new RawQuoteEvent("EX_A", "AAPL", "BID", 100.0, 50, i, SENT_AT, SENT_AT);
      scheduler.schedule(i - 1, () -> feed.accept(quote));
    }

    scheduler.runUntil(20_000);
  }

  @Test
  void aboutOnePercentIsLost() {
    sendAll();

    assertTrue(feed.dropped() > 60 && feed.dropped() < 140, "Dropped: " + feed.dropped());
    assertEquals(MESSAGES - feed.dropped(), received.size());
  }

  @Test
  void aboutTwoPercentIsVeryLate() {
    sendAll();

    long veryLate = delays.stream().filter(delay -> delay > 50).count();
    assertTrue(feed.late() > 140 && feed.late() < 260, "Late: " + feed.late());
    assertEquals(feed.late(), veryLate);
  }

  @Test
  void everyDelayIsWithinTheLimits() {
    sendAll();

    assertTrue(delays.stream().allMatch(delay -> delay >= 1 && delay <= 350), "Delay out of range");
  }

  /** Random delays mean a later message can overtake an earlier one; the engine must cope. */
  @Test
  void messagesOftenArriveOutOfOrder() {
    sendAll();

    long overtaken = 0;
    for (int i = 1; i < received.size(); i++) {
      if (received.get(i).sequenceNumber() < received.get(i - 1).sequenceNumber()) {
        overtaken++;
      }
    }

    assertTrue(overtaken > MESSAGES / 10, "Only " + overtaken + " out of order");
  }

  @Test
  void receivedTimeIsSetOnArrivalAndNothingElseChanges() {
    feed.accept(new RawQuoteEvent("EX_B", "MSFT", "ASK", 420.05, 75, 7, SENT_AT, SENT_AT));
    scheduler.runUntil(1_000);

    RawQuoteEvent quote = received.get(0);
    assertTrue(quote.receivedTimestamp().isAfter(SENT_AT), "receivedTimestamp not updated");
    assertEquals(SENT_AT, quote.exchangeTimestamp());
    assertEquals(
        List.of("EX_B", "MSFT", "ASK", 420.05, 75, 7L),
        List.of(
            quote.exchangeId(),
            quote.symbol(),
            quote.side(),
            quote.price(),
            quote.quantity(),
            quote.sequenceNumber()));
  }
}
