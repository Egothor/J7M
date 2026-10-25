# Typed Nugget Administration API

`org.j7m.middleware.operations.api` defines transport-neutral administration. `NuggetAdminService` combines discovery, PIN admission, read-only introspection and typed application/control operations. It starts no listener and grants no authorization merely by being present on the classpath.

## Operation families

| Family | Contract |
|---|---|
| Discovery | `NuggetDiscoveryService`, typed queries/selectors, safe records and resolutions. |
| Admission | `authenticate(NuggetAuthenticationRequest, char[])`; clear PIN material is separate from metadata. |
| Introspection | `NuggetIntrospection` request/query/result DTOs for objects, applications, environment, disk, health and metrics. |
| Application lifecycle | `NuggetApplicationLifecycle` typed requests, absolute deadlines and operation results. |
| Nugget control | `NuggetControl` typed drain/shutdown/kill requests and confirmation. |
| Capabilities | `adminCapabilities(NuggetAdminRequestContext)` reports actual supported contracts. |
| Optional common envelope | `NuggetAdminCommandRequest` / `NuggetAdminCommandResult` around exact public DTO families. |

Default capability/envelope implementations return truthful unavailable results. An adapter must not fabricate support or bypass the typed family's existing admission, authorization, deadline and lifecycle semantics.

## Context and identity

This host fragment assumes a service and a trusted target identity/generation obtained from the provider. It asks for capabilities without carrying credentials in the context:

```java
var target = new NuggetAdminTarget(
        nuggetId, runtimeId, generation, "", "");
var context = new NuggetAdminRequestContext(
        NuggetAdminContract.PROTOCOL_VERSION,
        "capabilities-1", "operator-check-1", target,
        NuggetAdminOperationKind.CAPABILITIES,
        Instant.now(), Duration.ofSeconds(10), Instant.EPOCH, Map.of());
var capabilities = adminService.adminCapabilities(context);
```

`nuggetId`, `runtimeId` and `generation` are authoritative. Display names, endpoints, provider names and CLI prompts are metadata. Use an exact positive generation for mutations where available; generation zero is weaker current/unknown matching only where explicitly permitted.

The shared context contains bounded client/request/correlation metadata, not a PIN, admission credential, authorization decision, OAuth token or browser session. Its client budget is bounded to five minutes and defaults to 30 seconds. Servers still enforce their own deadlines and preserve lifecycle/control absolute deadlines. Unsupported protocol versions fail during context construction, before provider work.

## Bounded query model

```java
var query = new NuggetAdminQuery(
        0L, 50,
        List.of(new NuggetAdminQuery.Filter(NuggetAdminField.STATE, "RUNNING")),
        List.of(new NuggetAdminQuery.Sort(
                NuggetAdminField.STABLE_ID, NuggetAdminSortDirection.ASC)));
```

The receiving typed family must support the selected field. Filters are exact matches on allowlisted public fields; providers must map them explicitly rather than reflect over implementation properties. Query construction rejects negative/overflowing offsets and oversized pages. The default page is 50, maximum 500; at most 16 filters and four requested sorts are retained. Stable identity is the final deterministic sort tie-breaker.

Inspect page and result metadata for partiality, truncation, unknown totals and warnings. Read-only results include only authorized visible data; hidden resources must not inflate counts. Concurrently changing sources do not become a transactional snapshot merely because they are paginated.

## Envelopes and uncertain effects

The optional envelope accepts exact public request/result DTOs bound to the selected `NuggetAdminOperationKind`. It rejects strings, maps, arrays, free-form JSON nodes, wrong-family DTOs and mismatched payload tokens. It is not a reflection router or a second command language.

Result headers distinguish accepted, in progress, completed, denied, unavailable, timed out, partial and outcome unknown. A timeout after acceptance can leave an effect in progress or uncertain. Preserve its operation identity and reconcile/status-check it; do not silently replay a lifecycle mutation against a new target generation.

See the [JSON contract](admin-json.md), [CLI](cli.md), [administrative PIN](../security/administrative-pin.md) and [lifecycle guide](../deployment/lifecycle.md).
