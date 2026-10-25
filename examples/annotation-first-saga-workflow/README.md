# Annotation First Saga Workflow

## Start Here
Start with `InvoiceSagaWorkflow`. It shows ordered steps, compensation, and
dead-letter metadata while keeping durable orchestration owned by `SagaService`.

## Purpose
Demonstrates saga annotations, compensation metadata, dead-letter metadata, and
safe correlation data with J7M-native injection.

## Architecture flow
The workflow class is annotated with `@SagaDefinition`. Its methods define
ordered steps and compensation. The demo validates this descriptor metadata and
manually runs a local failing scenario to show the intended compensation order.

## Annotations demonstrated
`@J7mApplication`, `@Inject`, `@J7mResource`, `@CurrentInvocation`,
`@SagaDefinition`, `@SagaStep`, `@Compensation`, and `@DeadLetterHandler`.

## Injected services demonstrated
The workflow receives the public `SagaService` API and the safe current
invocation context. It does not receive durable-log internals or transaction
coordinator internals.

## Enforcement status
Current invocation injection is executed by the J7M injector. Saga annotations
are descriptor-only in this example; durable orchestration remains in
`SagaService`.

## Prerequisites
Use Java 25 and the local J7M build. No external workflow engine, XA manager,
JTA manager, or distributed ACID transaction manager is required.

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
the source tree and compares deterministic metadata smoke output with
`EXPECTED_OUTPUT.txt`.

## Safe modification exercise
Add a third descriptor step named `notify`, update the smoke output marker,
and run `./gradlew validateAnnotationExamples --no-daemon`.

## Deploy to local repository
The annotation demo validates saga descriptor metadata locally. Use the standard
deployable examples when repository publishing is required.

## Start / activate
The demo constructs the workflow through J7M-native injection inside a current
invocation scope.

## Invoke / verify behavior
The demo runs reserve, simulated failure, compensation, and dead-letter steps
in a deterministic local order.

## Status / observability
The demo prints the ordered saga events and safe audit summary.

## Stop / unload
The demo closes invocation scopes and exits without external resources.

## Upgrade / rollback, where applicable
Use the existing upgrade and rollback examples for deployment strategy
coverage. This example focuses on saga annotation metadata.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`; the smoke output prints ordered step,
compensation, and dead-letter metadata.

## Security notes
Saga annotations are descriptor-only in this example. Durable execution,
recovery, retry, and compensation semantics remain owned by `SagaService`.

## Redaction notes
The example prints invoice ids only and never prints command payload bytes or
credentials.

## Troubleshooting
If descriptor validation fails, verify each `@SagaStep` has a stable name and
order and that compensation names match real step names.

## Cleanup
The demo uses in-memory example state and public capability proxies.

## Known limitations
This example does not create a generic distributed transaction or exactly-once
execution guarantee. It shows descriptor metadata and local step ordering.
