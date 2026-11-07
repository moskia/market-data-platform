package com.marketdata.service.simulator.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.scheduler.AgentTurns;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Three exchanges quoting one shared fair price, each with its own market maker and noise traders.
 * Every simulated millisecond we look at all three books, exactly as they are (no delay), and
 * record whether the market as a whole is crossed.
 */
class ThreeExchangeSimulationTest {

  private static final List<String> EXCHANGES = List.of("EX_A", "EX_B", "EX_C");
  private static final long[] FEED_DELAYS_MS = {2, 5, 8};
  private static final long RUN_MS = 10 * 60 * 1_000;

  /** What one run measured. */
  private record Result(
      long crossedMs, int crosses, long longestCrossMs, int exchangeCrossedMs) {
    double crossedPercent() {
      return 100.0 * crossedMs / RUN_MS;
    }

    double averageCrossMs() {
      return crosses == 0 ? 0 : (double) crossedMs / crosses;
    }

    @Override
    public String toString() {
      return String.format(
          "crossed %.3f%% of the time, %d crosses, average %.1f ms, longest %d ms",
          crossedPercent(), crosses, averageCrossMs(), longestCrossMs);
    }
  }

  /** The fix for the old simulator's bug: a single exchange is never crossed, at any moment. */
  @Test
  void noSingleExchangeIsEverCrossed() {
    assertEquals(0, run(1, false).exchangeCrossedMs());
    assertEquals(0, run(1, true).exchangeCrossedMs());
  }

  /**
   * Without the arbitrageur, crosses appear (market makers update at different moments) and can
   * last hundreds of ms. With it, they still appear, but are corrected within a few ms.
   */
  @Test
  void crossesAreRareAndShortWithTheArbitrageur() {
    for (long seed : new long[] {1, 2, 3}) {
      Result without = run(seed, false);
      Result with = run(seed, true);
      System.out.println("seed " + seed + " without arbitrageur: " + without);
      System.out.println("seed " + seed + " with arbitrageur:    " + with);

      assertTrue(without.crosses() > 0, "Crosses should appear on their own");
      assertTrue(
          with.crossedPercent() < without.crossedPercent() / 3,
          "Not much less crossed time: " + with + " vs " + without);
      assertTrue(
          with.averageCrossMs() < without.averageCrossMs() / 3,
          "Crosses not much shorter: " + with + " vs " + without);
      assertTrue(with.longestCrossMs() < 100, "A cross lasted too long: " + with);
    }
  }

  private static Result run(long seed, boolean withArbitrageur) {
    Random random = new Random(seed);
    EventScheduler scheduler = new EventScheduler();
    FundamentalValue fundamental = new FundamentalValue(10000, 3, random);
    Arbitrageur arbitrageur = new Arbitrageur("arb", scheduler, 3, 100);
    List<Exchange> exchanges = new ArrayList<>();

    for (int i = 0; i < EXCHANGES.size(); i++) {
      Exchange exchange =
          new Exchange(
              EXCHANGES.get(i),
              List.of("AAPL"),
              withArbitrageur ? scheduler.delayed(FEED_DELAYS_MS[i], arbitrageur::onQuote) : e -> {});
      exchanges.add(exchange);
      arbitrageur.connect(exchange);

      String id = EXCHANGES.get(i);
      AgentTurns.start(
          scheduler,
          new MarketMaker("mm-" + id, exchange, "AAPL", fundamental::current, 5, 100),
          100,
          random);
      AgentTurns.start(scheduler, new NoiseTrader("noise1-" + id, exchange, "AAPL", random), 200, random);
      AgentTurns.start(scheduler, new NoiseTrader("noise2-" + id, exchange, "AAPL", random), 300, random);
    }

    AgentTurns.start(scheduler, fundamental::step, 50, random);

    long[] crossedMs = {0};
    int[] crosses = {0};
    long[] currentCross = {0};
    long[] longest = {0};
    int[] exchangeCrossed = {0};

    scheduleEveryMs(
        scheduler,
        () -> {
          long bestBid = Long.MIN_VALUE;
          long bestAsk = Long.MAX_VALUE;

          for (Exchange exchange : exchanges) {
            OptionalLong bid = exchange.bestBid("AAPL");
            OptionalLong ask = exchange.bestAsk("AAPL");

            if (bid.isPresent() && ask.isPresent() && bid.getAsLong() >= ask.getAsLong()) {
              exchangeCrossed[0]++;
            }

            if (bid.isPresent()) {
              bestBid = Math.max(bestBid, bid.getAsLong());
            }

            if (ask.isPresent()) {
              bestAsk = Math.min(bestAsk, ask.getAsLong());
            }
          }

          if (bestBid > bestAsk) {
            crossedMs[0]++;
            if (currentCross[0] == 0) {
              crosses[0]++;
            }
            currentCross[0]++;
            longest[0] = Math.max(longest[0], currentCross[0]);
          } else {
            currentCross[0] = 0;
          }
        });

    scheduler.runUntil(RUN_MS);

    return new Result(crossedMs[0], crosses[0], longest[0], exchangeCrossed[0]);
  }

  private static void scheduleEveryMs(EventScheduler scheduler, Runnable check) {
    scheduler.schedule(
        1,
        () -> {
          check.run();
          scheduleEveryMs(scheduler, check);
        });
  }
}
