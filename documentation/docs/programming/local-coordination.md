# Local coordination and membership

`CoordinationService` and `LogBackedCoordinationService` provide a local durable authority for coordinates, exclusive leases and membership metadata. They are useful inside a host or as the state behind a deliberately owned service boundary. Independent instances do not form a replicated quorum and do not prevent split brain. For cross-JVM durable ownership use the [distributed lease/fencing facilities](../patterns/leases-fencing.md).

## Compare and set a coordinate

This source-checked fragment assumes an initialized `CoordinationService` named `coordination` and imports from `org.j7m.middleware.api.coordination`.

```java
CoordinateKey key = new CoordinateKey("index/current");
CoordinateValue expected = coordination.get(key); // null when absent
CoordinateValue replacement = new CoordinateValue(
        "generation-2".getBytes(StandardCharsets.UTF_8));
CasResult result = coordination.compareAndSet(
        key, expected, replacement, CasOptions.defaults());
if (result.success()) {
    // The coordinate now contains generation-2 at result.version().
}
```

The update succeeds only if the expected value still matches. Inspect the returned decision before acting on it. To reject an intervening change that restores the same value, supply an expected `CoordinateVersion` in `CasOptions`. When work belongs to a lease, supply its required lease ID and current fencing token as well. Values are bounded opaque bytes; status does not expose their contents.

## Lease and membership boundaries

Named exclusive leases support acquisition, renewal by expected epoch and release. Their scoped fencing tokens must be enforced by the resource accepting writes. A local lease named "leader" remains a local exclusive lease; the name does not make it a Raft leader election.

Membership distinguishes `JOINING`, `ALIVE`, `SUSPECT`, `DEAD` and `LEFT`. A heartbeat cannot revive a terminal `DEAD`/`LEFT` member. Stable member identity and membership epoch help reject stale updates, but membership metadata alone is not authorization to write a protected resource.

## Watches and recovery

Watches are local best-effort asynchronous notifications for coordinate, lease and membership changes. Their queue is bounded; overflow can drop notifications, callback failures are isolated, and closing a watch cancels queued delivery. Re-read authoritative state after a notification. Watches are not durable subscriptions and are not restored after restart.

Recovery uses a versioned, checksummed checkpoint and bounded log tail. Invalid semantic state fails recovery rather than silently manufacturing a new owner. Keep the host-owned durable log available for the lifetime of the coordination service. See [local durable log](durable-log.md) and [discovery refresh](../patterns/discovery-refresh.md) for the separate service-discovery behavior.
