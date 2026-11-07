package com.marketdata.service.simulator.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.simulator.agents.FundamentalValue;
import com.marketdata.service.simulator.agents.MarketMaker;
import com.marketdata.service.simulator.agents.NoiseTrader;
import com.marketdata.service.simulator.exchange.Exchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AgentTurnsTest {

  private final EventScheduler scheduler = new EventScheduler();

  @Test
  void agentGetsAboutOneTurnPerMeanDelay() {
    List<Long> turns = new ArrayList<>();
    AgentTurns.start(scheduler, () -> turns.add(scheduler.now()), 100, new Random(1));

    scheduler.runUntil(100_000);

    assertTrue(turns.size() > 900 && turns.size() < 1_100, "Turns: " + turns.size());
  }

  /** Each turn comes later than the one before, so time always moves forward. */
  @Test
  void turnsAreAtLeastOneMillisecondApart() {
    List<Long> turns = new ArrayList<>();
    AgentTurns.start(scheduler, () -> turns.add(scheduler.now()), 1, new Random(1));

    scheduler.runUntil(10_000);

    for (int i = 1; i < turns.size(); i++) {
      assertTrue(turns.get(i) > turns.get(i - 1), "Two turns at " + turns.get(i));
    }
  }

  @Test
  void delaysAreRandomNotFixed() {
    List<Long> turns = new ArrayList<>();
    AgentTurns.start(scheduler, () -> turns.add(scheduler.now()), 100, new Random(1));

    scheduler.runUntil(10_000);

    long distinctGaps =
        IntStream.range(1, turns.size())
            .mapToLong(i -> turns.get(i) - turns.get(i - 1))
            .distinct()
            .count();
    assertTrue(distinctGaps > 10, "Gaps look fixed: " + distinctGaps + " different values");
  }

  @Test
  void severalAgentsTakeTurnsInTimeOrder() {
    List<String> turns = new ArrayList<>();
    Random random = new Random(1);
    AgentTurns.start(scheduler, () -> turns.add("fast"), 10, random);
    AgentTurns.start(scheduler, () -> turns.add("slow"), 100, random);

    scheduler.runUntil(10_000);

    long fast = turns.stream().filter("fast"::equals).count();
    long slow = turns.stream().filter("slow"::equals).count();
    assertTrue(fast > 5 * slow, "fast=" + fast + " slow=" + slow);
  }

  @Test
  void invalidMeanDelayIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentTurns.start(scheduler, () -> {}, 0, new Random(1)));
  }

  /** The point of option B: the whole market, run twice with one seed, does exactly the same. */
  @Test
  void sameSeedGivesTheSameMarket() {
    assertEquals(runMarket(42), runMarket(42));
  }

  @Test
  void differentSeedGivesADifferentMarket() {
    assertTrue(!runMarket(1).equals(runMarket(2)));
  }

  /** Prices, quantities and sequence numbers of every message; timestamps use the real clock. */
  private static List<String> runMarket(long seed) {
    List<RawQuoteEvent> events = new ArrayList<>();
    Exchange exchange = new Exchange("EX_A", 1, List.of("AAPL"), events::add);
    Random random = new Random(seed);
    EventScheduler scheduler = new EventScheduler();

    FundamentalValue fundamental = new FundamentalValue(10000, 3, random);
    MarketMaker maker = new MarketMaker("mm", exchange, "AAPL", fundamental::current, 5, 100);

    AgentTurns.start(scheduler, fundamental::step, 50, random);
    AgentTurns.start(scheduler, maker, 100, random);
    AgentTurns.start(scheduler, new NoiseTrader("noise-1", exchange, "AAPL", random), 200, random);
    AgentTurns.start(scheduler, new NoiseTrader("noise-2", exchange, "AAPL", random), 300, random);

    scheduler.runUntil(60_000);

    assertTrue(events.size() > 100, "Market was too quiet: " + events.size());
    return events.stream()
        .map(e -> e.side() + " " + e.price() + " " + e.quantity() + " #" + e.sequenceNumber())
        .toList();
  }
}
