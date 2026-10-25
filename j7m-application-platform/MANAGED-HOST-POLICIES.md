# Managed application security, resources and probes

Status: **A-P06 K/I/V PASS (2026-10-23).** Security/resource ingress, bounded probes and actual readiness/discovery/canary integration are independently verified. The final incremental root build and engineering gate G3 passed; evidence is in `INBOX/A-P01-A-P17-OVERENI/ap06-final-integration/` and `ap05-ap06-final-source/`. This acceptance covers the documented managed-v1 profile.

This document describes the `managed-v1` host integrations for A-P06. The development plan and independent evidence determine acceptance; the existence of an annotation declaration alone does not enable its behavior.

## Host capabilities

The operator installs `ManagedSecurityPolicy` and `ManagedResourcePolicy` in `ManagedHostingOptions`. These objects are borrowed by applications. Application manifests cannot install policies, nominate trusted implementations or grant capabilities. Unsupported active policy annotations, unknown profile names and invalid combinations fail before application constructors. Nonexported helpers cannot silently claim service interception: annotations needing an invocation boundary are rejected there. The host also checks every claimed policy site against the actual manifest export set. Private/local helpers, factories, probe-only methods and unsupported field sites cannot silently retain an unenforced policy annotation.

Policy factories validate complete public `ManagedServiceContract` identities before construction, then bind to actual exports. A new replica receives the same checks on its own host; switching a client reference never bypasses transport authentication or server authorization. Ordinary Java calls within an application do not create another remote interception boundary.

## Security

`@RequiresRole`, `@RequiresPermission` and `@RequiresTrustDomain` use the actual authenticated transport security context. Applicable interface and implementation requirements combine restrictively; conflicting trust-domain requirements fail preflight. `@Confidential` requires authenticated encryption. A claimed identity in an application invocation snapshot or an `ObjectRef` is not authentication.

Permission evaluation receives the real target object ID, runtime, service, registry context, transport and full public method identity. An empty permission resource resolves to that actual object ID. The installed host authorization policy interprets the public action/resource strings.

`@Audit(policy="default")` selects an installed named sink. Missing mandatory sinks reject deployment. The sink acknowledges each event synchronously; the host chooses its durability implementation. This API does not make an in-memory sink durable. A failed audit before dispatch prevents the application effect. A failed audit after dispatch returns `UNAVAILABLE` with outcome `UNKNOWN`; it does not authorize replay. Events contain safe metadata, never arguments or results. Application exception messages, details and causes are redacted even on unsuccessful calls; `@Sensitive` data is not added to diagnostic events.

## Resources and isolation

`@ResourceRequirement` selects a named host profile shared across the bindings using that policy factory. The profile enforces concurrent executions, bounded queued callers, bounded wait and priority ageing. `@Priority` must fit the host's configured maximum. Capacity remains occupied until actual physical execution and cleanup finish. With A-P07 managed calling support this includes tracked asynchronous work, even after reply timeout or cancellation. An asynchronous entry without trusted physical ownership is rejected; the annotation itself never supplies that ownership. Cluster-wide admission remains a separate capability.

`@IsolationProfile` selects an installed capability profile. The implemented heap capability checks the actual whole-JVM `Runtime.maxMemory` ceiling. A mismatched ceiling fails before constructors. This is not a per-application memory limit, CPU quota or operating-system sandbox; unsupported isolation profiles are rejected. Independent child-JVM tests verify both deployment rejection and the actual configured JVM heap limit.

## Health and readiness

Managed probes are generated direct calls to public, zero-argument, primitive-boolean methods on activation-scoped components. `@HealthCheck` and `@ReadinessCheck` are separate from remotely exported business methods. Unsupported signatures and scopes fail before construction; no package-wide reflection or `opens` is required.

`ManagedProbeCoordinator` bounds physical probe execution and has no waiting task queue. Repeated inspections share unfinished callbacks. Saturation, exceptions, null results and exhausted wait budgets produce `UNKNOWN`. A timeout is not cancellation or completion: a blocked application callback keeps its actual invocation/lifetime accounting until it physically returns. Subsequent read-only inspections can recover from transient saturation.

`ManagedHostingOptions` accepts an operator-owned `ManagedProbeSettings` profile; the compatible two-argument constructor uses four workers, 64 activations, 100 ms sampling/wait and two-second freshness. Freshness ranges from 1 ms through one minute. These are development defaults, not a production SLO.

`ManagedProbeMonitor` samples finite activation registrations and expires cached observations using monotonic time. Its timer runs trusted scheduling/publication code; application probes run on separate bounded workers. Workers and timer use the host class loader, and idle threads can terminate. Probes run with an anonymous, unauthenticated context without caller credentials or host system/admin roles. Drain, failed activation and stop detach registrations. The host must discard closed handles and reject late publication from an obsolete activation generation.

A receiving export must enforce finite observation validity itself. An old successful sample must not admit traffic indefinitely when monitoring stalls. Managed deployment readiness and canary decisions require an actual fresh readiness observation; missing or unknown observations hold exposure. A failed or stale readiness result removes the target from selection and rejects new dispatches. Recovery permits subsequent calls, without replaying an earlier invocation. Applications without declared readiness keep ordinary hosting compatibility; they cannot claim verified managed canary readiness merely because their lifecycle state is `RUNNING`.

Normal `test`, `check` and `build` do not start the standalone hourly distributed qualification.
