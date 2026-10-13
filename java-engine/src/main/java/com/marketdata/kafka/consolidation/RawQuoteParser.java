package com.marketdata.kafka.consolidation;

import com.marketdata.kafka.JsonMapper;
import com.marketdata.model.RawQuoteEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Incoming adapter: turns a JSON message from raw-feed-events into a RawQuoteEvent. */
public class RawQuoteParser {

  private final ObjectMapper mapper = JsonMapper.create();

  public RawQuoteEvent parse(String json) throws JsonProcessingException {
    return mapper.readValue(json, RawQuoteEvent.class);
  }
}
