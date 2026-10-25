# Isolation, Sensitive Data and Audit

`@IsolationProfile` selects a host-supported capability/resource policy. An annotation is not a Java security sandbox. In-process code shares the enclosing JVM's fundamental execution boundary; a separate process provider establishes a different operational boundary with its own supported controls.

Choose the actual isolation provider and inspect its supported capabilities. Apply host/process, filesystem and network restrictions where required. Do not describe a requested CPU or memory string in a manifest as proof that the operating system enforces that limit.

## Bounded application behavior

Managed admission, bulkheads, payload bounds, tracked child tasks and physical drain reduce the consequences of overload and lifecycle mistakes. They do not terminate arbitrary uncooperative Java code or retroactively undo an external effect.

`@Confidential` and `@Sensitive` express security/data-handling requirements consumed by their applicable policy. Configure the transport protection and redaction policy that satisfy those requirements. A label alone does not encrypt a connection or sanitize a custom application log statement.

## Audit useful decisions

Audit admission/denial, lifecycle changes and controlled administrative effects using stable, bounded identifiers and safe status. Include correlation and the actual authorized principal where the contract supports it. Exclude secret values and arbitrary payloads.

Saga reconciliation is a privileged administrative decision about an uncertain effect. Its safe audit projection records decision/status and observed/committed versions without copying the business payload. A stale or unauthorized reconciliation must not create a successful audit event.

Audit is evidence of what the configured system observed and decided. It is not proof that an arbitrary external service applied an effect exactly once. Preserve uncertainty explicitly and use reconciliation when an effect cannot be determined from the protected resource's own durable state.

**Sources:** isolation API (repository source: `j7m-api/src/main/java/org/j7m/middleware/api/isolation/IsolationProvider.java`), managed saga contract (repository source: `j7m-application-distributed/SAGAS.md`).
