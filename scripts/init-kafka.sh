#!/bin/bash

set -e

KAFKA="/opt/kafka/bin/kafka-topics.sh"
BROKER="kafka:29092"
PARTITIONS=3

# Creates the topic, or, if it already exists with fewer partitions (for example one created
# automatically before this script ran), adds partitions up to $PARTITIONS.
# Kafka can add partitions but never remove them.
ensure_topic() {
    local topic="$1"

    $KAFKA --bootstrap-server "$BROKER" --create --if-not-exists \
        --topic "$topic" --partitions "$PARTITIONS" --replication-factor 1

    local current
    current=$($KAFKA --bootstrap-server "$BROKER" --describe --topic "$topic" \
        | grep -o 'PartitionCount: *[0-9]*' | grep -o '[0-9]*$')

    if [ "$current" -lt "$PARTITIONS" ]; then
        echo "$topic has $current partition(s), raising it to $PARTITIONS"
        $KAFKA --bootstrap-server "$BROKER" --alter --topic "$topic" --partitions "$PARTITIONS"
    fi
}

echo "Creating Kafka topics..."

ensure_topic raw-feed-events
ensure_topic consolidated-book

echo "Kafka topics ready:"
$KAFKA --bootstrap-server "$BROKER" --describe | grep 'PartitionCount'
