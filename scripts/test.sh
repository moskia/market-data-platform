#!/usr/bin/env bash
# Runs the Java engine's tests.
#
#   scripts/test.sh          unit + end-to-end tests (default)
#   scripts/test.sh unit     unit tests only (fast, no Docker needed)
#   scripts/test.sh e2e      end-to-end tests only (starts a throwaway Kafka in Docker)
set -euo pipefail

cd "$(dirname "$0")/../java-engine"

require_docker() {
  if ! docker info > /dev/null 2>&1; then
    echo "Docker is not reachable. Start Docker Desktop (and enable WSL integration), then retry." >&2
    exit 1
  fi
}

case "${1:-all}" in
  unit)
    mvn test
    ;;
  e2e)
    require_docker
    mvn verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false
    ;;
  all)
    require_docker
    mvn verify
    ;;
  *)
    echo "Usage: $0 [unit|e2e|all]" >&2
    exit 2
    ;;
esac
