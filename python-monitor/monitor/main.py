"""Reads both topics, runs the checks, and saves what the dashboard needs.

    python -m monitor.main [--bootstrap localhost:9092] [--db monitor.db]
"""

import argparse
import signal
from datetime import datetime, timedelta, timezone
from typing import List

from confluent_kafka import Consumer, KafkaException

from monitor.checks import LATENCY_OUTLIER, Alert, Check, all_checks
from monitor.models import ConsolidatedQuote, RawQuote
from monitor.store import KEEP_FOR, Store

RAW_TOPIC = "raw-feed-events"
CONSOLIDATED_TOPIC = "consolidated-book"
TICK = timedelta(milliseconds=100)
STATS_EVERY = timedelta(seconds=1)


class Monitor:
    """Everything except Kafka: turns messages and time into alerts and stats in the store."""

    def __init__(self, store: Store, checks: List[Check]):
        self.store = store
        self.checks = checks
        self.raw_count = 0
        self.consolidated_count = 0
        self.invalid_count = 0

    def on_raw(self, text: str, now: datetime) -> List[Alert]:
        try:
            quote = RawQuote.from_json(text)
        except (ValueError, KeyError, TypeError):
            self.invalid_count += 1
            return []

        self.raw_count += 1
        return self._save([a for check in self.checks for a in check.on_raw(quote, now)])

    def on_consolidated(self, text: str, now: datetime) -> List[Alert]:
        try:
            quote = ConsolidatedQuote.from_json(text)
        except (ValueError, KeyError, TypeError):
            self.invalid_count += 1
            return []

        self.consolidated_count += 1
        self.store.save_book(quote)
        return self._save([a for check in self.checks for a in check.on_consolidated(quote, now)])

    def on_tick(self, now: datetime) -> List[Alert]:
        return self._save([a for check in self.checks for a in check.on_tick(now)])

    def save_stats(self, now: datetime) -> None:
        """Message counts since the last call, then start counting again."""
        self.store.add_stats(now, self.raw_count, self.consolidated_count, self.invalid_count)
        self.raw_count = self.consolidated_count = self.invalid_count = 0
        self.store.forget_before(now - KEEP_FOR)

    def _save(self, alerts: List[Alert]) -> List[Alert]:
        self.store.add_alerts(alerts)
        return alerts


def main() -> None:
    parser = argparse.ArgumentParser(description="Watches the market data feed for problems.")
    parser.add_argument("--bootstrap", default="localhost:9092")
    parser.add_argument("--db", default="monitor.db")
    parser.add_argument("--group", default="market-data-monitor")
    args = parser.parse_args()

    store = Store(args.db)
    monitor = Monitor(store, all_checks())
    consumer = Consumer(
        {
            "bootstrap.servers": args.bootstrap,
            "group.id": args.group,
            "auto.offset.reset": "latest",
            # Without Kafka, librdkafka would otherwise log a failed connection every few ms.
            "log_level": 2,
        }
    )

    try:
        consumer.list_topics(timeout=5)
    except KafkaException:
        consumer.close()
        raise SystemExit(f"Kafka is not reachable at {args.bootstrap}. Is it running?")

    consumer.subscribe([RAW_TOPIC, CONSOLIDATED_TOPIC])

    running = True

    def stop(*_):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    print(f"Watching {RAW_TOPIC} and {CONSOLIDATED_TOPIC}; writing to {args.db}")
    now = datetime.now(timezone.utc)
    next_tick, next_stats = now + TICK, now + STATS_EVERY
    latency_outliers = 0

    try:
        while running:
            message = consumer.poll(TICK.total_seconds())
            now = datetime.now(timezone.utc)
            alerts: List[Alert] = []

            if message is not None and message.error() is None:
                text = message.value().decode("utf-8")
                if message.topic() == RAW_TOPIC:
                    alerts += monitor.on_raw(text, now)
                else:
                    alerts += monitor.on_consolidated(text, now)

            if now >= next_tick:
                alerts += monitor.on_tick(now)
                store.commit()
                next_tick = now + TICK

            if now >= next_stats:
                monitor.save_stats(now)
                next_stats = now + STATS_EVERY

            for alert in alerts:
                if alert.check == LATENCY_OUTLIER:
                    latency_outliers += 1  # too many to print: see the dashboard
                else:
                    print(f"{alert.time:%H:%M:%S.%f}"[:-3], alert.check, alert.subject, alert.message)
    finally:
        consumer.close()
        store.close()
        print(f"Stopped. Latency outliers seen: {latency_outliers}")


if __name__ == "__main__":
    main()
