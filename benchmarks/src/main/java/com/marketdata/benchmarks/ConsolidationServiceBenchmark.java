package com.marketdata.benchmarks;

import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.ConsolidationService;
import com.marketdata.service.consolidation.QuoteBook;
import java.time.Instant;
import java.util.List;
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
 * Everything a worker does for one message: validate it, store it, rebuild the best bid/ask and
 * publish if it changed. The book is filled like in production: 3 exchanges x 3 symbols x 2 sides.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ConsolidationServiceBenchmark {

  private static final List<String> EXCHANGES = List.of("EX_A", "EX_B", "EX_C");
  private static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "GOOG");

  private final Instant now = Instant.now();
  private ConsolidationService service;
  private ConsolidatedQuote lastPublished;
  private long sequence;
  private boolean flip;

  @Setup
  public void fillTheBook() {
    service = new ConsolidationService(new QuoteBook(), SYMBOLS, quote -> lastPublished = quote);
    sequence = 1;

    for (String exchange : EXCHANGES) {
      for (String symbol : SYMBOLS) {
        service.onQuote(quote(exchange, symbol, "BID", 100.00), now);
        service.onQuote(quote(exchange, symbol, "ASK", 100.05), now);
      }
    }
  }

  /** The best bid moves every time (100.01, 100.02, 100.01, ...), so every call publishes. */
  @Benchmark
  public ConsolidatedQuote quoteThatChangesTheBestPrice() {
    flip = !flip;
    service.onQuote(quote("EX_B", "AAPL", "BID", flip ? 100.01 : 100.02), now);
    return lastPublished;
  }

  /** A quote below the best bid: stored, but the consolidated quote stays the same. */
  @Benchmark
  public ConsolidatedQuote quoteThatChangesNothingVisible() {
    service.onQuote(quote("EX_C", "AAPL", "BID", 99.00), now);
    return lastPublished;
  }

  private RawQuoteEvent quote(String exchange, String symbol, String side, double price) {
    return new RawQuoteEvent(exchange, symbol, side, price, 50, 1, sequence++, now, now);
  }
}
