# Annotation First Lifecycle

## Start Here
Start with `LifecycleComponent`. It is the canonical BASIC lifecycle example:
initialize, startup, drain, shutdown, health, readiness, no remote export, and
reserved switch-level documentation.

From a copied project directory that contains the local `j7m-local-repository`,
run `gradle build --no-daemon`, then run
`gradle verifyExpectedOutput --no-daemon`.

## Purpose
Demonstrates lifecycle, health, readiness, local-only, no-remote-export, and
runtime-level-switch metadata without introducing new runtime behavior.

## Architecture flow
The component uses lifecycle annotations. The smoke owner invokes initialize,
startup, drain, and shutdown in that documented phase order through
`DefaultJ7mInjector`; the injector orders multiple methods for the same
annotation deterministically by method name. Local-only and no-remote
annotations are descriptor metadata that validation can reject when combined
with remote export metadata.

## Annotations demonstrated
`@J7mApplication`, `@J7mComponent`, `@Inject`, `@Initialize`, `@Startup`,
`@Drain`, `@Shutdown`, `@HealthCheck`, `@ReadinessCheck`, `@LocalOnly`,
`@NoRemoteExport`, and `@SwitchLevel`.

## Injected services demonstrated
The component is constructed by the J7M injector through its annotated
constructor and does not receive runtime internals.

## Enforcement status
Lifecycle hooks are executed by the J7M injector. `@LocalOnly` and
`@NoRemoteExport` are descriptor metadata. `@SwitchLevel` is reserved
documentation for a future lifecycle family; the current example has no
level-switch consumer and does not infer behavior from the annotation itself.

## Prerequisites
Use Java 25 and the local J7M build. No external services are required.

## Build
Run `gradle build --no-daemon` from the copied project directory.

## Test
Run `gradle verifyExpectedOutput --no-daemon` from the copied project
directory.

## Package
Use the standard deployable examples when bundle packaging is required. This
BASIC copy/paste example intentionally has no `META-INF/j7m-application.json`
manifest because its smoke path does not package or validate a production
application bundle.

## Verify bundle
Run `./gradlew validateAnnotationExamples --no-daemon` to validate the copied
smoke path, README wording, and manifest absence guard.

## Run / demo
Run `gradle verifyExpectedOutput --no-daemon`; the copied project compares the
output with `EXPECTED_OUTPUT.txt`.

## Safe modification exercise
Add a public non-sensitive lifecycle event named `ready-check` to the smoke
output, update `EXPECTED_OUTPUT.txt`, and run
`gradle verifyExpectedOutput --no-daemon`.

## Deploy to local repository
The annotation demo validates lifecycle metadata locally. Use the standard
deployable examples when repository publishing is required.

## Start / activate
The smoke run invokes initialization and startup hooks through the J7M injector.
It does not activate a packaged application instance through the deployer.

## Invoke / verify behavior
The demo verifies deterministic lifecycle order and readiness state.

## Status / observability
The smoke run prints lifecycle order and safe status text.

## Stop / unload
The demo invokes drain and shutdown hooks.

## Upgrade / rollback, where applicable
Use the existing upgrade and rollback examples for deployment strategy
coverage. This example focuses on lifecycle annotations.

## Verification
For this copied BASIC project, run `gradle build --no-daemon` and
`gradle verifyExpectedOutput --no-daemon`. In the repository root, run
`./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`.

## Security notes
Lifecycle hooks must not print secrets or retain invocation-scoped values.

## Redaction notes
Lifecycle status values are safe fixed strings only.

## Troubleshooting
Lifecycle methods must be no-argument methods and the package must be open to
the application platform when reflective invocation is needed.

## Cleanup
The example owns no external resources.

## Known limitations
`@SwitchLevel` is reserved documentation and does not switch runtime levels in
this Phase 7.10 example.
