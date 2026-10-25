# Limits, Timeouts and Observability

Configure a limit where the corresponding resource is owned. An application concurrency policy, transport message bound, queue credit count and native snapshot capacity are distinct controls. Raising one does not enlarge the others.

For each deployment, record the maximum payload and result size, concurrent invocations, retained durable identities, active workers, queue/event credits, JDBC capacity and shutdown budget. Include encoded overhead and recovery headroom. A queue with bounded messages can still exhaust a journal if every idle poll or repeated retained result is durably appended.

## Deadline budgets

Use one caller-visible deadline and pass the remaining budget through subordinate operations. A retry, alternate seed or peer expansion must not reset the overall budget. Logical timeout is an observation by the caller; it does not prove that entered business code, a child task or an external database operation has physically stopped.

Use measured authenticated clock health for native leases, schedules and time-sensitive ownership. A UTC timestamp alone does not establish clock accuracy. When the configured uncertainty policy denies a time-sensitive mutation, repair time/authority rather than bypassing the guard.

## Metrics and diagnostics

Observe queue depth and claims, physical in-flight work, admission rejection, lease renewal failures, stale fences, event lag, retained identity usage and JDBC/outbox backlog. Keep dimensions bounded: operation family and safe status are useful; arbitrary object IDs, payload strings and exception messages are usually unsuitable metric labels.

Registry diagnostics expose discovery-query counts, contacted peers, trust rejection and cache/peer state. The bootstrap server logs safe fixed diagnostic text and exception class names at DEBUG. It does not log raw credential material or record payloads.

J7M's approved Prometheus HTTP surface is a metrics scrape endpoint with an exact `/metrics` route. Do not infer a general administration REST API from that endpoint. The headless admin contract and the application's invocation transports remain separate.

See [numeric limits](../reference/limits.md), [monitoring](../operations/monitoring.md) and [capacity planning](../operations/capacity.md).
