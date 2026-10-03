import java.time.Instant;
import java.util.Objects;

public record ConsolidatedQuote(
    String symbol,
    Double bidPrice,
    Integer bidQuantity,
    String bidExchangeId,
    Double askPrice,
    Integer askQuantity,
    String askExchangeId,
    Double spread,
    Instant calculatedTimestamp) {
  public boolean sameMarketState(ConsolidatedQuote other) {
    return other != null
        && Objects.equals(symbol, other.symbol())
        && Objects.equals(bidPrice, other.bidPrice())
        && Objects.equals(bidQuantity, other.bidQuantity())
        && Objects.equals(bidExchangeId, other.bidExchangeId())
        && Objects.equals(askPrice, other.askPrice())
        && Objects.equals(askQuantity, other.askQuantity())
        && Objects.equals(askExchangeId, other.askExchangeId());
  }
}
