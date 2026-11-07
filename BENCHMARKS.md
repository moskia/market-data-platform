# Benchmarks

How fast the consolidation engine is, measured in two ways:

1. **Microbenchmarks (JMH):** single classes, in memory, without Kafka.
2. **End-to-end latency:** from the simulator handing a quote to Kafka until a worker has processed
   it, measured inside the running engine.

All numbers below were measured on 2026-11-07. The full JMH table is in
[`benchmarks/results/jmh-2026-11-07.txt`](benchmarks/results/jmh-2026-11-07.txt).

## Summary

| What | Typical (p50) | 1 in 100 slower than (p99) | Throughput |
|---|---|---|---|
| Store a quote (`QuoteBook.update`) | 0.074 µs | 0.158 µs | 24.4 M/s |
| Find the best bid (`QuoteBook.bestBid`) | 0.371 µs | 1.314 µs | 2.4 M/s |
| Full work for one message (`ConsolidationService.onQuote`) | 1.03 µs | 7.6 µs | 0.83 M/s per worker |
| Hand-off through the conflating queue | 0.137 µs | 0.369 µs | 8.4 M/s |
| **Kafka → engine processed**, 60 s live run | **2.2 ms** | **7.1 ms** | ~150 quotes/s (the simulator's rate) |

**Main finding:** the engine's own work takes about 1 µs per message, and most of that is spent
finding the best bid and ask. End to end, almost all the time (milliseconds, not microseconds)
is spent in Kafka.

## Setup

| | |
|---|---|
| CPU | AMD Ryzen 7 7840HS (8 cores, 16 threads) |
| Memory | about 8 GB available to WSL |
| OS | Windows, running Linux in WSL2 (kernel 6.6.87.2-microsoft-standard-WSL2) |
| Java | OpenJDK 21.0.12, no JVM options |
| JMH | 1.37: 1 fork, 3 × 1 s warm-up, 5 × 1 s measurement per benchmark |
| Kafka | 4.2 in Docker, a single broker on the same machine; producers use `acks=all`, `linger.ms=0` |

To reproduce:

```bash
scripts/bench.sh                 # all JMH benchmarks, about 3 minutes
scripts/bench.sh QuoteBook       # only those whose name contains "QuoteBook"
scripts/run-demo.sh              # 10 s live run; the engine prints its latency when it stops
```

## 1. Microbenchmarks

Each benchmark is measured twice:

- **Throughput:** operations per second.
- **Single-operation time:** given as percentiles. p50 is the typical case. p99 means 1 operation
  in 100 is slower than this, and p99.9 means 1 in 1,000.

The book is filled the way the engine fills it in production: 3 exchanges × 3 symbols × 2 sides,
so 18 stored quotes.

### QuoteBook

| Operation | Throughput | p50 | p99 | p99.9 |
|---|---|---|---|---|
| `update`: store a newer quote | 24.4 M/s | 0.074 µs | 0.158 µs | 9.4 µs |
| `bestBid`: best bid across exchanges | 2.4 M/s | 0.371 µs | 1.314 µs | 21.4 µs |

`bestBid` takes 5× longer than `update` at p50, and manages 10× fewer operations per second. It goes through all 18 stored quotes with a stream,
keeping only the right symbol and side, every time it is called.

### ConsolidationService: everything a worker does for one message

| Case | Throughput | p50 | p99 | p99.9 |
|---|---|---|---|---|
| Quote changes the best price, so it is published | 0.83 M/s | 1.03 µs | 7.6 µs | 65.9 µs |
| Quote changes nothing visible, so nothing is published | 0.86 M/s | 1.28 µs | 11.6 µs | 61.9 µs |

Both cases cost about the same. For every accepted quote, the service rebuilds the snapshot
(`bestBid` + `bestAsk`, about 0.75 µs together) before checking whether anything changed. So **one
worker can process at most about 830,000 messages per second**, and most of that time goes into the
two lookups.

### Conflating queue: the hand-off from the Kafka thread to a worker

**One thread offers a quote and takes it straight back.** This measures the cost of the hand-off
itself:

| Queue | Throughput | p50 | p99 | p99.9 |
|---|---|---|---|---|
| `ConcurrentConflatingQueue` | 8.4 M/s | 0.137 µs | 0.369 µs | 15.1 µs |
| Plain `LinkedBlockingQueue` (for comparison) | 22.7 M/s | 0.085 µs | 0.169 µs | 2.3 µs |

Conflation costs about 1.6× per hand-off at p50. The extra work is the map update with `compute`,
on top of the queue itself.

**Two threads, one producer and one consumer**, as with the Kafka thread and a worker:

| | Per second |
|---|---|
| Quotes offered | 1.90 M |
| Quotes delivered | 1.21 M |

The consumer could not keep up, so **about 37% of quotes were replaced by a newer quote** with the
same key instead of piling up. That is what conflation is for: under load, the worker always gets
the latest price, and the queue never grows. A plain unbounded queue would keep growing until
memory runs out.

Each thread is also much slower here than in the single-thread test (1.9 M/s instead of 8.4 M/s).
When two CPU cores work on the same data, that data has to move between their caches. This cost of
sharing is why the engine gives each symbol to exactly one worker.

## 2. End-to-end latency

Each worker records two times for every quote it processes, in an
[HdrHistogram](https://github.com/HdrHistogram/HdrHistogram):

- **Since handed to Kafka:** only our own system. This covers the Kafka producer, the broker, the
  consumer, the Kafka thread, the conflating queue and the worker.
- **Since the exchange created it:** everything above, plus the simulated network delay of the
  `UnreliableFeed`: 1–50 ms for every message, and 300 ms more for 2% of them.

Each worker writes only to its own histogram, so no locks are needed. The engine adds them
together after `join()` on every worker thread, which guarantees it sees everything the workers
wrote.

### Results (seed 42, 60 s, 8,955 quotes)

| Measured since | p50 | p99 | p99.9 | max |
|---|---|---|---|---|
| Handed to Kafka | 2.2 ms | 7.1 ms | 108 ms | 573 ms |
| The exchange created it | 28.9 ms | 326 ms | 351 ms | 575 ms |

The second line is mostly the delay the simulator adds on purpose: 1–50 ms for every message, plus
300 ms for the late 2%.

### Kafka's batching delay

Since Kafka 4.0, a producer waits up to 5 ms (`linger.ms = 5`) to send messages in batches. We
measured both settings in 10–20 s live runs:

| Producer setting | p50 | p99 | p99.9 |
|---|---|---|---|
| `linger.ms = 5` (Kafka's default) | 8.3 ms | 62.6 ms | 231 ms |
| `linger.ms = 0` (now used) | 2.8–3.1 ms | 9.3–10.6 ms | 18–190 ms |

With `linger.ms = 0`, every message is sent on its own. Under very heavy load that means more
requests and less total throughput, but much lower latency, which is the usual choice for market
data.

## Limits of these numbers

- **The rare slow cases (p99.9 and max) change a lot between runs.** We saw a p99.9 of 18 ms in
  one live run and 108 ms in another. Likely causes:
  - Java's garbage collector pausing the program. Every quote creates new objects.
  - Java still compiling code during the first seconds of a run.
  - WSL sharing the CPU with Windows.
- **One machine, one broker.** The simulator, Kafka and the engine share one laptop, and Kafka has
  no replicas. A real deployment would have network hops and replication, which add latency.
  Running everything on one machine is also what makes the end-to-end measurement possible:
  both timestamps come from the same clock.
- **The live runs use a small load** (about 150 quotes per second), well below what the engine can
  process. They show latency at a quiet time, not under stress.

## What to improve next

1. **Make `bestBid`/`bestAsk` cheaper.** Store quotes per symbol and side instead of in one map
   that every lookup has to scan. Better still, keep the current best up to date as quotes arrive.
   This is the biggest share of the ~1 µs per message.
2. **Rebuild the snapshot only when it can change.** A quote that is worse than the current best,
   and doesn't replace the best quote, cannot change the consolidated price.
3. **Create fewer objects per message**, to shorten garbage collection pauses and lower p99.9.
4. **Measure under load**, with a faster simulator setting, to see how latency grows as the
   conflating queues start to merge quotes.
