"""The monitor's dashboard. Reads monitor.db (written by the monitor) every 2 seconds.

    streamlit run dashboard/app.py
"""

import os
import sys
from datetime import datetime, timedelta, timezone

import altair as alt
import pandas as pd
import streamlit as st

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from monitor.checks import CROSSED_BOOK, LATENCY_OUTLIER, SEQUENCE_GAP, STALE_QUOTE  # noqa: E402
from monitor.store import Store  # noqa: E402

DB_PATH = os.environ.get("MONITOR_DB", os.path.join(os.path.dirname(__file__), "..", "monitor.db"))
REFRESH_SECONDS = 2
CHART_WINDOW = timedelta(minutes=5)
COUNT_WINDOW = timedelta(hours=1)

# Categorical slots 1 and 2 of the reference palette, with their light and dark steps.
SERIES_COLORS = {
    "light": {"From exchanges": "#2a78d6", "From the engine": "#eb6834"},
    "dark": {"From exchanges": "#3987e5", "From the engine": "#d95926"},
}

IMPORTANT_CHECKS = [CROSSED_BOOK, SEQUENCE_GAP, STALE_QUOTE]
CHECK_NAMES = {
    CROSSED_BOOK: "Crossed book",
    SEQUENCE_GAP: "Sequence gap",
    STALE_QUOTE: "Stale quote",
    LATENCY_OUTLIER: "Latency outlier",
}

st.set_page_config(page_title="Market data monitor", layout="wide")
st.title("Market data monitor")

if not os.path.exists(DB_PATH):
    st.info(
        "No data yet. Start the monitor with `scripts/monitor.sh`, "
        "then the market with `scripts/run-demo.sh`."
    )
    st.stop()


@st.cache_resource
def open_store() -> Store:
    return Store(DB_PATH)


def theme() -> str:
    return "dark" if st.context.theme.type == "dark" else "light"


def short_time(iso: str) -> str:
    return datetime.fromisoformat(iso).strftime("%H:%M:%S")


@st.fragment(run_every=REFRESH_SECONDS)
def show():
    store = open_store()
    now = datetime.now(timezone.utc)

    show_counts(store, now)
    left, right = st.columns([2, 3])
    with left:
        show_book(store, now)
    with right:
        show_rate_chart(store, now)
    show_alerts(store)

    st.caption(f"Updated {now:%H:%M:%S} UTC · refreshes every {REFRESH_SECONDS} s · alerts kept for 1 hour")


def show_counts(store: Store, now: datetime):
    recent = store.stats(now - timedelta(seconds=10))
    rate = sum(row[1] for row in recent) / max(len(recent), 1)
    counts = store.alert_counts(now - COUNT_WINDOW)

    tiles = st.columns(5)
    tiles[0].metric("Messages / s (last 10 s)", f"{rate:.0f}")
    for tile, check in zip(tiles[1:], [*IMPORTANT_CHECKS, LATENCY_OUTLIER]):
        tile.metric(f"{CHECK_NAMES[check]}s (last hour)", counts.get(check, 0))


def show_book(store: Store, now: datetime):
    st.subheader("Best prices across exchanges")
    rows = store.book()
    if not rows:
        st.write("Waiting for the engine's first quote.")
        return

    table = pd.DataFrame(
        [
            {
                "Symbol": symbol,
                "Bid": f"{bid:.2f}" if bid is not None else "–",
                "Bid on": f"{bid_ex} ({bid_qty})" if bid is not None else "–",
                "Ask": f"{ask:.2f}" if ask is not None else "–",
                "Ask on": f"{ask_ex} ({ask_qty})" if ask is not None else "–",
                "Spread (bps)": f"{spread:.2f}" if spread is not None else "–",
                "Crossed": "Yes" if spread is not None and spread < 0 else "No",
                "Age (s)": f"{(now - datetime.fromisoformat(updated)).total_seconds():.0f}",
            }
            for symbol, bid, bid_qty, bid_ex, ask, ask_qty, ask_ex, spread, updated in rows
        ]
    )
    st.dataframe(table, hide_index=True, width="stretch")


def show_rate_chart(store: Store, now: datetime):
    st.subheader("Messages per second (UTC)")
    rows = store.stats(now - CHART_WINDOW)
    if not rows:
        st.write("No messages in the last 5 minutes.")
        return

    data = pd.DataFrame(rows, columns=["time", "From exchanges", "From the engine", "invalid"])
    # Drop the time zone so the browser doesn't convert to local time: the page shows UTC.
    data["time"] = pd.to_datetime(data["time"]).dt.tz_convert(None)
    long = data.melt("time", ["From exchanges", "From the engine"], var_name="Feed", value_name="Messages")

    colors = SERIES_COLORS[theme()]
    color = alt.Color(
        "Feed:N",
        scale=alt.Scale(domain=list(colors), range=list(colors.values())),
        legend=alt.Legend(orient="top", title=None),
    )
    nearest = alt.selection_point(nearest=True, on="pointerover", fields=["time"], empty=False)

    base = alt.Chart(long).encode(
        x=alt.X("time:T", title=None, axis=alt.Axis(format="%H:%M:%S", grid=False)),
        y=alt.Y("Messages:Q", title="messages / s", axis=alt.Axis(gridOpacity=0.3)),
        color=color,
    )
    lines = base.mark_line(strokeWidth=2, interpolate="monotone")
    hover = (
        alt.Chart(data)
        .mark_rule(opacity=0.4)
        .encode(
            x="time:T",
            tooltip=[
                alt.Tooltip("time:T", title="Time", format="%H:%M:%S"),
                alt.Tooltip("From exchanges:Q"),
                alt.Tooltip("From the engine:Q"),
            ],
        )
        .transform_filter(nearest)
    )
    targets = alt.Chart(data).mark_rule(opacity=0).encode(x="time:T").add_params(nearest)
    points = base.mark_point(size=60, filled=True).transform_filter(nearest)

    st.altair_chart((lines + targets + hover + points).properties(height=260), width="stretch")

    with st.expander("Show as a table"):
        st.dataframe(data.sort_values("time", ascending=False), hide_index=True, width="stretch")


def show_alerts(store: Store):
    important, latency = st.tabs(["Alerts", "Latency outliers"])

    with important:
        rows = [row for check in IMPORTANT_CHECKS for row in store.recent_alerts(50, check)]
        rows.sort(key=lambda row: row[0], reverse=True)
        if rows:
            st.dataframe(alert_table(rows[:50]), hide_index=True, width="stretch")
        else:
            st.write("No crossed books, sequence gaps or stale quotes.")

    with latency:
        st.caption(
            "Messages that took over 200 ms since the exchange created them. "
            "The simulator delays 2% of messages by 300 ms on purpose."
        )
        rows = store.recent_alerts(50, LATENCY_OUTLIER)
        if rows:
            st.dataframe(alert_table(rows), hide_index=True, width="stretch")
        else:
            st.write("None.")


def alert_table(rows) -> pd.DataFrame:
    return pd.DataFrame(
        [
            {"Time (UTC)": short_time(time), "Check": CHECK_NAMES[check], "Where": subject, "What": message}
            for time, check, subject, message in rows
        ]
    )


show()
