# Callbacks And Events

## Purpose
Shows that J7M can pass live Java callback objects as remote references, not as serialized copies.

## Architecture flow
Client runtime creates `EventCallback`; server runtime exports `EventService`; client invokes `registerAndPing`; middleware auto-exports the callback and server calls back through an `ObjectRef`.

## Prerequisites
Use Java 25. gRPC and RMI tests run on local loopback.

## Build
Executable validation: `./gradlew buildExamples --no-daemon`.

## Test
Executable validation: `./gradlew :j7m-tests:test --no-daemon --rerun-tasks`.

## Package
This example is a runtime semantics example, not a deployable bundle. Equivalent tested task: `./gradlew verifyExamples --no-daemon`.

## Verify bundle
Documentation-only for this example; no `.j7m` bundle is produced.

## Deploy to local repository
Documentation-only; run the demo task instead.

## Start / activate
Executable demo: `./gradlew :j7m-demo:runCallbacksAndEventsExample --no-daemon`.

## Invoke / verify behavior
The demo invokes callbacks over gRPC and RMI.

## Status / observability
The demo prints callback values and safe audit/service invocation log entries for the callback invocation.

## Stop / unload
Runtime close unexports the service and callback references.

## Upgrade / rollback, where applicable
Not applicable to this runtime semantics example.

## Expected output
Look for `phase5.5 callbacks grpc values: [grpc-event]` and `phase5.5 callbacks rmi values: [rmi-event]`.

## Security notes
The example uses development security mode. Production callback services should use mTLS and authorization policies.

## Cleanup
The demo uses in-memory registries and closes all runtimes.

## Known limitations
This example proves remote object semantics only. It is not a durable event bus or messaging system.
