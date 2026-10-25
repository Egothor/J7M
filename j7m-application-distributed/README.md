# Managed distributed resource bindings

This optional module bridges generated managed-component dependencies to the existing distributed-core APIs. The application-platform module has no dependency on distributed consensus. Install a trusted `ResourceBindingFactory` under `resource.name` in `ManagedHostBindings`; application annotations cannot register clients or grant authority.

`@J7mResource` names resolve only explicitly registered authorized clients. Registrations support `ResourceEndpoint`, `PartitionEndpoint`, `ReplicatedQueue`, and `ExecutionClient`. The processor preserves `binding.refresh` and `binding.policy` through the generated component index. Unknown names, wrong types and unsupported modes fail before component construction.

Fixed raw group registrations support NEVER. Dynamic partition registrations support NEVER, ON_FAILURE and ON_TOPOLOGY_CHANGE. NEVER pins ownership; the underlying group's own leader-following protocol remains active. ON_FAILURE refreshes ownership before the next endpoint operation after a MOVED, FROZEN or transport outcome. ON_TOPOLOGY_CHANGE reads topology before each operation. Ownership epochs never move backwards. The adapter does not replay an endpoint operation or replace its durable request identity.

Native queue and execution clients retain their existing same-request-ID MOVED/FROZEN protocol loops. These loops differ from replaying an arbitrary business method. UNKNOWN or an IOException does not cause the binding to create a new invocation, claim or effect identity. Existing queue receipts, fences, job checkpoints, event cursors and result deduplication belong to the underlying transferred state.

The host owns the supplied topology and group clients. Each binding owns only its revocable guard and refreshing adapter. Closing a component container revokes retained typed clients without closing borrowed group clients. Execution registrations can receive the existing context supplier and observer for automatic instrumentation.

Rollback is additive before adoption. After adoption retain the existing distributed state and snapshots; removing this adapter does not change their format. The real-process tests cover generated index → ManagedContainer → resource factory → TCP backend, queue receipt and job checkpoint continuity across handoff, and fixed-owner rejection. They do not qualify a new distributed protocol or a new fault-tolerance profile.

Managed native resource 2PC and H2 TCP local JDBC policies are documented in [TRANSACTIONS.md](TRANSACTIONS.md). Durable invocation replay and its distinct atomicity boundary are documented in [DEDUPLICATION.md](DEDUPLICATION.md).

## Managed POJO partitions

See [the encoded partition contract](PARTITION-HANDLERS.md) for generated pure reducers, actual native registration, shard/order/locality routing, bounded storage and physical drain.

## Managed durable workflows

See [POJO sagas](SAGAS.md) for generated callbacks, direct claimed delivery, scoped
contexts, durable compensation and authorized reconciliation. Ownership contracts are described in [OWNERSHIP.md](OWNERSHIP.md), and durable
event callbacks in [EVENT-SUBSCRIPTIONS.md](EVENT-SUBSCRIPTIONS.md).

See [durable schedule contracts](SCHEDULES.md) for persisted occurrences, measured
time, DST, bounded missed-fire handling and recovery between metadata and execution.

See [managed queues](QUEUES.md) for reactive complete-payload push, durable handler
guards and explicit DLQ redrive, and [JDBC delivery](JDBC-DELIVERY.md) for owned
single-Connection inbox/outbox recovery and the signed process qualification boundary.

See [durable POJO jobs](DURABLE-JOBS.md) for generated entry points, checkpoint and
progress state, pinned claims, physical drain and bounded execution capacity.
