package com.marketdata.service.consolidation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuoteValidatorTest {

  @Test
  void normalQuoteIsValid() {
    assertDoesNotThrow(() -> QuoteValidator.validate(quote(100.05, 50)));
  }

  /** price 0 AND quantity 0 together = "this side is empty". */
  @Test
  void emptySideMessageIsValid() {
    assertDoesNotThrow(() -> QuoteValidator.validate(quote(0.0, 0)));
  }

  @Test
  void zeroPriceWithRealQuantityIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> QuoteValidator.validate(quote(0.0, 50)));
  }

  @Test
  void zeroQuantityWithRealPriceIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> QuoteValidator.validate(quote(100.05, 0)));
  }

  @Test
  void negativeValuesAreStillRejected() {
    assertThrows(IllegalArgumentException.class, () -> QuoteValidator.validate(quote(-1.0, 50)));
    assertThrows(IllegalArgumentException.class, () -> QuoteValidator.validate(quote(100.05, -5)));
    assertThrows(
        IllegalArgumentException.class, () -> QuoteValidator.validate(quote(Double.NaN, 50)));
  }

  /** Messages written before session ids existed have 0 here, and are rejected cleanly. */
  @Test
  void missingSessionIdIsRejected() {
    Instant now = Instant.now();
    RawQuoteEvent noSession = new RawQuoteEvent("EX_A", "AAPL", "BID", 100.05, 50, 0, 1, now, now);

    assertThrows(IllegalArgumentException.class, () -> QuoteValidator.validate(noSession));
  }

  private static RawQuoteEvent quote(double price, int quantity) {
    Instant now = Instant.now();
    return new RawQuoteEvent("EX_A", "AAPL", "BID", price, quantity, 1, 1, now, now);
  }
}
