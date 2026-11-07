package com.marketdata.service.simulator.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.exchange.Side;
import com.marketdata.service.simulator.exchange.SubmitResult;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MarketMakerTest {

  private final List<RawQuoteEvent> published = new ArrayList<>();
  private final Exchange exchange = new Exchange("EX_A", 1, List.of("AAPL"), published::add);

  // The fair price the market maker sees; each test sets it directly.
  private long fair = 10000;

  // Half spread 5 ticks, size 100: at fair 100.00 it quotes bid 99.95 and ask 100.05.
  private final MarketMaker maker = new MarketMaker("mm", exchange, "AAPL", () -> fair, 5, 100);

  @Test
  void firstActPostsABidAndAnAskAroundTheFairPrice() {
    maker.act();

    assertQuotes(9995, 100, 10005, 100);
  }

  @Test
  void samePriceDoesNothing() {
    maker.act();
    int before = published.size();

    maker.act();
    maker.act();

    assertEquals(before, published.size());
    assertQuotes(9995, 100, 10005, 100);
  }

  /** Both quotes move, in one clean update each: the feed never shows an empty side. */
  @Test
  void priceMoveMovesBothQuotesWithoutFlicker() {
    maker.act();
    int before = published.size();

    fair = 10001;
    maker.act();

    List<RawQuoteEvent> events = published.subList(before, published.size());
    assertEquals(2, events.size());
    assertTrue(events.stream().allMatch(e -> e.quantity() == 100), "Flicker: " + events);
    assertQuotes(9996, 100, 10006, 100);
    assertEquals(0, levelQuantity(Side.BUY, 9995), "The old bid must be gone");
    assertEquals(0, levelQuantity(Side.SELL, 10005), "The old ask must be gone");
  }

  /** Part of the ask is bought. It stays at 60, keeping its place in line. */
  @Test
  void partlyFilledQuoteStaysAtItsReducedSize() {
    maker.act();
    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 40);
    int before = published.size();

    maker.act();

    assertEquals(before, published.size());
    assertQuotes(9995, 100, 10005, 60);
  }

  /** The case that started this design: the ask is sold out, and the fair price hasn't moved. */
  @Test
  void fullyFilledQuoteIsPostedAgainOnTheNextAct() {
    maker.act();
    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 100);
    assertTrue(exchange.bestAsk("AAPL").isEmpty());

    maker.act();

    assertQuotes(9995, 100, 10005, 100);
  }

  @Test
  void priceMoveAfterAPartialFillGoesBackToFullSize() {
    maker.act();
    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 40);

    fair = 10001;
    maker.act();

    assertQuotes(9996, 100, 10006, 100);
  }

  @Test
  void priceMoveAfterAFullFillPostsBothQuotes() {
    maker.act();
    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 100);

    fair = 10002;
    maker.act();

    assertQuotes(9997, 100, 10007, 100);
  }

  /** Fills add up: 40 and then 60 is a full fill, so the quote must be posted again. */
  @Test
  void severalPartialFillsAddUp() {
    maker.act();
    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 40);
    exchange.submit("AAPL", "buyer", Side.BUY, 10005, 60);

    maker.act();

    assertQuotes(9995, 100, 10005, 100);
  }

  /** The new bid 100.45 would cross the old ask 100.05 if the bid moved first. */
  @Test
  void bigJumpUpDoesNotTradeWithItself() {
    maker.act();

    fair = 10050;
    maker.act();

    assertQuotes(10045, 100, 10055, 100);
  }

  @Test
  void bigJumpDownDoesNotTradeWithItself() {
    maker.act();

    fair = 9950;
    maker.act();

    assertQuotes(9945, 100, 9955, 100);
  }

  /** Its own new bid trades at once: part of it is reported only through SubmitResult. */
  @Test
  void newQuoteThatPartlyTradesRightAwayRemembersWhatIsLeft() {
    exchange.submit("AAPL", "seller", Side.SELL, 9990, 40);

    maker.act();
    assertEquals(OptionalLong.of(9995), exchange.bestBid("AAPL"));
    assertEquals(60, levelQuantity(Side.BUY, 9995));

    int before = published.size();
    maker.act();

    assertEquals(before, published.size(), "Same price and still waiting: nothing to do");
  }

  @Test
  void newQuoteThatFullyTradesRightAwayIsPostedAgainOnTheNextAct() {
    exchange.submit("AAPL", "seller", Side.SELL, 9990, 100);

    maker.act();
    assertTrue(exchange.bestBid("AAPL").isEmpty());

    maker.act();

    assertQuotes(9995, 100, 10005, 100);
  }

  /** At a very low fair price, fair − half spread would be zero or negative. */
  @Test
  void bidNeverGoesBelowOneTick() {
    fair = 3;

    maker.act();

    assertEquals(OptionalLong.of(1), exchange.bestBid("AAPL"));
    assertEquals(OptionalLong.of(8), exchange.bestAsk("AAPL"));
  }

  @Test
  void rejectsInvalidSettings() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MarketMaker("mm2", exchange, "AAPL", () -> fair, 0, 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MarketMaker("mm2", exchange, "AAPL", () -> fair, 5, 0));
  }

  private void assertQuotes(long bid, int bidQuantity, long ask, int askQuantity) {
    assertEquals(OptionalLong.of(bid), exchange.bestBid("AAPL"), "best bid");
    assertEquals(OptionalLong.of(ask), exchange.bestAsk("AAPL"), "best ask");
    assertEquals(bidQuantity, levelQuantity(Side.BUY, bid), "bid quantity");
    assertEquals(askQuantity, levelQuantity(Side.SELL, ask), "ask quantity");
  }

  /** Exchange doesn't show quantities, so read the last message published for that side. */
  private int levelQuantity(Side side, long priceTicks) {
    String name = side == Side.BUY ? "BID" : "ASK";

    for (int i = published.size() - 1; i >= 0; i--) {
      RawQuoteEvent event = published.get(i);

      if (event.side().equals(name)) {
        return Math.round(event.price() * 100) == priceTicks ? event.quantity() : 0;
      }
    }

    return 0;
  }
}
