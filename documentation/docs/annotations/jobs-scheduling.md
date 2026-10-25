# Durable jobs and scheduling annotations

The job host owns claim, restore, renewal and completion. A job component owns its application state only while the actual invocation remains admitted. Persistent scheduling uses durable occurrences, not a timer that exists only in one JVM.

## @DurableJob {#durablejob}
Declares one finite job entry. **Target:** method. **Attributes:** required `value`; `policy = "default"`, `version = 1`.

```java
@DurableJob(value = "rebuild-index", policy = "index-jobs")
public String rebuild(String partition) {
    return partition;
}
```

The callback has one concrete data argument and an allowed data/void/asynchronous result. The host installs the same `ManagedJobFactory` as factory and required policy, plus its finite `ManagedJobProfile`. Each mutable activation component has one job entry. The annotation does not install a scheduler or make external I/O exactly once.

## @Schedule {#schedule}
Selects a trusted persistent trigger for a job entry. **Target:** method. **Required attribute:** `value`.

```java
@DurableJob(value = "rebuild-index", policy = "index-jobs")
@Schedule("nightly-index")
public String rebuild(String partition) {
    return partition;
}
```

The host profile supplies time zone, DST gap/fold rules, missed-fire threshold, catch-up and overlap behavior. FORBID uses a native serial group; ALLOW permits independent runs. A changed semantic definition cannot silently resume an incompatible occurrence history.

## @Checkpoint {#checkpoint}
Marks a job state snapshot hook. **Target:** method. **Attributes:** none.

```java
@Checkpoint
public Integer savedPosition() {
    return nextBatch;
}
```

Pair it with `@Restore` when the component has mutable per-run state. The injected `ManagedJobContext` records the checkpoint at an explicit safe point. The host does not scan the heap or serialize the Java stack.

## @Restore {#restore}
Marks the matching state restoration hook. **Target:** method. **Attributes:** none.

```java
@Restore
public void restorePosition(Integer saved) {
    nextBatch = saved == null ? 0 : saved;
}
```

A fresh run receives null, so reset state explicitly. A takeover receives saved state through the actual application's allowed schema codec. Restoration does not authorize a stale worker's effects.

## @Progress {#progress}
Marks bounded progress state. **Targets:** method and parameter in the public declaration; the managed job profile uses its supported method hook. **Attributes:** none.

```java
@Progress
public Integer progress() {
    return nextBatch;
}
```

Progress is associated with the current run/fence and persisted at explicit context safe points. It is status data, not proof that an external operation committed. Keep it bounded and free of secrets.

## Example: resume a batch job from a safe point

A rebuild saves the next batch only after that batch reaches its application-defined safe point. This component's host registers the `index-jobs` job profile, `nightly-index` trigger and current job context binding.

```java
@J7mComponent
public final class RebuildJob {
    private final ManagedProvider<ManagedJobContext> context;
    private int nextBatch;

    @Inject
    public RebuildJob(@J7mResource("job-context")
                      ManagedProvider<ManagedJobContext> context) {
        this.context = context;
    }

    @DurableJob(value = "rebuild-index", policy = "index-jobs")
    @Schedule("nightly-index")
    public String rebuild(String partition) {
        while (nextBatch < 10) {
            // Rebuild this bounded batch using the application's safe protocol.
            nextBatch++;
            context.get().checkpoint();
        }
        return partition + ":complete";
    }

    @Checkpoint
    public Integer savedPosition() { return nextBatch; }

    @Restore
    public void restorePosition(Integer saved) {
        nextBatch = saved == null ? 0 : saved;
    }

    @Progress
    public Integer progress() { return nextBatch; }
}
```

These are application declarations for an installed host, not a standalone scheduler. A fresh run restores zero. After saving position 4, a takeover restores 4 and continues with the next batch instead of starting from zero. `checkpoint()` invokes the state/progress hooks at that safe point; it does not serialize a Java stack.

The batch operation is deliberately application-specific: implement it with idempotent or fenced effects before using this pattern against an external index. Saving a counter after an arbitrary external write does not make that write exactly once. The schedule's timezone, overlap and missed-fire behavior come from the trusted trigger profile. See [durable jobs](../patterns/durable-jobs.md) and [persistent schedules](../patterns/persistent-schedules.md).
