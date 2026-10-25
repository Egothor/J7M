# Cluster lifecycle management

This optional production module manages **hosted applications** across explicit middleware nodes.
It does not replace the middleware executable or infer consensus voters from discovery leases.
`ApplicationManagerHost` uses the real runtime and ApplicationManager; `HostingSupervisor` is the
extension boundary for another inspectable hosting environment.

`ClusterAdminClient` exposes authenticated inspect, plan, apply/upgrade, drain, rollback, operation
status and hash-linked audit. `ClusterNodeClient` implements actual bounded application invocation.
See [canary operation](CANARY.md) for the D17 layer and [versioned CP deployment profiles](CP-PROFILES.md) for the D18 configuration factory.

## Trust and compatibility

Operators distribute a trusted `ReleaseDescriptor` catalog to controllers and node agents. A remote
request chooses a release identity; it cannot supply compatibility or schema claims. Revisions must
use adjacent wire generations and mutually readable schemas. The durable node watermark records
the highest schema that a replacement may have written. A downgrade unable to read that watermark
is rejected before drain/replacement. This protocol never runs destructive schema migrations.

Every release is imported through `ArtifactRepositoryClient`: the D14 signer, complete digest and
ordered chunks are verified before local repository publication. The ApplicationManager additionally
applies the configured bundle verification profile. Production must select its strict bundle policy;
the real process tests use a signed D14 envelope and the development bundle profile.

Servers authorize exact operations per principal using independent >=32-byte keys. Outside loopback
mutual TLS is mandatory, with client hostname verification. Loopback HMAC-only mode is development
configuration. Requests/responses bind cluster identity and nonce; replay admission retains at most
65,536 nonces for at most 30 seconds, with explicit overload failure. Frames are <=256KiB, invocation
payload/results <=32KiB, fleet size <=16, with four workers and sixteen queued requests. No admin HTTP
routes or runtime class selection are exposed.

## Durable operation protocol

A plan binds the expected fleet version, exact ordered targets and trusted release. Apply uses a
stable operation identity. Reusing an identity with different arguments fails. Persisted operation
status is authoritative; a transport exception may mean UNKNOWN, so inspect status instead of
inventing a new operation identity. Planning leaves the fleet version unchanged; admitting and
advancing an operation increment it. Keep the original expected version when replaying an operation.

The controller coordinates one rolling operation through a D06 activity lease. All persisted progress
uses the exact current leadership epoch/fence. Before each drain it checks a fresh quorum barrier,
observed non-joint configuration, consistent configuration epochs and sufficient other ready hosts to
preserve a voting majority. A node's durable journal independently checks the live lease, highest
fence and epoch while serializing the physical effect. Drain closes actual admission, waits for admitted
calls, and invokes the application's drain hook. Replacement verifies bytes, deactivates the drained
instance and activates the new revision; verification checks actual readiness before proceeding.

A controller restart resumes the persisted phase after the former lease expires (15 seconds).
A node-side uncertain intent is reconciled against actual runtime state. An unprovable effect remains
HOLD/UNKNOWN; an operator must inspect the host and restore an inspectable supervisor state. It is
never blindly repeated. No local timer or cached fleet view authorizes effects. Revision-pinned
invocations check the current release and admission atomically at the real host.

Completed operation identities and their audit are retained in quorum resources. Provision resource
retention capacity; no silent eviction permits replayed destructive actions. Lease renewal occurs only
near expiration, rather than on every reconciliation tick. The worker exposes a monotonic failure
counter and durable HOLD error codes. An optional D15 Observability constructor exports fixed
`cluster_step` spans through its existing bounded collector buffer, without operation IDs or payloads.
Collector failure does not grant or block authority.

## Verification and reversal

`RollingClusterProcessIT` starts three actual consensus/resource/ApplicationManager JVMs, downloads
real signed v1/v2 bundles, rolls revisions while committing and reading data, kills/restarts the
controller, and verifies actual service bytes, operation replay, denied access, compatibility guards
and the retained audit hash chain. `ClusterAgentTest` and `ClusterCapacityTest` cover journal ownership,
closed-agent admission, malformed recovery, physical identity bounds and unsafe configuration views.
D17 `CanaryProcessIT` exercises the real same hosting path.

Run `:j7m-cluster-management:test :j7m-cluster-management:pmdMain
:j7m-cluster-management:pmdTest :j7m-cluster-management:javadoc` under the repository build policy.
Removing this additive module before use is reversible. After operations have been admitted, retain
its resource records and node journals: resume/reconcile forward or perform a compatibility-checked
rollback through the controller. Deleting journals is not rollback.

The real 10,000-call canary profile explicitly provisions the D06 resource listener's authenticated
nonce capacity to 65,536. `RemoteResourceServer` retains its compatible default of 8,192, exposes a
bounded 1..65,536 configuration and a numeric capacity rejection counter. A deployment must size this
30-second retention budget for all authenticated requests, including rejected follower probes; higher
application throughput does not bypass anti-replay admission.

Hosted handlers can read `ManagedCallContext.current()` during their actual runtime invocation,
or capture that immutable context for child work. Its deadline is the caller's authenticated absolute
budget; `cancellationRequested()` becomes true on deadline expiry or dispatch interruption. Runtime
lookup uses only the remaining caller budget. Handlers cooperate at safe boundaries; no forced abort,
rollback or remote early-cancel operation is implied. The host retains in-flight admission until the
handler actually returns, including after caller timeout, and clears/restores scoped context on both
success and exception. `ManagedCallCancellationIT` verifies this with a real signed application.
