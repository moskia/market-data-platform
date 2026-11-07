package com.marketdata.service.simulator.exchange;

/** How long an order may wait in the book for the part that can't trade right away. */
public enum TimeInForce {
  /** The rest waits in the book until it trades or is cancelled. */
  GOOD_TILL_CANCEL,

  /** Trade what is possible right now; the rest is dropped. */
  IMMEDIATE_OR_CANCEL
}
