package com.marketdata.kafka;

import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaConfig {

  public static final String BOOTSTRAP_SERVERS = "localhost:9092";
  public static final String RAW_FEED_TOPIC = "raw-feed-events";
  public static final String CONSOLIDATED_BOOK_TOPIC = "consolidated-book";

  private KafkaConfig() {}

  /** Settings for sending JSON strings, waiting until every replica has stored them. */
  public static Properties producerProperties(String bootstrapServers) {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    return properties;
  }

  /** Settings for reading JSON strings as part of a consumer group. */
  public static Properties consumerProperties(String bootstrapServers, String groupId) {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    properties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    return properties;
  }
}
