package com.marketdata.service.concurrency;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

import com.marketdata.model.RawQuoteEvent;

public class ConcurrentConflatingQueue implements ConflatingQueue {
    private record Key(
        String exchangeId,
        String symbol,
        String side
    ) {}

    private final ConcurrentHashMap<Key, RawQuoteEvent> map;
    private final LinkedBlockingQueue<Key> queue;

    public ConcurrentConflatingQueue() {
        this.map = new ConcurrentHashMap<>();
        this.queue = new LinkedBlockingQueue<>();
    }

    @Override 
    public RawQuoteEvent take() throws InterruptedException {
        Key key = this.queue.take();
        return this.map.remove(key);
    }

    @Override 
    public RawQuoteEvent poll(long timeout, TimeUnit unit) throws InterruptedException{
            Key key = this.queue.poll(timeout, unit);
            return key == null ? null : this.map.remove(key);
    }

    @Override 
    public void offer(RawQuoteEvent value) {
        AtomicBoolean shouldEnqueue = new AtomicBoolean(false);

        Key key = new Key(value.exchangeId(), value.symbol(), value.side());
        
        RawQuoteEvent quote = this.map.compute(key, (k, oldQuote) -> {
            if (oldQuote == null) {
                shouldEnqueue.set(true);
                return value;
            }

            if (value.sequenceNumber() > oldQuote.sequenceNumber()) {
                return value;
            }

            return oldQuote;
        });

        if (shouldEnqueue.get()) this.queue.offer(key);
    }
}
