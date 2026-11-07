#!/usr/bin/env bash
# Opens the monitor's dashboard at http://localhost:8501. It reads python-monitor/monitor.db,
# so run scripts/monitor.sh too. Stop it with Ctrl+C.
set -euo pipefail

cd "$(dirname "$0")/../python-monitor"

if [ ! -d .venv ]; then
  python3 -m venv .venv
  .venv/bin/pip install -q -r requirements.txt
fi

exec .venv/bin/streamlit run dashboard/app.py --server.headless true --browser.gatherUsageStats false "$@"
