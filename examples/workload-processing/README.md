# Workload Processing Example

## Purpose
Demonstrates a deployable J7M application that exposes a small in-memory workload service with task submission, progress, cancellation, checkpointing, lease ownership, and fencing-token rejection.

## Architecture flow
Manifest -> `.j7m` bundle -> local repository -> classloader activation -> gRPC export -> Java proxy lookup -> workload lifecycle calls -> stop and unload.

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
Executable demo: `./gradlew :j7m-demo:runWorkloadProcessingExample --no-daemon`.

## Start / activate
The demo activates `org.j7m.examples:workload-processing:1.0.0` in a local runtime.

## Invoke / verify behavior
The demo submits a task, reads progress, writes a checkpoint, acquires a lease, rejects a stale fencing token, cancels another task, and prints the example status line.

## Status / observability
The demo prints active application count, service admin status, task state, and registry cleanup after stop.

## Stop / unload
The demo deactivates and unloads the application, then verifies that the exported workload service is no longer registered.

## Upgrade / rollback, where applicable
Use `./gradlew :j7m-demo:runApplicationUpgradeRollbackDemo --no-daemon` for the common application upgrade and rollback flow. This example ships one workload version.

## Expected output
Look for `phase5.5 workload stale fence accepted: false`, `phase5.5 workload cancelled state: CANCELLED`, and `phase5.5 workload active after unload: 0`.

## Security notes
This example uses development security settings. Production deployments should apply authorization, resource policy, and payload limits appropriate to workload operations.

## Cleanup
The demo uses temporary directories and leaves no repository state under the project tree.

## Known limitations
The example uses local in-memory task state. It is not a durable queue, consensus system, exactly-once execution platform, distributed transaction manager, or full distributed compute platform.
