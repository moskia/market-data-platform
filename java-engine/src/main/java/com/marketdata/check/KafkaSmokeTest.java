package com.marketdata.check;

import com.marketdata.kafka.KafkaConfig;
import com.marketdata.kafka.simulator.KafkaQuotePublisher;
import com.marketdata.service.simulator.SimulatedExchangeFeedHandler;
import java.util.List;

public class KafkaSmokeTest {
  public static void main(String[] args) throws Exception {
    try (KafkaQuotePublisher publisher = new KafkaQuotePublisher(KafkaConfig.BOOTSTRAP_SERVERS)) {
      SimulatedExchangeFeedHandler exchange =
          new SimulatedExchangeFeedHandler("EX_A", List.of("AAPL"), 500, publisher::publish);

      publisher.publish(exchange.generateQuote());
    }
  }
}
