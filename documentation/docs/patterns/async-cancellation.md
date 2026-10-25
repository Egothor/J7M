# Asynchronous calls, cancellation and deadlines

**Use case:** a service fans out bounded work while preserving caller context and a single end-to-end deadline. Returning a stage must not detach its resource lifetime from the invocation.

=== "With annotations"

    ```java
    @Async
    @Cancellable
    @Deadline(millis = 1500)
    public CompletionStage<String> rebuild(String partition) {
        return J7mContext.currentInvocation().control().submit(
                () -> rebuildOnePartition(partition));
    }
    ```

    The body runs only under the installed calling/ownership policy. `rebuildOnePartition` is application work and must cooperate with the current control at its safe points.

=== "Explicit API"

    ```java
    InvocationControl control = J7mContext.currentInvocation().control();
    CompletionStage<String> work = control.submit(() -> calculate());
    return work;
    ```

    `InvocationControl` also exposes a contextual executor for explicit stage continuations. Outside an admitted managed invocation, this is not a general executor factory.

## Physical ownership

Success waits for actual tracked work, encoding, CALL cleanup and finalizers. Timeout or cancellation may complete the public reply sooner, but permits and scope ownership remain until the physical contract establishes exit. Cancelling an arbitrary application stage cannot prove that its hidden thread or external request stopped.

Do not block the root CALL waiting on a queued child that requires that same owned execution path. Return its stage and compose with explicit contextual continuations instead.

## Configuration and observable outcomes

Install finite invocation workers/task capacity and a calling policy. Selection, connect, queues, children and retry backoff consume the original budget. Long-running metadata cannot extend it. See [limits](../configuration/limits-observability.md) and [deployment lifecycle](../deployment/lifecycle.md).

## Worked scenario: cancel a bounded index rebuild

The implementation captures its current invocation control before submitting owned work. The index store is an application dependency whose single-page operation is bounded and whose writes have the required application idempotency/fencing protocol.

```java
@Async
@Cancellable
@Deadline(millis = 1500)
public CompletionStage<String> rebuild(String partition) {
    InvocationControl control = J7mContext.currentInvocation().control();
    return control.submit(() -> {
        for (int page = 0; page < 10; page++) {
            if (control.cancellationRequested()
                    || control.remainingBudget().isZero()
                    || control.remainingBudget().isNegative()) {
                return "stopped at safe point " + page;
            }
            indexStore.rebuildPage(partition, page);
        }
        return "complete";
    });
}
```

`indexStore.rebuildPage` is application code, not a J7M API. If cancellation arrives while page 3 is running, that page finishes or follows its own I/O cancellation protocol; the next safe point stops additional pages. The reply may already have timed out, so do not rely on the returned stop message as proof that a remote caller received it.

The host retains the worker's physical ownership until the submitted work really exits. Drain waits for that ownership; it does not close the component underneath a running page. If progress must survive a JVM restart, use the [checkpointed job pattern](durable-jobs.md) instead of an invocation-local stage.
