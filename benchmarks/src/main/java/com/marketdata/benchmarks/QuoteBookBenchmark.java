package com.marketdata.benchmarks;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.QuoteBook;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The engine's core data structure, filled like in production: 3 exchanges x 3 symbols x 2 sides.
 * Each benchmark is measured twice: operations per second, and the time of single operations
 * (p50 / p99 / p99.9).
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class QuoteBookBenchmark {

  private static final List<String> EXCHANGES = List.of("EX_A", "EX_B", "EX_C");
  private static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "GOOG");

  private final Instant now = Instant.now();
  private QuoteBook book;
  private long sequence;

  @Setup
  public void fillTheBook() {
    book = new QuoteBook();
    sequence = 1;

    for (String exchange : EXCHANGES) {
      for (String symbol : SYMBOLS) {
        book.update(quote(exchange, symbol, "BID", 100.00));
        book.update(quote(exchange, symbol, "ASK", 100.05));
      }
    }
  }

  /** A new quote replacing the stored one: what happens for every message the engine reads. */
  @Benchmark
  public boolean update() {
    return book.update(quote("EX_B", "AAPL", "BID", 100.01));
  }

  /** Finding the best bid across all exchanges: done after every accepted quote. */
  @Benchmark
  public Optional<RawQuoteEvent> bestBid() {
    return book.bestBid("AAPL", now);
  }

  private RawQuoteEvent quote(String exchange, String symbol, String side, double price) {
    return new RawQuoteEvent(exchange, symbol, side, price, 50, 1, sequence++, now, now);
  }
}
