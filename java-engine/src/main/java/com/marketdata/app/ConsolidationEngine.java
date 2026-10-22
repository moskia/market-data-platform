package com.marketdata.app;

import com.marketdata.kafka.KafkaConfig;
import com.marketdata.kafka.consolidation.ConsolidatedQuotePublisher;
import com.marketdata.kafka.consolidation.RawQuoteParser;
import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.concurrency.ConcurrentConflatingQueue;
import com.marketdata.service.concurrency.ConsolidationWorker;
import com.marketdata.service.concurrency.SymbolRouter;
import com.marketdata.service.consolidation.ConsolidationService;
import com.marketdata.service.consolidation.QuoteBook;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;

/**
 * The Kafka thread plus N consolidation workers. {@link #run()} blocks until {@link #shutdown()}
 * is called from another thread, then stops the workers, waits for them to drain, and closes the
 * Kafka clients (shutdown order b, c, d, a).
 */
public class ConsolidationEngine {

  public record Config(
      String bootstrapServers,
      String groupId,
      String inputTopic,
      String outputTopic,
      String offsetReset,
      Map<String, Integer> routingTable,
      int workerCount) {}

  private final Config config;

  // Created here, not in run(), so shutdown() can wake it up even before run() starts polling.
  private final KafkaConsumer<String, String> consumer;

  public ConsolidationEngine(Config config) {
    this.config = config;

    Properties properties =
        KafkaConfig.consumerProperties(config.bootstrapServers(), config.groupId());
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.offsetReset());
    this.consumer = new KafkaConsumer<>(properties);
  }

  /** Safe to call from any thread: wakeup() is the only thread-safe KafkaConsumer method. */
  public void shutdown() {
    consumer.wakeup();
  }

  public void run() throws InterruptedException {
    try (consumer;
        ConsolidatedQuotePublisher publisher =
            new ConsolidatedQuotePublisher(config.bootstrapServers(), config.outputTopic())) {

      RawQuoteParser parser = new RawQuoteParser();

      // 1. Routing
      Map<String, Integer> table = config.routingTable();
      SymbolRouter router = new SymbolRouter(table, config.workerCount());

      // 2. Workers: one ConsolidationService (own QuoteBook) per worker, shared publisher
      List<ConsolidationWorker> workers = new ArrayList<>();
      List<Thread> workerThreads = new ArrayList<>();

      try {
        for (int i = 0; i < config.workerCount(); i++) {
          final int workerIndex = i;
          List<String> symbols =
              table.entrySet().stream()
                  .filter(entry -> entry.getValue().equals(workerIndex))
                  .map(Map.Entry::getKey)
                  .toList();

          ConsolidationService service =
              new ConsolidationService(new QuoteBook(), symbols, publisher::publish);

          ConsolidationWorker worker =
              new ConsolidationWorker(new ConcurrentConflatingQueue(), service);

          Thread thread = new Thread(worker, "worker-" + i);

          workers.add(worker);
          workerThreads.add(thread);

          thread.start();
        }

        consumer.subscribe(List.of(config.inputTopic()));

        System.out.println("Waiting for market data...");

        while (true) {
          ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

          for (ConsumerRecord<String, String> record : records) {
            try {
              RawQuoteEvent quote = parser.parse(record.value());

              int workerIndex = router.workerFor(quote.symbol());

              workers.get(workerIndex).submit(quote);

            } catch (JsonProcessingException | IllegalArgumentException e) {
              System.err.printf(
                  "Rejected event at partition=%d offset=%d: %s%n",
                  record.partition(), record.offset(), e.getMessage());
            }
          }
        }
      } catch (WakeupException e) {
        // Step b: expected on shutdown, poll() was woken up by shutdown()
        System.out.println("Shutting down...");
      } finally {
        // Step c: stop every worker
        for (ConsolidationWorker worker : workers) {
          worker.stop();
        }

        // Step d: wait for every worker to drain
        for (Thread thread : workerThreads) {
          thread.join();
        }
      }
    } // Step a: try-with-resources closes the publisher and the consumer here
  }
}
