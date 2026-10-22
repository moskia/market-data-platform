package com.marketdata.kafka.simulator;

import com.marketdata.kafka.JsonMapper;
import com.marketdata.kafka.KafkaConfig;
import com.marketdata.model.RawQuoteEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.producer.ProducerRecord;

public class KafkaQuotePublisher implements AutoCloseable {
  private final ObjectMapper mapper;
  private final KafkaProducer<String, String> producer;
  private final String topic;

  public KafkaQuotePublisher(String bootstrapServers) {
    this(bootstrapServers, KafkaConfig.RAW_FEED_TOPIC);
  }

  public KafkaQuotePublisher(String bootstrapServers, String topic) {
    mapper = JsonMapper.create();
    producer = new KafkaProducer<>(KafkaConfig.producerProperties(bootstrapServers));
    this.topic = topic;
  }

  public void publish(RawQuoteEvent quote) {
    try {
      String json = mapper.writeValueAsString(quote);

      ProducerRecord<String, String> message =
          new ProducerRecord<>(topic, quote.symbol(), json);

      producer.send(
          message,
          (metadata, error) -> {
            if (error != null) {
              System.err.println(
                  "Failed to publish " + quote.exchangeId() + " #" + quote.sequenceNumber());
              error.printStackTrace();
            }
          });
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize quote", e);
    }
  }

  @Override
  public void close() {
    producer.close();
  }
}
