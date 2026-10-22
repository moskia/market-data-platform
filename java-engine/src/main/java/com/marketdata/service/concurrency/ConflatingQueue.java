package com.marketdata.service.concurrency;

import java.util.concurrent.TimeUnit;
import com.marketdata.model.RawQuoteEvent;

public interface ConflatingQueue {
    // Method for Kafka to push on the Queue
	void offer(RawQuoteEvent value);

    RawQuoteEvent take() throws InterruptedException;
    
    RawQuoteEvent poll(long timeout, TimeUnit unit) throws InterruptedException;

}