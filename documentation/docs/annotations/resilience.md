# Availability and resilience annotations

Availability declarations select or describe behavior at a supported host boundary. They do not make an external system transactional and do not turn a failed business operation into a safe retry.

## @HighAvailability {#highavailability}
Declares high-availability selection intent. **Targets:** type and method. **Attributes:** none.

```java
@HighAvailability
public interface Catalog {
    String find(String product);
}
```

The configured runtime/selection path determines eligible candidates and health information. Use [live service bindings](components.md#serviceref) when an application needs explicit refresh behavior. Do not infer durable failover or universal replay from this marker.

## @PreferLocal {#preferlocal}
Declares a preference for an eligible local candidate. **Targets:** type and method. **Attributes:** none.

```java
@PreferLocal
public interface ProductCache {
    String get(String key);
}
```

Locality is a preference, not permission to ignore contract, trust or readiness. A deployment may have no matching local instance. Explicit code can apply an eligible-candidate selection policy without this metadata.

## @CircuitBreaker {#circuitbreaker}
Selects a host-owned client connection-availability breaker. **Targets:** type and method. **Attribute:** `value = "default"`.

```java
@CircuitBreaker("payments")
String paymentStatus(String reference);
```

Install the corresponding resilience policy and binding integration on the host. Qualified dependency connection failures affect breaker state; ordinary business rejections do not mean the dependency is down. The annotation does not create the breaker profile.

## @Bulkhead {#bulkhead}
Selects a host-owned dependency admission budget. **Targets:** type and method. **Attribute:** `value = "default"`.

```java
@Bulkhead("payments")
@Async
CompletionStage<String> paymentStatus(String reference);
```

A permit remains owned until actual physical completion, including tracked asynchronous work. A timed-out public reply must not free the permit while the protected work still runs. Profiles are finite and host-local unless an explicitly documented resource provides a wider scope.

## @SwitchLevel {#switchlevel}
**Reserved metadata only.** **Target:** method. **Attributes:** none.

```java
@SwitchLevel
public void documentedRunLevelTransition() { }
```

This shows the available spelling, not an executable lifecycle feature. The current runtime does not consume it to switch levels, start services or change readiness. Use the actual lifecycle and administration APIs for current operations.

## Example: isolate payment-status traffic

A pricing outage should not consume every application worker. Put the resilience contract on the dependency interface:

```java
@HighAvailability
public interface PaymentStatus {
    @CircuitBreaker("payments")
    @Bulkhead("payments")
    @Async
    CompletionStage<String> paymentStatus(String reference);
}
```

The host registers a finite `payments` breaker and bulkhead and supplies the supported binding integration. If connection failures open the breaker, new calls fail promptly according to that profile. A rejected payment remains a business result, not evidence that the dependency is unavailable. A slow asynchronous call retains its permit until physical work settles, even if its public reply times out.

Use `@PreferLocal` on a cache contract when an eligible local instance is preferable; it must still pass trust, contract and readiness checks. Refreshable replacement is configured through `@ServiceRef`, not implied by `@HighAvailability`.

`@SwitchLevel` is reserved. Its declaration above has no runtime action to demonstrate: use the actual lifecycle or administration operation to drain/start an application. See [availability and failover](../patterns/availability-failover.md).
