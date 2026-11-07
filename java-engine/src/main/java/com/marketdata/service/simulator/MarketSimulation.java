package com.marketdata.service.simulator;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.simulator.agents.Arbitrageur;
import com.marketdata.service.simulator.agents.FundamentalValue;
import com.marketdata.service.simulator.agents.MarketMaker;
import com.marketdata.service.simulator.agents.NoiseTrader;
import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.feed.UnreliableFeed;
import com.marketdata.service.simulator.scheduler.AgentTurns;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The whole market: three exchanges, and for every symbol a shared fair price, a market maker and
 * two noise traders on each exchange, plus one arbitrageur watching all of them. Every price
 * message goes out through an unreliable feed. Creating it books everyone's first turn; run it with
 * {@code scheduler.runUntil(...)}.
 */
public class MarketSimulation {

  public record Listing(String symbol, long startTicks) {}

  /** {@code sessionId} is stamped on every message; use a new, larger one for every run. */
  public record Settings(
      List<Listing> listings, long seed, long sessionId, boolean withArbitrageur) {

    /** What the simulator program runs. */
    public static Settings standard(long seed, long sessionId) {
      return new Settings(
          List.of(new Listing("AAPL", 19000), new Listing("MSFT", 42000), new Listing("GOOG", 17000)),
          seed,
          sessionId,
          true);
    }
  }

  private static final List<String> EXCHANGE_IDS = List.of("EX_A", "EX_B", "EX_C");
  private static final long[] ARBITRAGEUR_FEED_DELAYS_MS = {2, 5, 8};
  private static final long ARBITRAGEUR_ORDER_DELAY_MS = 3;
  private static final int ARBITRAGEUR_MAX_QUANTITY = 100;

  private static final int MAX_FAIR_STEP_TICKS = 3;
  private static final long FAIR_STEP_MEAN_MS = 50;

  private static final int HALF_SPREAD_TICKS = 5;
  private static final int QUOTE_SIZE = 100;
  private static final long MARKET_MAKER_MEAN_MS = 100;
  private static final long[] NOISE_TRADER_MEAN_MS = {200, 300};

  private final List<Exchange> exchanges = new ArrayList<>();
  private final UnreliableFeed feed;

  public MarketSimulation(
      EventScheduler scheduler, Settings settings, Consumer<RawQuoteEvent> output) {
    Random random = new Random(settings.seed());
    feed = new UnreliableFeed(scheduler, random, output);

    List<String> symbols = settings.listings().stream().map(Listing::symbol).toList();
    Arbitrageur arbitrageur =
        new Arbitrageur("arb", scheduler, ARBITRAGEUR_ORDER_DELAY_MS, ARBITRAGEUR_MAX_QUANTITY);

    for (int i = 0; i < EXCHANGE_IDS.size(); i++) {
      Consumer<RawQuoteEvent> exchangeOutput =
          settings.withArbitrageur()
              ? scheduler.delayed(ARBITRAGEUR_FEED_DELAYS_MS[i], arbitrageur::onQuote).andThen(feed)
              : feed;

      Exchange exchange = new Exchange(EXCHANGE_IDS.get(i), settings.sessionId(), symbols, exchangeOutput);
      exchanges.add(exchange);
      arbitrageur.connect(exchange);
    }

    for (Listing listing : settings.listings()) {
      String symbol = listing.symbol();
      FundamentalValue fair = new FundamentalValue(listing.startTicks(), MAX_FAIR_STEP_TICKS, random);
      AgentTurns.start(scheduler, fair::step, FAIR_STEP_MEAN_MS, random);

      for (Exchange exchange : exchanges) {
        String where = exchange.exchangeId() + "-" + symbol;

        MarketMaker maker =
            new MarketMaker(
                "mm-" + where, exchange, symbol, fair::current, HALF_SPREAD_TICKS, QUOTE_SIZE);
        AgentTurns.start(scheduler, maker, MARKET_MAKER_MEAN_MS, random);

        for (int n = 0; n < NOISE_TRADER_MEAN_MS.length; n++) {
          NoiseTrader trader = new NoiseTrader("noise" + (n + 1) + "-" + where, exchange, symbol, random);
          AgentTurns.start(scheduler, trader, NOISE_TRADER_MEAN_MS[n], random);
        }
      }
    }
  }

  public List<Exchange> exchanges() {
    return List.copyOf(exchanges);
  }

  /** Messages the feed lost. */
  public long dropped() {
    return feed.dropped();
  }

  /** Messages the feed delivered very late. */
  public long late() {
    return feed.late();
  }
}
