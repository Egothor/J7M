# Metrics, Logs, Tracing and Audit

Monitor a user's operation across its real boundaries. Invocation success, physical completion, durable commit and an external effect can occur at different times. One latency histogram cannot explain all of them.

| Signal | Operational interpretation |
| --- | --- |
| Admission rejection and active physical work | Whether bounded worker/permit capacity is exhausted |
| Deadline/cancellation outcomes | What the caller observed; not proof of physical termination |
| Registry query/trust counters | Discovery activity, contact failure and rejected remote records |
| Queue claims, active receipts and DLQ state | Work ownership, retries, poison messages and recovery backlog |
| Event cursor lag and expiry | Whether a consumer can continue or requires an explicit recovery path |
| Lease renewals and stale-fence rejection | Authority continuity and obsolete worker activity |
| Job/saga state transitions | Durable progress, completion and unresolved effects |
| Journal/snapshot/disk usage | Remaining retained-state and recovery headroom |

## Export safely

The optional integrations module provides bounded observation/export infrastructure and an explicit Prometheus scrape endpoint. Its HTTP handler accepts the exact GET `/metrics` route; it is not a general web administration application. Bind and protect the scrape surface appropriately for the deployment.

Use `@Observed` where an application span is useful and the installed policy supports it. Keep application spans distinct from middleware transport/resource spans and avoid counting one boundary twice. Carry safe correlation through owned child work rather than inventing a new trace for each retry of the same durable identity.

Limit label cardinality. Use operation family, safe status and a controlled profile identifier; avoid message IDs, arbitrary exception text, user input or full application payloads. `@Sensitive` does not sanitize custom application logging automatically.

## Audit versus telemetry

Metrics summarize trends. Trace events help diagnose one operation. Audit records capture controlled decisions and effects under the configured policy. Keep the retention and access model appropriate to each. A safe saga reconciliation audit can identify the decision and versions without copying the business data.

When reporting incidents, include the time range, target identities, safe error codes, physical work state and relevant ownership version. Preserve UNKNOWN outcomes explicitly. See [runbooks](runbooks.md) for the next action.

**Source:** Observability (repository source: `j7m-integrations/src/main/java/org/j7m/middleware/integrations/Observability.java`).
