from datetime import datetime, timedelta, timezone

from monitor.checks import (
    CROSSED_BOOK,
    LATENCY_OUTLIER,
    SEQUENCE_GAP,
    STALE_QUOTE,
    CrossedBookCheck,
    LatencyOutlierCheck,
    SequenceGapCheck,
    StaleQuoteCheck,
)
from monitor.models import ConsolidatedQuote, RawQuote

START = datetime(2026, 11, 7, 12, 0, 0, tzinfo=timezone.utc)


def at(ms: float) -> datetime:
    return START + timedelta(milliseconds=ms)


def raw(sequence=1, exchange="EX_A", symbol="AAPL", session=1, created_ms=0.0) -> RawQuote:
    created = at(created_ms)
    return RawQuote(exchange, symbol, "BID", 190.0, 100, session, sequence, created, created)


def book(spread_bps, symbol="AAPL") -> ConsolidatedQuote:
    return ConsolidatedQuote(symbol, 190.0, 100, "EX_A", 190.05, 100, "EX_B", spread_bps, START)


# --- Stale quote ---------------------------------------------------------------------------


def test_stale_quote_is_reported_after_five_seconds_of_silence():
    check = StaleQuoteCheck()
    check.on_raw(raw(), at(0))

    assert check.on_tick(at(4_900)) == []
    alerts = check.on_tick(at(5_100))

    assert [(a.check, a.subject) for a in alerts] == [(STALE_QUOTE, "EX_A AAPL")]
    assert "5.1 s" in alerts[0].message


def test_stale_quote_is_reported_once_per_silence():
    check = StaleQuoteCheck()
    check.on_raw(raw(), at(0))
    check.on_tick(at(5_100))

    assert check.on_tick(at(6_000)) == []
    assert check.on_tick(at(60_000)) == []


def test_a_new_message_ends_the_silence_and_a_later_one_is_reported_again():
    check = StaleQuoteCheck()
    check.on_raw(raw(1), at(0))
    check.on_tick(at(5_100))

    check.on_raw(raw(2), at(6_000))
    assert check.on_tick(at(10_000)) == []
    assert len(check.on_tick(at(11_100))) == 1


def test_each_exchange_and_symbol_is_watched_separately():
    check = StaleQuoteCheck()
    check.on_raw(raw(1, symbol="AAPL"), at(0))
    check.on_raw(raw(2, symbol="MSFT"), at(4_000))

    alerts = check.on_tick(at(5_100))

    assert [a.subject for a in alerts] == ["EX_A AAPL"]


# --- Sequence gap ---------------------------------------------------------------------------


def test_numbers_in_order_are_fine():
    check = SequenceGapCheck()
    for sequence in range(1, 6):
        check.on_raw(raw(sequence), at(sequence))

    assert check.on_tick(at(5_000)) == []


def test_a_late_number_within_the_wait_is_not_a_gap():
    check = SequenceGapCheck()
    check.on_raw(raw(40), at(0))
    check.on_raw(raw(42), at(10))
    assert check.on_tick(at(100)) == []  # the monitor checks every 100 ms
    check.on_raw(raw(41), at(500))  # overtaken, but it did arrive

    assert check.on_tick(at(5_000)) == []


def test_a_number_still_missing_after_the_wait_is_lost():
    check = SequenceGapCheck()
    check.on_raw(raw(40), at(0))
    check.on_raw(raw(42), at(10))

    assert check.on_tick(at(900)) == []
    alerts = check.on_tick(at(1_100))

    assert [(a.check, a.subject) for a in alerts] == [(SEQUENCE_GAP, "EX_A")]
    assert "#41 lost" in alerts[0].message


def test_several_lost_numbers_in_a_row_make_one_alert():
    check = SequenceGapCheck()
    check.on_raw(raw(40), at(0))
    check.on_raw(raw(44), at(10))

    alerts = check.on_tick(at(2_000))

    assert len(alerts) == 1
    assert "#41-#43 lost" in alerts[0].message


def test_a_lost_number_is_reported_only_once():
    check = SequenceGapCheck()
    check.on_raw(raw(40), at(0))
    check.on_raw(raw(42), at(10))
    check.on_tick(at(2_000))

    assert check.on_tick(at(3_000)) == []


def test_a_restart_starts_counting_again_instead_of_reporting_gaps():
    check = SequenceGapCheck()
    check.on_raw(raw(3_000, session=1), at(0))
    check.on_raw(raw(1, session=2), at(10))
    check.on_raw(raw(2, session=2), at(20))

    assert check.on_tick(at(5_000)) == []


def test_a_late_message_from_before_the_restart_is_ignored():
    check = SequenceGapCheck()
    check.on_raw(raw(1, session=2), at(0))
    check.on_raw(raw(3_005, session=1), at(10))
    check.on_raw(raw(2, session=2), at(20))

    assert check.on_tick(at(5_000)) == []


def test_each_exchange_has_its_own_numbers():
    check = SequenceGapCheck()
    check.on_raw(raw(1, exchange="EX_A"), at(0))
    check.on_raw(raw(100, exchange="EX_B"), at(1))
    check.on_raw(raw(2, exchange="EX_A"), at(2))
    check.on_raw(raw(101, exchange="EX_B"), at(3))

    assert check.on_tick(at(5_000)) == []


# --- Latency outlier ------------------------------------------------------------------------


def test_normal_latency_is_fine():
    assert LatencyOutlierCheck().on_raw(raw(created_ms=0), at(50)) == []


def test_latency_above_200_ms_is_an_outlier():
    alerts = LatencyOutlierCheck().on_raw(raw(7, created_ms=0), at(352))

    assert [(a.check, a.subject) for a in alerts] == [(LATENCY_OUTLIER, "EX_A AAPL")]
    assert "#7 took 352 ms" in alerts[0].message


# --- Crossed book ---------------------------------------------------------------------------


def test_a_short_cross_is_not_reported():
    check = CrossedBookCheck()
    check.on_consolidated(book(-1.5), at(0))
    assert check.on_tick(at(50)) == []

    assert check.on_consolidated(book(2.6), at(60)) == []
    assert check.on_tick(at(1_000)) == []


def test_a_cross_lasting_over_100_ms_is_reported_once():
    check = CrossedBookCheck()
    check.on_consolidated(book(-1.5), at(0))

    alerts = check.on_tick(at(120))

    assert [(a.check, a.subject) for a in alerts] == [(CROSSED_BOOK, "AAPL")]
    assert check.on_tick(at(500)) == []


def test_the_end_of_a_reported_cross_says_how_long_it_lasted_and_how_bad_it_got():
    check = CrossedBookCheck()
    check.on_consolidated(book(-1.5), at(0))
    check.on_consolidated(book(-3.2), at(50))
    check.on_tick(at(120))

    alerts = check.on_consolidated(book(2.6), at(320))

    assert len(alerts) == 1
    assert "after 320 ms" in alerts[0].message
    assert "-3.20 bps" in alerts[0].message


def test_a_missing_side_is_not_a_cross():
    check = CrossedBookCheck()
    check.on_consolidated(book(None), at(0))

    assert check.on_tick(at(1_000)) == []


def test_each_symbol_is_watched_separately():
    check = CrossedBookCheck()
    check.on_consolidated(book(-1.5, symbol="AAPL"), at(0))
    check.on_consolidated(book(2.0, symbol="MSFT"), at(0))

    assert [a.subject for a in check.on_tick(at(200))] == ["AAPL"]
