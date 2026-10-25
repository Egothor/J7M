# Annotation guide and complete reference

J7M's annotations describe ordinary application code while the host supplies trusted capabilities, finite profiles and lifecycle ownership. The public API currently contains **80 annotation types** under `org.j7m.middleware.annotations`. `BindingRefresh` is a helper enum and is not an annotation.

Start with [components and injection](components.md), then follow the family relevant to your application. The [pattern guide](../patterns/index.md) shows how declarations fit a working distributed design and compares them with explicit API code.

## How to read an entry

Each entry identifies valid Java targets, required attributes and defaults, a usage example and the real consumer or limitation. Code fragments focus on the declaration: imports, application collaborators and host configuration may be supplied by the surrounding application. The executable [example projects](../testing/verified-examples.md) provide complete verified entry points.

**Declaration is not installation.** A named `policy` selects a trusted registered profile; it never installs a factory or grants credentials. Generated managed metadata is validated before construction. Unsupported or conflicting combinations fail rather than silently promising an unavailable behavior.

**Declarations need an installed capability.** Some markers are descriptive metadata, and `@SwitchLevel` is explicitly reserved. Each family below shows application code, the required host boundary and the resulting behavior. Use the practical scenarios to assemble the declarations into a working design; a reserved marker has no runtime operation to activate.

## Common combinations

| Task | Typical combination | Required host boundary |
| --- | --- | --- |
| Refreshable remote dependency | `@Inject` + `@ServiceRef` | Binding factory, discovery, admission and named policy |
| Safe pre-delivery retry | `@Idempotent` + `@Retry` | Qualified client retry interceptor and finite profile |
| Durable command replay | `@Deduplicated` + `@IdempotencyKey` | Durable invocation policy and stable namespace |
| Database-backed message handling | `@DurableQueueConsumer` + `@InboxHandler` + `@JdbcTransaction` | Exact shared JDBC registration and INBOX queue strategy |
| Recoverable mutable job | `@DurableJob` + `@Checkpoint` + `@Restore` | Job factory/profile and explicit checkpoint safe points |
| Persistent occurrence | `@DurableJob` + `@Schedule` | Durable trigger, clock and overlap/missed-fire policy |
| Recoverable workflow | `@SagaDefinition` + `@SagaStep` + optional inverses/reconciliation | Saga factory, exact program identity and explicit administrative grant |

## Validation and precedence

Use public interface declarations for client-visible calling semantics. Implementation-only metadata must not silently redefine that contract. Ordinary self-invocation does not create interception. Exact qualifier values and policy identities matter; a similarly configured second factory is not necessarily the same required policy instance.

Keep defaults explicit when they affect safety. `refresh=NEVER` does not mean cluster failover; `idempotent=false` does not authorize automatic replay; `version=1` is a logical contract version rather than a deployment instance number. See [configuration profiles](../configuration/application-policies.md) and [failure semantics](../reference/semantics.md).

## Alphabetical index: all 80 annotations

