#!/bin/bash

set -e

KAFKA="/opt/kafka/bin/kafka-topics.sh"
BROKER="kafka:29092"

echo "Creating Kafka topics..."

$KAFKA \
    --bootstrap-server "$BROKER" \
    --create \
    --if-not-exists \
    --topic raw-feed-events \
    --partitions 3 \
    --replication-factor 1


$KAFKA \
    --bootstrap-server "$BROKER" \
    --create \
    --if-not-exists \
    --topic consolidated-book \
    --partitions 3 \
    --replication-factor 1

echo "Kafka topics created."

$KAFKA \
    --bootstrap-server "$BROKER" \
    --list

