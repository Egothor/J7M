# Simple Service Template

## Purpose
Minimal classpath template for a synchronous remote service.

## Architecture flow
Service interface -> implementation -> `J7mApplication` lifecycle -> `.j7m` manifest.

## Prerequisites
Java 25 and the J7M Gradle wrapper.

## Build
Documentation-only template command: `./gradlew buildExamples --no-daemon`.

## Test
Validation task: `./gradlew verifyExamples --no-daemon`.

## Package
Use the root packaging task after copying the template into a real project.

## Verify bundle
Use `./gradlew verifyExamples --no-daemon` for repository template validation.

## Deploy to local repository
Use the deployer API or demo tasks after packaging.

## Start / activate
Use `ApplicationManager.activate` or generated deployment commands.

## Invoke / verify behavior
Look up the service by Java interface and invoke `execute`.

## Status / observability
Use runtime/admin status after activation.

## Stop / unload
Use the deployer deactivate and unload operations.

## Upgrade / rollback, where applicable
Stop-start upgrade is the current baseline.

## Expected output
`simple:<value>`.

## Security notes
Add explicit policy before production use.

## Cleanup
Remove generated local repository directories.

## Known limitations
Template code is intentionally minimal and not a full product.
