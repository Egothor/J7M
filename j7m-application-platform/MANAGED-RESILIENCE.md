# Managed resilience and observation

The operator installs `ManagedResiliencePolicy` in the managed host policy list. Annotations select finite, predefined profiles; application metadata cannot create tenants, capacities or exporters. Missing profiles, conflicting inherited declarations and unsupported active sites fail during preflight, before constructors. Method declarations override type defaults.

`@Bulkhead("dependency")` selects a shared dependency capacity within a declared tenant. `@Backpressure` uses the default bulkhead unless combined with an explicit bulkhead; an explicit maximum must match the installed capacity. Queues and wait time are bounded. Capacity remains occupied until actual execution and owned cleanup finish, including after reply timeout or cancellation. Another dependency or tenant has its own capacity.

`@RateLimited("profile")` consumes a token in a LOCAL fixed window after authorization and before bulkhead admission. A later overload does not refund that token. A rate rejection consumes no token. The host maps the actual authenticated invocation context to a predefined tenant; request arguments cannot select another tenant. DISTRIBUTED rate profiles are rejected because this adapter has no coordinated limiter backend.

`@CircuitBreaker("dependency")` belongs on the client-visible service contract. Install the corresponding `ManagedDependencyBreaker` as the `LiveServiceBindings` client interceptor, and provide the same registry to server preflight validation. Each registry has an explicit host tenant namespace and survives target refresh. An optional `ManagedClientRetry` is the inner delegate: one final failed logical connection call counts once, and OPEN never enters retry.

The breaker measures connection availability. Only qualified failures before business entry count. Authorization and application errors do not open it. Recovery uses a bounded number of readonly connector probes, never automatic business calls; an unsupported probe does not prove recovery. A successful probe allows the caller's explicit operation. This mechanism cannot infer whether a previously entered business effect completed.

The optional `j7m-application-distributed` module supplies `ManagedObservedPolicy`. `@Observed("profile")` selects a borrowed host-owned D-P15 observer. It creates one fixed `APPLICATION_HANDLER` span around actual user work and inner transaction/durable finalization, ending before transport encoding. Existing RPC instrumentation remains separate. Exporters remain owned by the host.

Only a validated W3C version-00 `traceparent` is propagated across managed children and subsequent RPC calls. Invalid carriers are dropped. Vendor state, baggage, principals and application payloads are excluded. Trace context carries no authorization. Attachments are thread-confined; detached span completion can occur on the worker that finishes the physical operation.

Verification uses actual TCP/gRPC target replacement, an OTLP collector, bounded half-open contention, tenant/dependency isolation and an asynchronous handler whose caller times out while its capacity remains occupied. The combined signed-module deployment and aggregate quality checks belong to A-P17.
