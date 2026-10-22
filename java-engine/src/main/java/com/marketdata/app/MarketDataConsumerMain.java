package com.marketdata.app;

import com.marketdata.kafka.KafkaConfig;
import java.util.Map;

public class MarketDataConsumerMain {

  public static void main(String[] args) throws Exception {
    ConsolidationEngine engine =
        new ConsolidationEngine(
            new ConsolidationEngine.Config(
                KafkaConfig.BOOTSTRAP_SERVERS,
                "market-data-engine",
                KafkaConfig.RAW_FEED_TOPIC,
                KafkaConfig.CONSOLIDATED_BOOK_TOPIC,
                "latest",
                Map.of("AAPL", 0, "GOOG", 0, "MSFT", 1),
                2));

    // Shutdown hook (runs on its own thread when you press Ctrl+C)
    Thread mainThread = Thread.currentThread();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  engine.shutdown();

                  // The JVM halts once the hooks return, so wait for the workers to drain.
                  try {
                    mainThread.join();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                },
                "market-data-shutdown"));

    engine.run();
  }
}
