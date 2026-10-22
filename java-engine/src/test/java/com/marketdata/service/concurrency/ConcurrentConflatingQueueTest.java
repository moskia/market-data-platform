package com.marketdata.service.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConcurrentConflatingQueueTest {

  @Test
  void returnsQuoteThatWasOffered() throws InterruptedException {
    ConflatingQueue queue = new ConcurrentConflatingQueue();
    queue.offer(quote("EX_A", "AAPL", "BID", 1));

    assertEquals(1, queue.take().sequenceNumber());
  }

  @Test
  void newerQuoteForSameKeyReplacesWaitingOne() throws InterruptedException {
    ConflatingQueue queue = new ConcurrentConflatingQueue();
    queue.offer(quote("EX_A", "AAPL", "BID", 10));
    queue.offer(quote("EX_A", "AAPL", "BID", 11));

    assertEquals(11, queue.take().sequenceNumber());
    assertNull(queue.poll(50, TimeUnit.MILLISECONDS), "#10 must not be delivered separately");
  }

  @Test
  void olderQuoteDoesNotReplaceNewerWaitingOne() throws InterruptedException {
    ConflatingQueue queue = new ConcurrentConflatingQueue();
    queue.offer(quote("EX_A", "AAPL", "BID", 12));
    queue.offer(quote("EX_A", "AAPL", "BID", 10));

    assertEquals(12, queue.take().sequenceNumber());
  }

  @Test
  void differentKeysAreNotConflated() throws InterruptedException {
    ConflatingQueue queue = new ConcurrentConflatingQueue();
    queue.offer(quote("EX_A", "AAPL", "BID", 1));
    queue.offer(quote("EX_A", "AAPL", "ASK", 2));
    queue.offer(quote("EX_B", "AAPL", "BID", 3));

    assertEquals(1, queue.take().sequenceNumber());
    assertEquals(2, queue.take().sequenceNumber());
    assertEquals(3, queue.take().sequenceNumber());
  }

  @Test
  void pollReturnsNullWhenNothingArrives() throws InterruptedException {
    ConflatingQueue queue = new ConcurrentConflatingQueue();

    assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
  }

  /**
   * One thread offers 200,000 quotes while another takes them. Checks the two promises a
   * conflating queue must keep under concurrency: for each key, sequence numbers come out in
   * increasing order, and the very last quote is always delivered (never stuck in the map).
   */
  @Test
  void stressTestOneProducerOneConsumer() throws Exception {
    ConflatingQueue queue = new ConcurrentConflatingQueue();
    List<String> exchanges = List.of("EX_A", "EX_B", "EX_C");
    int perExchange = 200_000 / exchanges.size();

    AtomicReference<Throwable> failure = new AtomicReference<>();
    Map<String, Long> lastSeen = new HashMap<>();

    Thread consumer =
        new Thread(
            () -> {
              try {
                while (lastSeen.size() < exchanges.size()
                    || lastSeen.values().stream().anyMatch(seq -> seq < perExchange)) {
                  RawQuoteEvent quote = queue.poll(2, TimeUnit.SECONDS);

                  if (quote == null) {
                    throw new AssertionError("Last quotes never delivered: " + lastSeen);
                  }

                  Long previous = lastSeen.put(quote.exchangeId(), quote.sequenceNumber());

                  if (previous != null && quote.sequenceNumber() <= previous) {
                    throw new AssertionError(
                        quote.exchangeId() + " went backwards: " + previous + " then "
                            + quote.sequenceNumber());
                  }
                }
              } catch (Throwable error) {
                failure.set(error);
              }
            });

    consumer.start();

    for (int seq = 1; seq <= perExchange; seq++) {
      for (String exchange : exchanges) {
        queue.offer(quote(exchange, "AAPL", "BID", seq));
      }
    }

    consumer.join(TimeUnit.SECONDS.toMillis(30));

    assertTrue(failure.get() == null, () -> "Consumer failed: " + failure.get());
    assertEquals(perExchange, lastSeen.get("EX_A"));
    assertEquals(perExchange, lastSeen.get("EX_B"));
    assertEquals(perExchange, lastSeen.get("EX_C"));
  }

  private static RawQuoteEvent quote(String exchange, String symbol, String side, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, symbol, side, 100.0, 50, sequence, now, now);
  }
}
