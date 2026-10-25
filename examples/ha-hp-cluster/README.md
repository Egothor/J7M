# HA HP Cluster

## Purpose
Demonstrates multiple instances of one logical service, HA failover, overload penalty handling, and visible HP selection explanation status.

## Architecture flow
Two runtimes export the same service name. The client uses HA lookup. After the first target closes, the proxy invokes the remaining healthy target.

## Prerequisites
Use Java 25. The example runs in one JVM with real gRPC loopback transports.

## Build
Executable validation: `./gradlew buildExamples --no-daemon`.

## Test
Executable validation: `./gradlew :j7m-tests:test --no-daemon --rerun-tasks`.

## Package
This runtime cluster example is not packaged as `.j7m`; equivalent tested task: `./gradlew verifyExamples --no-daemon`.

## Verify bundle
Documentation-only for this example.

## Deploy to local repository
Documentation-only; run the demo task instead.

## Start / activate
Executable demo: `./gradlew :j7m-demo:runHaHpClusterExample --no-daemon`.

## Invoke / verify behavior
The demo prints the serving instance before and after failover.

## Status / observability
Runtime admin status includes invocation and HP decision information.

## Stop / unload
Closing registrations and runtimes removes registry records.

## Upgrade / rollback, where applicable
Use the application upgrade demo for deployment lifecycle behavior.

## Expected output
Look for `phase5.5 cluster first target:` and `phase5.5 cluster after failover:`.

## Security notes
The example uses development security mode and focuses on HA/HP behavior.

## Cleanup
The demo uses an in-memory registry store and closes runtimes.

## Known limitations
HP scoring is intentionally basic. This is not consensus, durable cluster membership, or database replication.
