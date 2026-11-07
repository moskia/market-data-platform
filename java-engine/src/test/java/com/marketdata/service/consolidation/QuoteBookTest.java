package com.marketdata.service.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuoteBookTest {

  @Test
  void highestBidWins() {
    QuoteBook book = new QuoteBook();
    book.update(quote("EX_A", "BID", 100.05, 1));
    book.update(quote("EX_B", "BID", 100.08, 1));

    assertEquals("EX_B", book.bestBid("AAPL").orElseThrow().exchangeId());
  }

  @Test
  void olderQuoteIsRejected() {
    QuoteBook book = new QuoteBook();
    book.update(quote("EX_A", "BID", 100.05, 10));

    assertFalse(book.update(quote("EX_A", "BID", 999.00, 9)));
    assertEquals(100.05, book.bestBid("AAPL").orElseThrow().price());
  }

  /** An empty-side message (price 0, quantity 0) means "EX_A has no bid right now". */
  @Test
  void emptySideHidesThatExchangesQuote() {
    QuoteBook book = new QuoteBook();
    book.update(quote("EX_A", "BID", 100.05, 1));
    book.update(quote("EX_B", "BID", 100.00, 1));

    assertTrue(book.update(empty("EX_A", "BID", 2)));

    assertEquals("EX_B", book.bestBid("AAPL").orElseThrow().exchangeId());
  }

  /**
   * The dangerous side: best ask = LOWEST price, so an unfiltered 0.0 would become the best ask
   * and make every book look massively crossed.
   */
  @Test
  void emptyAskIsNeverTheBestAsk() {
    QuoteBook book = new QuoteBook();
    book.update(quote("EX_A", "ASK", 100.05, 1));
    book.update(quote("EX_B", "ASK", 100.10, 1));

    book.update(empty("EX_A", "ASK", 2));

    assertEquals("EX_B", book.bestAsk("AAPL").orElseThrow().exchangeId());
  }

  @Test
  void emptySideOnTheOnlyExchangeLeavesNoBestPrice() {
    QuoteBook book = new QuoteBook();
    book.update(quote("EX_A", "ASK", 100.05, 1));

    book.update(empty("EX_A", "ASK", 2));

    assertTrue(book.bestAsk("AAPL").isEmpty());
  }

  /** The tombstone keeps seq 41, so the delayed seq 40 cannot bring a dead bid back. */
  @Test
  void lateOlderQuoteDoesNotResurrectAnEmptySide() {
    QuoteBook book = new QuoteBook();
    book.update(empty("EX_A", "BID", 41));

    assertFalse(book.update(quote("EX_A", "BID", 100.00, 40)));
    assertTrue(book.bestBid("AAPL").isEmpty());
  }

  @Test
  void newerQuoteRestoresAnEmptySide() {
    QuoteBook book = new QuoteBook();
    book.update(quote("EX_A", "BID", 100.00, 1));
    book.update(empty("EX_A", "BID", 2));

    assertTrue(book.update(quote("EX_A", "BID", 99.95, 3)));
    assertEquals(99.95, book.bestBid("AAPL").orElseThrow().price());
  }

  /** The exchange restarted: its new session counts from 1 again, and that must be accepted. */
  @Test
  void newSessionIsAcceptedEvenThoughItsSequenceStartsAgain() {
    QuoteBook book = new QuoteBook();
    book.update(inSession(1, "BID", 100.00, 3_000));

    assertTrue(book.update(inSession(2, "BID", 101.00, 1)));
    assertEquals(101.00, book.bestBid("AAPL").orElseThrow().price());
  }

  /** A message from before the restart, arriving late, must not undo the new session's price. */
  @Test
  void lateMessageFromAnEarlierSessionIsRejected() {
    QuoteBook book = new QuoteBook();
    book.update(inSession(2, "BID", 101.00, 1));

    assertFalse(book.update(inSession(1, "BID", 100.00, 3_000)));
    assertEquals(101.00, book.bestBid("AAPL").orElseThrow().price());
  }

  private static RawQuoteEvent inSession(long session, String side, double price, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent("EX_A", "AAPL", side, price, 50, session, sequence, now, now);
  }

  private static RawQuoteEvent empty(String exchange, String side, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, "AAPL", side, 0.0, 0, 1, sequence, now, now);
  }

  private static RawQuoteEvent quote(String exchange, String side, double price, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, "AAPL", side, price, 50, 1, sequence, now, now);
  }
}
