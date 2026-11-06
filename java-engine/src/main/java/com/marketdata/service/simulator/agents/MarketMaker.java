package com.marketdata.service.simulator.agents;

import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.exchange.Side;
import com.marketdata.service.simulator.exchange.SubmitResult;
import com.marketdata.service.simulator.exchange.Trade;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Keeps one buy order and one sell order waiting around the fair price, so the exchange always has
 * prices to show.
 */
public class MarketMaker implements Agent {

  /** One of our orders waiting in the book. */
  private static final class LiveOrder {
    private final long id;
    private final long priceTicks;
    private int remaining;

    private LiveOrder(long id, long priceTicks, int remaining) {
      this.id = id;
      this.priceTicks = priceTicks;
      this.remaining = remaining;
    }
  }

  private final String agentId;
  private final Exchange exchange;
  private final String symbol;
  private final LongSupplier fairValue;
  private final int halfSpreadTicks;
  private final int size;

  // null means we have no order on that side.
  private LiveOrder bid;
  private LiveOrder ask;

  public MarketMaker(
      String agentId,
      Exchange exchange,
      String symbol,
      LongSupplier fairValue,
      int halfSpreadTicks,
      int size) {
    if (halfSpreadTicks <= 0) {
      throw new IllegalArgumentException("halfSpreadTicks must be positive");
    }

    if (size <= 0) {
      throw new IllegalArgumentException("size must be positive");
    }

    this.agentId = agentId;
    this.exchange = exchange;
    this.symbol = symbol;
    this.fairValue = fairValue;
    this.halfSpreadTicks = halfSpreadTicks;
    this.size = size;

    exchange.register(agentId, this::onFill);
  }

  @Override
  public void act() {
    long fair = fairValue.getAsLong();
    long bidTarget = Math.max(1, fair - halfSpreadTicks);
    long askTarget = fair + halfSpreadTicks;

    // Move the side that is moving away first, so our new bid can't hit our own old ask (or the
    // other way round).
    boolean priceRising =
        (ask != null && askTarget > ask.priceTicks) || (bid != null && bidTarget > bid.priceTicks);

    if (priceRising) {
      ask = moveTo(Side.SELL, ask, askTarget);
      bid = moveTo(Side.BUY, bid, bidTarget);
    } else {
      bid = moveTo(Side.BUY, bid, bidTarget);
      ask = moveTo(Side.SELL, ask, askTarget);
    }
  }

  /** Returns what we have on this side afterwards, or null if nothing is left waiting. */
  private LiveOrder moveTo(Side side, LiveOrder current, long targetTicks) {
    if (current != null && current.priceTicks == targetTicks) {
      return current; // same price: stay, even if partly filled, to keep our place in line
    }

    Optional<SubmitResult> replaced =
        current == null
            ? Optional.empty()
            : exchange.replace(symbol, current.id, targetTicks, size);

    SubmitResult result =
        replaced.orElseGet(() -> exchange.submit(symbol, agentId, side, targetTicks, size));

    // The new order may have traded the moment it arrived.
    int traded = result.trades().stream().mapToInt(Trade::quantity).sum();
    int remaining = size - traded;

    return remaining > 0 ? new LiveOrder(result.orderId(), targetTicks, remaining) : null;
  }

  /** Called by the exchange when one of our waiting orders trades. New orders wait for act(). */
  private void onFill(Trade trade) {
    if (bid != null && trade.buyOrderId() == bid.id) {
      bid.remaining -= trade.quantity();

      if (bid.remaining == 0) {
        bid = null;
      }
    }

    if (ask != null && trade.sellOrderId() == ask.id) {
      ask.remaining -= trade.quantity();

      if (ask.remaining == 0) {
        ask = null;
      }
    }
  }
}
