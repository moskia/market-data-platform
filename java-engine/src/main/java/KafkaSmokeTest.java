import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaSmokeTest {
  public static void main(String[] args) throws Exception {
    Properties config = new Properties();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

    try (KafkaQuotePublisher publisher = new KafkaQuotePublisher("localhost:9092")) {
      SimulatedExchangeFeedHandler exchange =
          new SimulatedExchangeFeedHandler("EX_A", List.of("AAPL"), 500, publisher);

      publisher.publish(exchange.generateQuote());
    }
  }
}
