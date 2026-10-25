# Deployable App Template

## Purpose
JPMS deployable application template for a service exported through J7M.

## Architecture flow
JPMS module -> `.j7m` bundle -> repository -> ModuleLayer activation -> service proxy.

## Prerequisites
Java 25 and the J7M Gradle wrapper.

## Build
Documentation-only template command: `./gradlew buildExamples --no-daemon`.

## Test
Validation task: `./gradlew verifyExamples --no-daemon`.

## Package
Build module jar and place it under `modules/` in a `.j7m` bundle.

## Verify bundle
Run bundle verification before activation.

## Deploy to local repository
Publish the verified bundle to a local repository.

## Start / activate
Activate with `ApplicationManager`.

## Invoke / verify behavior
Look up `DeployableService` and call `handle`.

## Status / observability
Status should show `moduleMode=JPMS` and the module layer id.

## Stop / unload
Deactivate and unload through deployer APIs.

## Upgrade / rollback, where applicable
Stop-start upgrade is supported for new versions.

## Expected output
`deployable:<input>`.

## Security notes
Unsigned bundles are for development only.

## Cleanup
Remove generated bundles and repositories.

## Known limitations
Template does not include production signing or external policy integration.
