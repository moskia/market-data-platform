package com.marketdata.service.simulator.agents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.QuoteBook;
import com.marketdata.service.consolidation.QuoteValidator;
import com.marketdata.service.simulator.exchange.Exchange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Everything from this step together: market maker + noise traders on one exchange. Checks what
 * we built this for: the book is never crossed, and the engine's QuoteBook, reading only the
 * published messages, always agrees with the real exchange.
 */
class SingleExchangeSimulationTest {

  @Test
  void bookIsNeverCrossedAndTheFeedMatchesTheExchange() {
    List<RawQuoteEvent> published = new ArrayList<>();
    QuoteBook engineBook = new QuoteBook(Duration.ofMinutes(5));
    Exchange exchange =
        new Exchange(
            "EX_A",
            1,
            List.of("AAPL"),
            event -> {
              published.add(event);
              assertDoesNotThrow(() -> QuoteValidator.validate(event), event::toString);
              engineBook.update(event);
            });

    Random random = new Random(42);
    FundamentalValue fundamental = new FundamentalValue(10000, 3, random);
    Agent maker = new MarketMaker("mm", exchange, "AAPL", fundamental::current, 5, 100);
    List<Agent> traders =
        List.of(
            new NoiseTrader("noise-1", exchange, "AAPL", random),
            new NoiseTrader("noise-2", exchange, "AAPL", random));

    for (int round = 0; round < 5_000; round++) {
      fundamental.step();
      maker.act();

      for (Agent trader : traders) {
        trader.act();
      }

      OptionalLong bid = exchange.bestBid("AAPL");
      OptionalLong ask = exchange.bestAsk("AAPL");

      if (bid.isPresent() && ask.isPresent()) {
        assertTrue(bid.getAsLong() < ask.getAsLong(), "Crossed in round " + round);
      }

      assertEquals(toPrice(bid), engineBid(engineBook), "Bid differs in round " + round);
      assertEquals(toPrice(ask), engineAsk(engineBook), "Ask differs in round " + round);
    }

    assertTrue(published.size() > 1_000, "Expected a busy feed, got " + published.size());
  }

  private static OptionalDouble toPrice(OptionalLong ticks) {
    return ticks.isPresent() ? OptionalDouble.of(ticks.getAsLong() / 100.0) : OptionalDouble.empty();
  }

  private static OptionalDouble engineBid(QuoteBook book) {
    return book.bestBid("AAPL").stream().mapToDouble(RawQuoteEvent::price).findFirst();
  }

  private static OptionalDouble engineAsk(QuoteBook book) {
    return book.bestAsk("AAPL").stream().mapToDouble(RawQuoteEvent::price).findFirst();
  }
}
