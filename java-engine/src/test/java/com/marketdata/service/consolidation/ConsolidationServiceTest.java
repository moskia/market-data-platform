package com.marketdata.service.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsolidationServiceTest {

  private final ConsolidationService service =
      new ConsolidationService(new QuoteBook(), List.of("AAPL", "MSFT"), quote -> {});

  /** spreadBps = (ask − bid) / mid × 10,000 */
  @Test
  void spreadIsInBasisPointsOfTheMidPrice() {
    service.onQuote(quote("EX_A", "AAPL", "BID", 190.00, 1), Instant.now());
    service.onQuote(quote("EX_B", "AAPL", "ASK", 190.05, 1), Instant.now());

    ConsolidatedQuote snapshot = service.snapshot("AAPL", Instant.now());

    assertEquals(0.05 / 190.025 * 10_000, snapshot.spreadBps(), 1e-9);
  }

  /** The same 0.05 spread is relatively tighter on a more expensive stock. */
  @Test
  void sameDollarSpreadIsFewerBasisPointsOnAPricierStock() {
    service.onQuote(quote("EX_A", "AAPL", "BID", 190.00, 1), Instant.now());
    service.onQuote(quote("EX_A", "AAPL", "ASK", 190.05, 2), Instant.now());
    service.onQuote(quote("EX_A", "MSFT", "BID", 420.00, 3), Instant.now());
    service.onQuote(quote("EX_A", "MSFT", "ASK", 420.05, 4), Instant.now());

    double aapl = service.snapshot("AAPL", Instant.now()).spreadBps();
    double msft = service.snapshot("MSFT", Instant.now()).spreadBps();

    assertEquals(2.63, aapl, 0.01);
    assertEquals(1.19, msft, 0.01);
  }

  /** A crossed book has a negative spread: that's how a monitor can spot one. */
  @Test
  void crossedBookHasANegativeSpread() {
    service.onQuote(quote("EX_A", "AAPL", "BID", 190.10, 1), Instant.now());
    service.onQuote(quote("EX_B", "AAPL", "ASK", 190.00, 1), Instant.now());

    assertTrue(service.snapshot("AAPL", Instant.now()).spreadBps() < 0);
  }

  @Test
  void noSpreadWhenASideIsMissing() {
    service.onQuote(quote("EX_A", "AAPL", "BID", 190.00, 1), Instant.now());

    assertNull(service.snapshot("AAPL", Instant.now()).spreadBps());
    assertNull(service.snapshot("MSFT", Instant.now()).spreadBps());
  }

  private static RawQuoteEvent quote(
      String exchange, String symbol, String side, double price, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, symbol, side, price, 50, 1, sequence, now, now);
  }
}
