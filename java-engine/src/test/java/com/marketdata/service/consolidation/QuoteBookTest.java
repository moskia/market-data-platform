package com.marketdata.service.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

  private static RawQuoteEvent quote(String exchange, String side, double price, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, "AAPL", side, price, 50, sequence, now, now);
  }
}
