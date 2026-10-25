# Local durable log

`DurableLog` is a local append-only persistence substrate. It supplies atomic batches, stream-scoped offsets, deterministic replay and recovery checks. It does not itself replicate records, deliver queue messages or coordinate distributed transactions. Higher-level [distributed patterns](../patterns/index.md) add their own protocols.

## Append and replay

This host fragment uses `DurableLogBuilder` from `org.j7m.middleware.durablelog` and the API types in `org.j7m.middleware.api.durablelog`. `dataDirectory` is a host-owned `Path`; the example owns the log and cursor.

```java
try (DurableLog log = new DurableLogBuilder()
        .directory(dataDirectory)
        .fsyncPolicy(FsyncPolicy.PER_APPEND)
        .build()) {
    LogStreamId stream = new LogStreamId("orders");
    LogRecord record = new LogRecord(
            LogRecordHeader.data(Map.of("kind", "accepted")),
            "order-42".getBytes(StandardCharsets.UTF_8));
    log.append(stream, List.of(record), AppendOptions.defaults());

    try (LogCursor cursor = log.openCursor(
            stream, LogOffset.beginning(), CursorOptions.defaults())) {
        LogRecord replayed = cursor.next().orElseThrow();
        String order = new String(replayed.payload(), StandardCharsets.UTF_8);
        // order is "order-42" in a newly created directory.
    }
}
```

The fragment is checked against the current API; it is not a standalone executable example. A reused directory can contain earlier records. Each successful batch receives ordered offsets atomically; do not infer transactionality for external effects from this append result. Close cursors promptly: the implementation bounds open cursors, record sizes and records per append.

## Choose the durability window

| `FsyncPolicy` | Storage behavior |
| --- | --- |
| `PER_APPEND` | Force storage sync after every append |
| `GROUP_COMMIT` | Share sync across appends according to the group-commit count/time policy |
| `ON_CLOSE` | Force sync when closing the log |
| `OS_BUFFERED` | Rely on OS buffering; intended for development |

Group commit counts appends rather than individual records. Segment rotation and close flush pending work. Document the accepted loss window for the selected policy and storage device; a successful buffered append is not proof that a power loss cannot lose it.

## Recovery, snapshots and retention

Construction performs recovery. The default recovery policy fails on corrupt or incomplete data. Explicit tail truncation is destructive: it removes the first damaged/incomplete batch and the following tail. Use it as a deliberate recovery decision, inspect the safe recovery report and preserve the original storage when investigation is needed.

`createSnapshot` writes a replay-visible marker. Applications own state serialization and restoration; the marker does not install an application snapshot or consensus snapshot. Retention can be disabled or constrained by size/age. It preserves the active segment and protected snapshot offsets. Replay from an old removed offset begins at the earliest retained eligible record, so applications must retain the state needed to interpret that replay.

`LogCursor.next()` returns empty at end of the available log. `CursorOptions.allowMissingStream` explicitly controls missing-stream behavior. Optional bounded `findLatestOffsetByMetadata` returns a hint that retention can invalidate before a cursor opens; its default API implementation returns empty.

Keep payloads and credentials out of header metadata. Status and recovery summaries are redacted; application code deliberately reading records remains responsible for their confidentiality. See [local coordination](local-coordination.md) and [work queues](../patterns/work-queues.md).
