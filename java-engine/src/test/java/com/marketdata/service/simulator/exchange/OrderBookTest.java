package com.marketdata.service.simulator.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class OrderBookTest {

  private final OrderBook book = new OrderBook();
  private long nextId = 1;

  @Test
  void emptyBookHasNoBestPrices() {
    assertTrue(book.bestBid().isEmpty());
    assertTrue(book.bestAsk().isEmpty());
    assertEquals(0, book.quantityAt(Side.BUY, 10000));
  }

  @Test
  void bestBidIsHighestAndBestAskIsLowest() {
    book.submit(buy(9990, 10));
    book.submit(buy(10000, 10));
    book.submit(buy(9995, 10));
    book.submit(sell(10020, 10));
    book.submit(sell(10010, 10));
    book.submit(sell(10030, 10));

    assertEquals(OptionalLong.of(10000), book.bestBid());
    assertEquals(OptionalLong.of(10010), book.bestAsk());
  }

  @Test
  void ordersThatDoNotCrossRestWithoutTrading() {
    List<Trade> buyTrades = book.submit(buy(10000, 10));
    List<Trade> sellTrades = book.submit(sell(10005, 20));

    assertTrue(buyTrades.isEmpty());
    assertTrue(sellTrades.isEmpty());
    assertEquals(10, book.quantityAt(Side.BUY, 10000));
    assertEquals(20, book.quantityAt(Side.SELL, 10005));
  }

  @Test
  void quantityAtAddsUpEveryOrderAtThatPrice() {
    book.submit(buy(10000, 10));
    book.submit(buy(10000, 25));
    book.submit(buy(9990, 7));

    assertEquals(35, book.quantityAt(Side.BUY, 10000));
    assertEquals(7, book.quantityAt(Side.BUY, 9990));
  }

  /** The example from our discussion: price first, then time, at the resting price. */
  @Test
  void incomingBuyTakesBestPriceThenEarliestOrder() {
    Order a = sell(10005, 30);
    Order b = sell(10005, 20);
    Order c = sell(10010, 50);
    book.submit(a);
    book.submit(b);
    book.submit(c);

    Order incoming = buy(10007, 40);
    List<Trade> trades = book.submit(incoming);

    assertEquals(2, trades.size());
    assertTrade(trades.get(0), incoming, a, 10005, 30);
    assertTrade(trades.get(1), incoming, b, 10005, 10);

    assertEquals(10, book.quantityAt(Side.SELL, 10005), "B keeps 10 and its place in line");
    assertEquals(50, book.quantityAt(Side.SELL, 10010), "C is too expensive for this buyer");
    assertEquals(OptionalLong.of(10005), book.bestAsk());
    assertTrue(book.bestBid().isEmpty(), "Fully filled, so nothing rests");
    assertTrue(incoming.isFilled());
  }

  /** The "BUY 100" variant: what can't trade rests as the new best bid. */
  @Test
  void unfilledRemainderRestsInTheBook() {
    book.submit(sell(10005, 30));
    book.submit(sell(10005, 20));
    book.submit(sell(10010, 50));

    Order incoming = buy(10007, 100);
    List<Trade> trades = book.submit(incoming);

    assertEquals(50, trades.stream().mapToInt(Trade::quantity).sum());
    assertEquals(50, incoming.remaining());
    assertEquals(OptionalLong.of(10007), book.bestBid());
    assertEquals(50, book.quantityAt(Side.BUY, 10007));
    assertEquals(OptionalLong.of(10010), book.bestAsk(), "The emptied 100.05 level is gone");
  }

  /** Same rules mirrored: an incoming sell takes the HIGHEST bids first. */
  @Test
  void incomingSellTakesHighestBidsFirst() {
    Order x = buy(10000, 10);
    Order y = buy(9990, 10);
    book.submit(y);
    book.submit(x);

    Order incoming = sell(9980, 15);
    List<Trade> trades = book.submit(incoming);

    assertEquals(2, trades.size());
    assertTrade(trades.get(0), x, incoming, 10000, 10);
    assertTrade(trades.get(1), y, incoming, 9990, 5);
    assertEquals(OptionalLong.of(9990), book.bestBid());
    assertEquals(5, book.quantityAt(Side.BUY, 9990));
  }

  @Test
  void incomingOrderCanSweepSeveralLevels() {
    book.submit(sell(10001, 5));
    book.submit(sell(10002, 5));
    book.submit(sell(10003, 5));

    List<Trade> trades = book.submit(buy(10003, 15));

    assertEquals(List.of(10001L, 10002L, 10003L), trades.stream().map(Trade::priceTicks).toList());
    assertTrue(book.bestAsk().isEmpty());
    assertTrue(book.bestBid().isEmpty());
  }

  /** The cancel scenario: 2nd order of a level is cancelled; 1st and 3rd keep their order. */
  @Test
  void cancelInTheMiddleOfALevelKeepsTimePriorityForTheOthers() {
    Order first = sell(10005, 10);
    Order second = sell(10005, 10);
    Order third = sell(10005, 10);
    book.submit(first);
    book.submit(second);
    book.submit(third);

    assertTrue(book.cancel(second.id()));
    assertEquals(20, book.quantityAt(Side.SELL, 10005));

    Order incoming = buy(10005, 20);
    List<Trade> trades = book.submit(incoming);

    assertEquals(2, trades.size());
    assertTrade(trades.get(0), incoming, first, 10005, 10);
    assertTrade(trades.get(1), incoming, third, 10005, 10);
  }

  @Test
  void cancellingTheLastOrderOfTheBestLevelRemovesThatLevel() {
    Order best = buy(10000, 10);
    book.submit(best);
    book.submit(buy(9990, 10));

    assertTrue(book.cancel(best.id()));

    assertEquals(OptionalLong.of(9990), book.bestBid(), "An empty level must not stay the best");
    assertEquals(0, book.quantityAt(Side.BUY, 10000));
  }

  @Test
  void cancelReturnsFalseForUnknownOrAlreadyFilledOrders() {
    Order resting = sell(10005, 10);
    book.submit(resting);
    book.submit(buy(10005, 10)); // fills `resting` completely

    assertFalse(book.cancel(resting.id()), "Filled orders are no longer in the book");
    assertFalse(book.cancel(999_999), "Unknown id");
  }

  @Test
  void cancelledOrderCanBeCancelledOnlyOnce() {
    Order order = buy(10000, 10);
    book.submit(order);

    assertTrue(book.cancel(order.id()));
    assertFalse(book.cancel(order.id()));
    assertTrue(book.bestBid().isEmpty());
  }

  /** An order that arrived and matched completely must not appear in the book at all. */
  @Test
  void fullyFilledIncomingOrderIsNotCancellable() {
    book.submit(sell(10005, 10));
    Order incoming = buy(10005, 10);
    book.submit(incoming);

    assertFalse(book.cancel(incoming.id()));
  }

  @Test
  void ordersRejectNonPositivePriceOrQuantity() {
    assertThrows(IllegalArgumentException.class, () -> new Order(1, "t", Side.BUY, 0, 10));
    assertThrows(IllegalArgumentException.class, () -> new Order(1, "t", Side.BUY, 10000, 0));
  }

  private Order buy(long priceTicks, int quantity) {
    return new Order(nextId++, "agent-" + nextId, Side.BUY, priceTicks, quantity);
  }

  private Order sell(long priceTicks, int quantity) {
    return new Order(nextId++, "agent-" + nextId, Side.SELL, priceTicks, quantity);
  }

  private static void assertTrade(
      Trade trade, Order buyer, Order seller, long priceTicks, int quantity) {
    assertEquals(buyer.id(), trade.buyOrderId(), "buy order id");
    assertEquals(seller.id(), trade.sellOrderId(), "sell order id");
    assertEquals(buyer.agentId(), trade.buyAgentId(), "buy agent");
    assertEquals(seller.agentId(), trade.sellAgentId(), "sell agent");
    assertEquals(priceTicks, trade.priceTicks(), "price");
    assertEquals(quantity, trade.quantity(), "quantity");
  }
}
