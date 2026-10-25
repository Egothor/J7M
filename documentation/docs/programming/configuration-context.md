# Configuration and current context

Configuration belongs to the host; a component declares which values it needs. Keep business configuration, trusted capability registration and secrets distinct. Selecting a named policy is not permission to create that policy.

```java
@J7mComponent
public final class Pricing {
    private final String currency;

    @Inject
    public Pricing(@ConfigValue(value = "pricing.currency",
                               defaultValue = "EUR") String currency) {
        this.currency = currency;
    }
}
```

Use `@ConfigProperties` for a supported record-shaped configuration group. Generated metadata describes the key or prefix, required/default values and refresh intent. The host validates a complete configuration update before publishing it.

## Stable values and live values

A direct injected value is an initial snapshot. For supported refreshable configuration, inject a `ManagedProvider<T>` and opt into refresh. This avoids concurrently mutating arbitrary fields while application code is using them. The provider observes host-approved snapshots; it does not open a configuration server itself.

## Invocation-local information

`@CurrentInvocation`, `@CurrentPrincipal` and `@CurrentApplication` select host-supplied contextual values. Do not retain an invocation or principal in an activation-scoped object as if it were a permanent caller identity. Resolve it inside the current owned call, using the supported provider/context boundary.

For application work that outlives the current Java stack frame, use the current invocation's `InvocationControl`:

```java
return J7mContext.currentInvocation().control().submit(() -> calculateQuote());
```

The example assumes an admitted managed invocation and a supported asynchronous result. Arbitrary executor tasks do not acquire managed context merely because their parent was managed. Use the control's contextual executor for explicit continuations that need the same ownership.

## Without annotations

Pass immutable settings and explicit call parameters to ordinary objects. At the host layer, use `LiveConfiguration` and its binding APIs when dynamic configuration is required. Do not replace an explicit `Principal` parameter with a global static variable or an inheritable thread local.

## Refresh a bounded business setting

An embedding host can use this explicit API for a quote-cache lifetime. Imports are `java.util.Map`, `java.time.Duration` and `org.j7m.middleware.application.components.LiveConfiguration`:

```java
try (var settings = new LiveConfiguration(1,
        Map.of("quotes.ttl", "PT30S"));
     var ttl = settings.bindValue(Duration.class, "quotes.ttl", true, "", true,
         value -> {
             if (value.isNegative() || value.isZero()) {
                 throw new IllegalArgumentException("Positive cache lifetime required");
             }
         })) {
    Duration firstLifetime = ttl.get(); // 30 seconds
    settings.update(2, Map.of("quotes.ttl", "PT10S"));
    Duration nextLifetime = ttl.get(); // 10 seconds for subsequently cached quotes
}
```

Each update supplies the complete configuration snapshot and a strictly advancing source version. Invalid conversion or validation preserves the accepted version; it does not publish half an update. Use the returned duration to set the expiry of a new cache entry. Existing entries keep the lifetime your application already assigned. The owner and binding close together when the host shuts down.

See [configuration](../configuration/index.md) and [invocation semantics](invocation.md).
