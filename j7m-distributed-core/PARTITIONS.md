# Replicated partitions and migration

`PartitionStateMachine` wraps **one complete resource partition per consensus group**.
Application servers expose only `PartitionServer`/`LocalPartitionEndpoint`, never a raw
`ReplicatedResourceClient` bypass around the wrapper. `PartitionRouter` hashes keys into a
fixed canonical partition set; changing that set is a separate repartition operation.
`RoutedPartitionEndpoint` reads the independent topology quorum and retries MOVED/FROZEN
with the original business request identity. The nested resource owns durable deduplication.
The envelope identity must match the nested identity.

`TransferableResource` must preserve all state, results, deduplication fingerprints,
prepared work, journal retention floors, subscriptions and acknowledged cursors in its
snapshot. Its admission adapter binds every request/query to the authenticated scope.
Map resources and replicated queues implement this SPI. Prepared transactions/held locks
block pinning and the final freeze until resolved; the coordinator never aborts them.

## Durable handoff protocol

1. The independent `PartitionTopology` commits a handoff with expected ownership epoch.
2. Source commits COPYING and pins a complete immutable image at its logical position.
   Application writes continue and enter a bounded ordered command tail.
3. Target streams/stores image chunks through its own quorum. Every chunk survives restart.
   SHA-256 and complete resource snapshot validation precede final installation.
4. Target catches up original commands and effective leadership contexts in order. A suffix
   replays on an independent candidate and publishes one validated snapshot; malformed later
   entries cannot leave a partial live mutation. Source validation is syntactic, while candidate
   state evolves serially for subscriptions followed by ACK or queue time/heartbeat sequences.
5. Source commits FROZEN after rechecking prepared work. A final suffix and full-image digest
   equality establish that target includes every acknowledged source effect and recovery record.
6. Topology commits owner/epoch CAS. Only its HMAC proof, bound to partition, target, epoch,
   handoff and verified image digest, can activate the staged target. Source remains fenced.
7. Coordinator records COMPLETE. Restarting `PartitionMigration.migrate` with the same handoff
   resumes durable progress, including rebuilding unactivated staging from the frozen source
   after cutover. It never unfreezes an old owner because of a timeout.

Expired command-tail retention is explicit `CURSOR_EXPIRED`. Migration repins a fresh full
image, resets unactivated staging and retries at the new barrier. Old digest proofs cannot
activate the new image. The frozen source remains the recovery authority until activation.
Trust assumes non-Byzantine configured server members and administrative services, as Raft
already does; ordinary application principals cannot stage, reset, cut over or obtain proofs.

Logical resource positions advance independently of destination consensus indexes. The
resource leadership context is `max(transferred effective term, local committed term)` and
original suffix contexts are retained. Thus leases from source term 7 can renew on destination
term 1 without losing their fencing tokens. Ownership epochs independently reject the old
owner. Event partition identity stays fixed; new committed events use the new ownership epoch,
while retained old cursor identities and ACK metadata remain intact.

## Bounds, protocol and failure semantics

- Version 1 authenticated socket protocol and topology/partition snapshot magic/version;
  no native serialization or dynamic class loading. Unknown versions fail closed. Resource
  snapshots retain their own N/N−1 compatibility policy. Rollback to code without the partition
  wrapper cannot read its disk state and is rejected, rather than starting an empty group.
- Nested commands/queries: 128 KiB; transfer chunks: 128 KiB; suffix pages: 192 KiB;
  command tail: 16 MiB; full transfer image: 128 MiB; recent response receipts: 4096.
  Images and a resource snapshot coexist in bounded memory during validation. Configure the
  host consensus snapshot budget for the complete wrapper, including pinned image and tail.
- Topology retains at most 100000 partitions and 100000 handoff identities. Exhaustion returns
  OVERLOADED, never an unbounded history or a malformed persisted snapshot.
- Four socket workers, sixteen queued connections, replay cache 8192 entries with at most
  30-second authenticated deadlines. HMAC keys bind distinct configured principals. Nonloopback
  sockets require mutual TLS; clients verify server hostnames. Administrative and resource
  maintenance permissions are separate from ordinary application operations.
- Time-sensitive resource operations commit trusted measured time first. Clock uncertainty or
  offset beyond 500 ms, or unstable monotonic/wall-clock agreement, fails closed. Ordinary
  application callers cannot submit arbitrary time controls.
- Deadline/cancellation or a lost response can leave an UNKNOWN committed outcome. Retry the
  same business identity/bytes; topology epochs do not enter its fingerprint. No client timeout
  undoes a committed mutation. A frozen unfinished handoff can temporarily block its partition;
  independent resource groups remain available.

Rollback before adoption removes the additive partition endpoint/wrapper. After migration,
retain the frozen source and snapshots, recover using the same handoff, or reverse ownership
with a **new handoff and higher epoch**. Never restore stale topology or directly reactivate an
old source. Production release remains gated by the later development-plan nodes.

Tests: `PartitionStateMachineTest`, `PartitionMigrationProcessIT`, `PartitionTransportIT`,
`PartitionRouterTest`, and `TransferableResourceTest`. The process workload moves 100000 map
keys with concurrent updates and a nonempty suffix across three independent JVM/groups,
kills source/target/topology at different phases, verifies full-image equality and acknowledged
updates, resumes unfinished cutover, and retries stale routes with the same request identity.

## Public map, event and transaction endpoints

Use `PartitionResourceEndpoint(node, wrapper, map, scope, permissions, maintenance, clock)`
as the binding passed to `RemoteResourceServer`. It verifies both host and exact delegate
identity; no raw-resource bypass is needed. `RemoteResourceClient` configured with the known
source/destination endpoints for this same partition supports `EventSubscriptions` and
`RecoverableTransactions` unchanged. All value/status/prepared/enrollment reads, event polling
and snapshot+tail calls establish a fresh quorum barrier and execute under the active-owner
wrapper lock. Polling first commits healthy measured time. Staged targets and frozen sources
refuse reads as well as mutations, so endpoint failover cannot expose obsolete cursors or locks.
The existing streaming snapshot protocol carries projections larger than a generic partition
frame. Administrative/coordinator and application bindings retain distinct keys and permissions.

Admission and committed application both check business identities. A raced changed payload
with an already committed tenant/request fingerprint returns a deterministic CONFLICT receipt
before advancing the logical resource position; it never reaches the raw delegate's throwing
identity-conflict branch. Original results, subsequent valid operations and snapshot replay
remain usable. External generic partition callers cannot inject time controls even with a
maintenance binding; measured time controls are generated only by the trusted host.

Coordination and clock maintenance use the same general endpoint: `ResourceEndpoint.lease`
returns authorized live ownership/version/fence/effective epoch after healthy committed time,
and `advanceTime` commits host-generated measured time through an explicitly authorized
maintenance identity. `ReplicatedCoordination(ResourceEndpoint)` and
`LeaseTimeMaintenance(ResourceEndpoint, interval)` work with raw, partition-wrapped and remote
resources. Their original `ReplicatedResourceClient` constructors remain compatible forwarding
overloads. Remote LEASE and ADVANCE_TIME operations are additive to wire version 1; old peers
reject the unsupported capability explicitly. ADVANCE_TIME checks coordinator permission by
message type, so changing a textual operation cannot elevate an application principal. A
migrated member or leader remains queryable; renewals issue higher fences and stale protected
writes remain rejected. Unsafe host clocks fail lease reads, renewals and maintenance closed.
