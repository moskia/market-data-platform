package com.marketdata.service.consolidation;

import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns raw exchange quotes into the best bid/ask per symbol. Knows nothing about Kafka or JSON:
 * quotes come in as objects, and new consolidated quotes go out through the onNewQuote callback.
 */
public class ConsolidationService {

  private final QuoteBook book;
  private final List<String> symbols;
  private final Consumer<ConsolidatedQuote> onNewQuote;
  private final Map<String, ConsolidatedQuote> lastPublished = new HashMap<>();

  public ConsolidationService(
      QuoteBook book, List<String> symbols, Consumer<ConsolidatedQuote> onNewQuote) {
    this.book = book;
    this.symbols = symbols;
    this.onNewQuote = onNewQuote;
  }

  /**
   * Validates the quote and adds it to the book.
   *
   * @return false if the quote is older than one already in the book (stale), true otherwise
   * @throws IllegalArgumentException if the quote is invalid
   */
  public boolean onQuote(RawQuoteEvent quote, Instant now) {
    QuoteValidator.validate(quote);

    boolean updated = book.update(quote);

    if (updated) {
      publishIfChanged(quote.symbol(), now);
    }

    return updated;
  }

  /** Re-checks every symbol so that quotes which expired since the last message are noticed. */
  public void checkExpiry(Instant now) {
    for (String symbol : symbols) {
      publishIfChanged(symbol, now);
    }
  }

  /** The current best bid/ask for a symbol; a side is null when no fresh quote exists. */
  public ConsolidatedQuote snapshot(String symbol, Instant now) {
    RawQuoteEvent bid = book.bestBid(symbol, now).orElse(null);
    RawQuoteEvent ask = book.bestAsk(symbol, now).orElse(null);

    Double spread = null;

    if (bid != null && ask != null) {
      spread = ask.price() - bid.price();
    }

    return new ConsolidatedQuote(
        symbol,
        bid == null ? null : bid.price(),
        bid == null ? null : bid.quantity(),
        bid == null ? null : bid.exchangeId(),
        ask == null ? null : ask.price(),
        ask == null ? null : ask.quantity(),
        ask == null ? null : ask.exchangeId(),
        spread,
        now);
  }

  public List<String> symbols() {
    return symbols;
  }

  private void publishIfChanged(String symbol, Instant now) {
    ConsolidatedQuote current = snapshot(symbol, now);
    ConsolidatedQuote previous = lastPublished.get(symbol);

    // Avoid publishing empty state before this symbol has any result.
    if (current.bidPrice() == null && current.askPrice() == null && previous == null) {
      return;
    }

    if (!current.sameMarketState(previous)) {
      onNewQuote.accept(current);
      lastPublished.put(symbol, current);
    }
  }
}
