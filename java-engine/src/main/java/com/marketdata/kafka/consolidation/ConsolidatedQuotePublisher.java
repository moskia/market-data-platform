package com.marketdata.kafka.consolidation;

import com.marketdata.kafka.JsonMapper;
import com.marketdata.kafka.KafkaConfig;
import com.marketdata.model.ConsolidatedQuote;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

public class ConsolidatedQuotePublisher implements AutoCloseable {

    private final ObjectMapper mapper;
    private final KafkaProducer<String, String> producer;

    public ConsolidatedQuotePublisher(String bootstrapServers) {
        mapper = JsonMapper.create();
        producer = new KafkaProducer<>(KafkaConfig.producerProperties(bootstrapServers));
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
