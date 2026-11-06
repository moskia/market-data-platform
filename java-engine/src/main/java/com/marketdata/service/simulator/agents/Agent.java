package com.marketdata.service.simulator.agents;

/** A pretend trader. Whoever runs the simulation decides when each agent gets a turn. */
public interface Agent {
  void act();
}
