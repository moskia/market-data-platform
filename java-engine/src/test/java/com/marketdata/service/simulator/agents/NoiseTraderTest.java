package com.marketdata.service.simulator.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.exchange.Side;
import com.marketdata.service.simulator.exchange.Trade;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoiseTraderTest {

  private final List<RawQuoteEvent> published = new ArrayList<>();
  private final Exchange exchange = new Exchange("EX_A", 1, List.of("AAPL"), published::add);

  @Test
  void doesNothingOnAnEmptyBook() {
    NoiseTrader trader = new NoiseTrader("noise", exchange, "AAPL", new Random(1));

    for (int i = 0; i < 100; i++) {
      trader.act();
    }

    assertTrue(published.isEmpty());
  }

  @Test
  void tradesAgainstOrdersWaitingInTheBook() {
    List<Trade> fills = new ArrayList<>();
    exchange.register("big", fills::add);
    addBigOrders();

    NoiseTrader trader = new NoiseTrader("noise", exchange, "AAPL", new Random(1));
    for (int i = 0; i < 200; i++) {
      trader.act();
    }

    assertTrue(fills.stream().anyMatch(t -> t.buyAgentId().equals("noise")), "Never bought");
    assertTrue(fills.stream().anyMatch(t -> t.sellAgentId().equals("noise")), "Never sold");
  }

  /** While there is plenty waiting at the best prices, those prices must not change. */
  @Test
  void doesNotMoveTheBestPricesWhileThereIsPlentyWaiting() {
    addBigOrders();

    NoiseTrader trader = new NoiseTrader("noise", exchange, "AAPL", new Random(1));
    for (int i = 0; i < 500; i++) {
      trader.act();

      assertEquals(OptionalLong.of(9995), exchange.bestBid("AAPL"));
      assertEquals(OptionalLong.of(10005), exchange.bestAsk("AAPL"));
    }
  }

  /** Clear the book with two huge orders: at most one noise order may still be waiting. */
  @Test
  void keepsAtMostOneWaitingOrder() {
    addBigOrders();

    NoiseTrader trader = new NoiseTrader("noise", exchange, "AAPL", new Random(1));
    for (int i = 0; i < 500; i++) {
      trader.act();
    }

    List<Trade> sweep = new ArrayList<>();
    sweep.addAll(exchange.submit("AAPL", "sweep", Side.BUY, 20000, 1_000_000).trades());
    sweep.addAll(exchange.submit("AAPL", "sweep", Side.SELL, 1, 2_000_000).trades());

    Set<Long> noiseOrders = new HashSet<>();
    for (Trade trade : sweep) {
      if (trade.buyAgentId().equals("noise")) {
        noiseOrders.add(trade.buyOrderId());
      }

      if (trade.sellAgentId().equals("noise")) {
        noiseOrders.add(trade.sellOrderId());
      }
    }

    assertTrue(noiseOrders.size() <= 1, "Waiting noise orders: " + noiseOrders);
  }

  @Test
  void sameSeedGivesTheSameBehaviour() {
    assertEquals(runWithSeed(42), runWithSeed(42));
  }

  private List<String> runWithSeed(long seed) {
    List<RawQuoteEvent> events = new ArrayList<>();
    Exchange seeded = new Exchange("EX_A", 1, List.of("AAPL"), events::add);
    seeded.submit("AAPL", "big", Side.BUY, 9995, 500);
    seeded.submit("AAPL", "big", Side.SELL, 10005, 500);

    NoiseTrader trader = new NoiseTrader("noise", seeded, "AAPL", new Random(seed));
    for (int i = 0; i < 200; i++) {
      trader.act();
    }

    return events.stream()
        .map(e -> e.side() + " " + e.price() + " " + e.quantity() + " #" + e.sequenceNumber())
        .toList();
  }

  private void addBigOrders() {
    exchange.submit("AAPL", "big", Side.BUY, 9995, 100_000);
    exchange.submit("AAPL", "big", Side.SELL, 10005, 100_000);
  }
}
