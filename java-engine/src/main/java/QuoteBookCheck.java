import java.time.Duration;
import java.time.Instant;

public class QuoteBookCheck {

  public static void main(String[] args) {
    QuoteBook book = new QuoteBook();

    book.update(quote("EX_A", "BID", 100.05, 10));
    book.update(quote("EX_B", "BID", 100.08, 10));

    book.update(quote("EX_A", "ASK", 100.15, 11));
    book.update(quote("EX_B", "ASK", 100.12, 11));

    check(book.bestBid("AAPL").orElseThrow().exchangeId().equals("EX_B"), "Highest bid wins");

    check(book.bestAsk("AAPL").orElseThrow().exchangeId().equals("EX_B"), "Lowest ask wins");

    boolean accepted = book.update(quote("EX_B", "BID", 999.00, 9));

    check(!accepted, "Older quote is rejected");

    check(
        book.bestBid("AAPL").orElseThrow().price() == 100.08,
        "Rejected quote does not change the book");

    book.update(quote("EX_B", "BID", 100.01, 12));

    check(
        book.bestBid("AAPL").orElseThrow().exchangeId().equals("EX_A"),
        "Best exchange changes when its competitor worsens");

    check(book.size() == 4, "Updates replace existing entries");

    ConsolidatedQuote first = consolidated(Instant.parse("2026-01-01T00:00:00Z"), 50);

    ConsolidatedQuote later = consolidated(Instant.parse("2026-01-01T00:00:01Z"), 50);

    check(first.sameMarketState(later), "Timestamp alone does not change market state");

    check(
        !first.sameMarketState(consolidated(Instant.now(), 60)),
        "Quantity change changes market state");

    check(!first.sameMarketState(null), "First result must be published");

    QuoteBook expiryBook = new QuoteBook(Duration.ofSeconds(5));

    Instant received = Instant.parse("2026-01-01T00:00:00Z");

    expiryBook.update(new RawQuoteEvent("EX_A", "AAPL", "BID", 100.05, 50, 1, received, received));

    check(
        expiryBook.bestBid("AAPL", received.plusSeconds(4)).isPresent(),
        "Quote is available before expiry");

    check(
        expiryBook.bestBid("AAPL", received.plusSeconds(5)).isEmpty(),
        "Quote is unavailable at expiry");

    System.out.println("All checks passed.");
  }

  private static RawQuoteEvent quote(String exchange, String side, double price, long sequence) {
    Instant now = Instant.now();

    return new RawQuoteEvent(exchange, "AAPL", side, price, 50, sequence, now, now);
  }

  private static ConsolidatedQuote consolidated(Instant timestamp, int bidQuantity) {
    return new ConsolidatedQuote(
        "AAPL", 100.05, bidQuantity, "EX_A", 100.12, 50, "EX_B", 100.12 - 100.05, timestamp);
  }

  private static void check(boolean condition, String description) {
    if (!condition) {
      throw new IllegalStateException("FAIL: " + description);
    }

    System.out.println("PASS: " + description);
  }
}
