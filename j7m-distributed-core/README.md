# Replicated distributed resources (D-P04–D-P07)

This opt-in JPMS module supplies the reference map resource, measured time and
leases, additive coordination, and participating-resource transactions. It
requires a real `NetworkConsensusNode`; it does not replace the compatible local
coordination module. It has no JavaSpaces dependency or external-database ACID
claim.

## Resource contract

`ResourceStateMachine` extends consensus callbacks with a quorum-barrier query.
`ReplicatedResources` synchronizes queries, application, snapshots and restore.
`appliedThrough` also tracks consensus configuration/no-op entries. The client
constructor rejects a state machine that is not hosted by its supplied node.
State changes are prepared on an isolated copy and published with the persisted
request result and event in one apply. A failure changes none of that batch's
resource values. Reads may observe a later committed state than their barrier.

Wire and snapshot formats start with version `1`. This is the first format;
there is no earlier resource format to migrate. Unknown versions are rejected
without replacing state. Future upgrades must retain version-one reads during
an N/N−1 rollout; deployment of a future incompatible writer is prohibited until
a migration exists. Snapshot integrity, durable publication and recovery are
provided by the hosting consensus layer. The snapshot includes the full result
fingerprint journal, event records, logical-time calibration flag, global fence,
leases, coordinator enrollment/decision and participant locks/staged operations.
The foreground identity budget is part of the snapshot and must match on restore.

Requests are scoped to an immutable authorization domain (tenant/service), with
an operation permission checked on every call. Remote service identities are
bound to that domain on the server, not supplied as resource keys by clients.
Request identity is the domain plus request ID. Retrying identical input returns
the original result; reusing the ID with different input is `CONFLICT`. Result
payload retention is 24 hours of committed logical time. Initial clock-epoch
calibration preserves retention of pre-clock results. After retention, `EXPIRED`
is returned without repeating effects and the large result payload is actually
discarded. Compact fingerprints remain until explicit future journal migration;
they are not silently evicted. The finite foreground identity budget is an
explicit admission limit: new requests return OVERLOADED without poisoning
consensus, while existing identities and internal time maintenance continue.

Capacity is bounded: 1 MiB command frames, 4096 arguments, 1024-character resource
keys, 4096 enrolled participants and staged operation arguments per transaction,
100000 each of values/leases/transactions/locks/foreground identities, a retained
tail of the latest 10000 foreground events, and 256 MiB
resource snapshots (the node may impose a lower snapshot bound). Foreground identity
capacity rejects new client work without committing partial effects or failing
the Raft group. Internal monotonic TIME controls use `appendControl` without a
permanent client dedupe/result/event entry. Provision or migrate before
exhausting the foreground identity bound; infinite foreground operation
retention is not claimed. No automatic background thread is started by the state machine or
client. Closing node/servers/time maintenance belongs to the application.

## Version-one operations

`ResourceCommand(operation, scope, requestId, arguments)` is an internal wire
vocabulary used by authorized `ResourceEndpoint` implementations. Applications
normally use the typed resource, coordination and transaction facades.

| Operation | Arguments | Result / invariant |
|---|---|---|
| BATCH | repeating key, expectedVersion, value | Atomic CAS; missing version is 0. |
| TIME | measuredTime, authenticatedReference, uncertaintyMillis | Monotonic committed time; measured server evidence required. |
| ACQUIRE | leaseKey, owner, durationMillis, 0 | Live ownership is exclusive; issues increasing fence. |
| RENEW | leaseKey, expectedVersion, owner, durationMillis, 0 | Exact live version/owner; fresh fence. |
| CANCEL / EXPIRE | leaseKey, expectedVersion | Exact version; EXPIRE additionally requires committed expiry. |
| FENCED | leaseKey, fence, epoch, targetKey, value | Rejects any stale lease/fence/epoch. |
| BEGIN | txId | Durable PREPARING coordinator record. |
| ENLIST | txId, participantId | Enrollment only before SEAL/decision. |
| SEAL | txId | Freezes enrollment before validating all prepare votes. |
| DECIDE | txId, COMMITTED or ABORTED | Immutable durable decision and decision epoch. |
| LOCK_READ | txId, key | Durable read lock before returning the observed value. |
| LOCK_RANGE | txId, prefix | Conservative full-domain lock, preventing phantoms. |
| PREPARE | txId, operation sequence | Persists staged writes and retained locks. |
| COMMIT / ROLLBACK | txId, durableDecisionEpoch | Idempotent decision delivery from the authorized coordinator. |

