# Invocation annotations

Place the client-visible calling contract on the public interface. The host installs `ManagedCallingPolicy` and any required client interceptor. Ordinary self-invocation does not introduce a new middleware boundary.

## @Async {#async}
Declares a logical asynchronous result. **Targets:** method and type. **Attributes:** none.

```java
@Async
CompletionStage<String> quote(String product);
```

Supported remote return shapes are concrete `CompletionStage<T>` or `CompletableFuture<T>` with an allowed payload `T`. The transport sends the payload, not a future object. Use `InvocationControl.submit` or its contextual executor for owned child work; arbitrary executors do not inherit managed context.

## @OneWay {#oneway}
Declares bounded local enqueue semantics. **Target:** method. **Attributes:** none.

```java
@OneWay
void recordPublicNotification(String text);
```

The method must return `void`. Success means locally enqueued, not durably delivered or acknowledged by a remote consumer. It conflicts with Async and Retry. Use a durable queue for restart-safe delivery.

## @Callback {#callback}
Documents an interface or parameter intended to pass by remote reference. **Targets:** type and parameter. **Attributes:** none.

```java
@Callback
public interface QuoteListener {
    void quoted(String quoteId);
}
```

Export the callback through the normal runtime reference path and keep its owner alive. The marker alone neither exports an object nor lets the remote caller bypass callback authorization.

## @Deadline {#deadline}
Declares a method's timeout metadata. **Target:** method. **Required attribute:** positive `millis`.

```java
@Deadline(millis = 750)
String availability(String product);
```

The effective budget can be shortened by caller, parent and host settings. It includes connection and queued work. Expiry ends waiting; it does not establish rollback or prove physical work has stopped.

## @Cancellable {#cancellable}
Declares application cooperation with invocation cancellation. **Targets:** method and type. **Attributes:** none.

```java
@Async
@Cancellable
CompletionStage<String> rebuildIndex(String partition);
```

The implementation must observe its current control at safe points. The host retains physical ownership until actual work exits. External I/O needs its own cancellation/idempotency protocol.

## @Retry {#retry}
Selects bounded retry metadata. **Target:** method. **Attributes:** `maxAttempts = 0`, `policy = "default"`. The count is additional attempts after the initial call.

```java
@Idempotent
@Retry(maxAttempts = 2, policy = "catalog-open")
String lookupCatalog(String key);
```

The managed client interceptor retries only a qualified connection/OPEN failure before Java business entry, with a registered finite host profile. It preserves the original target, principal, arguments and deadline. A business exception or lost reply after entry does not authorize replay.

## @Idempotent {#idempotent}
Declares a method's safe-repeat application contract. **Target:** method. **Attributes:** none.

```java
@Idempotent
String readOrder(String orderId);
```

This is not durable deduplication. The application must actually satisfy its contract, including its external effects. Combine a stable operation key and the appropriate durable protocol when duplicates must be recognized across restart.

## @NonIdempotent {#nonidempotent}
Makes unsafe repeat intent explicit. **Target:** method. **Attributes:** none.

```java
@NonIdempotent
String chargeCard(String orderId);
```

It conflicts with an idempotent retry declaration. A timeout on this operation needs an application-specific status lookup or reconciliation, not a blind second charge.

## Example: read safely, reconcile unsafe effects

These public contracts distinguish a bounded query from a payment command:

```java
public interface CatalogReader {
    @Idempotent
    @Deadline(millis = 750)
    @Retry(maxAttempts = 2, policy = "catalog-open")
    String lookupCatalog(String key);

    @Async
    @Cancellable
    CompletionStage<String> rebuildIndex(String partition);

    @OneWay
    void recordPublicNotification(String text);
}

public interface Payments {
    @NonIdempotent
    String chargeCard(String orderId);

    @Idempotent
    String paymentStatus(String orderId);
}
```

The host installs the calling policy and finite `catalog-open` retry profile. A qualified failure before business entry can trigger at most two additional attempts within the original deadline. A business failure or lost reply after entry is returned for application handling.

Suppose `chargeCard("42")` times out. The client asks `paymentStatus("42")` and follows the payment protocol instead of submitting another charge. For index work, implement cancellation safe points and submit owned asynchronous children through the current invocation control; returning a future alone does not stop work when a caller times out.

The one-way notification is suitable for optional public telemetry: return means bounded local enqueue, so loss on process failure is acceptable. Use a durable queue when loss is unacceptable. Export a `QuoteListener` callback normally before passing it to a remote service, and keep its registration alive until callbacks finish. See [invocation semantics](../programming/invocation.md).
