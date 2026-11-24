#!/usr/bin/env bash
# Builds and starts the whole platform in containers: Kafka, the engine, the simulator (running
# until stopped), the monitor and the dashboard at http://localhost:8501.
#
#   scripts/platform.sh            start (or update) everything in the background
#   scripts/platform.sh logs       follow every container's output (Ctrl+C stops following)
#   scripts/platform.sh down       stop and remove the containers (Kafka's data is kept)
set -euo pipefail

cd "$(dirname "$0")/.."

case "${1:-up}" in
  up)
    docker compose --profile platform up -d --build
    echo "Dashboard: http://localhost:8501   Logs: scripts/platform.sh logs"
    ;;
  logs)
    docker compose --profile platform logs -f --tail 20
    ;;
  down)
    docker compose --profile platform down
    ;;
  *)
    echo "Usage: $0 [up|logs|down]" >&2
    exit 2
    ;;
esac
