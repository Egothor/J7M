# Managed POJO partition resources

`@PartitionHandler` registers an encoded resource through the production
`TransferableResource → PartitionStateMachine → NetworkConsensusNode/PartitionServer`
path. Applications do not implement a framework interface:

```java
@SchemaType(id = "adjustment", version = 1)
public record Adjustment(@WireField(id = 7) String account,
                         @WireField(id = 8) int delta) {}

@PartitionHandler(resource = "balances", policy = "balances", version = 1)
@ShardKey("7")
@OrderedBy("7")
@DataLocality("near")
public class Balances {
    public Balances() {}
    public int initial() { return 0; }
    public int apply(int state, Adjustment command) { return state + command.delta(); }
    public int query(int state, String query) { return state; }
}
```

The reducer's state belongs to its partition. A multi-account map is not implied
by a shard field: applications requiring multiple balances inside one partition
must model that state explicitly using the supported data contract. Each callback
receives independently decoded state; query mutation and rejected command
candidates cannot change live encoded state.

## Trusted host setup

Install `ManagedPartitionFactory` under hosted kind `partition` in
`ManagedHostingOptions.hostedResources`. Its finite policy map contains
`ManagedPartitionProfile` values: native peer/storage/TLS configuration, original
bootstrap voter seed, stable partition identity and original epoch, fixed
resource scope, principal permissions, topology authority material, receipt
capacity and measured clock source. Application annotations cannot register a
factory or supply these credentials. Ordinary managed deployment still requires
a verified signed artifact; the manifest may declare zero RPC services.
`factory.addresses()` exposes the finite actual bind addresses, including port-zero
binds, for trusted host setup. This is metadata, not readiness or ownership.
Authenticated `PartitionClient.status(deadline)` supplies actual group, epoch,
phase and logical-position telemetry through quorum; unavailable quorum remains
an error rather than a fabricated healthy result.

Generated `ManagedPartitionDefinition` metadata provides the exact public
`initial()`, `apply(State,Command)` and `query(State,Query)` signatures. The factory
loads only the verified bundle classloader's finite generated `SchemaIndex`
providers. Named modules declare their generated providers through JPMS;
`opens` and reflective invocation of application methods are not required.

State, commands, queries and views use current A01 concrete scalars, byte arrays
or explicitly registered records. Reducers have config-only dependencies and
immutable configuration fields. They must be deterministic, bounded and free of
external effects, mutable static state, threads or ambient invocation-dependent
behavior. This is a programming contract, not a Java sandbox. Ordinary checked
or runtime command rejection stores an `ERROR` receipt without publishing its
candidate. Other application errors become safe native fail-stop failures;
fatal VM errors propagate. No mixed-program reducer upgrade or automatic Java
heap/schema migration is promised.

## Identity, routing and order

The portable program fingerprint includes the host-verified whole artifact,
actual backend code, Java runtime, complete method/data contract, fixed scope,
semantic configuration and limits. The native authenticated group/WAL identity
additionally binds partition identity, ORIGINAL bootstrap voter identities,
original epoch and native frame/snapshot limits. Keep the original voter seed
unchanged after 3→5 membership changes and restart. Local member selection,
addresses, directories, credentials and current/joint membership are excluded.
Different programs/configuration cannot exchange native replication frames;
a changed program cannot open the existing group's WAL/checkpoint.

`ManagedPartitionClient` uses an authenticated fresh topology source and finite
host-approved group endpoints. `execute` validates concrete command data;
`query(key, query, View.class, deadline)` also checks the requested Java view
against the actual program before RPC. `routingKey(command)` extracts the exact
scalar or required scalar numeric `WireField` identity. An explicit key must
match a declared selector. Related reads use the same key. No reflection path
or expression language is evaluated.

`@OrderedBy` must equal the shard selector. First-commit logical tickets and
complete terminal receipts are persisted in the encoded resource. A key's order
is a subsequence of its partition's committed order, not FIFO across partitions
or client wall-clock invocation order. Routing attempts/group/epoch are excluded
from the business request hash. After handoff, retry the identical business
identity and data: the original receipt is returned without applying again.
Its epoch is historical evidence, not current write authority.

Locality selects only an endpoint inside the current authoritative group.
A preferred endpoint receives at most 100 ms and one quarter of the remaining
budget, leaving time for same-group fallback. A security rejection is never
bypassed. Every nested live command/query names its expected group; a wrong
endpoint cannot acknowledge a mutation in another group.

## Lifecycle, transfer and bounds

The host prepares and arms all features with admission closed. Only successful
all-feature commit opens their shared guard. Drain closes admission immediately,
then waits for actual native apply and resource query/snapshot work. A timeout
returns false and retains the reducer/classloader. Native node close precedes
service-worker interruption, preventing shutdown from interrupting a committed
callback. Close workers are bounded (2 workers, queue 64), expire when idle and
use the host classloader.

Snapshots contain encoded state, complete request hashes/receipts and logical
cursor/epoch, protected by a checksum and exact program/scope validation.
Native initial INSTALL loads portable state into STAGED under privileged control;
only the topology HMAC activation proof enables the target's ACTIVE phase.
Suffix replay runs on independent transfer candidates. That candidate may accept
source-group routing evidence; ordinary live apply remains group-strict. The
old source stays fenced after cutover. Native wire and storage protocols are
unchanged.

The profile bounds inventory to 64 resources/policies, commands to 128 KiB,
encoded state to 12 MiB and complete resource snapshots/journals to 16 MiB.
Native checkpoint capacity is 128 MiB because the wrapper also retains transfer
images and tail. Retained request capacity is explicit, 1–10,000 without silent
eviction; new requests return uncommitted `OVERLOADED` when full. The existing
native transport's bounded nonce admission can independently reject overload;
an unavailable/expired reply remains `UNKNOWN`, never evidence of rollback.
The public A08 `ManagedPayloadCodec` format and 12 KiB cap remain unchanged.

`ResourceBindingFactory.partitionProgram(client)` exposes a prepared client
through existing `@J7mResource` DI. It supports `NEVER` binding selection because
the client itself resolves authoritative topology per operation. Closing that
binding revokes its scoped view before further codec/RPC entry and leaves the
borrowed host client/endpoints open. The client class is non-generic so it fits
the existing finite DI contract; its actual program/schema and checked query
method retain concrete data-type validation.

## Validation

Normal bounded tests include `ManagedPartitionResourceTest`,
`ManagedPartitionFactoryTest`, `ManagedPartitionClientTest`, `PartitionRoutingTest`,
`ManagedPartitionFingerprintIT`, `ManagedPartitionProcessIT` and
`ManagedPartitionMigrationIT`. They exercise actual generated POJOs/records,
separate JVMs and disk/network state, 3→5 voter full restart, wrong-program WAL,
incompatible live groups, locality fallback, native handoff and physically busy
drain. Existing A08 codec and deduplication snapshot tests also run after the
shared engine extraction. No hourly soak is part of normal `test`/`check`.

Before first use, rollback removes the optional factory and restores ordinary
hosting. After durable use, retain the exact program/profile and recover its
current WAL/snapshot; removing or silently replacing the reducer cannot migrate
its data. Retire storage only after an explicit authorized data transition.
