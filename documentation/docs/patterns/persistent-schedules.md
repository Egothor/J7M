# Persistent scheduling, overlap and missed executions

**Use case:** a nightly reconciliation must retain its planned occurrence through scheduler crashes, daylight-saving transitions and a lost submission reply.

=== "With annotations"

    ```java
    @DurableJob(value = "reconcile", policy = "finance-jobs")
    @Schedule("nightly-finance")
    public String reconcile(String businessDate) {
        return finance.reconcileWithStableIdentity(businessDate);
    }
    ```

    The host supplies the trigger's actual input, time zone, clock evidence, overlap and missed-fire rules. The annotation selects that profile, not a local timer expression.

=== "Explicit API"

    ```java
    schedule.prepare(deadline);
    DurableSchedules.Tick tick = schedule.tick(deadline);
    Instant nextWake = tick.nextDue();
    ```

    `schedule` is a configured threadless `DurableSchedules` engine. Its owner schedules the returned wake and dispatches bounded engine I/O. Each tick processes at most one occurrence; the engine does not start an unmanaged background thread.

## Planned time is the identity

The engine persists pending occurrence metadata before job submission. The stable submit ID derives from the definition and planned UTC instant, not the worker name or current clock. Only confirmed submission advances the cursor. Recovery repeats pending work before applying a new missed-fire policy.

The two stores are bridged by stable request replay, not XA. A crash after pending commit or after submission must not create a new logical occurrence.

## Time and overlap policy

Profiles support fixed intervals or daily local time. Configure DST gap behavior, fold choice, lateness threshold and bounded catch-up explicitly. Backward clock movement does not rewind the persisted cursor. Unsafe reference-clock evidence fails before occurrence mutation.

ALLOW permits different native runs to overlap. FORBID uses a persisted serial group, not one JVM's timer flag. An expired old handler can still be executing Java, so fencing and external idempotency remain necessary.

See [resource/profile configuration](../configuration/distributed-resources.md).

## Worked scenario: recover the same nightly occurrence

A finance application selects its trusted schedule on the durable job method:

```java
@DurableJob(value = "reconcile", policy = "finance-jobs")
@Schedule("nightly-finance")
public String reconcile(String businessDate) {
    return finance.reconcileWithStableIdentity(businessDate);
}
```

`finance` is the application's idempotent/fenced reconciliation integration. The host provides the input date and persistent trigger definition, including timezone and DST rules. The scheduler owner uses the threadless engine:

```java
schedule.prepare(deadline);
DurableSchedules.Tick tick = schedule.tick(deadline);
Instant nextWake = tick.nextDue();
```

Use `nextWake` to plan the owner's next bounded wakeup; it is not permission to busy-loop. The host supplies actual timer/executor ownership and shutdown.

If the scheduler dies after pending metadata commits, its replacement recovers that occurrence before applying missed-fire policy to later instants. If it dies after job submission but before cursor advancement, recovery repeats the same stable submit ID and confirms the same run. Being late is not a reason to discard already pending work under a fresh SKIP decision.

A new business date creates a distinct planned occurrence. FORBID prevents overlap through a native serial group, not an in-memory flag. A handler whose lease expires can still be running Java, so the finance integration still needs its external idempotency/fencing contract.
