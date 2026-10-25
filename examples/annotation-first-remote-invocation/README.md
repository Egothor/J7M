# Annotation First Remote Invocation

## Start Here
Start with `RemoteInvocationService`. It keeps remote service metadata,
retry-safety, one-way invocation, callback shape, and deadline metadata in one
small source file.

## Purpose
Demonstrates remote invocation annotations without hiding ObjectRef, proxy,
deadline, retry, callback, or authorization ownership.

## Architecture flow
`@RemoteService`, `@J7mService`, and `@RemoteMethod` identify exportable
service metadata. `@Idempotent`, `@Retry`, and `@Deadline` describe the
retry-safe method. `@NonIdempotent` explicitly marks the method that must not
be retried automatically. `@OneWay` is used only on a `void` method, and
`@Callback` marks the callback reference shape without creating a transport
export in this example.

## Annotations demonstrated
`@J7mApplication`, `@RemoteService`, `@J7mService`, `@RemoteMethod`,
`@OneWay`, `@Callback`, `@Deadline`, `@Retry`, `@Idempotent`, and
`@NonIdempotent`.

## Injected services demonstrated
This focused example does not inject services. The simple example teaches
injection first; this example teaches invocation policy metadata.

## Enforcement status
The annotations are descriptor metadata in this example. Runtime export,
proxy creation, retry, deadline enforcement, one-way enqueue, callback export,
and authorization remain owned by the configured J7M runtime paths.

## Prerequisites
Use Java 25 and the local J7M build. No external registry, network service,
or service mesh is required.

## Build
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Test
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Package
Run `./gradlew packageExamples --no-daemon`.

## Verify bundle
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Run / demo
Run `./gradlew validateAnnotationExamples --no-daemon`; the validator executes
the deterministic smoke main and compares it with `EXPECTED_OUTPUT.txt`.

## Safe modification exercise
Change the deadline from 250 ms to 300 ms in both `@Deadline` and
`EXPECTED_OUTPUT.txt`, then run `./gradlew validateAnnotationExamples --no-daemon`.

## Deploy to local repository
Use deployable examples when repository publishing is required. This example
is source-only and validates annotation metadata locally.

## Start / activate
The smoke run constructs the service locally and does not start a transport.

## Invoke / verify behavior
The smoke run calls the retry-safe method, the `void` one-way method, the
callback, and the non-idempotent method.

## Status / observability
The output is deterministic and contains only public invoice ids and metadata
summaries.

## Stop / unload
The example owns no external resources.

## Upgrade / rollback, where applicable
Use the existing deployment strategy examples for upgrade and rollback.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`.

## Security notes
A received ObjectRef is not authorization. Exported production calls still
require authentication and authorization at the authoritative server boundary.

## Redaction notes
The example prints no credentials, payloads, or private callback state.

## Troubleshooting
If validation fails, verify that the one-way method still returns `void` and
that retry metadata is present only on the idempotent method.

## Cleanup
No cleanup is required.

## Known limitations
The example does not start a transport, create a live proxy, or claim automatic
retry for non-idempotent operations.
