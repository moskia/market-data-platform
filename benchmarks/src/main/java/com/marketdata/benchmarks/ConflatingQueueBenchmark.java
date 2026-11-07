package com.marketdata.benchmarks;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.concurrency.ConcurrentConflatingQueue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The hand-off between the Kafka thread and a worker. Quotes cycle through the 18 keys a worker
 * would see (3 exchanges x 3 symbols x 2 sides).
 */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ConflatingQueueBenchmark {

  private static final List<RawQuoteEvent> KEYS = eighteenKeys();

  /** One thread hands one quote over and takes it back: the cost of the hand-off itself. */
  @State(Scope.Thread)
  public static class SingleThread {
    final ConcurrentConflatingQueue conflating = new ConcurrentConflatingQueue();
    final LinkedBlockingQueue<RawQuoteEvent> plain = new LinkedBlockingQueue<>();
    int next;

    RawQuoteEvent nextQuote() {
      next = (next + 1) % KEYS.size();
      return KEYS.get(next);
    }
  }

  @Benchmark
  @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public RawQuoteEvent offerThenTake_conflating(SingleThread state) throws InterruptedException {
    state.conflating.offer(state.nextQuote());
    return state.conflating.take();
  }

  /** The same with a plain queue, for comparison: no map, no conflation. */
  @Benchmark
  @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public RawQuoteEvent offerThenTake_plainQueue(SingleThread state) throws InterruptedException {
    state.plain.offer(state.nextQuote());
    return state.plain.take();
  }

  /** One queue shared by a producer thread and a consumer thread, like Kafka thread + worker. */
  @State(Scope.Group)
  public static class Shared {
    ConcurrentConflatingQueue queue;

    @Setup(Level.Iteration)
    public void newQueue() {
      queue = new ConcurrentConflatingQueue();
    }
  }

  /** How many quotes went in, and how many came out. The difference is what was conflated. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Counts {
    public long offered;
    public long delivered;
    int next;
  }

  @Benchmark
  @Group("producerAndConsumer")
  @GroupThreads(1)
  @BenchmarkMode(Mode.Throughput)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void producer(Shared shared, Counts counts) {
    counts.next = (counts.next + 1) % KEYS.size();
    shared.queue.offer(KEYS.get(counts.next));
    counts.offered++;
  }

  @Benchmark
  @Group("producerAndConsumer")
  @GroupThreads(1)
  @BenchmarkMode(Mode.Throughput)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public RawQuoteEvent consumer(Shared shared, Counts counts) throws InterruptedException {
    // Doesn't block, so the benchmark can always stop; an empty result counts as an empty poll.
    RawQuoteEvent quote = shared.queue.poll(0, TimeUnit.MILLISECONDS);

    if (quote != null) {
      counts.delivered++;
    }

    return quote;
  }

  /**
   * The conflating queue keeps the newer quote by sequence number, so each key gets an
   * ever-growing sequence. Building them once keeps object creation out of the measurement.
   */
  private static List<RawQuoteEvent> eighteenKeys() {
    Instant now = Instant.now();
    List<RawQuoteEvent> quotes = new ArrayList<>();
    long sequence = 1;

    for (String exchange : List.of("EX_A", "EX_B", "EX_C")) {
      for (String symbol : List.of("AAPL", "MSFT", "GOOG")) {
        for (String side : List.of("BID", "ASK")) {
          quotes.add(new RawQuoteEvent(exchange, symbol, side, 100.0, 50, 1, sequence++, now, now));
        }
      }
    }

    return List.copyOf(quotes);
  }
}
