# Managed POJO sagas

`@SagaDefinition(name="orders", version=1, policy="default")` registers a generated,
activation-owned workflow. Ordinary public `@SagaStep(name="reserve", order=0)`
methods accept one data value and return the next step's input, synchronously or as
`CompletionStage<T>`. Orders are contiguous from zero. The bounded profile supports
1–7 steps, their optional `@Compensation(step="reserve")` methods, and one optional
`@ReconciliationHandler`. A compensation accepts its forward step's original input
and returns `void` or `CompletionStage<Void>`. Callback methods are not remote aliases.

The trusted host installs the **same** `ManagedSagaFactory` as the `saga` hosted
factory and as its required invocation policy. Each `ManagedSagaProfile` supplies
an authenticated `ManagedExecutionBackend`, an exact payload codec/schema registry,
a namespace and finite budgets. Missing profiles, unsupported schemas, callback
shapes or a different required policy instance fail before application construction.
A reconciliation callback additionally requires a separately authorized administrative
backend. Annotation presence grants no capability.

The host selects `factory.client(policy, definition, version)` and may inject its
scoped view using `ResourceBindingFactory.saga(client)`. Start with a stable request
and logical saga ID; retry an uncertain submission with identical IDs and input.
Namespace, definition, version and logical ID determine the native run identity.
Before accepting starts, preparation registers an immutable definition fingerprint
in the same authenticated execution store's separate protected-map namespace.
Registration reuses native atomic CAS through the existing durable invocation
primitive; it never creates a READY business job or enters job discovery/push.
Concurrent incompatible registrations conflict. The durable input also contains
this fingerprint of the artifact and semantic configuration. An incompatible persisted nonterminal program prevents activation;
a changed artifact never silently resumes old work. The pre-release implementation
reads only its current snapshot format.

Native execution push atomically acquires a claim and delivers the full input,
owner, fence, partition epoch and persisted trace under one credit. There is no
notification followed by a separate client claim. The adapter requests another
credit only after its actual owned callback has completed. Transport threads and
timers never execute application callbacks. Live operations stay pinned to the
captured physical group and partition epoch; ownership movement revokes them.
The fixed saga lease is not renewed. A callback starts only with enough conservative
remaining lease and operation budget; native fencing remains authoritative. A fresh `S_VERIFY` physical request runs inside
the actual owned decoder after data construction and immediately before the handler.
It checks native measured-time owner/fence/expiry without extending the lease or
changing the run version. Historical intent or verification receipts never grant
fresh admission.

Use an injected `ManagedProvider<ManagedSagaContext>` within the actual invocation
or its owned child work. The context exposes the stable external idempotency key,
step, fence and partition epoch. `stageEffect` records up to16 distinct, bounded
native CAS effects atomically with successful forward completion. It does not fence
arbitrary JDBC, file or remote I/O. Context access after cancellation, lease expiry
or actual invocation exit fails. Result encoding and native completion run inside
owned finalization, after participating transaction completion.

A durable intent precedes each forward callback and inverse. Persisted results
allow recovery without repeating a completed body. An uncertain non-idempotent
external effect remains `UNKNOWN`; exceptions are not proof of nonexecution.
`idempotent=true` is an application contract, not a claim of universal exactly once.
A reconciliation callback receives `ManagedSagaUncertainty` and proposes
`SUCCEEDED`, `FAILED_NO_EFFECT`, or `UNRESOLVED`. The host records the proposal with
an authorized operation and the **observed run version**; stale evidence cannot
resolve a later compensation attempt. Successful reconciliation atomically appends
an independent `S_RECONCILE_AUDIT` projection containing only entity, step, observed
and committed versions, decision and status. Domain events retain their separate
recovery payload. Unauthorized/stale proposals emit no successful decision audit. An unresolved proposal leaves `UNKNOWN`
without repeated invocation for the same durable version.

`administrativeClient(...)` is a separate explicit host grant. Ordinary client views
do not inherit it from the backend used for reconciliation. A version-checked
compensation request records the decision atomically and fences forward ownership.
Completed steps are compensated in reverse order. A missing inverse records
`UNKNOWN` with `NO_COMPENSATION_DECLARED`, rather than pretending success. Failed
inverse attempts follow the finite configured retry/backoff policy. Uncertain
inverse outcomes require reconciliation before earlier inverses continue.

Stopping closes push admission immediately. A held Java callback remains owned;
a failed drain retains its container and classloader until physical completion.
Closing a factory with live activation bindings fails. Backend endpoints are borrowed.

Executable contracts include `SagaHostedGeneratorTest`, `ManagedSagaProgramTest`,
`ManagedSagaFactoryTest`, `ManagedSagaGeneratedIT`, `SagaControlTest` and
`ManagedSagaProcessIT`. The process gate uses a signed generated application in two
JVMs, three durable native voters, a real external effect, loss during forward work
and a non-idempotent inverse, and full voter snapshot/restart. The long D-P18 resource
qualification is a separate opt-in task, never part of the ordinary suite.
