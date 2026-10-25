# Streaming App Template

## Purpose
Minimal starting point for a streaming-capable application.

## Architecture flow
Remote facade -> stream creation -> transport-neutral streaming APIs.

## Prerequisites
Java 25 and J7M streaming baseline.

## Build
Documentation-only template command: `./gradlew buildExamples --no-daemon`.

## Test
Validation task: `./gradlew verifyExamples --no-daemon`.

## Package
Package after adding real stream methods.

## Verify bundle
Run bundle verification before deploy.

## Deploy to local repository
Publish verified bundles only.

## Start / activate
Activate through the deployer.

## Invoke / verify behavior
Invoke `open` and verify the returned stream marker.

## Status / observability
Expose stream metrics when integrated.

## Stop / unload
Cancel streams before draining.

## Upgrade / rollback, where applicable
Use stop-start upgrade until a broader strategy is implemented.

## Expected output
`stream:<name>`.

## Security notes
Streaming must enforce authorization, payload limits, and backpressure.

## Cleanup
Remove temporary bundles and repositories.

## Known limitations
This template is not a production streaming pipeline or durable data system.
