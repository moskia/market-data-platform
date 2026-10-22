package com.marketdata.service.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.ConsolidationService;
import com.marketdata.service.consolidation.QuoteBook;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConsolidationWorkerTest {

  // Written by the worker thread, read by the test thread, so it must be thread-safe.
  private final List<ConsolidatedQuote> published = new CopyOnWriteArrayList<>();

  private ConsolidationWorker worker;
  private Thread workerThread;

  @AfterEach
  void stopWorker() throws InterruptedException {
    if (worker != null) {
      worker.stop();
      workerThread.join(2_000);
    }
  }

  @Test
  void validQuoteIsPublished() throws InterruptedException {
    start(Duration.ofSeconds(5));

    worker.submit(quote("EX_A", "AAPL", "BID", 100.05, 1));

    assertTrue(
        waitUntil(() -> !published.isEmpty(), 2_000), "Nothing was published for a valid quote");
    assertEquals(100.05, published.get(0).bidPrice());
  }

  @Test
  void invalidQuoteDoesNotStopTheWorker() throws InterruptedException {
    start(Duration.ofSeconds(5));

    worker.submit(quote("EX_A", "AAPL", "BID", -1.0, 1));
    worker.submit(quote("EX_B", "MSFT", "BID", 100.05, 1));

    assertTrue(
        waitUntil(() -> published.stream().anyMatch(q -> q.symbol().equals("MSFT")), 2_000),
        "The worker stopped after an invalid quote");
  }

  /**
   * AAPL quotes arrive every millisecond, so the worker is never idle. MSFT's only bid must still
   * expire (lifetime 300 ms here) and be published as an empty bid.
   */
  @Test
  void expiryRunsEvenUnderConstantTraffic() throws InterruptedException {
    start(Duration.ofMillis(300));

    worker.submit(quote("EX_A", "MSFT", "BID", 100.05, 1));

    Thread feeder =
        new Thread(
            () -> {
              long seq = 1;
              long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

              while (System.nanoTime() < end && !Thread.currentThread().isInterrupted()) {
                worker.submit(quote("EX_B", "AAPL", "BID", 100.0, seq++));

                try {
                  Thread.sleep(1);
                } catch (InterruptedException e) {
                  return;
                }
              }
            });
    feeder.start();

    boolean msftExpired =
        waitUntil(
            () ->
                published.stream()
                    .anyMatch(q -> q.symbol().equals("MSFT") && q.bidPrice() == null),
            2_500);

    feeder.interrupt();
    feeder.join();

    assertTrue(msftExpired, "MSFT's expired bid was never published while AAPL kept the worker busy");
  }

  /** Quotes submitted before stop() must still be processed, and run() must then return. */
  @Test
  void stopDrainsWaitingQuotes() throws InterruptedException {
    ConsolidationService service =
        new ConsolidationService(
            new QuoteBook(Duration.ofSeconds(5)), List.of("AAPL", "MSFT", "GOOG"), published::add);
    ConsolidationWorker drainingWorker =
        new ConsolidationWorker(new ConcurrentConflatingQueue(), service);

    drainingWorker.submit(quote("EX_A", "AAPL", "BID", 100.0, 1));
    drainingWorker.submit(quote("EX_A", "MSFT", "BID", 100.0, 2));
    drainingWorker.submit(quote("EX_A", "GOOG", "BID", 100.0, 3));
    drainingWorker.stop();

    Thread thread = new Thread(drainingWorker);
    thread.start();
    thread.join(2_000);

    assertFalse(thread.isAlive(), "run() did not return after stop()");
    assertEquals(3, published.size(), "Waiting quotes were not drained before stopping");
  }

  private void start(Duration quoteLifetime) {
    ConsolidationService service =
        new ConsolidationService(
            new QuoteBook(quoteLifetime), List.of("AAPL", "MSFT", "GOOG"), published::add);
    worker = new ConsolidationWorker(new ConcurrentConflatingQueue(), service);
    workerThread = new Thread(worker);
    workerThread.start();
  }

  private static boolean waitUntil(BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) {
        return true;
      }

      Thread.sleep(10);
    }

    return condition.getAsBoolean();
  }

  private static RawQuoteEvent quote(
      String exchange, String symbol, String side, double price, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, symbol, side, price, 50, sequence, now, now);
  }
}
