import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MarketDataSimulatorMain {
  public static void main(String[] args) throws InterruptedException {
    try (KafkaQuotePublisher publisher = new KafkaQuotePublisher("localhost:9092")) {
      List<String> symbols = List.of("APPL", "MSFT", "GOOG");

      List<SimulatedExchangeFeedHandler> exchanges =
          List.of(
              new SimulatedExchangeFeedHandler("EX_A", symbols, 300, publisher),
              new SimulatedExchangeFeedHandler("EX_B", symbols, 500, publisher),
              new SimulatedExchangeFeedHandler("EX_C", symbols, 800, publisher));

      ExecutorService executor = Executors.newFixedThreadPool(3);

      try {
        for (SimulatedExchangeFeedHandler exchange : exchanges) {
          exchange.start();
          executor.execute(exchange);
        }

        Thread.sleep(10_000);
      } finally {
        for (SimulatedExchangeFeedHandler exchange : exchanges) {
          exchange.stop();
        }

        executor.shutdownNow();

        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          System.err.println("Feed threads did not stop in time");
        }
      }
    }
  }
}
