"""The two kinds of message on Kafka, parsed from the JSON the Java side writes."""

import json
from dataclasses import dataclass
from datetime import datetime
from typing import Optional


def parse_time(text: str) -> datetime:
    """Java writes times like 2026-11-07T15:55:20.595625854Z: up to 9 decimals, which Python
    can't read (it keeps 6), and a trailing Z, which Python 3.10 doesn't accept."""
    text = text.replace("Z", "+00:00")
    if "." in text:
        whole, rest = text.split(".", 1)
        digits = rest[: rest.index("+")]
        text = f"{whole}.{digits[:6].ljust(6, '0')}{rest[len(digits):]}"
    return datetime.fromisoformat(text)


@dataclass(frozen=True)
class RawQuote:
    """One price change from one exchange (topic raw-feed-events)."""

    exchange_id: str
    symbol: str
    side: str
    price: float
    quantity: int
    session_id: int
    sequence_number: int
    exchange_time: datetime
    received_time: datetime

    @staticmethod
    def from_json(text: str) -> "RawQuote":
        data = json.loads(text)
        return RawQuote(
            exchange_id=data["exchangeId"],
            symbol=data["symbol"],
            side=data["side"],
            price=data["price"],
            quantity=data["quantity"],
            session_id=data["sessionId"],
            sequence_number=data["sequenceNumber"],
            exchange_time=parse_time(data["exchangeTimestamp"]),
            received_time=parse_time(data["receivedTimestamp"]),
        )


@dataclass(frozen=True)
class ConsolidatedQuote:
    """The engine's best bid and ask across exchanges (topic consolidated-book)."""

    symbol: str
    bid_price: Optional[float]
    bid_quantity: Optional[int]
    bid_exchange_id: Optional[str]
    ask_price: Optional[float]
    ask_quantity: Optional[int]
    ask_exchange_id: Optional[str]
    spread_bps: Optional[float]
    calculated_time: datetime

    @property
    def is_crossed(self) -> bool:
        return self.spread_bps is not None and self.spread_bps < 0

    @staticmethod
    def from_json(text: str) -> "ConsolidatedQuote":
        data = json.loads(text)
        return ConsolidatedQuote(
            symbol=data["symbol"],
            bid_price=data["bidPrice"],
            bid_quantity=data["bidQuantity"],
            bid_exchange_id=data["bidExchangeId"],
            ask_price=data["askPrice"],
            ask_quantity=data["askQuantity"],
            ask_exchange_id=data["askExchangeId"],
            spread_bps=data["spreadBps"],
            calculated_time=parse_time(data["calculatedTimestamp"]),
        )
