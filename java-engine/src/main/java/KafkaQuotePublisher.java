import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.producer.ProducerRecord;

public class KafkaQuotePublisher implements AutoCloseable {
  private final ObjectMapper mapper;
  private final KafkaProducer<String, String> producer;

  public KafkaQuotePublisher(String bootstrapServers) {

    mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    Properties config = new Properties();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.ACKS_CONFIG, "all");

    producer = new KafkaProducer<>(config);
  }

  public void publish(RawQuoteEvent quote) {
    try {
      String json = mapper.writeValueAsString(quote);

      ProducerRecord<String, String> message =
          new ProducerRecord<>("raw-feed-events", quote.symbol(), json);

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
