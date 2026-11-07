package com.marketdata.app;

import com.marketdata.kafka.KafkaConfig;
import com.marketdata.kafka.simulator.KafkaQuotePublisher;
import com.marketdata.service.simulator.MarketSimulation;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import com.marketdata.service.simulator.scheduler.RealTimePacer;

/**
 * Runs the simulated market in real time and publishes every price message to Kafka.
 *
 * <p>Arguments (both optional): how many seconds to run (default 10), and the seed (default:
 * random, printed so a run can be repeated).
 */
public class MarketDataSimulatorMain {
  private static final long DEFAULT_RUN_SECONDS = 10;

  public static void main(String[] args) throws InterruptedException {
    long runSeconds = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_RUN_SECONDS;
    long seed = args.length > 1 ? Long.parseLong(args[1]) : System.nanoTime();

    // Ctrl+C interrupts the main thread: the scheduler stops waiting and the run ends early.
    Thread mainThread = Thread.currentThread();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  mainThread.interrupt();

                  try {
                    mainThread.join();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                },
                "simulator-shutdown"));

    try (KafkaQuotePublisher publisher = new KafkaQuotePublisher(KafkaConfig.BOOTSTRAP_SERVERS)) {
      long[] published = {0};
      EventScheduler scheduler = new EventScheduler(new RealTimePacer());
      MarketSimulation market =
          new MarketSimulation(
              scheduler,
              MarketSimulation.Settings.standard(seed),
              quote -> {
                publisher.publish(quote);
                published[0]++;
              });

      System.out.printf("Simulating the market for %d s (seed %d)...%n", runSeconds, seed);
      scheduler.runUntil(runSeconds * 1_000);

      // Clear the interrupt from Ctrl+C, or the Kafka producer can't finish sending on close().
      boolean stoppedEarly = Thread.interrupted();

      System.out.printf(
          "%s after %.1f s: published %d messages, dropped %d, delivered %d very late.%n",
          stoppedEarly ? "Stopped" : "Finished",
          scheduler.now() / 1_000.0,
          published[0],
          market.dropped(),
          market.late());
    }
  }
}
