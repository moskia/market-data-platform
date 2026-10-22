package com.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end: a real Kafka broker (started by Testcontainers) -> ConsolidationEngine (Kafka thread
 * + 2 workers) -> consolidated topic. Each test gets its own topics so tests can't see each
 * other's messages.
 */
@Testcontainers
class ConsolidationEngineIT {

  // Same image as docker-compose.yml. Started once for the whole class.
  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0");

  private static final Duration WAIT = Duration.ofSeconds(30);

  private final ObjectMapper mapper = JsonMapper.create();

  private String rawTopic;
  private String bookTopic;
  private ConsolidationEngine engine;
  private Thread engineThread;
  private final AtomicReference<Throwable> engineFailure = new AtomicReference<>();
  private KafkaConsumer<String, String> bookReader;
  private final List<ConsolidatedQuote> published = new ArrayList<>();

  @BeforeEach
  void startEngine() throws Exception {
    String id = UUID.randomUUID().toString().substring(0, 8);
    rawTopic = "raw-" + id;
    bookTopic = "book-" + id;

    try (AdminClient admin =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(new NewTopic(rawTopic, 3, (short) 1), new NewTopic(bookTopic, 3, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }

    // AAPL and GOOG on worker 0, MSFT on worker 1, like production.
    // "earliest" because the topics are brand new: nothing is missed if we send before it joins.
    engine =
        new ConsolidationEngine(
            new ConsolidationEngine.Config(
                KAFKA.getBootstrapServers(),
                "engine-" + id,
                rawTopic,
                bookTopic,
                "earliest",
                Map.of("AAPL", 0, "GOOG", 0, "MSFT", 1),
                2));

    engineThread =
        new Thread(
            () -> {
              try {
                engine.run();
              } catch (Throwable error) {
                engineFailure.set(error);
              }
            },
            "engine-under-test");
    engineThread.start();

    Properties properties =
        KafkaConfig.consumerProperties(KAFKA.getBootstrapServers(), "reader-" + id);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    bookReader = new KafkaConsumer<>(properties);
    bookReader.subscribe(List.of(bookTopic));
  }

  @AfterEach
  void stopEngine() throws InterruptedException {
    engine.shutdown();
    engineThread.join(TimeUnit.SECONDS.toMillis(15));
    bookReader.close();
  }

  @Test
  void consolidatesBestBidAndAskAcrossExchanges() throws Exception {
    try (KafkaQuotePublisher exchanges =
        new KafkaQuotePublisher(KAFKA.getBootstrapServers(), rawTopic)) {
      // AAPL (worker 0): best bid is EX_B 100.10, best ask is EX_C 100.20
      exchanges.publish(quote("EX_A", "AAPL", "BID", 100.00, 1));
      exchanges.publish(quote("EX_B", "AAPL", "BID", 100.10, 1));
      exchanges.publish(quote("EX_A", "AAPL", "ASK", 100.30, 2));
      exchanges.publish(quote("EX_C", "AAPL", "ASK", 100.20, 1));

      // MSFT (worker 1)
      exchanges.publish(quote("EX_A", "MSFT", "BID", 50.00, 3));
      exchanges.publish(quote("EX_B", "MSFT", "ASK", 50.05, 2));
    }

    ConsolidatedQuote aapl =
        awaitPublished(
            q ->
                q.symbol().equals("AAPL")
                    && Double.valueOf(100.10).equals(q.bidPrice())
                    && Double.valueOf(100.20).equals(q.askPrice()));
    assertEquals("EX_B", aapl.bidExchangeId());
    assertEquals("EX_C", aapl.askExchangeId());

    ConsolidatedQuote msft =
        awaitPublished(
            q ->
                q.symbol().equals("MSFT")
                    && Double.valueOf(50.00).equals(q.bidPrice())
                    && Double.valueOf(50.05).equals(q.askPrice()));
    assertEquals("EX_A", msft.bidExchangeId());
    assertEquals("EX_B", msft.askExchangeId());
  }

  /**
   * Bad events (not JSON, no symbol, negative price) are logged and skipped. They share the key
   * "GOOG" with the valid quote, so they land on the same partition and are read BEFORE it: if any
   * of them killed the engine, the valid quote would never be published.
   */
  @Test
  void badEventsAreSkippedAndTheEngineKeepsRunning() throws Exception {
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(KafkaConfig.producerProperties(KAFKA.getBootstrapServers()))) {
      send(producer, "this is not json");
      send(producer, "{\"exchangeId\":\"EX_A\",\"side\":\"BID\",\"price\":100.0}");
      send(producer, mapper.writeValueAsString(quote("EX_A", "GOOG", "BID", -5.0, 1)));
      send(producer, mapper.writeValueAsString(quote("EX_B", "GOOG", "BID", 99.00, 1)));
    }

    awaitPublished(q -> q.symbol().equals("GOOG") && Double.valueOf(99.00).equals(q.bidPrice()));

    assertTrue(engineThread.isAlive(), "The engine stopped after a bad event");
    assertTrue(
        published.stream().noneMatch(q -> Double.valueOf(-5.0).equals(q.bidPrice())),
        "A negative price reached the consolidated book");
  }

  /** shutdown() must make run() return, with every worker thread stopped and no exception. */
  @Test
  void shutdownStopsTheEngineAndAllWorkers() throws Exception {
    try (KafkaQuotePublisher exchanges =
        new KafkaQuotePublisher(KAFKA.getBootstrapServers(), rawTopic)) {
      exchanges.publish(quote("EX_A", "AAPL", "BID", 100.00, 1));
    }
    awaitPublished(q -> q.symbol().equals("AAPL")); // engine is up and processing

    engine.shutdown();
    engineThread.join(TimeUnit.SECONDS.toMillis(15));

    assertFalse(engineThread.isAlive(), "run() did not return after shutdown()");
    assertNull(engineFailure.get(), () -> "run() threw: " + engineFailure.get());
    assertTrue(
        Thread.getAllStackTraces().keySet().stream()
            .noneMatch(t -> t.getName().startsWith("worker-") && t.isAlive()),
        "A worker thread is still running after shutdown");
  }

  /** Reads the consolidated topic until a quote matches, or fails after WAIT. */
  private ConsolidatedQuote awaitPublished(Predicate<ConsolidatedQuote> match) throws Exception {
    long deadline = System.nanoTime() + WAIT.toNanos();

    while (System.nanoTime() < deadline) {
      for (ConsolidatedQuote quote : published) {
        if (match.test(quote)) {
          return quote;
        }
      }

      assertNull(engineFailure.get(), () -> "Engine crashed: " + engineFailure.get());

      for (ConsumerRecord<String, String> record : bookReader.poll(Duration.ofMillis(200))) {
        published.add(mapper.readValue(record.value(), ConsolidatedQuote.class));
      }
    }

    throw new AssertionError("Expected quote never published. Got: " + published);
  }

  private void send(KafkaProducer<String, String> producer, String value) throws Exception {
    producer.send(new ProducerRecord<>(rawTopic, "GOOG", value)).get(10, TimeUnit.SECONDS);
  }

  private static RawQuoteEvent quote(
      String exchange, String symbol, String side, double price, long sequence) {
    Instant now = Instant.now();
    return new RawQuoteEvent(exchange, symbol, side, price, 50, sequence, now, now);
  }
}
