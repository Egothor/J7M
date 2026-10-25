# Annotation First Security Audit

## Start Here
Start with `SecurityAuditService`, then read `SecurityAuditExampleMain` to see
the explicit allowed and denied paths.

## Purpose
Demonstrates role, permission, trust-domain, confidential handling, sensitive
redaction, audit metadata, current principal injection, and current invocation
injection in one focused example.

## Architecture flow
The service declares `@RequiresRole`, `@RequiresPermission`,
`@RequiresTrustDomain`, `@Confidential`, `@Sensitive`, and `@Audit`. The confidential boundary
is the configured runtime policy layer, not the annotation alone. The smoke
main performs explicit local checks before invoking the service so the README
can show allowed, role-denied, permission-denied, and trust-domain-denied
outcomes without changing production annotation semantics.

## Annotations demonstrated
`@J7mApplication`, `@J7mService`, `@Inject`, `@CurrentPrincipal`,
`@CurrentInvocation`, `@RemoteMethod`, `@RequiresRole`,
`@RequiresPermission`, `@RequiresTrustDomain`, `@Confidential`, `@Sensitive`,
and `@Audit`.

## Injected services demonstrated
The service receives safe current principal and current invocation context. It
does not receive credentials, transport sessions, private policy objects, or
mutable runtime maps.

## Enforcement status
Current context injection is runtime-supported by the J7M injector. Security,
audit, and confidential annotations are descriptor metadata in this example.
The smoke main explicitly checks the descriptor-shaped values; production
authorization, audit persistence, and confidential transport remain configured
runtime policy boundaries.

## Prerequisites
Use Java 25 and the local J7M build. No external identity provider, audit
product, TLS endpoint, or vendor SDK is required.

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
Change the required role from `billing-approver` to `finance-approver` in the
service and smoke main, then run `./gradlew validateAnnotationExamples --no-daemon`.

## Deploy to local repository
Use deployable examples when repository publishing is required. This example
is source-only and validates annotation metadata locally.

## Start / activate
The smoke run constructs the service with safe current context values.

## Invoke / verify behavior
The smoke run prints the allowed result, role denial, permission denial, trust
domain denial, redacted sensitive value, safe audit output, and confidential
boundary.

## Status / observability
Status is the deterministic smoke output in `EXPECTED_OUTPUT.txt`.

## Stop / unload
The example owns no external resources.

## Upgrade / rollback, where applicable
Use the existing deployment strategy examples for upgrade and rollback.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`; it must not contain
`J7M_SECURITY_AUDIT_SENTINEL_SECRET`.

## Security notes
The confidential enforcement boundary is the configured transport, security,
codec, status, and audit policy layer. `@Confidential` records metadata; it
does not enable encryption by itself.

## Redaction notes
The sensitive amount is redacted before public status or audit output.

## Troubleshooting
If a denial is unexpected, inspect role, permission tag, and trust-domain
inputs. Do not print raw secrets while debugging.

## Cleanup
No cleanup is required.

## Known limitations
The example does not replace the runtime authorization policy, persist audit
records, or configure confidential transport.
