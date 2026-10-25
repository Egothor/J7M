# Streaming Pipeline Example

## Purpose
Demonstrates a deployable J7M application-level streaming pattern with bounded server batch and client batch operations, bidirectional exchange, cancellation, backpressure metadata, max item size rejection, and metrics.

## Architecture flow
Manifest -> `.j7m` bundle -> local repository -> classloader activation -> gRPC export -> proxy lookup -> session and batch operations -> stop and unload.

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
Executable demo: `./gradlew :j7m-demo:runStreamingPipelineExample --no-daemon`.

## Start / activate
The demo activates `org.j7m.examples:streaming-pipeline:1.0.0` in a local runtime.

## Invoke / verify behavior
The demo opens a session, submits client batches, reads server batches incrementally, runs an exchange call, rejects an oversized item, observes backpressure, cancels the session, and prints metrics.

## Status / observability
The demo prints session metrics, service admin status, active application count, and registry cleanup after stop.

## Stop / unload
The demo deactivates and unloads the application, then verifies that the streaming service is no longer registered.

## Upgrade / rollback, where applicable
Use `./gradlew :j7m-demo:runApplicationUpgradeRollbackDemo --no-daemon` for the common application upgrade and rollback flow. This example ships one pipeline version.

## Expected output
Look for `phase5.5 streaming oversized rejected: 1`, `phase5.5 streaming backpressure: true`, and `phase5.5 streaming active after unload: 0`.

## Security notes
This example uses development security settings. Production streaming deployments should enforce authorization, payload limits, resource policy, and backpressure.

## Cleanup
The demo uses temporary directories and leaves no repository state under the project tree.

## Known limitations
The example uses local bounded session state. It is not a durable streaming engine, message broker, exactly-once delivery system, or distributed compute platform.
