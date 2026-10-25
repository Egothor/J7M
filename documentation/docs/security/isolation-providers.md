# Isolation Providers

The `org.j7m.isolation` JPMS module supplies first-party providers for the `org.j7m.middleware.api.isolation` SPI. Select a provider by its actual enforcement capabilities, not by the requested mode's name.

`IsolationProvider` owns execution admission, process handles, capture buffers and provider work resources. It exposes policy evaluation, status, health, metrics, audit and recovery cleanup. Methods may block for process launch, termination and output draining; use bounded execution timeouts and bounded `await` calls.

## Provider boundaries

`InProcessIsolationProvider` evaluates explicit J7M filesystem, network, environment, output, thread and queue capability requests. It cannot intercept arbitrary Java I/O, allocation or thread creation in the enclosing JVM.

`LocalProcessIsolationProvider` launches a local child process with a checked working directory and sanitized environment. It supervises termination and captures bounded stdout/stderr. It is not an OS filesystem jail, network firewall, container runtime or CPU/memory controller. Use host or container controls where those boundaries are required.

| Limit or control | Local process behavior |
|---|---|
| Concurrent executions | Enforced at admission; no unbounded waiting queue. |
| Runtime deadline | Enforced using the smaller request timeout and resource-budget maximum runtime. |
| Standard output/error | Independent enforced caps using the smaller stream-policy and resource-budget bounds; truncation is explicit. |
| Environment count/value size | Enforced before launch after policy evaluation. |
| Command-line length | Enforced before launch. |
| Queue/backlog budget | Policy only; the provider has no pending-work queue. |
| In-process thread/virtual-thread budget | Policy only. |
| CPU and memory budget | Advisory; requires external enforcement for a hard limit. |
| Working-directory size | Not a hard disk quota; apply external storage controls. |

Filesystem policy evaluates normalized roots and traversal; it does not turn a symlink-sensitive host filesystem into a kernel jail. Network policy evaluates explicit destinations, including host/port and loopback rules; it does not filter sockets opened outside that capability boundary.

## Execute and release ownership

This host fragment assumes an already configured provider and validated `IsolationExecutionRequest`. The caller owns the bounded wait and the provider's lifetime:

```java
try (IsolationProvider provider = configuredProvider) {
    IsolationExecutionHandle handle = provider.start(request);
    IsolationExecutionResult result = handle.await(request.timeout());
    // Inspect terminal outcome and output truncation before accepting results.
}
```

Use `cancel` on the execution handle when the caller withdraws work. A provider close stops admission, cancels owned active processes and boundedly releases supervision/capture resources; it is idempotent. Safe redacted status and audit remain inspectable after close. Pre-launch denial and launch failure release the admission slot.

## Outcomes and diagnostics

The lifecycle vocabulary includes `CREATED`, `STARTING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT`, `KILLED` and `CLEANED_UP`. The local provider's live handle reports `RUNNING`, followed by a terminal completed/failed/cancelled/timed-out result. Process starting, forced kill and cleanup are also audit events; a kill used to enforce a timeout does not convert that logical result into success. Cleanup preserves the execution's terminal outcome.

Stable failures include `OVERLOADED`, `DEADLINE_EXCEEDED`, `CANCELLED`, `PERMISSION_DENIED`, `UNAVAILABLE` and `INVALID_ARGUMENT`. Treat an overload response as admission rejection, not as a queued execution.

Output policies bound stdout and stderr separately and mark truncation. Redaction applies to secret patterns and configured literals before public diagnostics, audit or string rendering. Status and metrics expose safe summaries, not raw command/environment secrets. Application code remains responsible for what it writes to any logging mechanism outside the provider.
