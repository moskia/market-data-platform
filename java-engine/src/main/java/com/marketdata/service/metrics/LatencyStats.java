package com.marketdata.service.metrics;

import com.marketdata.model.RawQuoteEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

/**
 * How long quotes took to reach the point where the engine has processed them, as percentiles.
 * Two clocks: since the exchange created the price (includes the simulated network), and since the
 * feed handed it to Kafka (only our own system: Kafka, the Kafka thread, the queue, the worker).
 * Not thread-safe: one per worker, combined with add() once the workers have stopped.
 */
public class LatencyStats {
  private static final long MAX_MICROS = TimeUnit.MINUTES.toMicros(10);
  private static final int SIGNIFICANT_DIGITS = 3;

  private final Histogram sinceExchange = new Histogram(MAX_MICROS, SIGNIFICANT_DIGITS);
  private final Histogram sinceReceived = new Histogram(MAX_MICROS, SIGNIFICANT_DIGITS);

  public void record(RawQuoteEvent quote, Instant processedAt) {
    record(sinceExchange, quote.exchangeTimestamp(), processedAt);
    record(sinceReceived, quote.receivedTimestamp(), processedAt);
  }

  public void add(LatencyStats other) {
    sinceExchange.add(other.sinceExchange);
    sinceReceived.add(other.sinceReceived);
  }

  public long count() {
    return sinceReceived.getTotalCount();
  }

  /** Percentile of the "since handed to Kafka" latency, in microseconds. */
  public long systemPercentileMicros(double percentile) {
    return sinceReceived.getValueAtPercentile(percentile);
  }

  /** Percentile of the "since the exchange created it" latency, in microseconds. */
  public long exchangePercentileMicros(double percentile) {
    return sinceExchange.getValueAtPercentile(percentile);
  }

  public String summary() {
    return String.format(
        "Latency over %d quotes (ms)%n  %-30s %s%n  %-30s %s",
        count(),
        "since handed to Kafka:",
        line(sinceReceived),
        "since the exchange created it:",
        line(sinceExchange));
  }

  private static String line(Histogram histogram) {
    return String.format(
        "p50 %.3f | p99 %.3f | p99.9 %.3f | max %.3f",
        millis(histogram.getValueAtPercentile(50)),
        millis(histogram.getValueAtPercentile(99)),
        millis(histogram.getValueAtPercentile(99.9)),
        millis(histogram.getMaxValue()));
  }

  private static double millis(long micros) {
    return micros / 1_000.0;
  }

  /** Clocks on different machines can disagree slightly, so a negative time is counted as 0. */
  private static void record(Histogram histogram, Instant from, Instant to) {
    long micros = Duration.between(from, to).toNanos() / 1_000;
    histogram.recordValue(Math.min(Math.max(micros, 0), MAX_MICROS));
  }
}
