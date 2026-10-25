# Durable POJO jobs

An activation component declares one public `@DurableJob("name")` method with one
concrete data argument. Its result is data, `void`, or a corresponding
`CompletionStage`. The generated index supplies direct dispatch; applications do
not implement a job framework interface. The host installs the same
`ManagedJobFactory` instance as both hosted factory and invocation policy, chooses
a named `ManagedJobProfile`, and exposes a profile-qualified `ManagedJobClient`.
Missing policies and unsupported shapes fail before component construction.

`@Checkpoint` and `@Restore` form an optional pair. Mutable per-run component state
requires this pair: restore receives `null` before each fresh run and the saved
value after takeover. `@Progress` supplies a persisted progress value. Without the
pair, the structural profile rejects nonfinal instance fields. This is not a Java
sandbox or proof of statelessness: final collaborators must not retain per-run
state. App-owned annotated schema records are resolved from the actual generated
index and activation class loader; a second preload loader is unnecessary.

Inject `ManagedProvider<ManagedJobContext>` through the host's named `jobContext()`
resource registration. Within actual owned USER work, the context can checkpoint,
publish progress and stage bounded compare-and-set effects. Completion persists
the result and staged effects atomically. Retaining the context does not grant
access to another invocation. External I/O still requires an idempotent protocol,
inbox or outbox.

Claims are pinned to their native group and partition epoch. A fresh authority
check precedes argument decode and the handler. Renewal preserves the fence;
renewal uncertainty or cancellation requests cooperative cancellation. A timeout,
cancelled future or revoked lease does not prove that Java work has exited.
Drain retains the component, CALL scope and class loader until actual tracked
children and finalizers finish. Uncooperative work can therefore exhaust bounded
capacity; the host does not force an abort or release its physical lifetime early.

`ManagedJobClient` selects an exact host policy, definition and version. It cannot
submit before global admission or after drain starts. Its result and status report
durable logical outcomes, not proof of worker termination. Failures use a fixed
payload-free persisted category. Native request replay is historical evidence,
never fresh ownership authority.

A profile can bind `@Schedule("trigger")` to the durable trigger described in
[SCHEDULES.md](SCHEDULES.md). FORBID uses a persisted native serial group, including
claims from other hosts; ALLOW permits different runs on independent workers.
Neither promises to stop uncooperative old Java after takeover. Exact program,
schema and semantic configuration fingerprints reject incompatible restarts.

Data envelopes are bounded to 12 KiB before native base64 encoding; checkpoint
and progress share their envelope bound. A context stages at most 64 protected
effects and 64 KiB of effect data. Execution push uses a 256 KiB frame, four
credits per session, at most 64 sessions and a shared 16 MiB reservation budget;
queue frames remain 64 KiB. Native storage retains at most 10,000 runs and request
identities and has a 16 MiB byte budget. Renewal and checkpoints consume retained
identities. Saturation returns explicit overload rather than silently evicting
history; deployment capacity must account for the expected run and renewal rate.

Run enumeration loads pages lazily. Pages contain current views, not a single
historical snapshot. Consumers combining a scan with its event tail must merge by
monotonic run version or use events as refresh signals. A listing never authorizes
a claim. Expired cursors require an explicit fresh scan within the caller's
original deadline.
