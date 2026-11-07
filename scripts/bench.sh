#!/usr/bin/env bash
# Builds and runs the JMH benchmarks. Extra arguments go to JMH, for example:
#
#   scripts/bench.sh                     every benchmark
#   scripts/bench.sh QuoteBook           only benchmarks whose name contains "QuoteBook"
#   scripts/bench.sh -h                  JMH's own options
set -euo pipefail

cd "$(dirname "$0")/.."

# -am also builds java-engine, which the benchmarks measure. Tests are skipped: run scripts/test.sh.
mvn -q -pl benchmarks -am package -DskipTests

java -jar benchmarks/target/benchmarks.jar "$@"
