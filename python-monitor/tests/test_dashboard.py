"""Runs the dashboard script in Streamlit's test runner and checks it renders without errors."""

import os
from datetime import datetime, timedelta, timezone

from streamlit.testing.v1 import AppTest

from monitor.checks import Alert
from monitor.models import ConsolidatedQuote
from monitor.store import Store

APP = os.path.join(os.path.dirname(__file__), "..", "dashboard", "app.py")


def run_dashboard(db_path, monkeypatch) -> AppTest:
    monkeypatch.setenv("MONITOR_DB", str(db_path))
    app = AppTest.from_file(APP, default_timeout=30)
    app.run()
    return app


def test_without_a_database_it_explains_how_to_start(tmp_path, monkeypatch):
    app = run_dashboard(tmp_path / "missing.db", monkeypatch)

    assert not app.exception
    assert "scripts/monitor.sh" in app.info[0].value


def test_with_data_it_shows_prices_counts_and_alerts(tmp_path, monkeypatch):
    now = datetime.now(timezone.utc)
    store = Store(str(tmp_path / "monitor.db"))
    store.save_book(
        ConsolidatedQuote("AAPL", 190.0, 100, "EX_A", 190.05, 80, "EX_B", 2.63, now)
    )
    for second in range(5):
        store.add_stats(now - timedelta(seconds=second), 150, 60, 0)
    store.add_alerts(
        [
            Alert(now, "sequence_gap", "EX_A", "session 1: #41 lost"),
            Alert(now, "latency_outlier", "EX_B AAPL", "#7 took 352 ms"),
        ]
    )
    store.close()

    app = run_dashboard(tmp_path / "monitor.db", monkeypatch)

    assert not app.exception
    metrics = {metric.label: metric.value for metric in app.metric}
    assert metrics["Messages / s (last 10 s)"] == "150"
    assert metrics["Sequence gaps (last hour)"] == "1"
    assert metrics["Latency outliers (last hour)"] == "1"
    book = app.dataframe[0].value
    assert list(book["Symbol"]) == ["AAPL"] and list(book["Spread (bps)"]) == ["2.63"]
