package com.marketdata.service.simulator.scheduler;

import com.marketdata.service.simulator.agents.Agent;
import java.util.Random;

/**
 * Gives an agent turns at random times, forever: after each turn it books its next one. The
 * delays are random around a mean, like orders arriving in a real market.
 */
public final class AgentTurns {

  private AgentTurns() {}

  public static void start(
      EventScheduler scheduler, Agent agent, long meanDelayMs, Random random) {
    if (meanDelayMs <= 0) {
      throw new IllegalArgumentException("meanDelayMs must be positive");
    }

    scheduler.schedule(
        nextDelayMs(meanDelayMs, random),
        () -> {
          agent.act();
          start(scheduler, agent, meanDelayMs, random);
        });
  }

  /** Mostly short delays with an occasional long one; at least 1 ms so time always moves on. */
  private static long nextDelayMs(long meanDelayMs, Random random) {
    double delay = -meanDelayMs * Math.log(1.0 - random.nextDouble());
    return Math.max(1, Math.round(delay));
  }
}
