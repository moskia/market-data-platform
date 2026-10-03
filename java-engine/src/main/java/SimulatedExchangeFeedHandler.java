import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class SimulatedExchangeFeedHandler implements FeedHandler, Runnable {
  private final String exchangeId;
  private final List<String> symbols;
  private volatile boolean running;
  private final Random random = new Random();
  private long sequenceNumber = 0;
  private final Map<String, Double> prices = new HashMap<>();
  private final long meanIntervalMs;
  private final KafkaQuotePublisher publisher;
  private static final double DROP_PROBABILITY = 0.01;
  private static final double REORDER_PROBABILITY = 0.02;

  private record PendingQuote(RawQuoteEvent quote, long deliveryTimeNanos) {}

  private final PriorityQueue<PendingQuote> pendingQuotes =
      new PriorityQueue<>(Comparator.comparingLong(PendingQuote::deliveryTimeNanos));

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
    long nextGenerationTime = System.nanoTime();

    try {
      while (running || !pendingQuotes.isEmpty()) {
        long now = System.nanoTime();

        if (running && nextGenerationTime - now <= 0) {
          scheduleQuote();
          nextGenerationTime = System.nanoTime() + nextArrivalDelayNanos();
        }

        publishReadyQuotes();

        if (!running && pendingQuotes.isEmpty()) {
          break;
        }

        long nextWakeTime = running ? nextGenerationTime : pendingQuotes.peek().deliveryTimeNanos();

        if (!pendingQuotes.isEmpty()) {
          nextWakeTime = Math.min(nextWakeTime, pendingQuotes.peek().deliveryTimeNanos());
        }

        long waitNanos = nextWakeTime - System.nanoTime();

        if (waitNanos > 0) {
          TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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

  private long nextArrivalDelayNanos() {
    double delayMs = -meanIntervalMs * Math.log(1.0 - random.nextDouble());
    return Math.max(1L, (long) (delayMs * 1_000_000));
  }

  private void scheduleQuote() {
    RawQuoteEvent quote = generateQuote();

    if (random.nextDouble() < DROP_PROBABILITY) {
      System.out.println("Dropped " + exchangeId + " #" + quote.sequenceNumber());
      return;
    }

    long latencyMs = 1 + random.nextInt(50);

    if (random.nextDouble() < REORDER_PROBABILITY) {
      latencyMs += 3 * meanIntervalMs;

      System.out.println(
          "Delayed " + exchangeId + " #" + quote.sequenceNumber() + " by " + latencyMs + " ms");
    }

    long deliveryTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(latencyMs);

    pendingQuotes.add(new PendingQuote(quote, deliveryTime));
  }

  private void publishReadyQuotes() {
    while (!pendingQuotes.isEmpty()
        && pendingQuotes.peek().deliveryTimeNanos() - System.nanoTime() <= 0) {
      RawQuoteEvent quote = pendingQuotes.poll().quote();
      publisher.publish(markReceived(quote));
    }
  }
}
