# Hello Remote Service

## Purpose
Demonstrates the smallest classpath deployable J7M application: package a service, publish it, activate it, look it up through a Java interface proxy, invoke it, stop it, and unload it.

## Architecture flow
Manifest -> `.j7m` bundle -> local repository -> `ApplicationManager` activation -> gRPC export -> runtime lookup -> proxy invocation. This is the package/deploy/start/status flow for the smallest full example.

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
Executable demo: `./gradlew :j7m-demo:runHelloRemoteServiceExample --no-daemon`.

## Start / activate
The same demo activates the application in a local runtime.

## Invoke / verify behavior
The demo prints `phase5.5 hello example greeting: hello phase55 ...`.

## Status / observability
The demo prints active application count and registry cleanup after stop.

## Stop / unload
The demo stops and unloads the application and verifies zero active applications.

## Upgrade / rollback, where applicable
Use `./gradlew :j7m-demo:runApplicationUpgradeRollbackDemo --no-daemon`; this example has one version only.

## Expected output
Look for `phase5.5 hello example verify accepted: true` and `phase5.5 hello example active after unload: 0`.

## Security notes
Development mode allows unsigned bundles. Strict verification rejects unsigned bundles.

## Cleanup
The demo uses temporary directories and leaves no repository state under the project tree.

## Known limitations
This is a minimal classpath example. It does not demonstrate JPMS isolation, production signing, durable repository replication, or rolling upgrades.
