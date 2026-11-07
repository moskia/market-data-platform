#!/usr/bin/env bash
# Runs the Python monitor against the local Kafka. It writes python-monitor/monitor.db,
# which the dashboard reads. Stop it with Ctrl+C. Extra arguments go to the monitor.
set -euo pipefail

cd "$(dirname "$0")/../python-monitor"

if [ ! -d .venv ]; then
  python3 -m venv .venv
  .venv/bin/pip install -q -r requirements.txt
fi

exec .venv/bin/python -m monitor.main "$@"
