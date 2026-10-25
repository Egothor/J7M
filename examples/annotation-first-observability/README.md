# Annotation First Observability

## Start Here
Start with the four focused source units:
`workload/WorkloadAdmissionMetadata`,
`routing/RoutingOrderingMetadata`,
`recovery/RecoveryProgressCallbackMetadata`, and
`benchmark/ObservabilityBenchmarkMetadata`.

## Purpose
Demonstrates workload/admission, routing/ordering, recovery/progress/callback,
and observability/benchmark annotations without one overloaded teaching class.

## Architecture flow
The focused classes record descriptor metadata for asynchronous work,
callbacks, checkpoints, progress, shard routing, priority, rate limits, data
locality, resource requirements, and audit/benchmark behavior. Runtime
schedulers and policies may consume that descriptor metadata; the annotations
are metadata-only in this example.

## Annotations demonstrated
`@J7mApplication`, `@J7mService`, `@RemoteMethod`, `@Async`, `@Audit`,
`@BackpressureAware`, `@Benchmark`, `@Callback`, `@Cancellable`,
`@Checkpoint`, `@DataLocality`, `@HighAvailability`, `@LongRunning`,
`@OrderedBy`, `@PreferLocal`, `@Priority`, `@Progress`, `@RateLimited`,
`@ResourceRequirement`, `@Restore`, `@ShardKey`, and `@Sensitive`.

## Injected services demonstrated
This advanced metadata example does not inject services. The simple and
security/audit examples teach current context injection.

## Enforcement status
These annotations are descriptor or metadata-only in this example. The intended
consumers are configured schedulers, selectors, audit policy, recovery engines,
and resource admission components. Those consumers are not implemented here,
and behavior must not be inferred from the annotations by themselves.

## Prerequisites
Use Java 25 and the local J7M build. No external observability platform,
scheduler, vendor SDK, or service mesh is required.

## Build
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Test
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Package
Run `./gradlew packageExamples --no-daemon`.

## Verify bundle
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Run / demo
Run `./gradlew validateAnnotationExamples --no-daemon`; the validator compiles
the focused source units and compares deterministic metadata smoke output with
`EXPECTED_OUTPUT.txt`.

## Safe modification exercise
Change the workload task key from `task-7` to `task-8` in
`ObservabilityExampleMain` and `EXPECTED_OUTPUT.txt`, then run
`./gradlew validateAnnotationExamples --no-daemon`.

## Deploy to local repository
The annotation demo validates observability metadata locally. Use the standard
deployable examples when repository publishing is required.

## Start / activate
The smoke run constructs focused metadata classes directly.

## Invoke / verify behavior
The smoke run verifies descriptor-only workload, routing, recovery, callback,
audit, benchmark, and resource metadata.

## Status / observability
The smoke run prints safe descriptor and redaction summaries.

## Stop / unload
The demo closes invocation scopes and exits without external services.

## Upgrade / rollback, where applicable
Use the existing upgrade and rollback examples for deployment strategy
coverage. This example focuses on observability annotations.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`.

## Security notes
Audit and benchmark annotations are metadata in this example. Configured audit
policy remains authoritative.

## Redaction notes
Sensitive callback and progress values must be redacted before public output.

## Troubleshooting
If metadata is not present in a descriptor, verify the annotation target is
valid for the method, parameter, or type.

## Cleanup
The example owns no external resources.

## Known limitations
The example does not implement a scheduler, service mesh, tracing backend, or
external observability product.
