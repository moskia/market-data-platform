package com.marketdata.check;

import com.marketdata.kafka.KafkaConfig;
import com.marketdata.kafka.simulator.KafkaQuotePublisher;
import com.marketdata.model.RawQuoteEvent;
import java.time.Instant;

/** Sends one quote to Kafka, to check the connection works. */
public class KafkaSmokeTest {
  public static void main(String[] args) {
    try (KafkaQuotePublisher publisher = new KafkaQuotePublisher(KafkaConfig.BOOTSTRAP_SERVERS)) {
      Instant now = Instant.now();
      publisher.publish(new RawQuoteEvent("EX_A", "AAPL", "BID", 190.00, 100, 1, 1, now, now));
    }
  }
}
