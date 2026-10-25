# J7M Deployable Hello Application

## Purpose
Demonstrates the original classpath application-platform vertical slice with `HelloService`, `.j7m` packaging, repository publish, live activation, upgrade, and rollback validation paths.

## Architecture flow
Manifest -> `.j7m` bundle -> local repository -> classloader activation -> gRPC export -> proxy invocation.

## Prerequisites
Use Java 25 and the repository Gradle wrapper.

## Build
Executable validation: `./gradlew buildExamples --no-daemon`.

## Test
Executable validation: `./gradlew :j7m-tests:test --no-daemon --rerun-tasks`.

## Package
Executable validation: `./gradlew packageExamples --no-daemon`.

## Verify bundle
Executable validation: `./gradlew verifyExamples --no-daemon`.

## Deploy to local repository
Executable demo: `./gradlew :j7m-demo:runApplicationDeploymentDemo --no-daemon`.

## Start / activate
The demo activates the application through `ApplicationManager`.

## Invoke / verify behavior
The demo looks up `HelloService` and invokes `hello("platform")`.

## Status / observability
The demo prints active application count, activation state, repository artifact count, and registry cleanup.

## Stop / unload
The demo deactivates and unloads the application.

## Upgrade / rollback, where applicable
Use `./gradlew :j7m-demo:runApplicationUpgradeRollbackDemo --no-daemon`; this classpath example has one version.

## Expected output
Look for `phase5.2 verify accepted: true` and `phase5.2 active after unload: 0`.

## Security notes
Development mode permits unsigned bundles. Strict verification rejects unsigned bundles.

## Cleanup
The demo uses a temporary repository.

## Known limitations
Classpath unload is best-effort. This example does not implement production signing, repository replication, rolling upgrade, consensus, or exactly-once delivery.
