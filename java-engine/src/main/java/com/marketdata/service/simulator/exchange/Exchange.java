package com.marketdata.service.simulator.exchange;

import java.util.HashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import com.marketdata.model.RawQuoteEvent;

public class Exchange {
  // fields: exchangeId, output, one OrderBook per symbol (Map<String, OrderBook>),
  //         nextOrderId and sequenceNumber counters,
  //         what was last published per symbol+side (e.g. a small private record Top(long priceTicks, int quantity))
  private final String exchangeId;
  private final Consumer<RawQuoteEvent> output;

  private final Map<String, OrderBook> books = new HashMap<>();
  
  private long nextOrderId = 1;
  private long sequenceNumber = 0;

  /**
   * Top: best visible price and total on one side
   */
  private record Top(long priceTicks, int quantity) {}

  private final Map<String, Top> lastPublishedBids = new HashMap<>();
  private final Map<String, Top> lastPublishedAsks = new HashMap<>();

  private final Map<String, Consumer<Trade>> fillCallbacks = new HashMap<>();

  public Exchange(String exchangeId, List<String> symbols, Consumer<RawQuoteEvent> output) {
    this.exchangeId = exchangeId;
    this.output = output;

    for (String symbol : symbols) {
      books.put(symbol, new OrderBook());
    }
  }

  public SubmitResult submit(String symbol, String agentId, Side side, long priceTicks, int quantity) {
    OrderBook book = bookFor(symbol);

    long orderId = nextOrderId++;

    Order incoming = new Order(orderId, agentId, side, priceTicks, quantity);

    List<Trade> trades = book.submit(incoming);

    publishIfChanged(symbol);
    notifyRestingOwners(trades, side);

    return new SubmitResult(orderId, trades);
  }

  public boolean cancel(String symbol, long orderId) {
    OrderBook book = bookFor(symbol);

    boolean cancelled = book.cancel(orderId);

    if (cancelled) {
      publishIfChanged(symbol);
    }

    return cancelled;
  }

  public Optional<SubmitResult> replace(String symbol, long orderId, long newPriceTicks, int newQuantity) {
    OrderBook book = bookFor(symbol);

    Optional<Order> existing = book.findOrder(orderId);

    if (existing.isEmpty()) {
      return Optional.empty();
    }

    Order old = existing.get();
    String agentId = old.agentId();
    Side side = old.side();

    long newOrderId = nextOrderId++;

    Order replacement = new Order(newOrderId, agentId, side, newPriceTicks, newQuantity);

    // Only cancel after the replacement has been constructed successfully.
    book.cancel(orderId);

    List<Trade> trades = book.submit(replacement);

    publishIfChanged(symbol);
    notifyRestingOwners(trades, side);

    return Optional.of(new SubmitResult(newOrderId, trades));
  }

  public OptionalLong bestBid(String symbol) {
    return bookFor(symbol).bestBid();
  } 
  
  public OptionalLong bestAsk(String symbol) {
    return bookFor(symbol).bestAsk();
  }

  private void publishIfChanged(String symbol) {
    // Get the order book belonging to this symbol.
    // Each symbol (AAPL, MSFT, etc.) has its own independent book.
    OrderBook book = bookFor(symbol);

    // Check BOTH sides of the order book.
    // A single incoming order can change both the best bid and best ask.
    for (Side side : Side.values()) {

        boolean isBuy = side == Side.BUY;

        // Find the best price for the current side:
        // BUY  -> highest bid price
        // SELL -> lowest ask price
        //
        // OptionalLong.empty() means there are no orders on that side.
        OptionalLong bestPrice = isBuy
                ? book.bestBid()
                : book.bestAsk();

        Top currentTop;

        // Build a snapshot of the CURRENT best price and total quantity.
        if (bestPrice.isPresent()) {

            long priceTicks = bestPrice.getAsLong();

            // Sum the remaining quantities of ALL orders at this price.
            // Example: two BUY orders at 100.00 with quantities 30 and 20
            // produce a top-of-book quantity of 50.
            int quantity = book.quantityAt(side, priceTicks);

            currentTop = new Top(priceTicks, quantity);

        } else {

            // No orders exist on this side.
            // We represent an empty side with price=0 and quantity=0.
            currentTop = new Top(0, 0);
        }

        // Select the map that remembers the last PUBLISHED state
        // for this side, separately for each symbol.
        Map<String, Top> lastPublished = isBuy
                ? lastPublishedBids
                : lastPublishedAsks;

        // Retrieve the previously published top-of-book.
        //
        // If this side has NEVER been published, pretend its previous
        // state was empty (0, 0).
        //
        // This prevents publishing an unnecessary empty ASK when
        // the very first order submitted is a BUY.
        Top previousTop = lastPublished.getOrDefault(
                symbol,
                new Top(0, 0)
        );

        // Records automatically compare their fields using equals().
        // If both price and quantity are unchanged, no event is needed.
        //
        // Example:
        // Previous BID: 100.00 x 50
        // Current BID:  100.00 x 50
        // -> No publication.
        if (!currentTop.equals(previousTop)) {

            // Capture the time at which this quote is published.
            Instant now = Instant.now();

            RawQuoteEvent event = new RawQuoteEvent(
                    exchangeId,
                    symbol,

                    // Convert the simulator's BUY/SELL terminology
                    // into the market-data BID/ASK terminology.
                    isBuy ? "BID" : "ASK",

                    // Convert integer price ticks into a decimal price.
                    // Example: 10005 ticks -> 100.05
                    currentTop.priceTicks() / 100.0,

                    currentTop.quantity(),

                    // Increment the exchange-wide sequence number.
                    // It must increase across ALL symbols and sides,
                    // not restart for each symbol.
                    ++sequenceNumber,

                    now, // Exchange timestamp
                    now  // Received timestamp (simulator uses same time)
            );

            // Send the quote to the configured output.
            // This could be a list in tests or a Kafka publisher later.
            output.accept(event);

            // Remember what we just published.
            //
            // Next time this method runs, we compare the new top
            // against this value to avoid duplicate events.
            lastPublished.put(symbol, currentTop);
        }
    }
  }

  public void register(String agentId, Consumer<Trade> onFill) {
    fillCallbacks.put(agentId, onFill);
  }

  private OrderBook bookFor(String symbol) {
    OrderBook book = books.get(symbol);

    if (book == null) {
      throw new IllegalArgumentException("Unknown symbol: " + symbol);
    }

    return book;
  }

  private void notifyRestingOwners(List<Trade> trades, Side incomingSide) {
    for (Trade trade : trades) {
        String restingAgentId = incomingSide == Side.BUY
                ? trade.sellAgentId()
                : trade.buyAgentId();

        Consumer<Trade> callback = fillCallbacks.get(restingAgentId);

        if (callback != null) {
            callback.accept(trade);
        }
    }
  }
}
