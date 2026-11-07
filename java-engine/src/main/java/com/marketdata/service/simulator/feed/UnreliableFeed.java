package com.marketdata.service.simulator.feed;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import java.time.Instant;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The network between an exchange and the engine: every message is delayed a little (so they often
 * arrive out of order), a few are very late, and a few are lost.
 */
public class UnreliableFeed implements Consumer<RawQuoteEvent> {
  private static final double DROP_PROBABILITY = 0.01;
  private static final double LATE_PROBABILITY = 0.02;
  private static final int MAX_DELAY_MS = 50;
  private static final int LATE_EXTRA_DELAY_MS = 300;

  private final EventScheduler scheduler;
  private final Random random;
  private final Consumer<RawQuoteEvent> receiver;
  private long dropped = 0;
  private long late = 0;

  public UnreliableFeed(
      EventScheduler scheduler, Random random, Consumer<RawQuoteEvent> receiver) {
    this.scheduler = scheduler;
    this.random = random;
    this.receiver = receiver;
  }

  @Override
  public void accept(RawQuoteEvent quote) {
    if (random.nextDouble() < DROP_PROBABILITY) {
      dropped++;
      return;
    }

    long delayMs = 1 + random.nextInt(MAX_DELAY_MS);

    if (random.nextDouble() < LATE_PROBABILITY) {
      late++;
      delayMs += LATE_EXTRA_DELAY_MS;
    }

    scheduler.schedule(delayMs, () -> receiver.accept(markReceived(quote)));
  }

  public long dropped() {
    return dropped;
  }

  public long late() {
    return late;
  }

  private static RawQuoteEvent markReceived(RawQuoteEvent quote) {
    return new RawQuoteEvent(
        quote.exchangeId(),
        quote.symbol(),
        quote.side(),
        quote.price(),
        quote.quantity(),
        quote.sessionId(),
        quote.sequenceNumber(),
        quote.exchangeTimestamp(),
        Instant.now());
  }
}
