import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

public class ConsolidatedQuotePublisher implements AutoCloseable {

    private final ObjectMapper mapper;
    private final KafkaProducer<String, String> producer;

    public ConsolidatedQuotePublisher(String bootstrapServers) {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );
        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );
        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );
        properties.put(ProducerConfig.ACKS_CONFIG, "all");

        producer = new KafkaProducer<>(properties);
    }

    public void publish(ConsolidatedQuote quote) {
        try {
            String json = mapper.writeValueAsString(quote);

             producer.send(new ProducerRecord<> ("consolidated-book", quote.symbol(), json), (metadata, error) -> {
                 if (error != null) {
                     System.err.println("Failed to publish consolidated quote: " + error.getMessage());
                 }
             });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize consolidated quote", error); 
        } 
    }

    @Override
    public void close() {
        producer.close();
    } 
}
