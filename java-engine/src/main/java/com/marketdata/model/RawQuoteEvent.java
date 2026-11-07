package com.marketdata.model;

import java.time.Instant;

/**
 * One price change from one exchange. {@code sessionId} changes every time the exchange restarts,
 * and its sequence numbers start again from 1 in the new session.
 */
public record RawQuoteEvent(
    String exchangeId,
    String symbol,
    String side,
    double price,
    int quantity,
    long sessionId,
    long sequenceNumber,
    Instant exchangeTimestamp,
    Instant receivedTimestamp) {

  /** Newer means: from a later session, or from the same session with a higher sequence number. */
  public boolean isNewerThan(RawQuoteEvent other) {
    if (sessionId != other.sessionId) {
      return sessionId > other.sessionId;
    }

    return sequenceNumber > other.sequenceNumber;
  }
}
