# Learning Progress

## Pending decision
- Decision: Code structure (grouping classes by responsibility)
- Decision: Folder/package layout
- Stage: Steps 1-4 IMPLEMENTED (restructure plan complete). Step 4 done by Claude at the learner's explicit "do step 4": SimulatedExchangeFeedHandler takes Consumer<RawQuoteEvent>; the Mains pass publisher::publish. Layer rule verified by grep. No pending decision. Steps 2-4 were implemented on request without learner design reasoning, so the learner's understanding of them is not yet demonstrated.
- Learner chose: by layer, with feature subfolders inside layers. When asked to place the files, learner said "I don't know, just do it and explain". Layout below is CLAUDE'S PROPOSAL (not learner reasoning):
  com/marketdata/{model, service/{simulator,consolidation}, kafka/{KafkaConfig,JsonMapper,simulator,consolidation(RawQuoteParser, ConsolidatedQuotePublisher)}, app, check}
- Claude-proposed extension: SimulatedExchangeFeedHandler takes Consumer<RawQuoteEvent> instead of KafkaQuotePublisher (layer rule: service must not import kafka).
- Planned later steps: 2 helpers; 3 ConsolidationService + RawQuoteParser; 4 simulator callback. Suggested that the learner write steps 2-4 (hands-on preference).
- Earlier Design checkpoint (consumer structure) was CONFIRMED by the learner; see project-map.md. No code is authorized yet. The learner asked about the purpose of the discussion; Claude tied it back to their goal, and the learner chose "Keep going" at frequent checkpoints.
- Learner direction: option C, a business-logic "service" (a plain Java class, as Claude clarified) called from MarketDataConsumerMain. Learner proposed: everything except Kafka connect/poll moves to the service.
- Claude flagged: putting "send to Kafka" and "JSON parsing" in the service conflicts with the testability goal; summary print can be decided later.
- Learner proposal (round 6 answer): a "middleware" layer between Kafka and the service that handles JSON parsing and serialization/sending. Claude reflected it as: Kafka <-> adapter (parse / serialize+send) <-> service (RawQuoteEvent in, ConsolidatedQuote out). Not yet confirmed.
- Claude explained: "adapter"/infrastructure layer is the usual term; "middleware" usually means systems like Kafka.
- Round 7 answer: learner proposed a "webhook" that notifies only when there's a new quote, otherwise does nothing, reasoning that the system must update rapidly. Claude explained webhook (HTTP, cross-system) vs in-process callback/listener (option b), and that a vs b don't differ in speed; noted the expiry re-check can produce 0-3 results per call.
- Design checkpoint CONFIRMED: helpers (Kafka conn + JSON), Kafka-free service, adapter layer, callback on new quote only, Main = connect/poll/wire; summary print open. Proposed additions (Claude's, not learner's): name ConsolidationService; callback type Consumer<ConsolidatedQuote>. Not authorized for code. Next: folder layout (learner to decide).
- Learner proposal so far (not confirmed): a Kafka connection helper and a serialization helper; MarketDataConsumerMain would call the helpers and publish.
- Asked: where should business rules (validation, book update, publish-on-change, expiry tick, summary) live? Framed via two scenarios: testing publish-on-change without Kafka, and adding a tie-breaking rule.
- Claude noted: consumer vs producer configs differ (group id/deserializers vs serializers/acks).
- No structure confirmed yet. Claude's earlier restructure was reverted at the learner's request.

## Code organization
- Introduced (explained by Claude): Java packages; grouping classes by responsibility; separating business rules from infrastructure (Kafka).
- Demonstrated understanding: described each file's role accurately for FeedHandler, KafkaQuotePublisher, MarketDataSimulatorMain, ConsolidatedQuotePublisher (correctly guessed it publishes best bid/ask), QuoteBookCheck (test).
- Demonstrated reasoning: on their own, proposed extracting Kafka connection and JSON serialization helpers; this matches real duplication in the code (ObjectMapper setup x3, producer config x2).
- Demonstrated reasoning: on their own, proposed a separate layer between Kafka and the service for parsing and sending (the adapter idea), which keeps the service Kafka-free.
- Needs reinforcement: separating business rules from infrastructure; their proposal left the business logic in the consumer main loop. When asked where the rules should live, the learner said they didn't know; Claude explained input/output logic with no Kafka dependency, using QuoteBook/QuoteBookCheck as the existing example.
- Needs reinforcement (corrected by Claude): ConsolidatedQuote is the output snapshot, not a comparison utility; QuoteBook spans all exchanges and computes best bid/ask; QuoteValidator runs consumer-side (trust boundary); MarketDataConsumerMain also holds publish-on-change and expiry logic; exchanges are venues, not traders.

## Layered structure
- Introduced (explained by Claude): by-layer vs by-feature folders; one-way dependency rule app -> kafka -> service -> model; "where to add X" map.
- Learner decided: layers with feature subfolders. Could not place the files into layers themselves (asked Claude to decide). Needs reinforcement.

## Communication between components
- Introduced (explained by Claude): webhook (HTTP between systems) vs in-process callback/listener; return value vs callback, both equally fast in-process.
- Learner reasoning: wanted "notify only when new, otherwise nothing", which is a correct behavioral idea. Misconception: a webhook/notification would be faster (Claude corrected this).

## Java language
- Introduced (explained by Claude): `record` (immutable data class), `AutoCloseable` + try-with-resources.
- Learner had flagged both as unknown.

## Simulator mechanics
- Introduced (explained by Claude): delivery-time PriorityQueue causing out-of-order delivery; link to QuoteBook sequence-number staleness check.
- Learner had flagged delay mechanism as unknown.
