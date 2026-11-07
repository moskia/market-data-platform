package com.marketdata.service.simulator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.QuoteValidator;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** One simulated minute of the standard market. */
class MarketSimulationTest {

  private static List<RawQuoteEvent> runOneMinute(long seed) {
    List<RawQuoteEvent> received = new ArrayList<>();
    EventScheduler scheduler = new EventScheduler();
    new MarketSimulation(scheduler, MarketSimulation.Settings.standard(seed, 1), received::add);

    scheduler.runUntil(60_000);

    return received;
  }

  @Test
  void everyExchangeSendsPricesForEverySymbol() {
    Set<String> pairs =
        runOneMinute(1).stream()
            .map(quote -> quote.exchangeId() + " " + quote.symbol())
            .collect(Collectors.toSet());

    assertEquals(9, pairs.size(), "Exchange/symbol pairs seen: " + pairs);
  }

  @Test
  void theFeedIsBusyEnough() {
    int received = runOneMinute(1).size();

    assertTrue(received > 60 * 50, "Only " + received + " messages in a minute");
  }

  /** What the engine receives must always pass its validator, empty sides included. */
  @Test
  void everyMessagePassesTheEnginesValidator() {
    for (RawQuoteEvent quote : runOneMinute(1)) {
      assertDoesNotThrow(() -> QuoteValidator.validate(quote), quote::toString);
    }
  }

  /** Prices stay around each symbol's starting price (AAPL 190, MSFT 420, GOOG 170). */
  @Test
  void pricesStartNearEachSymbolsStartingPrice() {
    Map<String, Double> firstPrice =
        runOneMinute(1).stream()
            .filter(quote -> quote.quantity() > 0)
            .collect(
                Collectors.toMap(RawQuoteEvent::symbol, RawQuoteEvent::price, (a, b) -> a));

    assertEquals(190.0, firstPrice.get("AAPL"), 1.0);
    assertEquals(420.0, firstPrice.get("MSFT"), 1.0);
    assertEquals(170.0, firstPrice.get("GOOG"), 1.0);
  }

  @Test
  void sameSeedGivesTheSameMessages() {
    assertEquals(describe(runOneMinute(42)), describe(runOneMinute(42)));
  }

  @Test
  void theFeedLosesAndDelaysSomeMessages() {
    EventScheduler scheduler = new EventScheduler();
    MarketSimulation market =
        new MarketSimulation(scheduler, MarketSimulation.Settings.standard(1, 1), quote -> {});

    scheduler.runUntil(60_000);

    assertTrue(market.dropped() > 0, "Nothing was dropped");
    assertTrue(market.late() > 0, "Nothing was late");
  }

  @Test
  void everyMessageCarriesTheRunsSession() {
    List<RawQuoteEvent> received = new ArrayList<>();
    EventScheduler scheduler = new EventScheduler();
    new MarketSimulation(scheduler, MarketSimulation.Settings.standard(1, 99), received::add);

    scheduler.runUntil(10_000);

    assertTrue(!received.isEmpty() && received.stream().allMatch(q -> q.sessionId() == 99));
  }

  /** Everything except the timestamps, which come from the real clock. */
  private static List<String> describe(List<RawQuoteEvent> quotes) {
    return quotes.stream()
        .map(
            q ->
                q.exchangeId() + " " + q.symbol() + " " + q.side() + " " + q.price() + " "
                    + q.quantity() + " #" + q.sequenceNumber())
        .toList();
  }
}
