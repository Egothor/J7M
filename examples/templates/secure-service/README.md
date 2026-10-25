# Secure Service Template

## Purpose
JPMS template for role-protected service methods and sensitive argument redaction.

## Architecture flow
JPMS module -> security annotations -> application manifest -> deployer activation.

## Prerequisites
Java 25 and named J7M API modules.

## Build
Documentation-only template command: `./gradlew buildExamples --no-daemon`.

## Test
Validation task: `./gradlew verifyExamples --no-daemon`.

## Package
Package into a `.j7m` with the module jar under `modules/`.

## Verify bundle
Run bundle verification before activation.

## Deploy to local repository
Publish the verified bundle to a local repository.

## Start / activate
Activate through `ApplicationManager`.

## Invoke / verify behavior
Invoke with a principal that has role `operator`.

## Status / observability
Check audit and redaction counters.

## Stop / unload
Deactivate and unload through deployer APIs.

## Upgrade / rollback, where applicable
Use stop-start upgrade for a new coordinate.

## Expected output
Authorized calls return `handled`.

## Security notes
Replace placeholders with explicit production policies and mTLS.

## Cleanup
Remove temporary bundles and repositories.

## Known limitations
Template does not configure external IAM or production credential material.
