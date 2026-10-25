# Invocation semantics

Understand an invocation as a logical result together with a physical lifetime. The logical result is what the caller observes. The physical lifetime covers actual work, tracked children, encoding, owned CALL disposal and finalization.

## Asynchronous application code

```java
public interface QuoteService {
    @Async
    @Deadline(millis = 1000)
    CompletionStage<String> quote(String product);
}
```

The managed calling policy supports concrete `CompletionStage<T>` and `CompletableFuture<T>` declarations. It encodes `T`, not the Java future object. Raw stages, nested future payloads and unsupported generic shapes are rejected before entry. The host must install the calling policy and physical ownership adapter.

## One budget

Selection, connection, queues, backoff, root work and tracked children share a budget. Caller settings, parent control, method deadline and the finite host cap may shorten it. A retry or refreshed target does not restart the clock.

## Cancellation

`@Cancellable` declares cooperation. Check the current invocation control at appropriate work boundaries and stop initiating effects after cancellation. It does not authorize forced termination of arbitrary Java, prove an external effect did not happen or release a physical permit early.

## Fire-and-forget

`@OneWay` is a bounded, local enqueue contract for a `void` method. It is not a durable queue or a remote delivery acknowledgement. Shutdown can discard accepted pending work. Use a durable queue when restart and delivery recovery are application requirements.

## Retry decisions

`@Idempotent` and `@Retry` do not authorize blind replay of entered business code. The managed client retry interceptor only retries a qualified pre-business connection/OPEN failure under a registered host profile. A lost reply after entry is a different case; use durable deduplication or explicit reconciliation where required.

Without annotations, use explicit invocation/proxy options and application protocols with the same semantics. See [asynchronous cancellation](../patterns/async-cancellation.md), [deduplication](../patterns/deduplication.md) and [failure semantics](../reference/semantics.md).

