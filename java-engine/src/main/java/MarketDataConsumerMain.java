import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

public class MarketDataConsumerMain {

  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();

    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "market-data-engine");
    properties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        ConsolidatedQuotePublisher publisher = new ConsolidatedQuotePublisher("localhost:9092")) {

      consumer.subscribe(List.of("raw-feed-events"));

      System.out.println("Waiting for market data...");

      QuoteBook book = new QuoteBook();
      Map<String, ConsolidatedQuote> lastSubmitted = new HashMap<>();

      long count = 0;

      while (true) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

        for (ConsumerRecord<String, String> record : records) {
          RawQuoteEvent quote = mapper.readValue(record.value(), RawQuoteEvent.class);

          boolean updated = book.update(quote);

          if (updated) {
            publishCurrentState(book, publisher, lastSubmitted, quote.symbol(), Instant.now());
          }

          count++;

          if (count % 20 == 0) {
            for (String symbol : List.of("AAPL", "MSFT", "GOOG")) {
              Optional<RawQuoteEvent> bid = book.bestBid(symbol);
              Optional<RawQuoteEvent> ask = book.bestAsk(symbol);

              if (bid.isPresent() && ask.isPresent()) {
                RawQuoteEvent bestBid = bid.get();
                RawQuoteEvent bestAsk = ask.get();

                double spread = bestAsk.price() - bestBid.price();

                System.out.printf(
                    "%s | BID %.2f (%s, qty=%d) " + "| ASK %.2f (%s, qty=%d) " + "| spread=%.2f%n",
                    symbol,
                    bestBid.price(),
                    bestBid.exchangeId(),
                    bestBid.quantity(),
                    bestAsk.price(),
                    bestAsk.exchangeId(),
                    bestAsk.quantity(),
                    spread);
              } else {
                System.out.printf("%s | Waiting for both bid and ask%n", symbol);
              }
            }

            System.out.println();
          }

          if (!updated) {
            System.out.printf(
                "Ignored stale quote: %s / %s / %s / seq=%d%n",
                quote.exchangeId(), quote.symbol(), quote.side(), quote.sequenceNumber());
          }
        }
        Instant now = Instant.now();

        for (String symbol : List.of("AAPL", "MSFT", "GOOG")) {
          publishCurrentState(book, publisher, lastSubmitted, symbol, now);
        }
      }
    }
  }

  private static void publishCurrentState(
      QuoteBook book,
      ConsolidatedQuotePublisher publisher,
      Map<String, ConsolidatedQuote> lastSubmitted,
      String symbol,
      Instant now) {

    RawQuoteEvent bid = book.bestBid(symbol, now).orElse(null);

    RawQuoteEvent ask = book.bestAsk(symbol, now).orElse(null);

    // Avoid publishing empty state before this symbol has any result.
    if (bid == null && ask == null && !lastSubmitted.containsKey(symbol)) {
      return;
    }

    Double spread = null;

    if (bid != null && ask != null) {
      spread = ask.price() - bid.price();
    }

    ConsolidatedQuote current =
        new ConsolidatedQuote(
            symbol,
            bid == null ? null : bid.price(),
            bid == null ? null : bid.quantity(),
            bid == null ? null : bid.exchangeId(),
            ask == null ? null : ask.price(),
            ask == null ? null : ask.quantity(),
            ask == null ? null : ask.exchangeId(),
            spread,
            now);

    ConsolidatedQuote previous = lastSubmitted.get(symbol);

    if (!current.sameMarketState(previous)) {
      publisher.publish(current);
      lastSubmitted.put(symbol, current);
    }
  }
}
