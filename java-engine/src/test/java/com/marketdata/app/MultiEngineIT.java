package com.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marketdata.kafka.JsonMapper;
import com.marketdata.kafka.KafkaConfig;
import com.marketdata.kafka.simulator.KafkaQuotePublisher;
import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.model.RawQuoteEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Two engines in one consumer group, while fresh quotes for both sides of every symbol keep
 * arriving. A correct engine therefore never publishes a book with a side missing: any half-empty
 * or empty book means a partition move went wrong (the problems seen in the live experiment).
 */
@Testcontainers
class MultiEngineIT {

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0");

  // With 3 partitions, Kafka's hash puts these in partitions 0, 1 and 2.
  private static final List<String> SYMBOLS = List.of("AAPL", "MA", "AMZN");

  private final ObjectMapper mapper = JsonMapper.create();
  private final List<Running> engines = new ArrayList<>();
  private String rawTopic;
  private String bookTopic;
  private String group;
  private Thread feeder;
  private KafkaConsumer<String, String> bookReader;

  private record Running(ConsolidationEngine engine, Thread thread) {
    void stop() throws InterruptedException {
      engine.shutdown();
      thread.join(TimeUnit.SECONDS.toMillis(20));
    }
  }

  private record Published(long atMs, ConsolidatedQuote quote) {}

  @BeforeEach
  void setUp() throws Exception {
    String id = UUID.randomUUID().toString().substring(0, 8);
    rawTopic = "raw-" + id;
    bookTopic = "book-" + id;
    group = "engines-" + id;

    try (AdminClient admin =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(new NewTopic(rawTopic, 3, (short) 1), new NewTopic(bookTopic, 3, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }

    feeder = new Thread(this::feedQuotes, "feeder");
    feeder.start();

    Properties properties = KafkaConfig.consumerProperties(KAFKA.getBootstrapServers(), "reader-" + id);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    bookReader = new KafkaConsumer<>(properties);
    bookReader.subscribe(List.of(bookTopic));
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    feeder.interrupt();
    feeder.join();
    for (Running running : engines) {
      running.stop();
    }
    bookReader.close();
  }

  @Test
  void enginesJoiningAndLeavingNeverPublishIncompleteBooks() throws Exception {
    List<Published> published = new ArrayList<>();

    startEngine();
    readFor(Duration.ofSeconds(10), published);
    assertEquals(
        Set.copyOf(SYMBOLS),
        published.stream().map(p -> p.quote().symbol()).collect(Collectors.toSet()),
        "The first engine should publish every symbol");

    // A second engine joins: some partitions move to it, and the first must forget them.
    long joinedAt = System.currentTimeMillis();
    startEngine();
    readFor(Duration.ofSeconds(15), published);

    // The second engine stops cleanly: its partitions move back to the first.
    long leftAt = System.currentTimeMillis();
    engines.get(1).stop();
    readFor(Duration.ofSeconds(15), published);

    List<Published> afterJoin = published.stream().filter(p -> p.atMs() >= joinedAt).toList();
    List<Published> incomplete =
        afterJoin.stream()
            .filter(p -> p.quote().bidPrice() == null || p.quote().askPrice() == null)
            .toList();

    assertTrue(incomplete.isEmpty(), "Incomplete books published: " + incomplete);
    for (String symbol : SYMBOLS) {
      assertTrue(
          published.stream().anyMatch(p -> p.atMs() > leftAt + 8_000 && p.quote().symbol().equals(symbol)),
          symbol + " stopped being published after the second engine left");
    }
  }

  private void startEngine() {
    ConsolidationEngine engine =
        new ConsolidationEngine(
            new ConsolidationEngine.Config(
                KAFKA.getBootstrapServers(),
                group,
                rawTopic,
                bookTopic,
                "latest",
                Map.of(),
                2));
    Thread thread = new Thread(() -> {
      try {
        engine.run();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "engine-" + engines.size());
    thread.start();
    engines.add(new Running(engine, thread));
  }

  /** Both sides of every symbol, from two exchanges, every 100 ms: the books are never empty. */
  private void feedQuotes() {
    try (KafkaQuotePublisher exchanges =
        new KafkaQuotePublisher(KAFKA.getBootstrapServers(), rawTopic)) {
      long sequence = 1;
      boolean up = false;

      while (!Thread.currentThread().isInterrupted()) {
        Instant now = Instant.now();
        up = !up; // the price moves every round, so the engines keep publishing
        double bid = up ? 100.01 : 100.00;

        for (String symbol : SYMBOLS) {
          for (String exchange : List.of("EX_A", "EX_B")) {
            exchanges.publish(
                new RawQuoteEvent(exchange, symbol, "BID", bid, 50, 1, sequence++, now, now));
            exchanges.publish(
                new RawQuoteEvent(exchange, symbol, "ASK", bid + 0.05, 50, 1, sequence++, now, now));
          }
        }

        Thread.sleep(100);
      }
    } catch (InterruptedException e) {
      // the test is over
    }
  }

  private void readFor(Duration duration, List<Published> into) throws Exception {
    long end = System.nanoTime() + duration.toNanos();

    while (System.nanoTime() < end) {
      for (ConsumerRecord<String, String> record : bookReader.poll(Duration.ofMillis(200))) {
        into.add(new Published(record.timestamp(), mapper.readValue(record.value(), ConsolidatedQuote.class)));
      }
    }
  }
}
