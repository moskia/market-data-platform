from datetime import datetime, timezone

from monitor.models import ConsolidatedQuote, RawQuote, parse_time


def test_parses_java_times_with_nine_decimals():
    assert parse_time("2026-11-07T15:55:20.595625854Z") == datetime(
        2026, 11, 7, 15, 55, 20, 595625, tzinfo=timezone.utc
    )


def test_parses_java_times_without_decimals():
    assert parse_time("2026-11-07T15:55:20Z") == datetime(2026, 11, 7, 15, 55, 20, tzinfo=timezone.utc)


def test_parses_a_raw_quote_as_the_simulator_writes_it():
    text = (
        '{"exchangeId":"EX_A","symbol":"AAPL","side":"BID","price":189.84,"quantity":7,'
        '"sessionId":1794067389410,"sequenceNumber":41,'
        '"exchangeTimestamp":"2026-11-07T15:55:20.5Z","receivedTimestamp":"2026-11-07T15:55:20.52Z"}'
    )

    quote = RawQuote.from_json(text)

    assert (quote.exchange_id, quote.symbol, quote.side) == ("EX_A", "AAPL", "BID")
    assert (quote.price, quote.quantity) == (189.84, 7)
    assert (quote.session_id, quote.sequence_number) == (1794067389410, 41)
    assert quote.received_time > quote.exchange_time


def test_parses_a_consolidated_quote_as_the_engine_writes_it():
    text = (
        '{"symbol":"AAPL","bidPrice":189.84,"bidQuantity":7,"bidExchangeId":"EX_B",'
        '"askPrice":189.91,"askQuantity":100,"askExchangeId":"EX_A",'
        '"spreadBps":3.6866359447001016,"calculatedTimestamp":"2026-11-07T15:55:20.595625854Z"}'
    )

    quote = ConsolidatedQuote.from_json(text)

    assert quote.bid_exchange_id == "EX_B"
    assert quote.spread_bps == 3.6866359447001016
    assert not quote.is_crossed


def test_a_side_can_be_missing():
    text = (
        '{"symbol":"AAPL","bidPrice":189.84,"bidQuantity":7,"bidExchangeId":"EX_B",'
        '"askPrice":null,"askQuantity":null,"askExchangeId":null,'
        '"spreadBps":null,"calculatedTimestamp":"2026-11-07T15:55:20Z"}'
    )

    quote = ConsolidatedQuote.from_json(text)

    assert quote.ask_price is None
    assert not quote.is_crossed
