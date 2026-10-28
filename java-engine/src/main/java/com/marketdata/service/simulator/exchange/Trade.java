package com.marketdata.service.simulator.exchange;

/** One fill between a buy order and a sell order, at the resting order's price. */
public record Trade(
    long buyOrderId,
    long sellOrderId,
    String buyAgentId,
    String sellAgentId,
    long priceTicks,
    int quantity) {}
