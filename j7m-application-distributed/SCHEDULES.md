# Durable managed schedules

`@Schedule("daily")` on a `@DurableJob` selects an explicit trusted host profile.
`ManagedScheduleProfile` declares a fixed UTC interval or a daily local wall time,
an earliest planned UTC instant, an explicit zone, DST rules, lateness threshold,
misfire bound, overlap contract and a borrowed CAS metadata resource. The job host
captures a canonical encoded input snapshot before scheduling. Timers and trigger
workers do not decode application DTOs or invoke business callbacks.

`DurableSchedules` is the general threadless engine. `prepare(deadline)` atomically
registers or verifies the stable definition's fingerprint and initial cursor.
`tick(deadline)` processes at most one occurrence and returns a known wake instant.
The host arms one timer for that instant and dispatches actual engine I/O onto its
bounded workers. Calls before the cached due time make no metadata RPC. Healthy
clock evidence comes from an authenticated reference with bounded uncertainty;
unsafe time fails before occurrence mutation/submission. A conservative lower time
bound prevents early firing. The returned wake instant accounts for uncertainty
and local/reference offset, avoiding an immediate timer loop before eligibility.

The engine atomically stores a pending planned UTC occurrence before submitting.
Submission uses a stable ID derived from the definition and that planned instant,
never a worker identity or the current clock. Only confirmed native submission
advances the cursor and clears pending. A crash after metadata commit or after job
submission repeats the same native request on recovery. This bridges the two
stores without XA. Pending work always recovers before applying a new misfire
policy, even if `SKIP` is selected and recovery is late. A timeout proves neither
submission failure nor cursor advancement.

The stable metadata key represents namespace, job identity and version. Its stored
fingerprint includes the verified program/input semantics, schedule settings and
zone-rule version. A conflicting definition fails closed and preserves the cursor;
changing an artifact does not silently reset its schedule. Metadata uses the
existing resource's atomic CAS and finite request retention/capacity. Capacity or
contention failures stop confirmed progress; there is no unbounded retry or hidden
history eviction. All operations retain their original deadline.

DST gaps use `SKIP` or `SHIFT_BY_GAP`, including the actual transition duration.
Folds use `EARLIER`, `LATER` or `BOTH`; both occurrences have distinct planned UTC
identities. Ordinary timer lateness within `misfireThreshold` still executes the
due occurrence. Beyond it, `SKIP` advances without submission, `FIRE_ONCE` retains
only the latest overdue instant, and `CATCH_UP` retains at most the latest configured
1–256 overdue instants. Fixed intervals are calculated arithmetically; daily
occurrence lookup has a finite civil-date search. A backward clock never rewinds
the persisted cursor.

`ALLOW` permits concurrent native claims for different runs. `FORBID` requires the
job profile's native serial group: pending occurrences may queue, while at most
one run has a current authoritative claim. A local timer flag is not the lock.
An expired uncooperative Java thread may still run; its stale native fence cannot
commit new protected effects. No schedule policy forcibly rolls back external I/O.

Closing the engine stops future calls without I/O and leaves durable pending work
for recovery. Metadata and backend endpoints remain borrowed. The containing job
host owns timer cancellation, actual worker drain and application lifecycle.

`DurableSchedulesTest` checks real resource/execution state machines, concurrent
schedulers, lost replies at both stores, incompatible definitions, bounded catch-up,
clock safety and both DST transitions. `DurableSchedulesProcessIT` kills independent
scheduler JVMs after pending commit and after submission against three metadata and
three execution voters, then restarts both durable stores. `ManagedScheduleProfileTest`
checks configuration rejection before effects. These are ordinary bounded tests;
the long resource qualification remains a separate opt-in task.
