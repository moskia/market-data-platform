from datetime import datetime, timedelta, timezone

from monitor.checks import all_checks
from monitor.main import Monitor
from monitor.store import Store

NOW = datetime(2026, 11, 7, 12, 0, 0, tzinfo=timezone.utc)

RAW = (
    '{{"exchangeId":"EX_A","symbol":"AAPL","side":"BID","price":190.0,"quantity":100,'
    '"sessionId":1,"sequenceNumber":{seq},'
    '"exchangeTimestamp":"2026-11-07T12:00:00Z","receivedTimestamp":"2026-11-07T12:00:00Z"}}'
)
BOOK = (
    '{"symbol":"AAPL","bidPrice":190.10,"bidQuantity":100,"bidExchangeId":"EX_A",'
    '"askPrice":190.00,"askQuantity":100,"askExchangeId":"EX_B",'
    '"spreadBps":-5.26,"calculatedTimestamp":"2026-11-07T12:00:00Z"}'
)


def new_monitor():
    store = Store(":memory:")
    return Monitor(store, all_checks()), store


def test_alerts_from_the_checks_are_saved():
    monitor, store = new_monitor()
    monitor.on_raw(RAW.format(seq=1), NOW)
    monitor.on_raw(RAW.format(seq=3), NOW)

    monitor.on_tick(NOW + timedelta(seconds=2))

    assert [row[1] for row in store.recent_alerts()] == ["sequence_gap"]


def test_the_latest_engine_quote_is_kept_per_symbol():
    monitor, store = new_monitor()
    monitor.on_consolidated(BOOK, NOW)
    monitor.on_consolidated(BOOK.replace("190.10", "190.20"), NOW)

    rows = store.book()

    assert len(rows) == 1
    assert rows[0][0] == "AAPL" and rows[0][1] == 190.20


def test_a_long_cross_reaches_the_store():
    monitor, store = new_monitor()
    monitor.on_consolidated(BOOK, NOW)

    monitor.on_tick(NOW + timedelta(milliseconds=150))

    assert store.alert_counts(NOW) == {"crossed_book": 1}


def test_broken_messages_are_counted_not_fatal():
    monitor, store = new_monitor()

    monitor.on_raw("not json", NOW)
    monitor.on_raw('{"exchangeId":"EX_A"}', NOW)  # e.g. written before session ids existed
    monitor.on_raw(RAW.format(seq=1), NOW)
    monitor.save_stats(NOW)

    assert store.stats(NOW)[0][1:] == (1, 0, 2)


def test_counts_restart_after_each_stats_row():
    monitor, store = new_monitor()
    monitor.on_raw(RAW.format(seq=1), NOW)
    monitor.save_stats(NOW)
    monitor.on_raw(RAW.format(seq=2), NOW)
    monitor.on_raw(RAW.format(seq=3), NOW)
    monitor.save_stats(NOW + timedelta(seconds=1))

    assert [row[1] for row in store.stats(NOW)] == [1, 2]


def test_old_alerts_are_forgotten_after_an_hour():
    monitor, store = new_monitor()
    monitor.on_raw(RAW.format(seq=1), NOW)
    monitor.on_raw(RAW.format(seq=3), NOW)
    monitor.on_tick(NOW + timedelta(seconds=2))

    monitor.save_stats(NOW + timedelta(hours=2))

    assert store.recent_alerts() == []
