# Capacity Planning and Performance

Size the deployment from measured encoded operations and their physical lifetime. A small business payload can produce a larger authenticated frame, retained result, journal entry and snapshot. A future that has completed exceptionally can still correspond to physically active work.

Record the expected and worst-case concurrency, request/result sizes, queue backlog, durable identity retention, event lag, checkpoint size, outbox backlog and recovery window. Measure under the target JVM, disks, network and security profile. Include a failure/recovery phase; a steady-state throughput number alone does not establish operational headroom.

| Symptom | Investigate before changing a limit |
| --- | --- |
| High tail latency | Physical permits, downstream latency, queueing, GC and storage sync latency |
| Admission rejection | Actual bounded capacity and whether cancelled work still runs |
| Discovery saturation | Poll interval, peer/contact bounds, DNS/TLS failures and context filtering |
| Growing queue/outbox backlog | Consumer physical throughput, retries, ownership renewal and DB commit latency |
| Snapshot/journal exhaustion | Retained identities/results, encoded size and retention policy |
| Slow recovery | Snapshot age, bounded replay work, disk throughput and quorum reformation |

Use backpressure and finite admission instead of an unbounded executor or queue. Increasing a worker count without increasing the constrained downstream resource can make latency and recovery worse.

Keep profiling and benchmark conclusions attached to their workload and environment. `@Benchmark` is metadata; it is not a built-in guarantee of throughput measurement or an automatic benchmark runner. Use the actual benchmark/test tooling and state what was measured.

Clock health is also operational capacity: native time-sensitive mutations stop when the measured reference/uncertainty policy is unsafe. `MeasuredClockHealth` starts unsafe, detects wall/monotonic/reference discrepancies above its 500 ms boundary and requires two stable aligned samples to recover. A process reading its own wall clock is not an authenticated external reference.

See [numeric limits](../reference/limits.md) for concrete defaults and the source constructors that validate them.
