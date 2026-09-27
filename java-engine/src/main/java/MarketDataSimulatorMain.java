import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MarketDataSimulatorMain {
    public static void main(String[] args) throws InterruptedException {
        SimulatedExchangeFeedHandler exA =
            new SimulatedExchangeFeedHandler("EX_A", List.of("AAPL", "MSFT", "GOOG"), 300);

        SimulatedExchangeFeedHandler exB =
            new SimulatedExchangeFeedHandler("EX_B", List.of("AAPL", "MSFT", "GOOG"), 500);

        SimulatedExchangeFeedHandler exC = 
            new SimulatedExchangeFeedHandler("EX_C", List.of("AAPL", "MSFT", "GOOG"), 800);

        List<SimulatedExchangeFeedHandler> exchanges = List.of(exA, exB, exC);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        for (SimulatedExchangeFeedHandler exchange: exchanges) {
            exchange.start();
            executor.submit(exchange);
        }

        Thread.sleep(5_000);


        for (SimulatedExchangeFeedHandler exchange: exchanges) {
            exchange.stop();
        }
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
