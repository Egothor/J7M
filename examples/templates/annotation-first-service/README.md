# Annotation-First Service Template

## Purpose
This template is the preferred starting point for Phase 6.11 application code.
It shows how a developer declares J7M metadata with annotations and requests
safe public capabilities through J7M-native injection.

## Architecture flow
The application class uses `@J7mApplication`. The service class uses
`@J7mService` and method-level metadata. The application platform can derive an
`AnnotatedApplicationDescriptor` from the annotated classes. The full examples
under `examples/annotation-first-*` and `runAnnotationModelDemo` demonstrate
the executable path.

## Annotations demonstrated
`@J7mApplication`, `@J7mService`, `@Inject`, `@J7mResource`, `@ConfigValue`,
`@Startup`, `@RemoteMethod`, `@RequiresRole`, `@RequiresPermission`,
`@Idempotent`, `@DurableQueueConsumer`, `@SagaDefinition`, and `@SagaStep`.

## Injected services demonstrated
The constructor requests public `DurableQueue` and `SagaService` capabilities
plus a configuration value. It must not receive runtime maps, registry
internals, transport internals, or classloader controls.

## Prerequisites
Use Java 25 and the local J7M build. No external DI framework, broker, cloud
SDK, vendor adapter, Docker, or Kubernetes dependency is required.

## Build
Run `./gradlew buildExamples --no-daemon` to validate the template structure
and compile the backing `j7m-examples` annotation examples.

## Test
Run `./gradlew :j7m-examples:test --no-daemon --rerun-tasks`.

## Package
Run `./gradlew packageExamples --no-daemon`.

## Run / demo
Run `./gradlew runAnnotationModelDemo --no-daemon`.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
The demo prints descriptor service names, lifecycle order, authorized and
denied security outcomes, saga and queue descriptor-only summaries, and a safe
redaction summary.

## Security notes
Security annotations become descriptor metadata. The secure full example also
evaluates the role rule locally to prove an authorized and denied path. Runtime
authorization policy remains the authoritative enforcement point for exported
services.

## Redaction notes
Use `@Sensitive` for request values that must not appear in status, audit, or
demo output. Injection diagnostics redact secret-like configuration keys and
sentinel secret values.

## Troubleshooting
Missing dependencies usually mean the qualifier in `@Named` or `@J7mResource`
does not match a bound public capability. Invalid annotation combinations fail
descriptor validation with stable middleware errors.

## Cleanup
The annotation demo uses in-memory state and public capability proxies.

## Known limitations
Saga and durable-queue annotations in this template are descriptor metadata.
Durable execution, queue leasing, retry, recovery, and compensation are still
provided by the public `SagaService` and `DurableQueue` APIs.

## Low-Level Fallback
Advanced code can still use `ApplicationContext.services().export(...)` or
`MiddlewareRuntime.export(...)` directly when explicit control is required.

## Managed distributed reference

`src/main/java/org/j7m/examples/template/distributed/ManagedDistributedUsage.java`
shows the current managed policy, reducer, subscription, job, producer and saga
annotations on ordinary application classes. Compile it with `j7m-api`,
`j7m-application-distributed` and `j7m-annotation-processor`. The repository test
`ManagedDistributedReferenceTest` performs that compilation with the real processor.
The host explicitly installs the named policies, authenticated backends, schemas
and administrative reconciliation capability. The reference does not install them.
See [managed saga contracts](../../../j7m-application-distributed/SAGAS.md) and the
[current implementation gates](../../../INBOX/PLAN-IMPLEMENTACE-ANOTACI.md).

The older template classes above exercise the legacy descriptor profile. Their
limitations do not describe the opt-in `managed-v1` production adapters.
