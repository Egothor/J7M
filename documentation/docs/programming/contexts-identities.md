# Context propagation and identities

J7M separates request relationships, authenticated callers, application activations and remote objects. Preserve that distinction in logs and authorization: a trace ID helps correlate work, but it does not grant permission to perform it.

## Relate nested work

The following example is adapted from the context contract tests and uses types in `org.j7m.middleware.api.reliability`.

```java
CausalContext root = new CausalContext("trace-a", "span-a", "");
CausalContext child = root.child();
assert child.traceId().equals(root.traceId());
assert child.parentSpanId().equals(root.spanId());
assert !child.spanId().equals(root.spanId());

CorrelationContext request = new CorrelationContext("corr-a", "invoke-a", "");
CorrelationContext nested = request.nestedInvocation();
assert nested.correlationId().equals(request.correlationId());
assert nested.parentInvocationId().equals(request.invocationId());
```

`CausalContext.root()` creates a root with no parent. Child creation preserves the trace and allocates a new span. Correlation context preserves a business/request correlation ID while assigning each nested invocation a distinct invocation ID. This is a source-derived fragment; Java assertions require `-ea` when used as executable checks.

The invocation/transport integration propagates these ID-only contexts. Use the host's [configuration and context facilities](configuration-context.md) for managed invocation access; constructing a context record alone does not install it as the current caller context. Keep credentials, user documents and payloads out of these IDs and metadata.

## Use the right identity

| Identity | Identifies |
| --- | --- |
| `ApplicationIdentity` | Exact application coordinate, display name and declared trust domain |
| `ApplicationInstanceIdentity` | One active application instance and its hosting runtime |
| `DeploymentIdentity` | The deployment represented by the hosting operations model |
| `ActivationIdentity` | A particular activation of deployed application code |
| `RuntimeServiceIdentity` | A service export associated with its runtime/application activation |
| `CallerIdentity` | Caller information available to application invocation policy |
| `RuntimeIdentity` | The middleware runtime |
| `ObjectRef` | One concrete remote object incarnation and its routing/lifetime metadata |

Do not use a display name as an authorization key or collapse two activations because they have the same application coordinate. Conversely, a new trace/span does not create a new application or remote object identity.

Digests, coordinates, endpoint metadata and object references are identifiers rather than bearer credentials. Server-side authentication and authorization remain authoritative, including local proxy dispatch. See [services and references](services.md), [security](../security/index.md) and [monitoring](../operations/monitoring.md).
