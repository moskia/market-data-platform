package com.marketdata.service.concurrency;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.ConsolidationService;
import com.marketdata.model.ConsolidatedQuote;
import com.marketdata.service.metrics.LatencyStats;

public class ConsolidationWorker implements Runnable {
    private final ConflatingQueue conflatingQueue;
    private final ConsolidationService consolidationService;
    private volatile boolean running = true;

    // Instructions from the Kafka thread (e.g. "forget MSFT"), run on this worker's thread so the
    // book keeps a single writer. Always run before the next quote.
    private final Queue<Consumer<ConsolidationService>> commands = new ConcurrentLinkedQueue<>();

    // Written only by this worker's thread; read by others only after the thread has finished.
    private final LatencyStats latency = new LatencyStats();

    public ConsolidationWorker(ConflatingQueue conflatingQueue, ConsolidationService consolidationService) {
        this.conflatingQueue = conflatingQueue;
        this.consolidationService = consolidationService;
    }

    /**
     * Runs {@code command} on this worker's thread, before any quote submitted after this call.
     * It may also run before older quotes that are still waiting: all waiting commands run before
     * the next quote is taken.
     */
    public void command(Consumer<ConsolidationService> command) {
        commands.offer(command);
    }

    public void submit(RawQuoteEvent rawQuoteEvent) {
        this.conflatingQueue.offer(rawQuoteEvent);
    }

    /** Safe to read once this worker's thread has finished (after join()). */
    public LatencyStats latency() {
        return latency;
    }

    public void stop() {
        this.running = false;
    }

    @Override
    public void run() {
        long lastExpiryCheck = System.nanoTime();
        long expiryInterval = TimeUnit.MILLISECONDS.toNanos(500);

        boolean wasInterrupted = false;

        try {
            // Main processing loop
            while (running) {
                RawQuoteEvent rawQuoteEvent =
                        conflatingQueue.poll(500, TimeUnit.MILLISECONDS);

                runCommands();

                if (rawQuoteEvent != null) {
                    processQuote(rawQuoteEvent);
                }

                // Check expiry every ~500 ms
                long now = System.nanoTime();

                if (now - lastExpiryCheck >= expiryInterval) {
                    consolidationService.checkExpiry(Instant.now());
                    lastExpiryCheck = now;
                }
            }

        } catch (InterruptedException e) {
            wasInterrupted = true;

        } finally {
            // Drain remaining quotes before exiting
            while (true) {
                try {
                    RawQuoteEvent rawQuoteEvent =
                            conflatingQueue.poll(0, TimeUnit.MILLISECONDS);

                    runCommands();

                    if (rawQuoteEvent == null) {
                        break;
                    }

                    processQuote(rawQuoteEvent);

                } catch (InterruptedException e) {
                    // Remember the interruption but keep draining
                    wasInterrupted = true;
                }
            }

            // Restore interrupt status after   
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final long SUMMARY_INTERVAL = TimeUnit.SECONDS.toNanos(5);

    // Only this worker's thread touches these.
    private long processedSinceSummary = 0;
    private long staleSinceSummary = 0;
    private long lastSummary = System.nanoTime();

    private void runCommands() {
        Consumer<ConsolidationService> command;

        while ((command = commands.poll()) != null) {
            command.accept(consolidationService);
        }
    }

    private void processQuote(RawQuoteEvent rawQuoteEvent) {
        try {
            Instant now = Instant.now();
            boolean updated =
                    consolidationService.onQuote(rawQuoteEvent, now);
            latency.record(rawQuoteEvent, Instant.now());

            processedSinceSummary++;

            if (!updated) {
                staleSinceSummary++; // out-of-order arrival: too frequent to print one by one
            }

            // At most one summary every 5 s: with many symbols, one per N quotes floods the log.
            if (System.nanoTime() - lastSummary >= SUMMARY_INTERVAL) {
                printSummary();
                processedSinceSummary = 0;
                staleSinceSummary = 0;
                lastSummary = System.nanoTime();
            }

        } catch (IllegalArgumentException e) {
            System.err.println("Invalid quote: " + e.getMessage());
        }
    }

    private void printSummary() {
        System.out.printf(
            "[%s] %d quotes in the last 5 s (%d stale), %d symbols%n",
            Thread.currentThread().getName(),
            processedSinceSummary,
            staleSinceSummary,
            consolidationService.symbols().size());

        Instant now = Instant.now();

        for (String symbol : consolidationService.symbols()) {
            ConsolidatedQuote quote =
                    consolidationService.snapshot(symbol, now);

            if (quote.bidPrice() != null && quote.askPrice() != null) {
                System.out.printf(
                    "%s | BID %.2f (%s, qty=%d) "
                        + "| ASK %.2f (%s, qty=%d) "
                        + "| spread=%.2f bps%n",
                    symbol,
                    quote.bidPrice(),
                    quote.bidExchangeId(),
                    quote.bidQuantity(),
                    quote.askPrice(),
                    quote.askExchangeId(),
                    quote.askQuantity(),
                    quote.spreadBps()
                );
            } else {
                System.out.printf(
                    "%s | Waiting for both bid and ask%n",
                    symbol
                );
            }
        }

        System.out.println();
    }
}
