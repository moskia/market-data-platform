package com.marketdata.service.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LatencyStatsTest {

  private static final Instant PROCESSED = Instant.parse("2026-01-01T00:00:01Z");

  /** 100 quotes received 1, 2, ... 100 ms before they were processed. */
  @Test
  void percentilesMatchTheRecordedLatencies() {
    LatencyStats stats = new LatencyStats();

    for (int ms = 1; ms <= 100; ms++) {
      stats.record(quote(PROCESSED.minusMillis(ms + 10), PROCESSED.minusMillis(ms)), PROCESSED);
    }

    assertEquals(100, stats.count());
    assertEquals(50_000, stats.systemPercentileMicros(50), 50);
    assertEquals(99_000, stats.systemPercentileMicros(99), 100);
    assertEquals(60_000, stats.exchangePercentileMicros(50), 60, "10 ms more since the exchange");
  }

  /** How the engine combines its workers once they have stopped. */
  @Test
  void statsFromSeveralWorkersAddUp() {
    LatencyStats first = new LatencyStats();
    LatencyStats second = new LatencyStats();
    first.record(quote(PROCESSED.minusMillis(1), PROCESSED.minusMillis(1)), PROCESSED);
    second.record(quote(PROCESSED.minusMillis(3), PROCESSED.minusMillis(3)), PROCESSED);

    LatencyStats total = new LatencyStats();
    total.add(first);
    total.add(second);

    assertEquals(2, total.count());
    assertEquals(3_000, total.systemPercentileMicros(100), 10);
  }

  @Test
  void aClockAheadOfOursCountsAsZeroNotAnError() {
    LatencyStats stats = new LatencyStats();

    stats.record(quote(PROCESSED.plusMillis(5), PROCESSED.plusMillis(5)), PROCESSED);

    assertEquals(0, stats.systemPercentileMicros(50));
  }

  @Test
  void summaryShowsThePercentilesInMilliseconds() {
    LatencyStats stats = new LatencyStats();
    stats.record(quote(PROCESSED.minusMillis(2), PROCESSED.minusMillis(2)), PROCESSED);

    String summary = stats.summary();

    assertTrue(summary.contains("Latency over 1 quotes"), summary);
    assertTrue(summary.contains("p50 2.00"), summary);
  }

  private static RawQuoteEvent quote(Instant exchangeTime, Instant receivedTime) {
    return new RawQuoteEvent("EX_A", "AAPL", "BID", 100.0, 50, 1, 1, exchangeTime, receivedTime);
  }
}
