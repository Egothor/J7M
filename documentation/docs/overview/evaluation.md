# Evaluate J7M for an application

J7M is a good fit when a Java application benefits from distributed object identity, callbacks, discoverable service instances and managed application lifecycles. Select the smallest set of resource services that provides the required failure behavior.

| Requirement | J7M approach | Compare with |
| --- | --- | --- |
| Address a particular live object, return a child object, or pass a callback | Typed proxies and concrete `ObjectRef` identities | Static RPC when stable service endpoints and value messages suffice |
| Find and replace available service instances | Discovery, selection and refresh policies | A service mesh for network routing; application semantics still belong to the application |
| Recover accepted work after process failure | Durable queue/job backend with explicit acknowledgment and fencing | A message broker when durable messaging is the principal requirement |
| Host isolated Java application activations | Nugget deployment, lifecycle, policy and resource ownership | An application server/container when its existing programming model is already sufficient |
| Coordinate durable distributed ownership | Native quorum resources and enforced fences | A coordination service when that is the only required capability |

These choices can coexist: a J7M application can integrate with an external database, broker or identity provider. An adapter must preserve the required semantics; naming an external product does not establish equivalence.

## A useful proof of concept

Use a representative business operation rather than a ping benchmark. Define its payload limits, completion deadline, authorization, retry policy and acceptable recovery window. Then demonstrate:

1. Two independent JVMs discover and invoke the intended public interface.
2. Unauthorized callers and incompatible schemas fail before business effects.
3. A lost reply leaves an explicitly understood outcome; retry does not silently repeat an unsafe effect.
4. Process termination and restart preserve the promises of the selected durable backend.
5. Queue capacity, overload, cancellation and shutdown remain bounded.
6. Operators can identify ownership, availability and recovery from safe status and metrics.

Measure throughput and tail latency with the intended transport, security, disk and deployment topology. Results from an in-memory scheduler or buffered local log do not establish durable multi-JVM performance. See [capability boundaries](capabilities.md), [failure injection](../testing/failure-injection.md) and [performance guidance](../programming/selection-performance.md).
