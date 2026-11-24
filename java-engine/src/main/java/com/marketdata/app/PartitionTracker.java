package com.marketdata.app;

import com.marketdata.service.concurrency.ConsolidationWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;

/**
 * Keeps the workers in step with the partitions Kafka gives this engine. Used only on the Kafka
 * thread: Kafka calls the rebalance methods from inside poll().
 *
 * <ul>
 *   <li>A partition is taken away: every symbol seen on it is forgotten by its worker, so this
 *       engine publishes nothing more for it.
 *   <li>A partition is given: it is re-read from {@code rebuildWindow} ago (quotes older than that
 *       have expired anyway). Its symbols are processed without publishing until the re-read
 *       reaches where the partition ended when it was given, then each publishes its full book.
 * </ul>
 */
final class PartitionTracker implements ConsumerRebalanceListener {
  private final Consumer<?, ?> consumer;
  private final Duration rebuildWindow;
  private final Function<String, ConsolidationWorker> workerFor;

  private final Map<String, TopicPartition> partitionOf = new HashMap<>();
  private final Set<String> owned = new HashSet<>();

  // Partitions still being re-read: where to stop, and the symbols seen so far.
  private final Map<TopicPartition, Long> rebuildUntil = new HashMap<>();
  private final Map<TopicPartition, Set<String>> rebuilding = new HashMap<>();

  PartitionTracker(
      Consumer<?, ?> consumer,
      Duration rebuildWindow,
      Function<String, ConsolidationWorker> workerFor) {
    this.consumer = consumer;
    this.rebuildWindow = rebuildWindow;
    this.workerFor = workerFor;
  }

  @Override
  public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    System.out.println("Partitions taken away: " + numbers(partitions));

    for (TopicPartition partition : partitions) {
      List<String> lost =
          partitionOf.entrySet().stream()
              .filter(entry -> entry.getValue().equals(partition))
              .map(Map.Entry::getKey)
              .toList();

      for (String symbol : lost) {
        workerFor.apply(symbol).command(service -> service.forget(symbol));
        partitionOf.remove(symbol);
        owned.remove(symbol);
      }

      rebuildUntil.remove(partition);
      rebuilding.remove(partition);
    }
  }

  @Override
  public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    System.out.println("Partitions given to this engine: " + numbers(partitions));

    if (partitions.isEmpty()) {
      return;
    }

    Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
    long since = Instant.now().minus(rebuildWindow).toEpochMilli();
    Map<TopicPartition, Long> times = new HashMap<>();
    partitions.forEach(partition -> times.put(partition, since));
    Map<TopicPartition, OffsetAndTimestamp> starts = consumer.offsetsForTimes(times);

    for (TopicPartition partition : partitions) {
      long end = ends.get(partition);
      OffsetAndTimestamp start = starts.get(partition);
      long from = start == null ? end : start.offset(); // null: nothing written since `since`

      consumer.seek(partition, from);

      if (from < end) {
        rebuildUntil.put(partition, end);
        rebuilding.put(partition, new HashSet<>());
        System.out.printf(
            "Rebuilding partition %d from the last %d s (%d messages)%n",
            partition.partition(), rebuildWindow.toSeconds(), end - from);
      }
    }
  }

  /** Call for every quote, before handing it to its worker. */
  void beforeQuote(String symbol, TopicPartition partition) {
    partitionOf.put(symbol, partition);

    if (owned.add(symbol)) {
      boolean rebuild = rebuildUntil.containsKey(partition);
      workerFor.apply(symbol).command(service -> service.own(symbol, rebuild));

      if (rebuild) {
        rebuilding.get(partition).add(symbol);
      }
    }
  }

  /** Call after each poll's quotes have been handed out: finishes the re-reads that caught up. */
  void afterPoll() {
    var finished =
        rebuildUntil.entrySet().stream()
            .filter(entry -> consumer.position(entry.getKey()) >= entry.getValue())
            .map(Map.Entry::getKey)
            .toList();

    for (TopicPartition partition : finished) {
      Set<String> symbols = rebuilding.remove(partition);
      rebuildUntil.remove(partition);

      for (String symbol : symbols) {
        workerFor.apply(symbol).command(service -> service.goLive(symbol, Instant.now()));
      }

      System.out.printf(
          "Partition %d rebuilt: %d symbols live%n", partition.partition(), symbols.size());
    }
  }

  private static List<Integer> numbers(Collection<TopicPartition> partitions) {
    return partitions.stream().map(TopicPartition::partition).sorted().toList();
  }
}
