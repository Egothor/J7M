# Annotation First Secure Service

## Start Here
Start with `SecureApprovalService` if you are maintaining the historical
secure-service example. New readers should use
`examples/annotation-first-security-audit` for the focused security/audit
walkthrough.

## Purpose
Demonstrates security annotations, current principal injection, current
invocation injection, redaction, and denied-call behavior in the annotation
model.

## Architecture flow
The service descriptor records `@RequiresRole` and `@RequiresPermission`.
The executable demo constructs the service through J7M-native injection inside
a current invocation scope and evaluates the required role locally for the
authorized and denied paths.

## Annotations demonstrated
`@J7mApplication`, `@J7mService`, `@Inject`, `@CurrentInvocation`,
`@CurrentPrincipal`, `@RemoteMethod`, `@RequiresRole`, `@RequiresPermission`,
`@Sensitive`, `@Idempotent`, `@NonIdempotent`, and `@Deadline`.

## Injected services demonstrated
The service receives the safe current principal, the safe current invocation
context, and a safe audit collector. It receives no credentials or transport
internals.

## Enforcement status
Current principal and invocation injection are executed by the J7M injector.
Security annotations are descriptor metadata. This example evaluates the role
rule locally from the descriptor path so the authorized and denied flows are
visible without claiming automatic runtime enforcement by the annotation alone.

## Prerequisites
Use Java 25 and the local J7M build. No Spring, no Guice, no CDI, no Micronaut,
no Dagger, no external identity provider, no vendor SDK, and no container
runtime is required.

## Build
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Test
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Package
Run `./gradlew packageExamples --no-daemon`.

## Verify bundle
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Run / demo
Run `./gradlew runAnnotationModelDemo --no-daemon`.

## Safe modification exercise
Change the public invoice id in the demo support test, keep the sensitive
amount redacted, and run `./gradlew validateAnnotationExamples --no-daemon`.

## Deploy to local repository
The annotation demo validates descriptor and policy metadata locally. Use the
standard deployable examples when repository publishing is required.

## Start / activate
The demo opens a current invocation scope and constructs the secure service
through J7M-native injection.

## Invoke / verify behavior
The demo performs one authorized call and one denied call.

## Status / observability
The demo prints safe security metadata, denial status, and audit redaction
state.

## Stop / unload
The demo closes invocation scopes and exits without external resources.

## Upgrade / rollback, where applicable
Use the existing upgrade and rollback examples for deployment strategy
coverage. This example focuses on annotation-driven security metadata.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`; the demo prints authorized and denied outcomes.

## Security notes
Security annotations are descriptor metadata in the platform processor. This
example also evaluates the role rule locally so the demo proves the annotated
policy path without adding a new authorization framework.

## Redaction notes
The `@Sensitive` amount is redacted from audit output and the demo scans for
sentinel secret leakage.

## Troubleshooting
If a call is denied, inspect the principal roles and the descriptor method
metadata. Injection failures use stable middleware error codes.

## Cleanup
The demo uses in-memory state only.

## Known limitations
This example does not replace the runtime authorization policy; it demonstrates
how annotation metadata becomes a safe descriptor and policy input.
