package com.marketdata.service.simulator.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.exchange.Side;
import com.marketdata.service.simulator.exchange.Trade;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Prices reach the arbitrageur 2 ms after they are published, and its orders reach the exchange 3
 * ms after it decides. So a cross set up at time 0 is traded at time 5.
 */
class ArbitrageurTest {

  private final EventScheduler scheduler = new EventScheduler();
  private final Arbitrageur arbitrageur = new Arbitrageur("arb", scheduler, 3, 1_000);
  private final List<RawQuoteEvent> fromA = new ArrayList<>();
  private final List<RawQuoteEvent> fromB = new ArrayList<>();
  private final Exchange exchangeA = exchange("EX_A", fromA);
  private final Exchange exchangeB = exchange("EX_B", fromB);

  private Exchange exchange(String id, List<RawQuoteEvent> sink) {
    Exchange exchange =
        new Exchange(
            id,
            1,
            List.of("AAPL"),
            scheduler.delayed(2, arbitrageur::onQuote).andThen(sink::add));
    arbitrageur.connect(exchange);
    return exchange;
  }

  /** EX_A bids 100.10, EX_B asks 100.00: buy 30 on B, sell 30 on A, keep 0.10 a share. */
  @Test
  void buysOnTheCheapExchangeAndSellsOnTheDearOne() {
    exchangeA.submit("AAPL", "mm-a", Side.BUY, 10010, 50);
    exchangeB.submit("AAPL", "mm-b", Side.SELL, 10000, 30);

    scheduler.runUntil(100);

    assertTrue(exchangeB.bestAsk("AAPL").isEmpty(), "EX_B's 30 should all be bought");
    assertQuote(last(fromA), "BID", 100.10, 20);
    assertEquals(0, arbitrageur.position("AAPL"), "Bought 30 and sold 30");
    assertEquals(30 * 10, arbitrageur.cashTicks(), "0.10 profit on each of 30 shares");
  }

  @Test
  void ordersArriveOnlyAfterBothDelays() {
    List<Trade> fills = new ArrayList<>();
    exchangeB.register("mm-b", fills::add);
    exchangeA.submit("AAPL", "mm-a", Side.BUY, 10010, 50);
    exchangeB.submit("AAPL", "mm-b", Side.SELL, 10000, 30);

    scheduler.runUntil(4);
    assertTrue(fills.isEmpty(), "Traded before the price could even have reached it");

    scheduler.runUntil(5);
    assertEquals(1, fills.size());
  }

  @Test
  void doesNothingWhenNothingIsCrossed() {
    exchangeA.submit("AAPL", "mm-a", Side.BUY, 10000, 50);
    exchangeB.submit("AAPL", "mm-b", Side.SELL, 10005, 30);

    scheduler.runUntil(100);

    assertEquals(OptionalLong.of(10000), exchangeA.bestBid("AAPL"));
    assertEquals(OptionalLong.of(10005), exchangeB.bestAsk("AAPL"));
    assertEquals(0, arbitrageur.cashTicks());
  }

  @Test
  void tradesNoMoreThanItsLimit() {
    Arbitrageur small = new Arbitrageur("small", scheduler, 3, 10);
    small.connect(exchangeA);
    small.connect(exchangeB);
    small.onQuote(quote("EX_A", "BID", 100.10, 50, 1));
    small.onQuote(quote("EX_B", "ASK", 100.00, 30, 1));
    exchangeA.submit("AAPL", "mm-a", Side.BUY, 10010, 50);
    exchangeB.submit("AAPL", "mm-b", Side.SELL, 10000, 30);

    scheduler.runUntil(3);

    assertEquals(0, small.position("AAPL"));
    assertEquals(10 * 10, small.cashTicks(), "Only 10 shares each way");
  }

  /** A bid and an ask from the same exchange can look crossed only because a message is late. */
  @Test
  void ignoresACrossWithinOneExchange() {
    arbitrageur.onQuote(quote("EX_A", "BID", 100.10, 50, 1));
    arbitrageur.onQuote(quote("EX_A", "ASK", 100.00, 50, 2));
    exchangeA.submit("AAPL", "mm-a", Side.BUY, 10010, 50);

    scheduler.runUntil(100); // long after any orders would have arrived

    assertEquals(0, arbitrageur.cashTicks());
    assertEquals(0, arbitrageur.position("AAPL"));
  }

  /** The cross is gone when the orders arrive: the buy finds nothing and leaves nothing behind. */
  @Test
  void crossGoneByArrivalLeavesNoWaitingOrderButCanLeaveShares() {
    exchangeA.submit("AAPL", "mm-a", Side.BUY, 10010, 50);
    long askId = exchangeB.submit("AAPL", "mm-b", Side.SELL, 10000, 30).orderId();
    scheduler.schedule(4, () -> exchangeB.cancel("AAPL", askId));

    scheduler.runUntil(100);

    assertTrue(exchangeB.bestBid("AAPL").isEmpty(), "The failed buy must not wait in EX_B's book");
    assertEquals(-30, arbitrageur.position("AAPL"), "Only the sell happened: leg risk");
  }

  /** A second crossing update arrives while the first orders are on their way: no second pair. */
  @Test
  void doesNotChaseTheSameCrossTwice() {
    List<Trade> fillsOnA = new ArrayList<>();
    exchangeA.register("mm-a", fillsOnA::add);
    exchangeA.submit("AAPL", "mm-a", Side.BUY, 10010, 50);
    exchangeB.submit("AAPL", "mm-b", Side.SELL, 10000, 30);
    scheduler.schedule(1, () -> exchangeA.submit("AAPL", "mm-a", Side.BUY, 10010, 50));

    scheduler.runUntil(100);

    assertEquals(1, fillsOnA.size(), "Sold on EX_A more than once");
    assertEquals(0, arbitrageur.position("AAPL"));
  }

  @Test
  void rejectsInvalidSettings() {
    assertThrows(IllegalArgumentException.class, () -> new Arbitrageur("a", scheduler, -1, 10));
    assertThrows(IllegalArgumentException.class, () -> new Arbitrageur("a", scheduler, 3, 0));
  }

  private static RawQuoteEvent quote(
      String exchange, String side, double price, int quantity, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, "AAPL", side, price, quantity, 1, sequence, now, now);
  }

  private static RawQuoteEvent last(List<RawQuoteEvent> events) {
    return events.get(events.size() - 1);
  }

  private static void assertQuote(RawQuoteEvent event, String side, double price, int quantity) {
    assertEquals(side, event.side());
    assertEquals(price, event.price());
    assertEquals(quantity, event.quantity());
  }
}
