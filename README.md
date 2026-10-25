# J7M

J7M is a Java 25 distributed-object middleware and sovereign application platform. It provides live remote-object references, automatic Java interface proxies, transport-neutral invocation, decentralized discovery, application packaging and activation, sovereign reliability services, and a secured nugget operations plane.

The runtime is built around concrete `ObjectRef` identities rather than ordinary static RPC endpoints. gRPC is the default modern transport channel, RMI remains a legacy transport implementation of the same middleware semantics, and authorization is still enforced at the server-side boundary when a reference is invoked.

## Capabilities

- Remote `ObjectRef` passing for arguments, return values, callbacks, and rediscovery.
- Automatic proxies for Java service interfaces.
- Transport-neutral dispatch with gRPC as the default modern channel and RMI as the legacy channel.
- Decentralized discovery with local preference when policy permits.
- Versioned `.j7m` application packaging, verification, repository handling, and activation lifecycle.
- Reliability services for durable logs, durable queues, coordination, consensus, idempotency, fencing, outbox/inbox, repository replication, and saga workflows.
- Secured nugget administration for runtime status, discovery, inspection, lifecycle controls, JSON output, and audit-aware operations.

## Requirements

- Java 25.
- The repository Gradle wrapper.

Build the repository:

```bash
./gradlew --no-daemon build
```

Run the shortest verified demo:

```bash
./gradlew --no-daemon :j7m-demo:runHelloRemoteServiceExample
```

## Examples

Source-adjacent example guides are kept with the runnable examples:

- [Hello remote service](examples/hello-remote-service/README.md)
- [Annotation-first simple service](examples/annotation-first-simple-service/README.md)
- [Nugget admin console](examples/nugget-admin-console/README.md)

Additional examples under `examples/` cover callbacks, deployable applications, HA/HP selection, secure services, regulated-style evidence, streaming, workload processing, and application templates.

### Annotation glossary

Annotation examples distinguish executable lifecycle methods from build and
descriptor metadata. See the [annotation guide](documentation/docs/annotations/index.md)
and [coverage matrix](documentation/docs/annotations/index.md)
for the complete inventory and the consumer responsible for each annotation.

- `@HealthCheck` and `@ReadinessCheck` identify health/readiness methods; the example does not install a polling service.
- `@Retry` and `@Backpressure` describe invocation/resource policy; the configured runtime policy owns enforcement.
- `@Checkpoint`: recovery metadata; the annotation alone does not persist a checkpoint.
- `@Restore`: recovery metadata; the annotation alone does not run a durable restore engine.
- `@Async`: descriptor metadata; the annotation alone does not start a scheduler.
- `@HighAvailability`: descriptor metadata; client-side selection policy owns HA routing.
- `@Benchmark`: descriptor metadata; the annotation alone does not install a benchmark runner.

The glossary above describes the legacy examples. The opt-in managed profile
provides production adapters for DI, live bindings, invocation policies and durable
work. See [managed distributed support](j7m-application-distributed/README.md),
[the complete managed syntax](documentation/docs/annotations/index.md)
for application examples. Annotations select
explicit host capabilities; they do not install backends or grant authority.

## Module Orientation

The main implementation is split across bounded Gradle modules:

- `j7m-api`, `j7m-kernel`, and `j7m-runtime` define the public API, transport-neutral core model, and runtime behavior.
- `j7m-annotation-processor` generates explicit schema adapters and finite component indexes at compilation; application runtimes consume their public SPI without depending on the compiler module.
- Managed applications: [POJO hosting](j7m-application-platform/MANAGED-POJO-HOSTING.md), [dependency injection](j7m-application-platform/MANAGED-CONTAINER.md), [live configuration](j7m-application-platform/CONFIGURATION.md) and [host policies](j7m-application-platform/MANAGED-HOST-POLICIES.md).
- `j7m-transport-grpc` and `j7m-transport-rmi` provide concrete transports.
- `j7m-application-platform`, `j7m-deployer-cli`, and `j7m-gradle-plugin` support packaging, verification, deployment, and local tooling; the opt-in authenticated [network repository](j7m-application-platform/NETWORK-REPOSITORY.md) adds verified resumable artifact transfer.
- `j7m-durable-log`, `j7m-messaging`, `j7m-coordination`, `j7m-consensus`, `j7m-saga`, and `j7m-isolation` provide sovereign reliability and isolation capabilities.
- `j7m-distributed-core` adds opt-in quorum-backed resources, leases, coordination, participating-resource transactions, retained events, fenced migration, replicated queues, durable jobs/sagas and deduplicated RPC; see its [contract and limits](j7m-distributed-core/README.md).
- `j7m-integrations` connects application JDBC transactions to replicated queues through a transactional outbox/inbox and provides concrete OpenTelemetry/Prometheus export; see its [contracts and verification limits](j7m-integrations/README.md).
- `j7m-cluster-management` manages authenticated, fenced rolling upgrades of real hosted applications and canary traffic; see its [contract and limits](j7m-cluster-management/README.md).
- `j7m-adapter-spi`, `j7m-adapter-tck`, `j7m-testkit`, `j7m-tests`, `j7m-demo`, and `j7m-examples` contain extension, validation, demo, and example support.

## Maturity and Limits

J7M is an active middleware implementation with repository-local validation and examples. It does not claim generic exactly-once distributed execution, ACID transactions across arbitrary external systems, full OS sandboxing, certification status, or performance superiority over other systems. The opt-in distributed transaction contract covers participating replicated resources and may block while a prepared participant cannot obtain its coordinator's durable decision. Reliability features require the documented idempotency, fencing, durability, and application side-effect discipline supplied by the relevant modules and tests.

## Documentation

The [J7M manual](documentation/docs/index.md) is the primary developer and operator
reference. It covers annotated and explicit APIs, distributed application patterns,
deployment, discovery, security, operation and current implementation boundaries.
See [documentation build instructions](documentation/README.md) for a local preview.
