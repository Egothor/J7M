# Distributed middleware integrations (D-P13, D-P15)

This optional production module connects the replicated primitives to a relational
application database and interoperable OpenTelemetry/Prometheus tools. It does
not host business application logic or implement a JavaSpaces layer.

## JDBC outbox/inbox

`JdbcDelivery.initialize(connection, Dialect.H2)` creates additive tables on a
**dedicated migration connection**. The verified reference database is H2 2.3.232
(including its real TCP server and durable file storage). The PostgreSQL dialect
emits BYTEA instead of BLOB; PostgreSQL runtime integration has not been verified
by this change. Production applications supply their own JDBC driver and pool.

Application publication uses the exact existing JDBC transaction:

1. Disable autocommit and select READ_COMMITTED or stronger isolation.
2. Apply the application's database mutation.
3. Call `enqueue` on the same connection, with stable message/aggregate IDs,
   immutable payload and retry-stable `TraceCarrier`.
4. Commit the application transaction, or roll it all back on **any** exception.

The adapter does not commit, close, replace or roll back caller connections.
Concurrent creation of a previously unknown aggregate can produce a unique-key
conflict; roll back and retry the **whole** transaction. Aggregate row locking
allocates monotonically increasing sequence numbers. The relay selects only the
first unsent row per aggregate. Concurrent relays may send an identical head more
than once but cannot publish its successor before a confirmed head publication.
Queue request IDs are stable hashes of scope/message identity. The queue's durable
message identity prevents a retransmission from inserting a new reordered entry.

`relayOnce` owns its factory-created connections and short mark transactions. It
sends at least once to the real `ReplicatedQueue`, then marks only a confirmed
`SENT` response. DB commit and remote queue send are **separate commits**. An
unknown send leaves the DB row pending for an identical retry; no XA or automatic
exactly-once external effect is claimed. Bind a delivery scope to the same
scope-authorized queue facade and a consistent destination queue.

Consumers call `consume` on their existing non-autocommit transaction. A unique
inbox `(scope,messageId)` and its SHA-256 content fingerprint commit together
with the callback's application mutation. Commit that transaction **before**
acknowledging the queue receipt. A crash between DB commit and queue ACK results
in redelivery and a verified inbox replay, without another database mutation.
Conflicting ID/content is an explicit `MESSAGE_CONFLICT`. A concurrent uniqueness
failure requires rollback/retry of the complete consumer transaction; do not ACK
an aborted transaction.

Retention removes only sent payloads after their configured retention timestamp.
Pending payloads are never pruned. Identity/hash/order and inbox tombstones remain
indefinitely, preventing conflicting ID reuse after payload deletion. Provision
and monitor database tombstone capacity; this API does not silently evict IDs.
Uninstall/rollback stops relay/consumer workers while retaining all tables for
recovery. No destructive down migration is supplied.

## OpenTelemetry and Prometheus

`Observability` uses the official OpenTelemetry API/context and W3C propagator.
It emits real OTLP/HTTP JSON to the configured `/v1/traces` collector and serves
Prometheus text at its actual bound `/metrics` address. Remote collectors require
HTTPS; plaintext HTTP is a loopback development profile. Redirects are disabled.

`TraceCarrier` contains only bounded `traceparent` and `tracestate` metadata;
baggage and arbitrary headers are rejected. `TracedQueue` carries a versioned
context envelope in actual queue payloads. Keep that envelope/context identical
on send retries. Its `consume` callback starts a child of the persisted context.
`claim`, `ack` and `deadLetters` record actual protocol outcomes without exporting
message IDs, payloads, queue names or receipt tokens.

Configure the generic execution facades with both
`() -> telemetry.capture().asMap()` and `telemetry.executionObserver()` in the
five-argument `ExecutionClient` constructor. RPC/job/saga spans then open and
close automatically around production operations. Retry spans are nonsemantic
metadata: the execution journal preserves the first committed context without
changing the protected business fingerprint. A worker calls `runJob(actualClaim,
callback)` to attach the persisted parent while running its own job callback;
it must separately commit completion/effects using its current job fence.

The exporter has a fixed queue capacity and at most one further batch of 32
constant-size span records. Export is performed by one background worker with
bounded connect/request timeouts. Full queues and collector failures increment
`j7m_telemetry_dropped_total`; data operations never wait for the collector and
export failures never retry application work. Closing drops remaining telemetry
and cancels exporter I/O without flushing an unavailable collector.

Only trace/span/parent IDs, fixed operation names, numeric status and timestamps
are exported. Payloads, principal/tenant/message/request IDs, errors, tokens,
baggage and vendor tracestate values never enter OTLP or metric labels. Fixed
operation vocabulary bounds cardinality.

Use `watchConsensus`, `watchLease` and `watchExecution` to poll actual middleware
state off the operation and scrape paths; close their handles when retiring a
source. The quorum gauge means **the observed local leader's fresh quorum
barrier succeeded**, not cluster-wide availability inferred from discovery.
`j7m_apply_lag` is committed-minus-applied progress, not remote follower lag.
Recovery reports observed groups opened with durable history; execution gauges
come from scope-authorized replicated `E_STATS`. Migration redirect counts come
from actual `MOVED/FROZEN` transport results. Observer failures are numeric,
never exception text. Multiple sources aggregate only where documented; use one
instance per independently monitored group/scope for unambiguous gauges.

## Validation and rollback

```sh
./gradlew :j7m-integrations:test :j7m-integrations:pmdMain \
  :j7m-integrations:pmdTest :j7m-integrations:javadoc
```

`JdbcDeliveryIT` uses a real durable H2 TCP database and three broker JVMs. It
kills relay JVMs after DB commit, after send, before mark, and consumer JVMs after
DB commit before ACK. It checks rollback, concurrent relay ordering, concurrent
inbox uniqueness, conflicting payloads and retention tombstones.
`ObservabilityIT` carries a trace through three independent application JVMs and
two real replicated-queue hops, validates actual collector requests, scrapes a
quorum fault and drives queue operations with the collector down. The exporter
capacity/drop tests use real production exporters and HTTP scrape endpoints.
`ExecutionObservationIT` checks automatically instrumented generic RPC/job/saga
facades, changed retry spans, persisted worker parent context and replicated replay
metrics. Retained evidence is in `build/reports/integrations/`.

Telemetry rollback closes its instance and removes optional decorators/observer
configuration; queue envelopes already persisted still require the same decoder.
JDBC rollback retains integration tables and pending rows. Existing primitives,
transport APIs and applications do not depend on this optional module.
