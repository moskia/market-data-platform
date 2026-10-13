# Project Map

## Purpose
Consolidated market data feed: simulated exchanges publish quotes; an engine merges them
into the best bid/ask per symbol across exchanges and publishes changes.

## Requirements
- Learner wants the code structured so it is clear what functionality to add next and where.
- Layered structure decided and implemented (steps 1-4).

## Components
Implemented layout (step 1, layout proposed by Claude and approved by the learner), under `java-engine/src/main/java/com/marketdata/`:
model/ (RawQuoteEvent, ConsolidatedQuote); service/simulator/ (FeedHandler, SimulatedExchangeFeedHandler);
service/consolidation/ (QuoteBook, QuoteValidator); kafka/simulator/ (KafkaQuotePublisher);
kafka/consolidation/ (ConsolidatedQuotePublisher); app/ (both Mains); check/ (QuoteBookCheck, KafkaSmokeTest).
Dependency rule: app -> kafka -> service -> model. Rule holds as of step 4 (verified by grep: model imports nothing; service imports only model).
kafka/KafkaConfig (BOOTSTRAP_SERVERS, producerProperties, consumerProperties) and kafka/JsonMapper (create) implemented in step 2.
Step 3 implemented: service/consolidation/ConsolidationService, kafka/consolidation/RawQuoteParser, check/ConsolidationServiceCheck; MarketDataConsumerMain now only connects/polls/wires and prints the summary.
Class roles (unchanged by the move):
- Simulator: `MarketDataSimulatorMain` (3 exchanges EX_A/B/C, 300/500/800 ms mean interval, runs 10 s),
  `SimulatedExchangeFeedHandler` (random-walk prices, Poisson arrivals, 1% drop, 2% delayed/reordered),
  `FeedHandler` interface.
- Kafka publishing: `KafkaQuotePublisher` (raw-feed-events), `ConsolidatedQuotePublisher` (consolidated-book).
- Consolidation: `MarketDataConsumerMain` (Kafka loop + publish-on-change logic, hard-coded symbols),
  `QuoteBook` (latest quote per exchange/symbol/side, sequence staleness check, 5 s freshness, best bid/ask),
  `QuoteValidator` (uncommitted; field validation).
- Model: `RawQuoteEvent`, `ConsolidatedQuote` (with `sameMarketState` ignoring timestamp/spread).
- Dev checks: `QuoteBookCheck` (hand-rolled assertions), `KafkaSmokeTest`.

## Main Flow
SimulatedExchangeFeedHandler x3 --JSON, key=symbol--> Kafka `raw-feed-events`
  --> MarketDataConsumerMain: parse -> QuoteValidator -> QuoteBook.update (reject stale seq)
  --> best bid/ask (fresh only) --if changed--> Kafka `consolidated-book` --> ? (no consumer yet)

## Confirmed design (not implemented)
Consumer side, confirmed by the learner:
- Shared helpers: Kafka connection helper (producer and consumer configs) and JSON serialization helper.
- ConsolidationService (name proposed by Claude, confirmed): plain Java class, no Kafka/JSON. Handles validation, QuoteBook updates,
  building ConsolidatedQuote, skip-if-same-as-last, expiry re-check.
- Adapter layer (learner called it "middleware"): JSON -> RawQuoteEvent in; ConsolidatedQuote -> JSON -> Kafka out.
- Service notifies through a callback only when there's a new consolidated quote; callback type Consumer<ConsolidatedQuote> (proposed by Claude, confirmed).
- Main: connect, poll, wire the components.
- Open: where the summary print lives. Folder layout is not decided yet.

  Kafka --JSON--> adapter --RawQuoteEvent--> ConsolidationService --callback(ConsolidatedQuote)--> adapter --JSON--> Kafka

## Data and Trust Boundaries
- Book state is in memory only; lost on restart. Consumer group `market-data-engine`, auto.offset.reset=latest.
- Kafka is local, PLAINTEXT, no auth.

## Build and Deployment
- `docker-compose.yml`: Kafka 4.2 KRaft single broker + `kafka-init` creating topics via `scripts/init-kafka.sh` (3 partitions each).
- `java-engine/pom.xml`: Java 17, kafka-clients 4.0.1, Jackson 2.18.2. No test framework, no exec plugin.

## Unknowns
- Target package/module structure.
- What consumes `consolidated-book`.
- Whether to address: crossed markets in simulator, inconsistent tie-breaking, restart state loss, hard-coded symbols.
