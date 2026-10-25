# Managed application canary traffic

Owner: j7m maintainers. Updated: 2026-10-23. Review after a traffic-policy or lifecycle schema change.

`CanaryDeployment` routes actual managed application requests through `ManagedInvocation` endpoints. `ClusterNodeClient` is the authenticated TCP implementation; its revision-pinned invocation enters the `ApplicationManagerHost` admission gate. The controller never substitutes a local arithmetic load generator for application invocation. The D-P17 process gates are pending final measurement and independent acceptance.

## Compose the public APIs

Provide a scope-authorized `ResourceEndpoint` backed by the coordination resource group, a `ClusterLifecycle` implementation such as `ClusterAdminClient`, and a map of configured node IDs to `ClusterNodeClient` instances. Each client uses its configured cluster identity, principal, independent key and TLS context. Outside loopback, mutually authenticated TLS and hostname verification are required. Principals must have the appropriate server-assigned invocation or administrative capabilities; possession of a request argument cannot grant them.

Create `CanaryDeployment(scope, resources, lifecycle, endpoints)`, then install a `CanaryPolicy` with `configure(operationId, expectedResourceVersion, policy, deadline)`. Use version zero only when the policy does not exist. The policy namespace is `canary/<scope>` inside the endpoint's authenticated resource scope. Obtain the version from `status(deadline)` for later changes. The configured endpoint set contains 1–64 entries; the lifecycle fleet may impose a smaller bound.

The policy specifies an increasing revision, distinct baseline/candidate release IDs, candidate weight in 0–10 000 basis points, minimum candidate sample count, maximum error fraction, maximum arithmetic mean latency in milliseconds and maximum probe age. For the development gate, 1 000 basis points means 90/10 baseline/candidate traffic, and the bad-candidate threshold requires 200 candidate samples. These values are test policy rather than a production SLO. Sample count is bounded to 1–1 000 000; latency and probe-age thresholds to 1–60 000 ms. Error fraction must be finite and within 0–1.

Call `invoke(requestId, contract, payload, deadline)`. Scope, revision and the unchanged request ID determine the SHA-256 traffic bucket and selected eligible node. Request IDs contain 1–512 characters. Scope/release identities contain 1–256 characters from the documented deployment-identity alphabet. Contract aliases select server-configured managed services; clients cannot choose arbitrary implementation classes or methods. Payload/frame limits and physical concurrency limits remain enforced by the underlying node transport and managed host.

## Consistency, observations and outcomes

Policy reads use the resource endpoint's fresh quorum barrier; mutations use durable CAS and its scoped idempotency journal. Readiness is inspected through the lifecycle. A selected node must be ready, accepting requests and hosting the selected revision when its invocation admission gate checks it. A readiness observation does not authorize a later invocation by itself. This implementation does not claim an atomic snapshot across every node's independent readiness observation.

Routing remains stable for a policy revision and unchanged eligible node set. A changed policy, readiness, node set or rollback can deliberately change the target. Stable routing does not deduplicate application effects: carry the same business request ID into the application's supported dedupe/reconciliation protocol. The router does not retry on another endpoint after an ambiguous failure. Timeout or `IOException` may mean UNKNOWN; cancellation and rollback do not undo committed or external effects.

The default probes measure actual candidate invocation successes, errors and monotonic elapsed durations in this controller instance. Samples reset for a new revision or process restart, so a restarted controller requires fresh minimum samples. Late completions from an older revision cannot erase the newer revision's observations. The optional `ProbeReader` adapter must authenticate its source and supply observations for the requested revision. Missing, stale or invalid observations never justify raising exposure.

The single background evaluator runs every 100 ms with bounded lifecycle deadlines. It evaluates readiness, error fraction and arithmetic mean latency only after the explicit minimum sample threshold. `metrics()` exposes bounded, identity-free hold and completed rollback counters; it does not expose payloads or request identities. Deploy one supervised controller per traffic-policy namespace when a single shared sample window is required; observations from separate controller instances are not aggregated automatically.

## HOLD, rollback and recovery

Missing probes or unavailable readiness produce durable HOLD. HOLD routes subsequent requests to the eligible baseline and preserves the configured weight as history; an exposure increase requires a new explicit policy and fresh checks. HOLD does not silently resume candidate exposure when a collector recovers.

When error/latency thresholds fail, the controller first durably records ROLLING_BACK, disabling candidate admission. It stores the original lifecycle version and exact candidate node list before invoking D16 rollback. D16 performs authenticated, fenced drain/replacement against the compatible baseline release. The controller marks ROLLED_BACK only after the lifecycle operation is COMPLETED. Controller restart or a lost reply replays the same operation ID and original arguments; it does not infer completion from a timeout or silently re-enable the old policy.

Rollback targets an operator-trusted compatible release and remains subject to D16 schema/N/N−1 and quorum/capacity checks. If lifecycle authority or compatibility is unavailable, traffic stays disabled for the candidate and the durable rollback can be reconciled. Preserve the policy resource state, operation history, request tombstones and complete compatible snapshots. Resource identity/retention capacity is explicit; overload requires operator action, never deletion of identities to permit accidental replay.

Before adoption, remove the optional canary wiring to roll back the feature. After adoption, install an explicit compatible baseline policy or finish the durable D16 rollback, drain in-flight calls through the managed host and retain its durable state. Downgrading a binary requires a reader compatible with the stored policy and lifecycle schemas; otherwise retain the state and apply a forward fix. Closing the canary controller only stops its evaluator: it neither undoes effects nor deletes policy state.

## Verification boundary

`CanaryPolicyTest`, `CanaryRouterTest` and `CanaryDeploymentTest` cover bounds, routing identity, probe thresholds, HOLD, revision races and recovery of exact rollback arguments. `CanaryProcessIT` uses signed application bundles hosted by real middleware JVMs: 10 000 application requests, 90/10 distribution, 200 failed candidate invocations followed by automatic rollback, and unavailable probes with restart. Passing unit routing arithmetic alone does not satisfy the process gate. Final build/runtime evidence and independent verdict belong to the INBOX implementation record; this guide makes no product-release or customer-SLO claim.
