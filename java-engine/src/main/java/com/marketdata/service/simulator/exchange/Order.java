package com.marketdata.service.simulator.exchange;

/**
 * A limit order. A class rather than a record because {@code remaining} goes down on partial
 * fills. Prices are in ticks (1 tick = 0.01), so 100.05 is 10005.
 */
public final class Order {
  private final long id;
  private final String agentId;
  private final Side side;
  private final long priceTicks;
  private int remaining;

  public Order(long id, String agentId, Side side, long priceTicks, int quantity) {
    if (priceTicks <= 0) {
      throw new IllegalArgumentException("priceTicks must be positive");
    }

    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }

    this.id = id;
    this.agentId = agentId;
    this.side = side;
    this.priceTicks = priceTicks;
    this.remaining = quantity;
  }

  public long id() {
    return id;
  }

  public String agentId() {
    return agentId;
  }

  public Side side() {
    return side;
  }

  public long priceTicks() {
    return priceTicks;
  }

  public int remaining() {
    return remaining;
  }

  public boolean isFilled() {
    return remaining == 0;
  }

  /** Only the OrderBook (same package) may fill an order. */
  void fill(int quantity) {
    if (quantity <= 0 || quantity > remaining) {
      throw new IllegalArgumentException("Cannot fill " + quantity + " of " + remaining);
    }

    remaining -= quantity;
  }

  @Override
  public String toString() {
    return side + " #" + id + " " + remaining + "@" + priceTicks + " (" + agentId + ")";
  }
}
