# Managed live service bindings

Generated `@ServiceRef` dependencies and `@Inject` with explicit `@Named` plus nondefault refresh/policy attributes resolve through the trusted host's `ServiceBindingFactory`. Optional policies require `ManagedProvider<Optional<T>>`; generated bindings return explicit empty/present values and validate the contained contract type. Direct optional proxies and nullable fallbacks are rejected. Default `@Inject` continues to mean local dependency injection. A policy name must exist in the host registry; metadata does not grant credentials, trust or readiness. Contract identity, version and qualifier fingerprint must match. The mandatory host admission callback checks authorization and current readiness.

`LiveServiceBindings` supplies stable business-interface proxies and explicit optional providers. NEVER selects once. ON_FAILURE retains a healthy target; ON_TOPOLOGY_CHANGE reselects from new authoritative snapshots. Each invocation captures one exact ObjectRef. A failed business invocation is returned to its caller without replay. Only transport-stage or dirty-target failures quarantine the incarnation; ordinary business NOT_FOUND, UNAVAILABLE or permission failures do not select another target.

Discovery UNKNOWN fails new calls closed. Identity history retains at most the policy's maxCandidates distinct runtime/object identities over the binding's lifetime. GENERATION_CAPACITY reports exhausted history; close and reopen the binding to establish a new bounded lifetime. Old generations never supersede accepted newer generations. Failure quarantine stores one marker per retained identity, not one per generation. A higher generation removes its older marker.

A repeated discovery snapshot cannot clear quarantine. A connector may supply a positive read-only exact-reference recovery proof. The built-in RuntimeBindingConnector qualifies only gRPC OPEN, which validates target identity without business or lifecycle invocation. Other transports remain quarantined until a higher generation or explicit binding replacement. Recovery attempts examine at most one candidate per snapshot, at most once per identity per second, with a 50 ms budget. Failed or unsupported probes do not run business code. A newer failure marker cannot be erased by an older probe result.

WAIT checks the remaining caller/policy deadline before each wait. Active managed parent control can shorten that budget; an exhausted or cancelled parent starts no child connection. Outside managed dispatch the unavailable control does not disable ordinary host-created bindings. A process-local absolute monotonic deadline spans selection, gRPC OPEN and business invocation; the local option is removed before wire dispatch. A longer parent SecurityContext deadline cannot extend that bound. The default owner executes one attempt, including for @Idempotent methods. An explicit trusted
`ManagedClientRetry` interceptor can retry only a qualified read-only connector/OPEN failure before
Java business-proxy entry, with @Idempotent, @Retry and a registered finite ClientRetryProfile.
`@Retry.maxAttempts` remains the number of additional attempts; the host cap can shorten it. Named
profiles bound exponential backoff and jitter inside the same monotonic deadline. Every attempt
keeps the original target, arguments and caller authority. Once method.invoke begins, the outcome
is never replayed by this interceptor, even when an application error claims NOT_STARTED. A
failed connection can affect selection for the next logical call while current retries stay pinned.
This is pre-delivery retry, not the D12 durable deduplication protocol. OneWay retry is rejected. Exact-reference connection requires the configured transport to honor its budget.

Shared discovery and observer workers use the middleware classloader and do not inherit application InheritableThreadLocal values, including when workers start lazily during application activation. Closing a binding/watch cannot leave its caller classloader installed on those host workers. Bindings own subscriptions, revocation and bounded observer delivery; runtime, source and connector remain host-owned. Owner close revokes proxies and wakes waiting callers. Callback failures are counted and isolated. Resource, queue and execution bindings live in the optional application-distributed module and preserve their native durable protocol identities.

Actual tests cover generated annotations through ManagedContainer, three service JVMs over discovery/gRPC, unauthorized candidates, a killed target, no replay of an effect whose business result is UNAVAILABLE, delayed connection with a longer parent deadline, blackhole OPEN, generation churn, and bounded recovery probes. Rollback removes additive adapters before use; existing legacy injection and runtime lookup remain available.


The interceptor observes calls through the managed proxy. Ordinary direct Java calls and application
self-invocation do not acquire a second client interception boundary. Implementation-only client
retry metadata cannot silently redefine a public interface contract; managed hosting validates that
contract before application construction. The existing three-argument InvocationContext constructor
remains available and supplies the fail-closed unavailable InvocationControl. Real invocation work,
contextual execution, physical cleanup and permit ownership are provided by the host lifetime.
