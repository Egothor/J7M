# Managed ownership

`ManagedOwnershipPolicy` adds `@LeaseGuard` and on-demand `@LeaderTask` to ordinary
managed service methods. `@LeaderTask` grants authority for that invocation; it does
not start a background scheduler. Configure a finite trusted profile map and a
trusted mapping from the actual invocation identity to an allowed tenant. An
annotation or argument never grants a principal permission.

A guard acquires a native resource lease before entering application code. One
scalar `@ShardKey` parameter can select the ownership namespace; an absent key uses
a fixed namespace. Profile, authenticated tenant and scalar type/value all enter
the namespace. Arbitrary object extractors and type/method shard selectors are
rejected for guards before construction. Pure partition programs have their own
routing contract and cannot also acquire leases inside their replicated reducer.

Applications obtain `ManagedOwnershipContext` through a managed provider supplied
by `ResourceBindingFactory.ownershipContext()`, or the current owned invocation.
The context exposes `fence`, `leadershipEpoch`, `partitionEpoch`, `read` and
`write(effectId, key, value)`. A provider resolves the current call; a captured
context cannot authorize another call. Protected writes execute native `FENCED`
in the same resource that holds the lease. These methods protect the managed map;
they do not fence arbitrary JDBC, HTTP or filesystem effects.

Protected values are bounded Java strings (4096 UTF16 code units), preserving even
unpaired surrogates. Keys and effect IDs are bounded nonempty strings. Each call
retains at most `maximumEffects` distinct effect frames (default64, maximum1024).
Repeating an identical effect returns its original known receipt, including after
renewal changes the native fence. Reusing the ID with different data fails before
RPC. Exhausting the limit is explicit `OVERLOADED`; receipts are never evicted to
permit accidental replay. Transport ambiguity is not proof of nonexecution.

Renewal uses bounded middleware workers, not application threads or a consensus
apply callback. Renewal uncertainty, expiry, cancellation, deadline or binding
closure revokes authority. A conservative local clock margin does not replace
native validation. An uncooperative handler can continue Java execution after
revocation, but protected effects fail. The middleware keeps physical invocation
ownership until that work actually finishes; cancellation does not undo effects.
Cleanup uses a separate bounded cleanup budget and does not renew the application
business deadline.

For partitioned resources, configure `ManagedOwnershipBackend.partition` with the
actual authorized topology and a finite inventory of matching partition/resource
endpoint pairs. Each new claim resolves the current owner and pins its exact
partition epoch. Existing contexts never follow a handoff, even when the same
physical group becomes owner again at a later epoch. Both views in each configured
pair must refer to the same resource and authenticated scope. Borrowed endpoints
are not closed by the policy. A direct fixed-group backend does not accept the
known epoch-following local `PartitionResourceEndpoint` facade.

Profiles bound lease, renewal, operation and cleanup budgets. Closing the policy
while actual claims remain fails instead of discarding their ownership. Stop
application admission and drain owned calls before final policy close. Timers and
queues are bounded; no scheduled task installs application authority itself.

Qualification includes native state-machine fault tests, an actual three-voter TCP
quorum and two application JVMs (renewal network loss, takeover and worker kill),
and a separate three-group topology/resource migration test proving epoch pinning
through a forward and reverse handoff. The latter uses single-voter groups and
makes no multi-voter quorum claim.
