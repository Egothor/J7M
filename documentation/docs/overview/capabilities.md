# Capabilities and boundaries

Choose a feature for its actual contract, rather than treating all distributed
operations as interchangeable calls.

| Capability | What it provides | Boundary to design for |
| --- | --- | --- |
| Remote references | Interface proxies, callbacks and returned object references | A reference can expire or become unavailable |
| Discovery and refresh | Candidate lookup and opt-in replacement of live bindings | Discovery is not an atomic reservation of future availability |
| Managed annotations | Generated metadata, wiring and installed host policy selection | An annotation does not provision a backend or confer permission |
| Replicated resource state | Durable decisions within configured consensus groups | Progress requires the configured quorum and healthy storage |
| Queues | Receipt/generation-based delivery and controlled acknowledgements | Delivery is distinct from arbitrary external effect atomicity |
| Durable jobs | Claimed execution, progress, checkpoints and takeover | Recovery resumes explicit state, not a serialized Java stack |
| Sagas | Durable decisions and compensating steps | Compensation is application logic; uncertain effects need reconciliation |
| Resource transactions | Atomic decisions for participating replicated resources | Arbitrary databases and HTTP services are not implicit participants |
| JDBC inbox/outbox | Same-Connection database boundaries and recoverable delivery | Database commit and queue acknowledgement remain separate steps |
| Fencing | Rejection of stale ownership at cooperating effect boundaries | An external system must actually enforce the fence |

## Transport deployment boundary

The built-in gRPC and RMI providers currently advertise loopback addresses with
OS-selected ports. They support communication between JVMs on the same machine.
Their public constructors do not expose an advertised-host setting. Invoking
application services across machines therefore requires an explicitly integrated
transport provider or host solution that supplies routable endpoints. Do not
invent a configuration property to change the built-in providers' addresses.

The network bootstrap registry and native resource groups have their own explicit
address configuration; configuring them does not rewrite application transport
references. See [transports](../configuration/transports.md) and
[network deployment](../deployment/network-storage.md).

J7M is in pre-release development. This manual describes the current
implementation, not a promised roadmap. It makes no general claim of exactly-once
external effects, certification, automatic cross-system ACID transactions or
operating-system isolation. Performance depends on workload, deployment and
configured limits; [capacity guidance](../operations/capacity.md) explains how to
measure your own profile.

[Patterns](../patterns/index.md) · [Security](../security/index.md) ·
[Failure semantics](../reference/semantics.md)
