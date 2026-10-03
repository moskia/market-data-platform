import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class QuoteBook {

  private record QuoteKey(String exchangeId, String symbol, String side) {}

  private final Map<QuoteKey, RawQuoteEvent> latestQuotes = new HashMap<>();

  private final Duration quoteLifetime;

  public QuoteBook() {
    this(Duration.ofSeconds(5));
  }

  public QuoteBook(Duration quoteLifetime) {
    if (quoteLifetime.isZero() || quoteLifetime.isNegative()) {
      throw new IllegalArgumentException("Quote lifetime must be positive");
    }

    this.quoteLifetime = quoteLifetime;
  }

  public boolean update(RawQuoteEvent quote) {
    QuoteKey key = new QuoteKey(quote.exchangeId(), quote.symbol(), quote.side());

    RawQuoteEvent previous = latestQuotes.get(key);

    if (previous != null && quote.sequenceNumber() <= previous.sequenceNumber()) {
      return false;
    }

    latestQuotes.put(key, quote);
    return true;
  }

  public int size() {
    return latestQuotes.size();
  }

  private boolean isFresh(RawQuoteEvent quote, Instant now) {
    Instant expiresAt = quote.receivedTimestamp().plus(quoteLifetime);

    return now.isBefore(expiresAt);
  }

  public Optional<RawQuoteEvent> bestBid(String symbol) {
    return bestBid(symbol, Instant.now());
  }

  public Optional<RawQuoteEvent> bestBid(String symbol, Instant now) {
    return latestQuotes.values().stream()
        .filter(quote -> quote.symbol().equals(symbol))
        .filter(quote -> quote.side().equals("BID"))
        .filter(quote -> isFresh(quote, now))
        .max(
            Comparator.comparingDouble(RawQuoteEvent::price)
                .thenComparing(RawQuoteEvent::exchangeId));
  }

  public Optional<RawQuoteEvent> bestAsk(String symbol) {
    return bestAsk(symbol, Instant.now());
  }

  public Optional<RawQuoteEvent> bestAsk(String symbol, Instant now) {
    return latestQuotes.values().stream()
        .filter(quote -> quote.symbol().equals(symbol))
        .filter(quote -> quote.side().equals("ASK"))
        .filter(quote -> isFresh(quote, now))
        .min(
            Comparator.comparingDouble(RawQuoteEvent::price)
                .thenComparing(RawQuoteEvent::exchangeId));
  }
}
