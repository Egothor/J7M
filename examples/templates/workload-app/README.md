# Workload App Template

## Purpose
Minimal starting point for an application that will integrate with workload APIs.

## Architecture flow
Remote facade -> application-owned workload logic -> scheduler integration in later code.

## Prerequisites
Java 25 and J7M workload API understanding.

## Build
Documentation-only template command: `./gradlew buildExamples --no-daemon`.

## Test
Validation task: `./gradlew verifyExamples --no-daemon`.

## Package
Package once concrete workload behavior is added.

## Verify bundle
Run bundle verification before deploy.

## Deploy to local repository
Publish verified bundles only.

## Start / activate
Activate through the deployer.

## Invoke / verify behavior
Invoke `submit` and verify an accepted marker.

## Status / observability
Expose scheduler metrics when integrated.

## Stop / unload
Drain before stop.

## Upgrade / rollback, where applicable
Use stop-start upgrade until a broader strategy is implemented.

## Expected output
`accepted:<request>`.

## Security notes
Configure resource policy and authorization before production use.

## Cleanup
Remove temporary bundles and repositories.

## Known limitations
No durable queue, consensus, exactly-once, or compute platform is provided.
