# Distributed Resource Hosts and Profiles

Distributed state is hosted separately from the application's service directory. A discoverable service record does not make a JVM a voter, grant topology authority or create durable storage. Configure consensus membership and resource authorization explicitly.

## Network consensus

`NetworkConsensusConfig` identifies the node, authorized peer endpoints, initial voting set, private durable directory, authentication key, election/heartbeat timing, frame/snapshot bounds, TLS context and stable group identity. The shared authentication key grants peer authority and must contain at least 32 secret bytes. Outside loopback, mutual TLS is required.

The constructor enforces a minimum election timeout of 150 ms and a positive heartbeat interval less than one third of that timeout. These are validation bounds, not a recommendation to use the minimum in every network. Tune timing against measured latency and pauses, and test recovery before deployment.

The local deterministic `ConsensusServiceBuilder` is a simulation-oriented service builder. Do not present it as a substitute for deploying independent `NetworkConsensusNode` JVMs. Discovery never changes the voting set; a learner and an authorized voter are different roles.

## Resource access

`PartitionServer` exposes a partition or a separate topology group. Its immutable principal bindings select the tenant/scope, distinct authentication key and administrative/maintenance permissions. Application access must use a nonadministrative binding unless a specific controlled operation requires otherwise. A client cannot elevate itself by sending another scope string.

The optional `j7m-application-distributed` module bridges managed application declarations to installed distributed clients. A trusted host registers `ResourceBindingFactory` through `ManagedHostBindings`. Resource names select explicitly registered `ResourceEndpoint`, `PartitionEndpoint`, `ReplicatedQueue` or `ExecutionClient` capabilities.

| Resource family | Host profile concerns |
| --- | --- |
| Partition handlers | Encoded state schema, bounded reducer, partition ownership and epoch |
| Queue consumers/publishers | Queue identity, group, concurrency, credits, visibility and native permission |
| Inbox/outbox | JDBC connection ownership, marker namespace, destination identity and relay bounds |
| Event subscriptions | Snapshot/tail cursor, retention, payload bounds and acknowledgment boundary |
| Durable jobs/schedules | Execution backend, metadata backend, checkpoint codec, clock health and capacity |
| Sagas | Versioned definition, step/compensation shape, reconciliation authority and uncertainty policy |

A fixed resource binding can pin ownership while still allowing its native group's internal leader-following protocol. A dynamic binding can refresh ownership according to its policy. Neither creates a new durable operation identity after an uncertain result.

Use the [distributed patterns](../patterns/index.md) to select the application behavior, then configure its actual host profile. Preserve the same durable namespace, request IDs, cursors and definition identity through restart.

**Sources:** NetworkConsensusConfig (repository source: `j7m-consensus/src/main/java/org/j7m/middleware/consensus/NetworkConsensusConfig.java`), PartitionServer (repository source: `j7m-distributed-core/src/main/java/org/j7m/middleware/distributed/PartitionServer.java`), managed resource bridge (repository source: `j7m-application-distributed/README.md`).
