package com.marketdata.service.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SymbolRouterTest {

  private final SymbolRouter router =
      new SymbolRouter(Map.of("AAPL", 0, "GOOG", 0, "MSFT", 1), 2);

  @Test
  void symbolInTableGoesToItsWorker() {
    assertEquals(0, router.workerFor("AAPL"));
    assertEquals(0, router.workerFor("GOOG"));
    assertEquals(1, router.workerFor("MSFT"));
  }

  /** The same symbol must always land on the same worker, or two threads would write its book. */
  @Test
  void unknownSymbolAlwaysGoesToSameWorker() {
    int first = router.workerFor("TSLA");

    for (int i = 0; i < 1_000; i++) {
      assertEquals(first, router.workerFor("TSLA"));
    }
  }

  @Test
  void unknownSymbolsStayInRange() {
    for (int i = 0; i < 10_000; i++) {
      int worker = router.workerFor("SYM" + i);
      assertTrue(worker >= 0 && worker < 2, "SYM" + i + " routed to worker " + worker);
    }
  }

  /** hashCode() can be negative, and a negative % gives a negative index. */
  @Test
  void negativeHashCodeStillGivesValidWorker() {
    String symbol = "polygenelubricants";
    assertTrue(symbol.hashCode() < 0, "precondition: this string has a negative hashCode");

    SymbolRouter threeWorkers = new SymbolRouter(Map.of(), 3);
    int worker = threeWorkers.workerFor(symbol);

    assertTrue(worker >= 0 && worker < 3, "routed to worker " + worker);
  }

  /** A quote without a symbol must be rejected, not crash the Kafka thread with an NPE. */
  @Test
  void missingSymbolIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> router.workerFor(null));
    assertThrows(IllegalArgumentException.class, () -> router.workerFor(""));
    assertThrows(IllegalArgumentException.class, () -> router.workerFor("   "));
  }

  /** A typo in the table (worker 2 when only 0 and 1 exist) should fail at startup, not later. */
  @Test
  void tableEntryOutsideWorkerRangeIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new SymbolRouter(Map.of("AAPL", 2), 2));
    assertThrows(IllegalArgumentException.class, () -> new SymbolRouter(Map.of("AAPL", -1), 2));
  }
}
