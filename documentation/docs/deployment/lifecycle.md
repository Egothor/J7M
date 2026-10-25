# Readiness, Drain and Shutdown

An application is usable only after its required components, policies, resources and exports have been prepared and admitted. Successful construction of one object is not the application's global readiness barrier.

Managed activation validates generated metadata and installed capabilities before application construction where the contract requires it. Hosted factories prepare their resources, and admission opens only after the activation has committed its required registrations. A failed preparation must not leave a partially usable application behind.

## Probes

Use `@HealthCheck` for the declared health contract and `@ReadinessCheck` for whether the instance can accept its intended work. The host probe coordinator consumes the registered checks. A successful health check does not mean a replica owns a partition, a queue has available credit, or a database transaction can commit.

Avoid expensive unbounded work in a probe. Keep results safe for operational display and ensure a blocked dependency cannot indefinitely retain the probe executor.

## Graceful replacement

1. Prepare and verify the replacement application and its host configuration.
2. Admit the replacement only after its dependencies and policies are ready.
3. Stop admitting new work to the instance being retired.
4. Request drain and wait for the tracked physical invocation/child-task lifetime.
5. Close application-owned registrations, subscriptions and borrowed-resource guards.
6. Run shutdown hooks and release classloader/resource ownership only when that physical lifetime has finished.

`ApplicationManager` provides `drain`, `deactivate` and `unload` operations for actual application instances. Their results must be inspected. A timeout or incomplete drain is an operational outcome; it does not prove that all Java threads have stopped.

## Logical and physical completion

The caller's future can fail, time out or be cancelled while entered application work remains physically active. J7M retains the corresponding managed ownership until tracked work exits. Releasing the application classloader or physical bulkhead permit early would make resource limits and shutdown guarantees misleading.

A handler that ignores interruption can continue executing. Revocation/fencing prevents its protected middleware effects where supported; it cannot forcibly undo arbitrary HTTP, JDBC or filesystem work. Design external effects for explicit idempotency and reconciliation.

**Sources:** ApplicationManager (repository source: `j7m-application-platform/src/main/java/org/j7m/middleware/deployer/ApplicationManager.java`), managed hosting contract (repository source: `j7m-application-platform/MANAGED-POJO-HOSTING.md`).
