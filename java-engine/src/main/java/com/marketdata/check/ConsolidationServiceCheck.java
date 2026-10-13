package com.marketdata.check;

import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.ConsolidationService;
import com.marketdata.service.consolidation.QuoteBook;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs the consolidation rules without Kafka: published quotes are collected in a list. */
public class ConsolidationServiceCheck {

  public static void main(String[] args) {
    List<ConsolidatedQuote> published = new ArrayList<>();
    ConsolidationService service =
        new ConsolidationService(
            new QuoteBook(Duration.ofSeconds(5)), List.of("AAPL"), published::add);

    Instant start = Instant.parse("2026-01-01T00:00:00Z");

    service.onQuote(quote("EX_A", "BID", 100.05, 1, start), start);
    check(published.size() == 1, "First quote is published");

    service.checkExpiry(start.plusSeconds(1));
    check(published.size() == 1, "Nothing is published when only time has passed");

    service.onQuote(quote("EX_B", "BID", 100.01, 1, start), start);
    check(published.size() == 1, "A worse bid does not change the best quote");

    boolean accepted = service.onQuote(quote("EX_A", "BID", 999.00, 1, start), start);
    check(!accepted && published.size() == 1, "A stale quote is ignored");

    service.checkExpiry(start.plusSeconds(5));
    check(
        published.size() == 2 && published.get(1).bidPrice() == null,
        "Expired quotes produce an empty best bid");

    try {
      service.onQuote(quote("EX_A", "BID", -1.0, 2, start), start);
      check(false, "Invalid quote is rejected");
    } catch (IllegalArgumentException expected) {
      check(true, "Invalid quote is rejected");
    }

    System.out.println("All checks passed.");
  }

  private static RawQuoteEvent quote(
      String exchange, String side, double price, long sequence, Instant received) {
    return new RawQuoteEvent(exchange, "AAPL", side, price, 50, sequence, received, received);
  }

  private static void check(boolean condition, String description) {
    if (!condition) {
      throw new IllegalStateException("FAIL: " + description);
    }

    System.out.println("PASS: " + description);
  }
}
