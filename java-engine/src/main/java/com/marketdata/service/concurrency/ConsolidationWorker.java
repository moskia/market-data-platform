package com.marketdata.service.concurrency;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.ConsolidationService;
import com.marketdata.model.ConsolidatedQuote;

public class ConsolidationWorker implements Runnable {
    private final ConflatingQueue conflatingQueue;
    private final ConsolidationService consolidationService;
    private volatile boolean running = true;

    public ConsolidationWorker(ConflatingQueue conflatingQueue, ConsolidationService consolidationService) {
        this.conflatingQueue = conflatingQueue;
        this.consolidationService = consolidationService;
    }

    public void submit(RawQuoteEvent rawQuoteEvent) {
        this.conflatingQueue.offer(rawQuoteEvent);
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

    private long processedCount = 0;

    private void processQuote(RawQuoteEvent rawQuoteEvent) {
        try {
            boolean updated =
                    consolidationService.onQuote(rawQuoteEvent, Instant.now());

            processedCount++;

            if (processedCount % 20 == 0) {
                printSummary();
            }

            if (!updated) {
                System.out.printf(
                    "Ignored stale quote: %s / %s / %s / seq=%d%n",
                    rawQuoteEvent.exchangeId(),
                    rawQuoteEvent.symbol(),
                    rawQuoteEvent.side(),
                    rawQuoteEvent.sequenceNumber()
                );
            }

        } catch (IllegalArgumentException e) {
            System.err.println("Invalid quote: " + e.getMessage());
        }
    }

    private void printSummary() {
        Instant now = Instant.now();

        for (String symbol : consolidationService.symbols()) {
            ConsolidatedQuote quote =
                    consolidationService.snapshot(symbol, now);

            if (quote.bidPrice() != null && quote.askPrice() != null) {
                System.out.printf(
                    "%s | BID %.2f (%s, qty=%d) "
                        + "| ASK %.2f (%s, qty=%d) "
                        + "| spread=%.2f%n",
                    symbol,
                    quote.bidPrice(),
                    quote.bidExchangeId(),
                    quote.bidQuantity(),
                    quote.askPrice(),
                    quote.askExchangeId(),
                    quote.askQuantity(),
                    quote.spread()
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
