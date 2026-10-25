# Annotation First Isolation Profile

## Start Here
Start with `IsolatedReportService`. It shows `@IsolationProfile`, public
provider capability injection, and redacted sensitive output without claiming a
container or OS sandbox.

## Purpose
Demonstrates `@IsolationProfile` and safe isolation-provider capability
injection.

## Architecture flow
The service declares an isolation profile name in descriptor metadata. The
application may inject the public `IsolationProvider` API. Baseline enforcement
levels still come from the configured provider; the annotation does not create
an OS sandbox or container runtime.

## Annotations demonstrated
`@J7mApplication`, `@J7mService`, `@Inject`, `@J7mResource`,
`@IsolationProfile`, `@RemoteMethod`, and `@Sensitive`.

## Injected services demonstrated
The example injects the public `IsolationProvider` capability. It does not
receive process internals, filesystem internals, or container-runtime handles.

## Enforcement status
`@IsolationProfile` is descriptor-only in this example. Actual policy behavior
is provided by the configured isolation provider.

## Prerequisites
Use Java 25 and the local J7M build. No Docker, Kubernetes, container runtime,
or external sandbox product is required.

## Build
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Test
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Package
Run `./gradlew packageExamples --no-daemon`.

## Verify bundle
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Run / demo
Run `./gradlew validateAnnotationExamples --no-daemon`; the validator compiles
the source tree and compares deterministic metadata smoke output with
`EXPECTED_OUTPUT.txt`.

## Safe modification exercise
Rename the profile from `baseline-process` to `reports-process` in source and
`EXPECTED_OUTPUT.txt`, then run `./gradlew validateAnnotationExamples --no-daemon`.

## Deploy to local repository
The annotation demo validates isolation metadata locally. Use the standard
deployable examples when repository publishing is required.

## Start / activate
The demo constructs annotation-first components and validates the profile
metadata.

## Invoke / verify behavior
The demo prints the isolation profile as descriptor-only metadata.

## Status / observability
The demo prints safe operational metadata and redaction state.

## Stop / unload
The demo exits without launching an isolated process.

## Upgrade / rollback, where applicable
Use the existing upgrade and rollback examples for deployment strategy
coverage. This example focuses on isolation annotations.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`.

## Security notes
The profile name is public metadata, not a sandbox guarantee.

## Redaction notes
Sensitive output is redacted before status or demo printing.

## Troubleshooting
If a profile is unknown, bind or configure the intended isolation provider and
profile before activation.

## Cleanup
The example does not launch a process or create temporary files.

## Known limitations
The annotation selects metadata only. Provider enforcement/advisory distinctions
remain documented in [isolation provider guide](../../documentation/docs/security/isolation-providers.md).