Transaction operation sequences use `READ,key,expectedVersion`,
`WRITE,key,value`, and `RANGE,prefix`. For strict two-phase locking, obtain reads
with `RecoverableTransactions.read` and `lockRange` before inspecting values.
Read locks are conservative exclusive locks. `prepare` also validates supplied
read versions atomically while acquiring locks. Normal CAS and fenced writes
respect prepared and pre-prepare transaction locks. Conflicting transactions are
selected as abort victims before any durable COMMIT. Prepared locks never time
out or disappear on restart; loss of coordinator authority may block them.

The coordinator persists enrollment before contacting participants and seals it
before checking votes, preventing a late-enlistment/commit race. Only after every
enlisted participant reports PREPARED can it record COMMIT. The configured
coordinator service is trusted to validate votes and deliver its own durable
journal decision; ordinary application identities cannot send decisions. A lost
coordinator or transport reply produces unavailable/UNKNOWN or IN_DOUBT, never
heuristic success. Recovery explicitly scans unfinished participants and reads
the authoritative coordinator decision. Recovery of PREPARING/SEALED without a
decision leaves locks intact until a durable abort or commit is recorded.

## Time and ownership

`MeasuredClockHealth` compares wall-clock evolution with monotonic elapsed time,
reference offset, and uncertainty. The authenticated reference/uncertainty
suppliers are deployment dependencies: using the local clock itself as its own
reference is not a production clock-health implementation. Offset/uncertainty
above 500 ms, a wall-clock jump, or missing health suspends ownership grants,
renewals, protected mutations, authoritative lease queries, time advance and expiration. Two stable aligned samples restore clock
health; committed time still never decreases. Client deadlines never authorize
expiration. TIME proposals are checked against fresh server measurements.

`LeaseTimeMaintenance` commits safe time automatically at a configured interval
up to one second. Run it on replicas with the configured trusted time source;
follower ticks fail without mutating state. It exposes successful/failed tick
counters. Ownership operations, protected mutations and authoritative lease queries first
commit a measured time tick, avoiding
leases created against a stale bootstrap epoch. Expiry becomes visible through
committed time; no timer alone grants ownership. Use the exact returned fence
and epoch on every protected mutation. Acquisition/renewal encode a zero epoch sentinel;
the state machine obtains the authoritative epoch from the committed log entry
term, keeping retry command bytes stable across leader changes.

## Network and lifecycle

`RemoteResourceServer` binds principals to authorized local endpoints and
coordinator permissions. `RemoteResourceClient` resolves configured replica
candidates per call and preserves exact request identity/input. HMAC authenticates
bounded requests and replies, with replay rejection, deadlines and bounded
workers. Cleartext is permitted only for the loopback development profile;
non-loopback endpoints require TLS with authenticated service credentials.
Local callback methods perform no network or wall-clock operations.

Cancellation is cooperative: interruption/deadline before submission prevents
that submission; after submission the outcome may be UNKNOWN and recovery must
query/retry the same identity. It cannot roll back an already committed effect.

Rollback is removal of the additive module and its opt-in wiring before use.
After writing durable version-one state, preserve it and use a compatible
forward fix; older local coordination services cannot read this new state.

## Validation

Run `:j7m-distributed-core:test` using the repository's supported Gradle/Java.
The suite includes actual authenticated loopback sockets, three replica JVMs
with concurrent CAS, deterministic snapshot hashes and full restart; independent
coordinator/participant JVMs killed at prepare/decision/ack boundaries; durable
read/range locks and coordinator-loss recovery; and real JVM clock jumps with
automatic expiry. These are development-profile measurements, not a production
release approval. Independent review and the parent D-P00–D-P07 gates still
control acceptance.

## D-P09 durable events

