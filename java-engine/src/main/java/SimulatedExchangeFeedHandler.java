import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class SimulatedExchangeFeedHandler implements FeedHandler, Runnable {
  private final String exchangeId;
  private final List<String> symbols;
  private volatile boolean running;
  private final Random random = new Random();
  private long sequenceNumber = 0;
  private final Map<String, Double> prices = new HashMap<>();
  private final long meanIntervalMs;
  private final KafkaQuotePublisher publisher;
  private static final double DROP_PROBABILITY = 0.2;

  public SimulatedExchangeFeedHandler(
      String exchangeId, List<String> symbols, long meanIntervalMs, KafkaQuotePublisher publisher) {
    if (meanIntervalMs <= 0) {
      throw new IllegalArgumentException("meanIntervalMs must be positive");
    }

    this.exchangeId = exchangeId;
    this.symbols = symbols;
    this.meanIntervalMs = meanIntervalMs;
    this.publisher = publisher;

    for (String symbol : symbols) {
      prices.put(symbol, 100.0);
    }
  }

  @Override
  public void start() {
    running = true;
    System.out.println("Started " + exchangeId + " with symbols " + symbols);
  }

  @Override
  public void stop() {
    running = false;
    System.out.println("Stopped " + exchangeId);
  }

  @Override
  public void run() {
    while (running & !Thread.currentThread().isInterrupted()) {
      try {
        RawQuoteEvent quote = generateQuote();

        if (random.nextDouble() < DROP_PROBABILITY) {
          System.out.println("Dropped " + quote.exchangeId() + " #" + quote.sequenceNumber());
        } else {
          int latencyMs = 1 + random.nextInt(50);
          Thread.sleep(latencyMs);

          publisher.publish(markReceived(quote));
        }

        long delaysMs = Math.max(1, (long) (-meanIntervalMs * Math.log(1.0 - random.nextDouble())));
        Thread.sleep(delaysMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public RawQuoteEvent generateQuote() {
    String symbol = symbols.get(random.nextInt(symbols.size()));
    String side = random.nextBoolean() ? "BID" : "ASK";

    double price = prices.get(symbol);
    price += (random.nextDouble() - 0.5) * 0.5;
    price = Math.round(price * 100.0) / 100.0;
    prices.put(symbol, price);

    int quantity = 1 + random.nextInt(100);
    Instant now = Instant.now();

    return new RawQuoteEvent(exchangeId, symbol, side, price, quantity, ++sequenceNumber, now, now);
  }

  private RawQuoteEvent markReceived(RawQuoteEvent quote) {
    return new RawQuoteEvent(
        quote.exchangeId(),
        quote.symbol(),
        quote.side(),
        quote.price(),
        quote.quantity(),
        quote.sequenceNumber(),
        quote.exchangeTimestamp(),
        Instant.now());
  }
}
