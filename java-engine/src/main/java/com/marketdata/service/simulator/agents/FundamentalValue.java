package com.marketdata.service.simulator.agents;

import java.util.Random;

/** What a stock is "really" worth, in ticks. Moves by a random step each time step() is called. */
public class FundamentalValue {
  private final int maxStepTicks;
  private final Random random;
  private long currentTicks;

  public FundamentalValue(long startTicks, int maxStepTicks, Random random) {
    if (startTicks <= 0) {
      throw new IllegalArgumentException("startTicks must be positive");
    }

    if (maxStepTicks <= 0) {
      throw new IllegalArgumentException("maxStepTicks must be positive");
    }

    this.currentTicks = startTicks;
    this.maxStepTicks = maxStepTicks;
    this.random = random;
  }

  public long current() {
    return currentTicks;
  }

  public void step() {
    int move = random.nextInt(2 * maxStepTicks + 1) - maxStepTicks;
    currentTicks = Math.max(1, currentTicks + move);
  }
}
