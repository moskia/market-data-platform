package com.marketdata.service.simulator.exchange;

import java.util.List;

/** What an agent gets back: the id (to cancel or replace later) and any trades it caused. */
public record SubmitResult(long orderId, List<Trade> trades) {}
