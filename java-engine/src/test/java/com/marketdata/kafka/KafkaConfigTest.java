package com.marketdata.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

class KafkaConfigTest {

  /** Measured: p50 8.3 ms with Kafka's default 5 ms batching delay, 2.8 ms without it. */
  @Test
  void producersSendEachMessageRightAway() {
    assertEquals(
        0, KafkaConfig.producerProperties("localhost:9092").get(ProducerConfig.LINGER_MS_CONFIG));
  }
}
