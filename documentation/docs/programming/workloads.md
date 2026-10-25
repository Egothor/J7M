# Workload scheduling API

The `org.j7m.middleware.workload.api` family describes asynchronous tasks, leases, progress, checkpoints, streams and multi-candidate calls. Its baseline contract does not require durable storage or quorum replication. The runtime's `InMemoryWorkloadScheduler` is a local implementation. Use the [durable jobs pattern](../patterns/durable-jobs.md) when accepted work must survive host loss.

## Submit bounded asynchronous work

This source-checked host fragment assumes a configured `WorkloadScheduler` named `scheduler`. The host owns its executor, admission, security and shutdown policy.

```java
TaskSubmission submission = new TaskSubmission(
        new TaskDescriptor("normalize", "normalizeText", "hello",
                WorkloadMetadata.empty(), Map.of()),
        TaskOptions.defaults(), Map.of());

AsyncResult<Object> result = scheduler.submitAsync(
        submission, (context, cancellation) -> "HELLO");
Object normalized = result.await(Duration.ofSeconds(2));
// normalized is "HELLO" if execution completes before the wait expires.
```

This is an API fragment, not an independently executed example. Defaults request best-effort at-most-once delivery, no retry, a five-second task deadline and rejection on backpressure. Set explicit payload/result limits and application-specific policy for deployment. `submitOneWay` returns after local acceptance, not after business completion or durable replication. `AsyncResult.cancel()` is a best-effort cancellation request.

## Ownership and checkpoints

The scheduler exposes `acquireLease`, `renewLease`, `releaseLease`, `reportProgress`, `checkpoint`, `complete` and `fail`. Mutating an owned task requires the owner and current fencing token. A stale worker must not report success after reassignment. Checkpoint metadata describes scheduler state; it does not atomically commit a database write or restore arbitrary in-memory application state.

Long-running handlers must cooperate with their supplied cancellation token. A timeout on `await` limits that caller's wait; it does not establish that an external operation never executed.

## Fan-out and scatter-gather

Candidate discovery is an input, not an implicit global scan. `FanOutRequest` bounds candidate count, total deadline and per-target timeout. `ScatterGatherRequest` bounds candidates and total deadline and explicitly selects whether partial results are acceptable.

```java
FanOutRequest request = new FanOutRequest(
        candidates, 3, Duration.ofSeconds(2),
        Duration.ofMillis(500), Map.of());
FanOutResult result = scheduler.fanOut(request, invoker);
int succeeded = result.successes().size();
```

Here `candidates` is a discovered `List<ObjectRef>` and `invoker` is the host/application `CandidateInvoker` implementing one bounded call. Inspect per-target outcomes rather than assuming every candidate succeeded. Read [remote streams](remote-streams.md) for streaming transport calls and [selection/performance](selection-performance.md) for avoiding excessive remote work.

The in-memory host bounds retained tasks, concurrent executions, stream buffers and approximate stream-item size through `InMemorySchedulerOptions`. Its development defaults are not a production security configuration. Install explicit host security and resource limits, and close the scheduler when its owning host stops.
