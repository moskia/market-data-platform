package com.marketdata.service.simulator.agents;

import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.exchange.Side;
import com.marketdata.service.simulator.exchange.SubmitResult;
import com.marketdata.service.simulator.exchange.Trade;
import java.util.OptionalLong;
import java.util.Random;

/**
 * Buys and sells at random, so that trades happen. Half of its orders are priced to trade right
 * away, the others wait behind the best price. It keeps at most one waiting order, so the book
 * doesn't fill up with old orders.
 */
public class NoiseTrader implements Agent {
  private static final double AGGRESSIVE_PROBABILITY = 0.5;
  private static final int MAX_QUANTITY = 20;
  private static final int MAX_PASSIVE_OFFSET_TICKS = 3;

  private final String agentId;
  private final Exchange exchange;
  private final String symbol;
  private final Random random;
  private Long restingOrderId;

  public NoiseTrader(String agentId, Exchange exchange, String symbol, Random random) {
    this.agentId = agentId;
    this.exchange = exchange;
    this.symbol = symbol;
    this.random = random;
  }

  @Override
  public void act() {
    if (restingOrderId != null) {
      exchange.cancel(symbol, restingOrderId); // false if it already traded, which is fine
      restingOrderId = null;
    }

    Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
    boolean aggressive = random.nextDouble() < AGGRESSIVE_PROBABILITY;
    int offset = 1 + random.nextInt(MAX_PASSIVE_OFFSET_TICKS);
    int quantity = 1 + random.nextInt(MAX_QUANTITY);

    OptionalLong price = aggressive ? aggressivePrice(side) : passivePrice(side, offset);

    if (price.isEmpty()) {
      return;
    }

    SubmitResult result = exchange.submit(symbol, agentId, side, price.getAsLong(), quantity);
    int filled = result.trades().stream().mapToInt(Trade::quantity).sum();

    if (filled < quantity) {
      restingOrderId = result.orderId();
    }
  }

  /** The other side's best price, so the order trades immediately. */
  private OptionalLong aggressivePrice(Side side) {
    return side == Side.BUY ? exchange.bestAsk(symbol) : exchange.bestBid(symbol);
  }

  /** A little worse than this side's best price, so the order waits. */
  private OptionalLong passivePrice(Side side, int offset) {
    OptionalLong best = side == Side.BUY ? exchange.bestBid(symbol) : exchange.bestAsk(symbol);

    if (best.isEmpty()) {
      return best;
    }

    long price = side == Side.BUY ? best.getAsLong() - offset : best.getAsLong() + offset;

    return price > 0 ? OptionalLong.of(price) : OptionalLong.empty();
  }
}
