# Application Configuration and Named Policies

The application manifest declares identity, runtime requirements, services, security metadata and resource requests. The host validates those requests against installed capabilities before constructing managed components. See [manifest reference](../reference/manifests-policies.md) for the actual JSON shape.

Named policies are host-owned objects. `@Retry(policy=...)`, resource bindings and related annotations select an installed policy; application metadata does not create its implementation or credentials. Missing names, mismatched types and unsupported combinations must be resolved during activation, rather than left for the first production request.

## Stable and live application values

`LiveConfiguration` accepts complete, monotonically versioned string snapshots. It provides typed scalar values and immutable public configuration records. Supported scalar forms include strings, primitive wrappers, `Duration` and enums. Boolean conversion accepts only `true` or `false`; nonfinite floating-point values are rejected.

```java
@ConfigProperties(prefix = "orders", refresh = true)
public record OrderSettings(
        @ConfigValue(value = "batch-size", defaultValue = "32") int batchSize,
        @ConfigValue(value = "timeout", defaultValue = "PT2S") Duration timeout) {
}
```

This is a declaration fragment. A refreshing declaration is consumed through the managed provider contract, not an ordinary mutable field that is silently rewritten. A properties record gives one coherent value/version when several fields must change together. An optional primitive needs an explicit default; use a nullable wrapper when absence is meaningful.

Every source update is a full replacement snapshot. An omitted key is a removal. The service converts and validates all refreshing registrations before publishing one state; invalid updates retain the previous accepted version. Stable bindings keep their initial value. Validators are application code: keep them side-effect-free and bounded.

## Source integration and cleanup

`ConfigurationSource` is the host adapter SPI. It supplies an atomic initial subscription plus complete subsequent snapshots, and owns its provider-specific connection/reconnect behavior. `LiveConfiguration.attach` supports one active source; closing the attachment detaches it. Late deliveries from a detached source cannot publish.

The host owns the configuration service. A component container owns its bindings and detaches them when it closes, without closing a borrowed host service. This configuration mechanism does not perform service discovery, object failover or business-method replay.

**Sources:** live configuration implementation (repository source: `j7m-application-platform/src/main/java/org/j7m/middleware/application/components/LiveConfiguration.java`), configuration contract (repository source: `j7m-application-platform/CONFIGURATION.md`).