`EventSubscriptions` uses the same authorized `ResourceEndpoint` locally and over
`RemoteResourceClient`. `subscribe`, `ack` and `resume` are committed source
commands; `poll` replays from the durable acknowledgement. Handle duplicate
`(partition, epoch, sequence)` identities in the consumer. Acknowledgement does
not make an arbitrary external side effect transactional. Subscription leases use
healthy committed logical time; unsafe clocks suspend time-sensitive admission.

`EventJournal` is reusable by other deterministic resources. It owns no thread or
wall clock. Include its copy in the resource's atomic state publication and its
versioned bytes in the complete resource snapshot. Retention has both count and
payload-byte bounds; map resources retain at most 10,000 events / 16 MiB and
4,096 subscription records. Poll scans at most 256 positions, targets 512 KiB of
payload and permits one larger event alone. Filters advance `through` across
excluded records. `CURSOR_EXPIRED` is explicit: take a new source snapshot and
subscribe from its cursor; history is never silently skipped. IDs and leases
remain subject to the source's documented foreground deduplication budget.

`snapshotAndTail` captures the authenticated scope's map projection and journal
cursor under one source lock after a fresh quorum read barrier. Projection format
is `int version=1`, `int count`, followed by `UTF key`, `UTF value`, `long version`.
Use the returned cursor to subscribe to subsequent changes. The remote adapter
streams at most 64 MiB in independently authenticated 64 KiB chunks, never a giant
frame. Frames are bounded to 2 MiB to carry a maximum source command plus event
metadata. Source journal payloads decode with `ResourceEvent.decode`: inspect the
persisted status before applying a change, or apply `ResourceEvent.changes()`
upserts directly. Version-two events carry effective materialized commands, not
the exact original request: a successful COMMIT contains its full BATCH write
delta and committed value versions even when PREPARE preceded the snapshot.
Version-one event payloads remain readable, except historical successful COMMIT
payloads lacking a write delta explicitly report `SNAPSHOT_REQUIRED`. A BATCH event represents
all its CAS triples, not just one key. This preserves every changed key while
allowing efficient large batches. The journal operation remains the original
operation (for example COMMIT), even when its effective payload is a BATCH. A
consumer rebuilding the complete map must subscribe with an empty operation
filter; a BATCH-only filter intentionally excludes COMMIT and FENCED changes.
Other resource implementations define their
own versioned payload contracts.

Transfer snapshots include journal history, floors, subscription progress,
request fingerprints, results and prepared locks. `ownershipChanged` binds map
journal identity to the wrapper's stable partition and committed ownership epoch;
old cursors remain valid across a handoff while newly emitted events use the new
epoch. Version-two map snapshots read and upgrade version-one snapshots without
losing existing values, results, transaction state or legacy event metadata.
Version-one code cannot read version-two output: retain a pre-upgrade data backup
for downgrade, or apply a compatible forward fix. Do not reopen newer durable
state with an older binary. The tests exercise upgrading a version-one image and
restoring version-two images, which is the supported forward recovery path.

## D-P11/D-P12 execution resources

`ExecutionStateMachine` is a reusable transferable production resource hosted by
`PartitionStateMachine`, `PartitionServer` and authenticated `PartitionClient`.
It performs no external calls. `ExecutionClient` resolves committed topology
routes; `DurableJobs`, `DurableSagas` and `DeduplicatedRpc` expose typed operations.
An application supplies its worker/handler implementation and opaque input; the
middleware persists ownership, protocol decisions and results. This is a generic
job/saga/RPC protocol, independent of a particular application or demo.

Jobs persist input, checkpoint, result, version, owner, expiry and monotonic fence.
`claim` takes unowned/expired work; every checkpoint and completion checks the
exact live owner/fence. Completion can atomically apply protected-map CAS triples
`key, expectedVersion, newValue` in the same state machine. A cancelled or replaced
owner cannot mutate that protected resource. Workers performing external effects must use a stable external idempotency
protocol keyed by the job identity, or compose `DeduplicatedRpc.claim/unknown`
for explicit ambiguity and reconciliation. A new job claim alone does not prove
that a previous external call had no effect. Claim and status return the persisted
W3C context for a worker to attach before creating child spans.

