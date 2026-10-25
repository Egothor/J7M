# Application Upgrade and Rollback

`ApplicationManager` upgrades an active application to an exact verified coordinate. Its strategies operate within that manager's runtime boundary. They are separate from cluster-wide rollout coordination, external load-balancer switching and distributed business-data migration.

## Strategy selection

| Strategy | Runtime behavior and operational implication |
|---|---|
| `stop-start` | Drain/stop the current activation, then activate the target. Availability can have a gap. If activation fails, the manager attempts to restore the previous coordinate. |
| `rolling` | Replace managed service instances progressively according to upgrade options and readiness; verify available capacity during the transition. |
| `blue-green` | Prepare the target activation alongside the existing one, check it and select the new active version before retiring the old activation. Budget capacity for overlap. |
| `canary-baseline` | A baseline strategy identifier, not proof of a fully configured fleet-wide canary controller or metric-based traffic analysis. |

An in-flight invocation can keep a managed upgrade awaiting physical completion. A deadline or cancelled client does not prove that arbitrary application work has physically stopped. Read the result and actual lifecycle state before proceeding.

## Perform an upgrade

This host fragment assumes an authorized manager, the current `ApplicationInstanceId`, an exact target coordinate and an appropriate activation request:

```java
var result = applicationManager.upgrade(
        instanceId, targetCoordinate, "stop-start", activationRequest);
// Inspect result success, state and safe details before updating routing.
```

The target must pass the current signature, manifest, classloader/JPMS and policy checks. The previous release must remain retained and verified for restoration. Prepare capacity, readiness checks, drain budgets, secrets and configuration before beginning; publication alone is not preparation of a running replacement.

## Failure and rollback

The manager tracks previous coordinates and activation information for rollback. A failed target activation with a successful restoration can leave the application running on the previous version while the upgrade result still reports failure. If restoration also fails, treat the application as unavailable and escalate through the host recovery procedure.

After any upgrade, verify the selected coordinate, active instance/generation, readiness, real invocation and stale-instance rejection. Repository promotion, runtime selection and external routing are separate effects. Retain a decision record of the before/after coordinates and digests, requested strategy, safe result, readiness check and rollback outcome.

J7M's cluster rollout primitives can coordinate broader release decisions, but do not infer cross-host coordination from this local manager method. See [deployment topology](topologies.md), [lifecycle](lifecycle.md) and [backup/recovery](../operations/recovery.md).
