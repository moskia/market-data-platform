package com.marketdata.service.simulator.agents;

import com.marketdata.model.RawQuoteEvent;
import com.marketdata.service.consolidation.QuoteBook;
import com.marketdata.service.simulator.exchange.Exchange;
import com.marketdata.service.simulator.exchange.Side;
import com.marketdata.service.simulator.exchange.SubmitResult;
import com.marketdata.service.simulator.exchange.Trade;
import com.marketdata.service.simulator.scheduler.EventScheduler;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Watches the prices of every exchange (they reach it late) and, when one exchange's best bid is
 * above another's best ask, buys on the cheap one and sells on the dear one. Its orders also take
 * time to arrive, and they are immediate-or-cancel, so nothing is left waiting on old prices.
 */
public class Arbitrageur {
  private final String agentId;
  private final EventScheduler scheduler;
  private final long orderDelayMs;
  private final int maxQuantity;

  // What it has heard from the exchanges; the engine's QuoteBook does exactly this job.
  private final QuoteBook view = new QuoteBook();
  private final Map<String, Exchange> exchanges = new HashMap<>();
  private final Set<String> ordersOnTheirWay = new HashSet<>();
  private final Map<String, Integer> positions = new HashMap<>();
  private long cashTicks = 0;

  public Arbitrageur(String agentId, EventScheduler scheduler, long orderDelayMs, int maxQuantity) {
    if (orderDelayMs < 0) {
      throw new IllegalArgumentException("orderDelayMs must not be negative");
    }

    if (maxQuantity <= 0) {
      throw new IllegalArgumentException("maxQuantity must be positive");
    }

    this.agentId = agentId;
    this.scheduler = scheduler;
    this.orderDelayMs = orderDelayMs;
    this.maxQuantity = maxQuantity;
  }

  /** Lets it send orders to this exchange. */
  public void connect(Exchange exchange) {
    exchanges.put(exchange.exchangeId(), exchange);
  }

  /** Called for every price message, after its delay. */
  public void onQuote(RawQuoteEvent quote) {
    view.update(quote);

    String symbol = quote.symbol();

    if (ordersOnTheirWay.contains(symbol)) {
      return; // still waiting on the last attempt: don't chase the same cross twice
    }

    Optional<RawQuoteEvent> bestBid = view.bestBid(symbol);
    Optional<RawQuoteEvent> bestAsk = view.bestAsk(symbol);

    if (bestBid.isEmpty() || bestAsk.isEmpty()) {
      return;
    }

    RawQuoteEvent bid = bestBid.get();
    RawQuoteEvent ask = bestAsk.get();

    // One exchange can't really be crossed: if its bid and ask seem to cross, it's only because
    // one of its two messages hasn't reached us yet.
    if (bid.price() <= ask.price() || bid.exchangeId().equals(ask.exchangeId())) {
      return;
    }

    Exchange buyOn = exchanges.get(ask.exchangeId());
    Exchange sellOn = exchanges.get(bid.exchangeId());

    if (buyOn == null || sellOn == null) {
      return;
    }

    long buyTicks = toTicks(ask.price());
    long sellTicks = toTicks(bid.price());
    int quantity = Math.min(maxQuantity, Math.min(bid.quantity(), ask.quantity()));

    ordersOnTheirWay.add(symbol);

    scheduler.schedule(
        orderDelayMs,
        () -> {
          SubmitResult bought =
              buyOn.submitImmediateOrCancel(symbol, agentId, Side.BUY, buyTicks, quantity);
          SubmitResult sold =
              sellOn.submitImmediateOrCancel(symbol, agentId, Side.SELL, sellTicks, quantity);

          record(symbol, bought, +1);
          record(symbol, sold, -1);
          ordersOnTheirWay.remove(symbol);
        });
  }

  /** Shares held: positive after buying more than selling. Not zero = one leg didn't trade. */
  public int position(String symbol) {
    return positions.getOrDefault(symbol, 0);
  }

  /** Money made (in ticks × shares): what it sold for minus what it paid. */
  public long cashTicks() {
    return cashTicks;
  }

  private void record(String symbol, SubmitResult result, int direction) {
    for (Trade trade : result.trades()) {
      positions.merge(symbol, direction * trade.quantity(), Integer::sum);
      cashTicks -= direction * trade.priceTicks() * trade.quantity();
    }
  }

  private static long toTicks(double price) {
    return Math.round(price * 100);
  }
}
