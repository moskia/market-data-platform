#!/usr/bin/env bash
# Runs the whole pipeline once against the docker-compose Kafka:
# starts the engine, runs the simulator for 10 s, then stops the engine with SIGTERM
# (the same graceful shutdown as Ctrl+C). Logs go to java-engine/target/demo/.
#
#   scripts/run-demo.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/java-engine"

docker compose -f "$root/docker-compose.yml" up -d

echo "Waiting for Kafka topics..."
for _ in $(seq 1 30); do
  if docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2> /dev/null \
      | grep -qx raw-feed-events; then
    break
  fi
  sleep 2
done

mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
classpath="target/classes:$(cat target/cp.txt)"
logs="target/demo"
mkdir -p "$logs"

java -cp "$classpath" com.marketdata.app.MarketDataConsumerMain > "$logs/engine.log" 2>&1 &
engine_pid=$!
trap 'kill -TERM $engine_pid 2> /dev/null || true' EXIT

# The engine reads from "latest", so give it time to join the consumer group first.
sleep 8

echo "Running the simulator for 10 s..."
java -cp "$classpath" com.marketdata.app.MarketDataSimulatorMain > "$logs/simulator.log" 2>&1
sleep 2

kill -TERM "$engine_pid"
wait "$engine_pid" || true
trap - EXIT

echo
echo "=== engine output ($logs/engine.log) ==="
cat "$logs/engine.log"

if grep -q "Shutting down..." "$logs/engine.log"; then
  echo "Engine shut down gracefully."
else
  echo "Engine did not shut down gracefully." >&2
  exit 1
fi
