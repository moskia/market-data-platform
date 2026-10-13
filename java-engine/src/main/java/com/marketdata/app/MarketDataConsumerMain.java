package com.marketdata.app;

import com.marketdata.kafka.KafkaConfig;
import com.marketdata.kafka.consolidation.ConsolidatedQuotePublisher;
import com.marketdata.kafka.consolidation.RawQuoteParser;
import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.ConsolidationService;
import com.marketdata.service.consolidation.QuoteBook;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

public class MarketDataConsumerMain {

  public static void main(String[] args) throws Exception {
    Properties properties =
        KafkaConfig.consumerProperties(KafkaConfig.BOOTSTRAP_SERVERS, "market-data-engine");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        ConsolidatedQuotePublisher publisher =
            new ConsolidatedQuotePublisher(KafkaConfig.BOOTSTRAP_SERVERS)) {

      RawQuoteParser parser = new RawQuoteParser();
      ConsolidationService service =
          new ConsolidationService(
              new QuoteBook(), List.of("AAPL", "MSFT", "GOOG"), publisher::publish);

      consumer.subscribe(List.of("raw-feed-events"));

      System.out.println("Waiting for market data...");

      long count = 0;

      while (true) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

        for (ConsumerRecord<String, String> record : records) {

          RawQuoteEvent quote;
          boolean updated;

          try {
            quote = parser.parse(record.value());
            updated = service.onQuote(quote, Instant.now());
          } catch (JsonProcessingException | IllegalArgumentException error) {
            System.err.printf(
                "Rejected event at partition=%d offset=%d: %s%n",
                record.partition(), record.offset(), error.getMessage());

            continue;
          }

          count++;

          if (count % 20 == 0) {
            printSummary(service);
          }

          if (!updated) {
            System.out.printf(
                "Ignored stale quote: %s / %s / %s / seq=%d%n",
                quote.exchangeId(), quote.symbol(), quote.side(), quote.sequenceNumber());
          }
        }

        service.checkExpiry(Instant.now());
      }
    }
  }

  private static void printSummary(ConsolidationService service) {
    Instant now = Instant.now();

    for (String symbol : service.symbols()) {
      ConsolidatedQuote quote = service.snapshot(symbol, now);

      if (quote.bidPrice() != null && quote.askPrice() != null) {
        System.out.printf(
            "%s | BID %.2f (%s, qty=%d) " + "| ASK %.2f (%s, qty=%d) " + "| spread=%.2f%n",
            symbol,
            quote.bidPrice(),
            quote.bidExchangeId(),
            quote.bidQuantity(),
            quote.askPrice(),
            quote.askExchangeId(),
            quote.askQuantity(),
            quote.spread());
      } else {
        System.out.printf("%s | Waiting for both bid and ask%n", symbol);
      }
    }

    System.out.println();
  }
}
