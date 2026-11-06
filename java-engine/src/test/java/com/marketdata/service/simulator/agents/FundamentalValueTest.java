package com.marketdata.service.simulator.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FundamentalValueTest {

  @Test
  void startsAtTheGivenValue() {
    assertEquals(10000, new FundamentalValue(10000, 5, new Random(1)).current());
  }

  @Test
  void eachStepStaysWithinTheLimitAndThePriceDoesMove() {
    FundamentalValue value = new FundamentalValue(10000, 5, new Random(1));
    boolean moved = false;

    for (int i = 0; i < 1_000; i++) {
      long before = value.current();
      value.step();
      long move = value.current() - before;

      assertTrue(Math.abs(move) <= 5, "Moved too far: " + move);
      moved |= move != 0;
    }

    assertTrue(moved);
  }

  @Test
  void neverGoesBelowOneTick() {
    FundamentalValue value = new FundamentalValue(2, 5, new Random(1));

    for (int i = 0; i < 1_000; i++) {
      value.step();
      assertTrue(value.current() >= 1);
    }
  }

  @Test
  void sameSeedGivesTheSamePath() {
    FundamentalValue first = new FundamentalValue(10000, 5, new Random(42));
    FundamentalValue second = new FundamentalValue(10000, 5, new Random(42));

    for (int i = 0; i < 100; i++) {
      first.step();
      second.step();
      assertEquals(first.current(), second.current());
    }
  }

  @Test
  void rejectsInvalidSettings() {
    assertThrows(IllegalArgumentException.class, () -> new FundamentalValue(0, 5, new Random(1)));
    assertThrows(
        IllegalArgumentException.class, () -> new FundamentalValue(10000, 0, new Random(1)));
  }
}
