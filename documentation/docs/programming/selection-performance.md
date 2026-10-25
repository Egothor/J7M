# Selection and application performance

Availability selection and performance selection answer different questions. Availability asks whether an eligible service can be used. High-performance selection ranks eligible candidates using currently available observations. Neither permits transparent repetition of an already entered business operation.

## What high-performance selection considers

The kernel's `HpSelector` filters references for an active lease, positive generation, trusted metadata and valid fencing/topology metadata. Known dead or unavailable peers are excluded. It then scores locality, peer load and reliability, observed latency, object invocation statistics and optional benchmark metadata. Lower scores are preferred; equal scores use deterministic runtime/object identity ordering.

The statistics are observations, not reservations. A candidate can become overloaded or disappear after selection. A benchmark hint does not prove that the candidate is globally fastest for the next operation. Apply freshness, trust and authorization at the relevant boundary, and handle invocation failure explicitly.

For application code, choose the binding policy illustrated in [availability and failover](../patterns/availability-failover.md): a stable binding, a refreshable binding, or an exact reference. Host policy decides which selection facilities and discovery providers are installed.

## Reduce remote round trips

This application fragment makes the cost visible. Assume `catalog` is an already authorized proxy and both operations are part of its public interface.

```java
// One network round trip for each item.
for (String id : ids) {
    render(catalog.describe(id));
}

// One bounded operation with an explicit application batch limit.
for (List<String> page : pagesOf(ids, 100)) {
    catalog.describeBatch(page).forEach(this::render);
}
```

`pagesOf` and `render` are application helpers. The second form requires a real `describeBatch` implementation and bounded response size; it is a design example, not an automatic J7M rewrite. Preserve per-item failure information when a batch can partly succeed.

| Practice | Reason |
| --- | --- |
| Reuse a managed proxy while its binding policy remains valid | Avoid repeated discovery and proxy construction |
| Keep remote contracts coarse enough for useful work | Avoid chatty property-by-property RPC |
| Bound payload, result, batch and queue sizes | Prevent one request consuming an unbounded resource |
| Move blocking application work off transport event-loop threads | Preserve progress for unrelated calls and cancellation |
| Use deadlines and cooperative cancellation | Bound waiting and make abandoned work observable |
| Measure with authentication and the actual codec enabled | Include costs present in deployment |

See [invocation semantics](invocation.md) for physical completion and [workloads](workloads.md) for bounded concurrency and partial results.
