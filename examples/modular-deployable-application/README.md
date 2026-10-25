# JPMS Modular Deployable Application

## Purpose
Demonstrates JPMS `ModuleLayer` activation with separate API and implementation modules.

## Architecture flow
Module jars -> `.j7m` bundle `modules/` directory -> repository -> dedicated `ModuleLayer` -> gRPC export -> proxy invocation.

## Prerequisites
Use Java 25 and named J7M API modules.

## Build
Executable validation: `./gradlew buildExamples --no-daemon`.

## Test
Executable validation: `./gradlew :j7m-tests:test --no-daemon --rerun-tasks`.

## Package
Executable validation: `./gradlew packageExamples --no-daemon`.

## Verify bundle
Executable validation: `./gradlew verifyExamples --no-daemon`.

## Deploy to local repository
Executable demo: `./gradlew :j7m-demo:runModularApplicationDeploymentDemo --no-daemon`.

## Start / activate
The demo activates the application through a dedicated `ModuleLayer`.

## Invoke / verify behavior
The demo looks up `ModularHelloService` through the normal runtime proxy path.

## Status / observability
The demo prints module mode, module layer id, application modules, and active count after unload.

## Stop / unload
The demo deactivates and unloads the JPMS application.

## Upgrade / rollback, where applicable
Executable demo: `./gradlew :j7m-demo:runApplicationUpgradeRollbackDemo --no-daemon`.

## Expected output
Look for `phase5.2 jpms module mode: JPMS` and `phase5.4 rollback result: true`.

## Security notes
This example uses development-mode unsigned bundles.

## Cleanup
The demos use temporary repositories.

## Known limitations
JPMS unload is best-effort. This example does not implement rolling upgrade, consensus, durable repository replication, or exactly-once delivery.