| Annotation | Purpose |
| --- | --- |
| [@Async](invocation.md#async) | Logical asynchronous result |
| [@Audit](security-observability.md#audit) | Host-selected safe audit policy |
| [@Backpressure](admission-workloads.md#backpressure) | Concurrent admission metadata |
| [@BackpressureAware](admission-workloads.md#backpressureaware) | Backpressure cooperation marker |
| [@Benchmark](admission-workloads.md#benchmark) | Optional safe latency-sampling method |
| [@Bulkhead](resilience.md#bulkhead) | Dependency admission budget |
| [@Callback](invocation.md#callback) | Remote-reference callback intent |
| [@Cancellable](invocation.md#cancellable) | Cooperative cancellation contract |
| [@Checkpoint](jobs-scheduling.md#checkpoint) | Explicit job state snapshot hook |
| [@CircuitBreaker](resilience.md#circuitbreaker) | Connection-availability breaker policy |
| [@Compensation](sagas.md#compensation) | Named inverse saga callback |
| [@Confidential](security-observability.md#confidential) | Confidential-handling declaration |
| [@ConfigProperties](configuration-context.md#configproperties) | Immutable configuration record |
| [@ConfigValue](configuration-context.md#configvalue) | Configuration key binding |
| [@CurrentApplication](configuration-context.md#currentapplication) | Safe application context |
| [@CurrentInvocation](configuration-context.md#currentinvocation) | Current owned invocation context |
| [@CurrentPrincipal](configuration-context.md#currentprincipal) | Authenticated current principal |
| [@DataLocality](ownership-partitioning.md#datalocality) | Eligible same-group endpoint preference |
| [@DeadLetterHandler](messaging-events.md#deadletterhandler) | Observational DLQ callback |
| [@Deadline](invocation.md#deadline) | Method timeout metadata |
| [@Deduplicated](transactions.md#deduplicated) | Durable invocation replay policy |
| [@Drain](lifecycle.md#drain) | Application drain callback |
| [@DurableJob](jobs-scheduling.md#durablejob) | Durable POJO job entry |
| [@DurableQueueConsumer](messaging-events.md#durablequeueconsumer) | Durable payload handler |
| [@EventSubscription](messaging-events.md#eventsubscription) | Durable cursor callback |
| [@HealthCheck](lifecycle.md#healthcheck) | Component health callback |
| [@HighAvailability](resilience.md#highavailability) | Availability selection intent |
| [@IdempotencyKey](transactions.md#idempotencykey) | Stable logical operation identity |
| [@Idempotent](invocation.md#idempotent) | Safe-repeat application contract |
| [@InboxHandler](messaging-events.md#inboxhandler) | Same-Connection delivery marker |
| [@Initialize](lifecycle.md#initialize) | Initialization callback |
| [@Inject](components.md#inject) | Managed dependency resolution |
| [@IsolationProfile](security-observability.md#isolationprofile) | Installed isolation/resource profile |
| [@J7mApplication](services.md#j7mapplication) | Application coordinates |
| [@J7mComponent](components.md#j7mcomponent) | Generated component definition |
| [@J7mResource](components.md#j7mresource) | Authorized host capability binding |
| [@J7mService](services.md#j7mservice) | Managed service declaration |
| [@JdbcTransaction](transactions.md#jdbctransaction) | One-Connection JDBC unit of work |
| [@LeaderTask](ownership-partitioning.md#leadertask) | Leadership-gated work |
| [@LeaseGuard](ownership-partitioning.md#leaseguard) | Fenced lease policy |
| [@LocalOnly](services.md#localonly) | Local contract boundary |
| [@LongRunning](admission-workloads.md#longrunning) | Long-work declaration |
| [@Named](components.md#named) | Exact dependency name |
| [@NoRemoteExport](services.md#noremoteexport) | Non-export declaration |
| [@NonIdempotent](invocation.md#nonidempotent) | Unsafe-repeat contract |
| [@Observed](security-observability.md#observed) | Application observation policy |
| [@OneWay](invocation.md#oneway) | Bounded local enqueue |
| [@OrderedBy](ownership-partitioning.md#orderedby) | Explicit per-key ordering selector |
| [@OutboxPublisher](messaging-events.md#outboxpublisher) | Transactional SQL staging producer |
| [@PartitionHandler](ownership-partitioning.md#partitionhandler) | Pure encoded state reducer |
| [@PreferLocal](resilience.md#preferlocal) | Eligible local-candidate preference |
| [@Priority](admission-workloads.md#priority) | Workload priority metadata |
| [@Produces](components.md#produces) | Typed factory product |
| [@Progress](jobs-scheduling.md#progress) | Bounded progress hook |
| [@Qualifier](components.md#qualifier) | Custom dependency qualifier marker |
| [@QueuePublisher](messaging-events.md#queuepublisher) | Typed durable producer |
| [@RateLimited](admission-workloads.md#ratelimited) | Rate-policy scope |
| [@ReadinessCheck](lifecycle.md#readinesscheck) | New-work readiness callback |
| [@ReconciliationHandler](sagas.md#reconciliationhandler) | Evidence-based UNKNOWN resolution |
| [@RemoteMethod](services.md#remotemethod) | Remote method declaration |
| [@RemoteService](services.md#remoteservice) | Remote service intent |
| [@RequiresPermission](security-observability.md#requirespermission) | Action/resource authorization requirement |
| [@RequiresRole](security-observability.md#requiresrole) | Caller role requirement |
| [@RequiresTrustDomain](security-observability.md#requirestrustdomain) | Caller trust-domain requirement |
| [@ResourceRequirement](admission-workloads.md#resourcerequirement) | Resource policy requirement |
| [@ResourceTransaction](transactions.md#resourcetransaction) | Native resource 2PC policy |
| [@Restore](jobs-scheduling.md#restore) | Checkpoint restoration hook |
| [@Retry](invocation.md#retry) | Bounded pre-delivery retry declaration |
| [@SagaDefinition](sagas.md#sagadefinition) | Versioned durable workflow |
| [@SagaStep](sagas.md#sagastep) | Ordered forward callback |
| [@Schedule](jobs-scheduling.md#schedule) | Persistent trigger selection |
| [@SchemaType](serialization.md#schematype) | Stable allowlisted record schema |
| [@Scope](components.md#scope) | Activation/CALL/prototype lifetime |
| [@Sensitive](security-observability.md#sensitive) | Redaction-aware value declaration |
| [@ServiceRef](components.md#serviceref) | Refreshable business-interface binding |
| [@ShardKey](ownership-partitioning.md#shardkey) | Canonical routing selector |
| [@Shutdown](lifecycle.md#shutdown) | Final resource-release callback |
| [@Startup](lifecycle.md#startup) | Startup callback |
| [@SwitchLevel](resilience.md#switchlevel) | Reserved, currently non-executing marker |
| [@WireField](serialization.md#wirefield) | Stable schema field identity |

The inventory is derived from the current source declarations, not from names remembered by the guide. Changes to that package must update this index and the corresponding examples together.
