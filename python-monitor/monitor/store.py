"""Everything the dashboard shows, kept in one SQLite file. The monitor writes, the dashboard reads;
WAL mode lets the dashboard read while the monitor is writing."""

import sqlite3
from datetime import datetime, timedelta
from typing import Iterable, List, Tuple

from monitor.checks import Alert
from monitor.models import ConsolidatedQuote

KEEP_FOR = timedelta(hours=1)

SCHEMA = """
CREATE TABLE IF NOT EXISTS alerts (
    id         INTEGER PRIMARY KEY,
    time       TEXT NOT NULL,
    check_name TEXT NOT NULL,
    subject    TEXT NOT NULL,
    message    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS alerts_by_time ON alerts (time);

CREATE TABLE IF NOT EXISTS book (
    symbol        TEXT PRIMARY KEY,
    bid_price     REAL,
    bid_quantity  INTEGER,
    bid_exchange  TEXT,
    ask_price     REAL,
    ask_quantity  INTEGER,
    ask_exchange  TEXT,
    spread_bps    REAL,
    updated       TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS stats (
    time                  TEXT PRIMARY KEY,
    raw_messages          INTEGER NOT NULL,
    consolidated_messages INTEGER NOT NULL,
    invalid_messages      INTEGER NOT NULL
);
"""


class Store:
    def __init__(self, path: str):
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.executescript(SCHEMA)

    # --- written by the monitor -----------------------------------------------------------

    def add_alerts(self, alerts: Iterable[Alert]) -> None:
        self.connection.executemany(
            "INSERT INTO alerts (time, check_name, subject, message) VALUES (?, ?, ?, ?)",
            [(a.time.isoformat(), a.check, a.subject, a.message) for a in alerts],
        )

    def save_book(self, quote: ConsolidatedQuote) -> None:
        self.connection.execute(
            "INSERT OR REPLACE INTO book VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                quote.symbol,
                quote.bid_price,
                quote.bid_quantity,
                quote.bid_exchange_id,
                quote.ask_price,
                quote.ask_quantity,
                quote.ask_exchange_id,
                quote.spread_bps,
                quote.calculated_time.isoformat(),
            ),
        )

    def add_stats(self, time: datetime, raw: int, consolidated: int, invalid: int) -> None:
        self.connection.execute(
            "INSERT OR REPLACE INTO stats VALUES (?, ?, ?, ?)",
            (time.isoformat(), raw, consolidated, invalid),
        )

    def forget_before(self, time: datetime) -> None:
        """Keeps the file small: drop alerts and stats older than `time`."""
        self.connection.execute("DELETE FROM alerts WHERE time < ?", (time.isoformat(),))
        self.connection.execute("DELETE FROM stats WHERE time < ?", (time.isoformat(),))

    def commit(self) -> None:
        self.connection.commit()

    def close(self) -> None:
        self.connection.commit()
        self.connection.close()

    # --- read by the dashboard ------------------------------------------------------------

    def recent_alerts(self, limit: int = 50, check: str = None) -> List[Tuple]:
        if check is None:
            sql = "SELECT time, check_name, subject, message FROM alerts ORDER BY id DESC LIMIT ?"
            return self.connection.execute(sql, (limit,)).fetchall()
        sql = (
            "SELECT time, check_name, subject, message FROM alerts "
            "WHERE check_name = ? ORDER BY id DESC LIMIT ?"
        )
        return self.connection.execute(sql, (check, limit)).fetchall()

    def alert_counts(self, since: datetime) -> dict:
        rows = self.connection.execute(
            "SELECT check_name, COUNT(*) FROM alerts WHERE time >= ? GROUP BY check_name",
            (since.isoformat(),),
        ).fetchall()
        return dict(rows)

    def book(self) -> List[Tuple]:
        return self.connection.execute("SELECT * FROM book ORDER BY symbol").fetchall()

    def stats(self, since: datetime) -> List[Tuple]:
        return self.connection.execute(
            "SELECT * FROM stats WHERE time >= ? ORDER BY time", (since.isoformat(),)
        ).fetchall()
