package com.marketdata.service.simulator.exchange;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.QuoteValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ExchangeTest {

  private final List<RawQuoteEvent> published = new ArrayList<>();
  private final Exchange exchange = new Exchange("EX_A", 1, List.of("AAPL", "MSFT"), published::add);

  @Test
  void firstOrderPublishesItsSide() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);

    assertEquals(1, published.size());
    RawQuoteEvent event = published.get(0);
    assertEquals("EX_A", event.exchangeId());
    assertEquals("AAPL", event.symbol());
    assertEquals("BID", event.side());
    assertEquals(100.00, event.price());
    assertEquals(50, event.quantity());
    assertNotNull(event.exchangeTimestamp());
    assertNotNull(event.receivedTimestamp());
  }

  @Test
  void pricesAreConvertedFromTicks() {
    exchange.submit("AAPL", "mm", Side.SELL, 10005, 10);

    assertEquals(100.05, last().price());
    assertEquals("ASK", last().side());
  }

  /** Level 1 only shows the best price: a deeper order changes nothing visible. */
  @Test
  void deeperOrderDoesNotPublish() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    exchange.submit("AAPL", "noise", Side.BUY, 9990, 30);

    assertEquals(1, published.size());
  }

  @Test
  void moreQuantityAtTheBestPricePublishes() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    exchange.submit("AAPL", "noise", Side.BUY, 10000, 30);

    assertEquals(2, published.size());
    assertQuote(last(), "BID", 100.00, 80);
  }

  @Test
  void betterPricePublishes() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    exchange.submit("AAPL", "noise", Side.BUY, 10005, 30);

    assertQuote(last(), "BID", 100.05, 30);
  }

  @Test
  void cancellingTheOnlyBidPublishesAnEmptySide() {
    SubmitResult order = exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);

    assertTrue(exchange.cancel("AAPL", order.orderId()));

    assertQuote(last(), "BID", 0.0, 0);
    assertTrue(exchange.bestBid("AAPL").isEmpty());
  }

  @Test
  void cancellingADeeperOrderDoesNotPublish() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    SubmitResult deeper = exchange.submit("AAPL", "noise", Side.BUY, 9990, 30);
    int before = published.size();

    assertTrue(exchange.cancel("AAPL", deeper.orderId()));

    assertEquals(before, published.size());
  }

  @Test
  void cancelOfUnknownOrderReturnsFalseAndPublishesNothing() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    int before = published.size();

    assertFalse(exchange.cancel("AAPL", 999_999));
    assertEquals(before, published.size());
  }

  /** A buy that trades part of the best ask: the ask's quantity shrinks; the buy never rests. */
  @Test
  void tradePublishesTheReducedQuantity() {
    exchange.submit("AAPL", "mm", Side.SELL, 10005, 50);
    int before = published.size();

    SubmitResult result = exchange.submit("AAPL", "noise", Side.BUY, 10005, 20);

    assertEquals(1, result.trades().size());
    assertEquals(20, result.trades().get(0).quantity());
    assertEquals(before + 1, published.size(), "Only the ask changed");
    assertQuote(last(), "ASK", 100.05, 30);
    assertTrue(exchange.bestBid("AAPL").isEmpty());
  }

  /** One order can change BOTH sides: it clears the ask, then rests as the new bid. */
  @Test
  void sweepThatRestsChangesBothSides() {
    exchange.submit("AAPL", "mm", Side.SELL, 10005, 20);
    int before = published.size();

    exchange.submit("AAPL", "noise", Side.BUY, 10010, 50);

    List<RawQuoteEvent> events = published.subList(before, published.size());
    assertEquals(2, events.size());
    assertTrue(events.stream().anyMatch(e -> matches(e, "ASK", 0.0, 0)), "Ask side emptied");
    assertTrue(events.stream().anyMatch(e -> matches(e, "BID", 100.10, 30)), "Rest became bid");
  }

  /** The flicker fix: one clean update, never the empty moment in between. */
  @Test
  void replacePublishesOnceWithoutFlicker() {
    SubmitResult order = exchange.submit("AAPL", "mm", Side.BUY, 9995, 100);
    int before = published.size();

    Optional<SubmitResult> replaced = exchange.replace("AAPL", order.orderId(), 9996, 100);

    assertTrue(replaced.isPresent());
    assertNotEquals(order.orderId(), replaced.get().orderId(), "A replace is a new order");
    assertEquals(before + 1, published.size(), "Exactly one update");
    assertQuote(last(), "BID", 99.96, 100);
  }

  @Test
  void replaceToTheSamePriceAndSizePublishesNothing() {
    SubmitResult order = exchange.submit("AAPL", "mm", Side.BUY, 9995, 100);
    int before = published.size();

    exchange.replace("AAPL", order.orderId(), 9995, 100);

    assertEquals(before, published.size());
  }

  @Test
  void replaceCanTradeIfTheNewPriceCrosses() {
    exchange.submit("AAPL", "seller", Side.SELL, 10005, 10);
    SubmitResult bid = exchange.submit("AAPL", "mm", Side.BUY, 10000, 10);

    Optional<SubmitResult> replaced = exchange.replace("AAPL", bid.orderId(), 10005, 10);

    assertEquals(1, replaced.orElseThrow().trades().size());
    assertTrue(exchange.bestBid("AAPL").isEmpty());
    assertTrue(exchange.bestAsk("AAPL").isEmpty());
  }

  /** Replacing an order that already traded must not create new exposure. */
  @Test
  void replaceOfAFilledOrderReturnsEmptyAndPostsNothing() {
    SubmitResult ask = exchange.submit("AAPL", "mm", Side.SELL, 10005, 10);
    exchange.submit("AAPL", "noise", Side.BUY, 10005, 10); // fills the ask
    int before = published.size();

    Optional<SubmitResult> replaced = exchange.replace("AAPL", ask.orderId(), 10010, 10);

    assertTrue(replaced.isEmpty());
    assertTrue(exchange.bestAsk("AAPL").isEmpty());
    assertEquals(before, published.size());
  }

  /** A replace that is rejected must leave the original order working and the feed unchanged. */
  @Test
  void invalidReplaceLeavesTheOriginalOrderInPlace() {
    SubmitResult order = exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    int before = published.size();

    assertThrows(
        IllegalArgumentException.class, () -> exchange.replace("AAPL", order.orderId(), 10001, 0));

    assertEquals(OptionalLong.of(10000), exchange.bestBid("AAPL"));
    assertEquals(before, published.size());
    assertTrue(exchange.cancel("AAPL", order.orderId()), "The original order is still live");
  }

  @Test
  void eachSymbolHasItsOwnBook() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    exchange.submit("MSFT", "mm", Side.SELL, 9990, 50); // would cross AAPL's bid if books were shared

    assertEquals(OptionalLong.of(10000), exchange.bestBid("AAPL"));
    assertEquals(OptionalLong.of(9990), exchange.bestAsk("MSFT"));
    assertEquals("MSFT", last().symbol());
  }

  /** One counter per exchange, across all symbols: what the engine's staleness check relies on. */
  @Test
  void sequenceNumbersIncreaseForEveryPublishedEvent() {
    exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    exchange.submit("MSFT", "mm", Side.SELL, 20000, 50);
    exchange.submit("AAPL", "mm", Side.SELL, 10010, 50);
    exchange.submit("AAPL", "mm", Side.BUY, 10001, 50);

    for (int i = 1; i < published.size(); i++) {
      assertTrue(
          published.get(i).sequenceNumber() > published.get(i - 1).sequenceNumber(),
          "Sequence went backwards at event " + i);
    }
    assertTrue(published.get(0).sequenceNumber() > 0);
  }

  @Test
  void everyMessageCarriesTheExchangesSession() {
    Exchange restarted = new Exchange("EX_A", 7, List.of("AAPL"), published::add);
    restarted.submit("AAPL", "mm", Side.BUY, 10000, 50);
    restarted.submit("AAPL", "mm", Side.SELL, 10010, 50);

    assertTrue(published.stream().allMatch(e -> e.sessionId() == 7));
  }

  @Test
  void orderIdsAreUnique() {
    long first = exchange.submit("AAPL", "mm", Side.BUY, 10000, 50).orderId();
    long second = exchange.submit("MSFT", "mm", Side.BUY, 10000, 50).orderId();

    assertNotEquals(first, second);
  }

  @Test
  void unknownSymbolIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> exchange.submit("TSLA", "mm", Side.BUY, 10000, 5));
    assertThrows(IllegalArgumentException.class, () -> exchange.cancel("TSLA", 1));
    assertThrows(IllegalArgumentException.class, () -> exchange.bestBid("TSLA"));
  }

  /** Whatever the exchange publishes must be accepted by the engine, empty sides included. */
  @Test
  void everyPublishedEventPassesTheEnginesValidator() {
    SubmitResult bid = exchange.submit("AAPL", "mm", Side.BUY, 10000, 50);
    exchange.submit("AAPL", "mm", Side.SELL, 10010, 50);
    exchange.submit("AAPL", "noise", Side.BUY, 10010, 20);
    exchange.cancel("AAPL", bid.orderId());

    for (RawQuoteEvent event : published) {
      assertDoesNotThrow(() -> QuoteValidator.validate(event), event::toString);
    }
  }

  /** Step 3 of our example: the seller was waiting, so the exchange tells the seller privately. */
  @Test
  void ownerOfTheWaitingOrderIsToldAboutTheTrade() {
    List<Trade> sellerFills = new ArrayList<>();
    exchange.register("seller", sellerFills::add);
    SubmitResult ask = exchange.submit("AAPL", "seller", Side.SELL, 10005, 100);

    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 30);

    assertEquals(1, sellerFills.size());
    assertEquals(ask.orderId(), sellerFills.get(0).sellOrderId());
    assertEquals(30, sellerFills.get(0).quantity());
  }

  /** The incoming order's owner already gets the trades back from submit(), so no callback. */
  @Test
  void ownerOfTheIncomingOrderIsNotCalledBack() {
    List<Trade> buyerFills = new ArrayList<>();
    exchange.register("buyer", buyerFills::add);
    exchange.submit("AAPL", "seller", Side.SELL, 10005, 100);

    SubmitResult result = exchange.submit("AAPL", "buyer", Side.BUY, 10005, 30);

    assertTrue(buyerFills.isEmpty());
    assertEquals(1, result.trades().size());
  }

  @Test
  void everyWaitingOwnerHitByOneOrderIsTold() {
    List<Trade> firstFills = new ArrayList<>();
    List<Trade> secondFills = new ArrayList<>();
    exchange.register("first", firstFills::add);
    exchange.register("second", secondFills::add);
    exchange.submit("AAPL", "first", Side.BUY, 10000, 10);
    exchange.submit("AAPL", "second", Side.BUY, 9990, 10);

    exchange.submit("AAPL", "seller", Side.SELL, 9990, 15);

    assertEquals(10, firstFills.get(0).quantity());
    assertEquals(5, secondFills.get(0).quantity());
  }

  @Test
  void aReplaceThatTradesTellsTheWaitingOwner() {
    List<Trade> sellerFills = new ArrayList<>();
    exchange.register("seller", sellerFills::add);
    exchange.submit("AAPL", "seller", Side.SELL, 10005, 10);
    SubmitResult bid = exchange.submit("AAPL", "buyer", Side.BUY, 10000, 10);

    exchange.replace("AAPL", bid.orderId(), 10005, 10);

    assertEquals(1, sellerFills.size());
  }

  /** By the time the owner hears about a trade, the exchange has finished its own work. */
  @Test
  void ownerIsToldOnlyAfterThePricesArePublished() {
    List<Integer> publishedAtCallback = new ArrayList<>();
    exchange.register("seller", trade -> publishedAtCallback.add(published.size()));
    exchange.submit("AAPL", "seller", Side.SELL, 10005, 100);

    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 30);

    assertEquals(List.of(published.size()), publishedAtCallback);
    assertEquals(2, published.size(), "The first ask, then the reduced ask");
  }

  /** Only the reduced ask is published: the leftover never shows up as a bid. */
  @Test
  void immediateOrCancelPublishesOnceAndNeverShowsTheLeftover() {
    List<Trade> sellerFills = new ArrayList<>();
    exchange.register("seller", sellerFills::add);
    exchange.submit("AAPL", "seller", Side.SELL, 10000, 10);
    int before = published.size();

    SubmitResult result =
        exchange.submitImmediateOrCancel("AAPL", "arb", Side.BUY, 10000, 50);

    assertEquals(10, result.trades().get(0).quantity());
    assertEquals(before + 1, published.size(), "One update: the ask side emptied");
    assertQuote(last(), "ASK", 0.0, 0);
    assertTrue(published.stream().noneMatch(e -> e.side().equals("BID")), "Leftover was shown");
    assertTrue(exchange.bestBid("AAPL").isEmpty());
    assertEquals(1, sellerFills.size(), "The waiting seller is still told");
  }

  @Test
  void immediateOrCancelWithNothingToTradePublishesNothing() {
    exchange.submit("AAPL", "seller", Side.SELL, 10010, 10);
    int before = published.size();

    SubmitResult result =
        exchange.submitImmediateOrCancel("AAPL", "arb", Side.BUY, 10000, 50);

    assertTrue(result.trades().isEmpty());
    assertEquals(before, published.size());
  }

  private RawQuoteEvent last() {
    return published.get(published.size() - 1);
  }

  private static boolean matches(RawQuoteEvent event, String side, double price, int quantity) {
    return event.side().equals(side) && event.price() == price && event.quantity() == quantity;
  }

  private static void assertQuote(RawQuoteEvent event, String side, double price, int quantity) {
    assertTrue(
        matches(event, side, price, quantity),
        "Expected " + side + " " + price + "/" + quantity + " but got " + event);
  }
}
