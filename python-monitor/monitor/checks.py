"""The four checks. Each one is told the current time instead of reading the clock, so tests can
say "5.1 seconds later" without waiting. Nothing here knows about Kafka or the database."""

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Set, Tuple

from monitor.models import ConsolidatedQuote, RawQuote

STALE_QUOTE = "stale_quote"
SEQUENCE_GAP = "sequence_gap"
LATENCY_OUTLIER = "latency_outlier"
CROSSED_BOOK = "crossed_book"


@dataclass(frozen=True)
class Alert:
    time: datetime
    check: str
    subject: str
    message: str


class Check:
    """Every check can react to raw quotes, to consolidated quotes, and to time passing."""

    def on_raw(self, quote: RawQuote, now: datetime) -> List[Alert]:
        return []

    def on_consolidated(self, quote: ConsolidatedQuote, now: datetime) -> List[Alert]:
        return []

    def on_tick(self, now: datetime) -> List[Alert]:
        return []


class StaleQuoteCheck(Check):
    """An exchange has sent nothing for a symbol for too long. One alert per silence."""

    def __init__(self, limit: timedelta = timedelta(seconds=5)):
        self.limit = limit
        self.last_seen: Dict[Tuple[str, str], datetime] = {}
        self.alerted: Set[Tuple[str, str]] = set()

    def on_raw(self, quote, now):
        key = (quote.exchange_id, quote.symbol)
        self.last_seen[key] = now
        self.alerted.discard(key)
        return []

    def on_tick(self, now):
        alerts = []
        for key, seen in self.last_seen.items():
            silent_for = now - seen
            if silent_for > self.limit and key not in self.alerted:
                self.alerted.add(key)
                exchange, symbol = key
                alerts.append(
                    Alert(
                        now,
                        STALE_QUOTE,
                        f"{exchange} {symbol}",
                        f"no update for {silent_for.total_seconds():.1f} s",
                    )
                )
        return alerts


@dataclass
class _Session:
    session_id: int
    highest: int
    missing: Dict[int, datetime] = field(default_factory=dict)  # sequence -> give-up time


class SequenceGapCheck(Check):
    """A sequence number never arrived. Messages can arrive out of order, so a missing number is
    only reported if it still hasn't arrived after `wait`. Counted per exchange and session."""

    def __init__(self, wait: timedelta = timedelta(seconds=1)):
        self.wait = wait
        self.sessions: Dict[str, _Session] = {}

    def on_raw(self, quote, now):
        state = self.sessions.get(quote.exchange_id)

        if state is None or quote.session_id > state.session_id:
            # First message seen, or the exchange restarted: start counting from here.
            self.sessions[quote.exchange_id] = _Session(quote.session_id, quote.sequence_number)
            return []

        if quote.session_id < state.session_id:
            return []  # a late message from before the restart

        sequence = quote.sequence_number
        if sequence > state.highest:
            give_up_at = now + self.wait
            for skipped in range(state.highest + 1, sequence):
                state.missing[skipped] = give_up_at
            state.highest = sequence
        else:
            state.missing.pop(sequence, None)  # it was only late
        return []

    def on_tick(self, now):
        alerts = []
        for exchange, state in self.sessions.items():
            lost = sorted(seq for seq, give_up_at in state.missing.items() if now >= give_up_at)
            for seq in lost:
                del state.missing[seq]
            for first, last in _ranges(lost):
                numbers = f"#{first}" if first == last else f"#{first}-#{last}"
                alerts.append(
                    Alert(
                        now,
                        SEQUENCE_GAP,
                        exchange,
                        f"session {state.session_id}: {numbers} lost",
                    )
                )
        return alerts


def _ranges(numbers: List[int]) -> List[Tuple[int, int]]:
    """[4, 5, 6, 9] -> [(4, 6), (9, 9)]"""
    ranges: List[Tuple[int, int]] = []
    for number in numbers:
        if ranges and number == ranges[-1][1] + 1:
            ranges[-1] = (ranges[-1][0], number)
        else:
            ranges.append((number, number))
    return ranges


class LatencyOutlierCheck(Check):
    """A message took much longer than usual to arrive since the exchange created it."""

    def __init__(self, limit: timedelta = timedelta(milliseconds=200)):
        self.limit = limit

    def on_raw(self, quote, now):
        latency = now - quote.exchange_time
        if latency <= self.limit:
            return []
        return [
            Alert(
                now,
                LATENCY_OUTLIER,
                f"{quote.exchange_id} {quote.symbol}",
                f"#{quote.sequence_number} took {latency.total_seconds() * 1000:.0f} ms",
            )
        ]


class CrossedBookCheck(Check):
    """The best bid is above the best ask for longer than `limit`. Short crosses are normal (the
    arbitrageur fixes them in a few ms), so only long ones are reported, once when they pass the
    limit and once when they end."""

    def __init__(self, limit: timedelta = timedelta(milliseconds=100)):
        self.limit = limit
        self.crossed_since: Dict[str, datetime] = {}
        self.worst_bps: Dict[str, float] = {}
        self.alerted: Set[str] = set()

    def on_consolidated(self, quote, now):
        symbol = quote.symbol

        if quote.is_crossed:
            self.crossed_since.setdefault(symbol, now)
            self.worst_bps[symbol] = min(self.worst_bps.get(symbol, 0.0), quote.spread_bps)
            return []

        since: Optional[datetime] = self.crossed_since.pop(symbol, None)
        worst = self.worst_bps.pop(symbol, None)
        if since is None or symbol not in self.alerted:
            return []

        self.alerted.discard(symbol)
        lasted = (now - since).total_seconds() * 1000
        return [
            Alert(
                now,
                CROSSED_BOOK,
                symbol,
                f"no longer crossed after {lasted:.0f} ms (worst {worst:.2f} bps)",
            )
        ]

    def on_tick(self, now):
        alerts = []
        for symbol, since in self.crossed_since.items():
            if now - since > self.limit and symbol not in self.alerted:
                self.alerted.add(symbol)
                alerts.append(
                    Alert(
                        now,
                        CROSSED_BOOK,
                        symbol,
                        f"crossed for over {self.limit.total_seconds() * 1000:.0f} ms "
                        f"({self.worst_bps[symbol]:.2f} bps)",
                    )
                )
        return alerts


def all_checks() -> List[Check]:
    return [StaleQuoteCheck(), SequenceGapCheck(), LatencyOutlierCheck(), CrossedBookCheck()]
