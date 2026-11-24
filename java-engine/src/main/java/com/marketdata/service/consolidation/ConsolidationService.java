package com.marketdata.service.consolidation;

import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns raw exchange quotes into the best bid/ask per symbol. Knows nothing about Kafka or JSON:
 * quotes come in as objects, and new consolidated quotes go out through the onNewQuote callback.
 */
public class ConsolidationService {

  private final QuoteBook book;
  // Starts with the symbols we were told about; grows with every new symbol that arrives.
  private final Set<String> symbols;
  private final Consumer<ConsolidatedQuote> onNewQuote;
  private final Map<String, ConsolidatedQuote> lastPublished = new HashMap<>();

  // Ownership, set by the engine when Kafka moves partitions between engines (see forget/own).
  private final Set<String> notOwned = new HashSet<>();
  private final Set<String> quiet = new HashSet<>();

  public ConsolidationService(
      QuoteBook book, List<String> symbols, Consumer<ConsolidatedQuote> onNewQuote) {
    this.book = book;
    this.symbols = new LinkedHashSet<>(symbols);
    this.onNewQuote = onNewQuote;
  }

  /**
   * Validates the quote and adds it to the book.
   *
   * @return false if the quote is older than one already in the book (stale) or its symbol is no
   *     longer ours, true otherwise
   * @throws IllegalArgumentException if the quote is invalid
   */
  public boolean onQuote(RawQuoteEvent quote, Instant now) {
    QuoteValidator.validate(quote);

    if (notOwned.contains(quote.symbol())) {
      return false; // a quote that was still waiting when this symbol moved to another engine
    }
    symbols.add(quote.symbol());

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

    Double spreadBps = null;

    if (bid != null && ask != null) {
      double mid = (bid.price() + ask.price()) / 2;
      spreadBps = (ask.price() - bid.price()) / mid * 10_000;
    }

    return new ConsolidatedQuote(
        symbol,
        bid == null ? null : bid.price(),
        bid == null ? null : bid.quantity(),
        bid == null ? null : bid.exchangeId(),
        ask == null ? null : ask.price(),
        ask == null ? null : ask.quantity(),
        ask == null ? null : ask.exchangeId(),
        spreadBps,
        now);
  }

  /**
   * This symbol moved to another engine: drop everything about it, publish nothing more for it,
   * and ignore its quotes until {@link #own} is called.
   */
  public void forget(String symbol) {
    book.removeSymbol(symbol);
    lastPublished.remove(symbol);
    symbols.remove(symbol);
    quiet.remove(symbol);
    notOwned.add(symbol);
  }

  /**
   * This symbol is ours (again). If {@code rebuilding}, its quotes are processed but nothing is
   * published until {@link #goLive}: the engine is re-reading recent history to rebuild the book.
   */
  public void own(String symbol, boolean rebuilding) {
    notOwned.remove(symbol);

    if (rebuilding) {
      quiet.add(symbol);
    }
  }

  /** The book for this symbol is rebuilt: publish it, and from now on publish every change. */
  public void goLive(String symbol, Instant now) {
    if (quiet.remove(symbol)) {
      publishIfChanged(symbol, now);
    }
  }

  /** Every symbol this service has been told about or has received a quote for. */
  public List<String> symbols() {
    return List.copyOf(symbols);
  }

  private void publishIfChanged(String symbol, Instant now) {
    if (quiet.contains(symbol)) {
      return;
    }

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
