package com.marketdata.service.simulator.exchange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.List;

import com.marketdata.service.simulator.exchange.Order;
import com.marketdata.service.simulator.exchange.Side;

public class OrderBook {
  private final TreeMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());

  private final TreeMap<Long, Deque<Order>> asks = new TreeMap<>();

  private final Map<Long, Order> ordersById = new HashMap<>();


  public List<Trade> submit(Order incoming) {
    List<Trade> trades = new ArrayList<>();

    TreeMap<Long, Deque<Order>> opposite = incoming.side() == Side.BUY ? asks : bids;

    while (!incoming.isFilled() && !opposite.isEmpty()) {
      long bestPrice = opposite.firstKey();

      if ((bestPrice > incoming.priceTicks() && incoming.side() == Side.BUY) 
        || (bestPrice < incoming.priceTicks() && incoming.side() == Side.SELL)) {
        break;
      }

      Deque<Order> level = opposite.get(bestPrice);
      Order resting = level.peekFirst();

      int quantity = Math.min(
        incoming.remaining(),
        resting.remaining()
      );

      resting.fill(quantity);
      incoming.fill(quantity);

      Order buyer = incoming.side() == Side.BUY ? incoming : resting;
      Order seller = incoming.side() == Side.SELL ? incoming : resting;

      trades.add(new Trade(
        buyer.id(),
        seller.id(),
        buyer.agentId(),
        seller.agentId(),
        resting.priceTicks(),
        quantity
      ));

      if (resting.isFilled()) {
        level.pollFirst();
        ordersById.remove(resting.id());

        if (level.isEmpty()) {
          opposite.remove(bestPrice);
        }
      }
    }

    if (!incoming.isFilled()) {
      TreeMap<Long, Deque<Order>> ownSide = incoming.side() == Side.BUY ? bids : asks;

      ownSide.computeIfAbsent(incoming.priceTicks(), price -> new ArrayDeque<>()).addLast(incoming);

      ordersById.put(incoming.id(), incoming);
    }

    return trades;
  }

  public boolean cancel(long orderId) {
    Order order = ordersById.get(orderId);
    if (order == null) {
      return false;
    }
    TreeMap<Long, Deque<Order>> side = order.side() == Side.BUY ? bids : asks;
    Deque<Order> level = side.get(order.priceTicks());
    level.remove(order);

    if (level.isEmpty()) {
      side.remove(order.priceTicks());
    }

    ordersById.remove(orderId);

    return true;
  }

  public OptionalLong bestBid() {
    if (bids.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(bids.firstKey());
  }

  public OptionalLong bestAsk() {
    if (asks.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(asks.firstKey());
  }

  public int quantityAt(Side side, long priceTicks) {
    TreeMap<Long, Deque<Order>> book = side == Side.BUY ? bids : asks;
    Deque<Order> level = book.get(priceTicks);

    if (level == null) {
      return 0;
    }

    return level.stream().mapToInt(Order::remaining).sum();
  }
}