A saga's step intent commits before its external handler runs. `step` records the
stable external idempotency key and compensation input; `completeStep` records
its established outcome and optional protected effects. The terminal-step flag
survives takeover. Compensation attempts commit before execution, proceed in
reverse completed-step order, and retain attempt count, exponential backoff and a
configured limit. Non-idempotent in-flight forward/compensation intent becomes
`UNKNOWN` on takeover, requiring `reconcile`. Confirmed compensation success is
`COMPENSATED`, not permission to execute it again. Ordinary job operations cannot
bypass the saga protocol. `status` selects the persisted current step, including
an earlier reverse compensation. `stepStatus` and bounded version-consistent
`steps` pages expose every persisted key, action, phase, attempt and due time;
workers recover without an application-side step cache. The hosting principal's administrative capability is
mandatory for reconciliation; it cannot be supplied as a request argument.

RPC `invoke` atomically records the first input SHA-256 hash, result/error and
protected CAS effects. Duplicates return the stored outcome; different input is
`CONFLICT`. `claim`/`complete` expose two-phase `IN_PROGRESS` work. The first claim
has `acquired=true` and its durable `claimIdentity`; a different claimant gets
`acquired=false`. An external invocation has no transactional external protocol:
a lost reply or recovery query is `UNKNOWN`, never permission to repeat an
unprotected effect. Every physical claim call has a fresh delivery-attempt identity. Its network
retries preserve that identity, while a later or concurrent call with even the
exact same operation ID receives `acquired=false`/`UNKNOWN` for unresolved
external work. The first permit is not exposed by ordinary result queries or
events. Applications must reconcile ambiguity instead of repeating its handler. `unknown` records ambiguity,
and privileged `reconcile` records independently established outcomes.

RPC retention uses committed logical time. Once expired, both a new operation
for that invocation and an exact operation-result replay return `EXPIRED`.
Identities remain tombstones and cannot silently execute again. Public status
queries first refresh healthy host-measured committed time using a replay-safe
control that consumes no foreground request slots. External errors/transport
uncertainty never imply rollback of an already committed mutation.

Saga `resolveStep` distinguishes established no-effect failure from `UNKNOWN` under the
current owner fence. A forward failure atomically requests compensation and blocks later steps.
An uncertain step retains its forward/compensation phase. `requestCompensation` is an
administrative compare-and-set on the observed run version: it persists the decision and revokes
the old owner in the same mutation, including explicitly selected completed runs. Every entry
into compensation preserves that mode through takeover. Administrative `reconcile` also requires
the exact run version captured with the evidence before any callback; stale forward evidence cannot
resolve a later uncertain compensation. Identical operation retries retain the original result.

Execution snapshots use explicit version-three binary encoding, never native Java
serialization. In this pre-release implementation the reader accepts only the current format; it
preserves the durable compensation decision and refuses unknown/corrupt images before publishing state. The resource is bounded to 10,000 retained protocol identities,
10,000 jobs/sagas, 10,000 RPC identities and protected keys, and 16 MiB of retained
command/result/metadata accounting. Result payloads are charged, so repeated tiny
checkpoints of a large job cannot exceed snapshot capacity invisibly. Overload
rejects the entire proposed state/effect publication. Monitor the exposed
`statistics` counters and provision partitions before exhausting retention.
The journal retains at most 10,000 events / 16 MiB. `ExecutionEvents` supplies
scope-authorized subscriptions, durable acknowledgements and atomic bounded
public job/saga projections with exact tail cursors; oversized projections fail
explicitly and require a smaller partition. Expired cursors require a new view.

`ExecutionClient.Observer` provides automatic operation lifecycle instrumentation
without an observability vendor dependency. Its context supplier accepts only
bounded W3C traceparent/tracestate; submit/start/invoke persist the first committed
carrier. Trace metadata is excluded from business fingerprints, so retries with
fresh child spans cannot change business identity. `statistics` returns actual
scope-local counters, including RPC replays, and no high-cardinality identity
labels. A production observability adapter lives in `j7m-integrations`.

Before first use, rollback removes the additive execution classes/wiring. Once
used, retain complete durable images and deploy compatible readers/forward fixes;
do not discard request tombstones to reclaim capacity. Tests exercise full-image
restore plus actual three-JVM cluster restart, lease takeover, stale fencing,
protocol boundaries, parallel duplicate invocation and quorum-commit-before-reply
crash. Independent review and the development-plan gates govern acceptance.
