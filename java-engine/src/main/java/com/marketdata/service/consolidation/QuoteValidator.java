package com.marketdata.service.consolidation;

import com.marketdata.model.RawQuoteEvent;

public class QuoteValidator {

  public static void validate(RawQuoteEvent quote) {
    if (quote == null) {
      throw new IllegalArgumentException("Quote is missing");
    }

    if (quote.exchangeId() == null || quote.exchangeId().isBlank()) {
      throw new IllegalArgumentException("Exchange ID is missing");
    }

    if (quote.symbol() == null || quote.symbol().isBlank()) {
      throw new IllegalArgumentException("Symbol is missing");
    }

    if (!"BID".equals(quote.side()) && !"ASK".equals(quote.side())) {
      throw new IllegalArgumentException("Side must be BID or ASK");
    }

    if (!Double.isFinite(quote.price()) || quote.price() <= 0) {
      throw new IllegalArgumentException("Price must be finite and positive");
    }

    if (quote.quantity() <= 0) {
      throw new IllegalArgumentException("Quantity must be positive");
    }

    if (quote.sequenceNumber() <= 0) {
      throw new IllegalArgumentException("Sequence number must be positive");
    }

    if (quote.exchangeTimestamp() == null || quote.receivedTimestamp() == null) {
      throw new IllegalArgumentException("Timestamps are missing");
    }
  }
}
