# Annotation First Simple Service

## Start Here
Start with `SimpleGreetingService`. It is the canonical copy/paste service for
the first annotation-first pass: application identity, service identity,
constructor injection, named/config/resource values, current application
context, and one remote method.

From a copied project directory that contains the local `j7m-local-repository`,
run `gradle build --no-daemon`, then run
`gradle verifyExpectedOutput --no-daemon`.

## Purpose
Demonstrates a low-boilerplate J7M service that declares application identity,
export metadata, constructor injection, safe public resources, and remote
method metadata with annotations.

## Architecture flow
`@J7mApplication` identifies the application. `@J7mService` describes the
service. `DefaultJ7mInjector` constructs the service with a named formatter,
configuration, a public resource binding, and the current application context.
`AnnotationModelProcessor` builds stable descriptor metadata used by validation.

## Annotations demonstrated
`@J7mApplication`, `@J7mComponent`, `@J7mService`, `@RemoteMethod`,
`@Inject`, `@Named`, `@ConfigValue`, `@J7mResource`, and
`@CurrentApplication`.

## Injected services demonstrated
The example injects an application-owned formatter, a safe public audit helper,
the current application context, and a safe configuration value. It receives no
runtime maps, registry internals, transport internals, or classloader controls.

## Enforcement status
Constructor injection is executed by the J7M injector. Remote service metadata
is descriptor metadata in this example. Startup is invoked through the public
J7M injector in the smoke run; no transport export or application-platform
deployment is performed. Retry, deadline, security, and observability
annotations are intentionally taught by later focused examples.

## Prerequisites
Use Java 25 and the local J7M build. No external DI framework, broker,
database, container runtime, or vendor SDK is required.

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
smoke path, descriptor metadata, README wording, and manifest absence guard.

## Run / demo
Run `gradle verifyExpectedOutput --no-daemon`; the copied project compares the
output with `EXPECTED_OUTPUT.txt`.

## Safe modification exercise
Change the default region from `eu` to `us` in `SimpleExampleMain` and
`EXPECTED_OUTPUT.txt`, then run `gradle verifyExpectedOutput --no-daemon`.

## Deploy to local repository
The annotation demo validates descriptor metadata locally. Use the standard
deployable examples when repository publishing is required.

## Start / activate
The smoke run constructs the annotated application and invokes startup lifecycle
hooks through the J7M-native injector. It does not activate a packaged
application instance through the deployer.

## Invoke / verify behavior
The smoke run invokes the annotated `greet` method and prints the safe greeting.

## Status / observability
The demo prints the public startup summary produced by the injector-owned
startup hook.

## Stop / unload
The BASIC simple-service smoke path owns no external resources and performs no
packaged unload step.

## Upgrade / rollback, where applicable
Use the existing upgrade and rollback examples for deployment strategy
coverage. This example focuses on annotation and injection ergonomics.

## Verification
For this copied BASIC project, run `gradle build --no-daemon` and
`gradle verifyExpectedOutput --no-daemon`. In the repository root, run
`./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`; the smoke output prints the application coordinate
and greeting marker produced after injector-owned startup.

## Security notes
This simple service has no authorization rule. Security annotations are shown
in the secure annotation example.

## Redaction notes
Configuration keys that look secret must be redacted by injection diagnostics.
This BASIC example prints only public greeting text.

## Troubleshooting
Missing bindings fail with `INVALID_ARGUMENT`. Check qualifier names on
`@Named` and configuration keys on `@ConfigValue`.

## Cleanup
The demo uses only in-memory objects and creates no durable external resources.

## Known limitations
The annotation descriptor records export intent; advanced applications may
still call low-level export APIs directly when they need explicit control.
