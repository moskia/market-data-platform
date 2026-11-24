package com.marketdata.service.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

  /** A symbol nobody told the service about is still tracked, and its quotes still expire. */
  @Test
  void aNewSymbolIsDiscoveredAndExpiresLikeTheOthers() {
    List<ConsolidatedQuote> published = new ArrayList<>();
    ConsolidationService fresh =
        new ConsolidationService(new QuoteBook(Duration.ofSeconds(5)), List.of(), published::add);
    Instant start = Instant.now();

    fresh.onQuote(quote("EX_A", "TSLA", "BID", 250.00, 1), start);
    fresh.checkExpiry(start.plusSeconds(6));

    assertEquals(List.of("TSLA"), fresh.symbols());
    assertNull(published.get(published.size() - 1).bidPrice(), "The expired bid was not cleared");
  }

  // --- Ownership: partitions moving between engines -------------------------------------------

  /** The bug seen live: an engine kept expiring a lost symbol and published an empty book. */
  @Test
  void aForgottenSymbolIsNotPublishedWhenItsQuotesExpire() {
    List<ConsolidatedQuote> published = new ArrayList<>();
    ConsolidationService owner = serviceInto(published);
    Instant start = Instant.now();
    owner.onQuote(quote("EX_A", "MSFT", "BID", 420.00, 1), start);
    int before = published.size();

    owner.forget("MSFT");
    owner.checkExpiry(start.plusSeconds(6));

    assertEquals(before, published.size(), "Published for a symbol it no longer owns");
    assertEquals(List.of(), owner.symbols());
  }

  /** A quote still waiting in the queue when the symbol moved away must not bring it back. */
  @Test
  void quotesForAForgottenSymbolAreIgnoredUntilItIsOwnedAgain() {
    List<ConsolidatedQuote> published = new ArrayList<>();
    ConsolidationService owner = serviceInto(published);
    owner.forget("MSFT");

    assertFalse(owner.onQuote(quote("EX_A", "MSFT", "BID", 420.00, 1), Instant.now()));
    assertTrue(published.isEmpty());

    owner.own("MSFT", false);
    assertTrue(owner.onQuote(quote("EX_A", "MSFT", "BID", 420.00, 2), Instant.now()));
    assertEquals(1, published.size());
  }

  /** While rebuilding from history nothing is published; going live publishes the full book. */
  @Test
  void aRebuildingSymbolPublishesOnlyOnceItIsLive() {
    List<ConsolidatedQuote> published = new ArrayList<>();
    ConsolidationService owner = serviceInto(published);
    owner.own("MSFT", true);

    owner.onQuote(quote("EX_A", "MSFT", "BID", 420.00, 1), Instant.now());
    owner.onQuote(quote("EX_B", "MSFT", "ASK", 420.05, 1), Instant.now());
    owner.checkExpiry(Instant.now());
    assertTrue(published.isEmpty(), "Published while rebuilding: " + published);

    owner.goLive("MSFT", Instant.now());

    assertEquals(1, published.size());
    assertEquals(420.00, published.get(0).bidPrice());
    assertEquals(420.05, published.get(0).askPrice());
  }

  @Test
  void goingLiveWhenNotRebuildingPublishesNothingExtra() {
    List<ConsolidatedQuote> published = new ArrayList<>();
    ConsolidationService owner = serviceInto(published);
    owner.onQuote(quote("EX_A", "MSFT", "BID", 420.00, 1), Instant.now());
    int before = published.size();

    owner.goLive("MSFT", Instant.now());

    assertEquals(before, published.size());
  }

  private static ConsolidationService serviceInto(List<ConsolidatedQuote> published) {
    return new ConsolidationService(new QuoteBook(), List.of(), published::add);
  }

  private static RawQuoteEvent quote(
      String exchange, String symbol, String side, double price, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, symbol, side, price, 50, 1, sequence, now, now);
  }
}
