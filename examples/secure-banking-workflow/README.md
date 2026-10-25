# Secure Banking Workflow

## Purpose
Demonstrates security annotations, safe caller identity inspection through `J7mContext`, denial behavior, audit-safe redaction, and normalized middleware errors. The source uses `@RequiresRole`, `@RequiresPermission`, and `@Sensitive`.

## Architecture flow
Application activation exports an approval service. A caller with role `banking-approver` succeeds. A caller without that role receives `PERMISSION_DENIED`.

## Prerequisites
Use Java 25. This is a local development-mode security pattern demo.

## Build
Executable validation: `./gradlew buildExamples --no-daemon`.

## Test
Executable validation: `./gradlew :j7m-tests:test --no-daemon --rerun-tasks`.

## Package
Executable validation: `./gradlew packageExamples --no-daemon`.

## Verify bundle
Executable validation: `./gradlew verifyExamples --no-daemon`.

## Deploy to local repository
Executable demo: `./gradlew :j7m-demo:runSecureBankingWorkflowExample --no-daemon`.

## Start / activate
The demo packages, deploys, and activates the service.

## Invoke / verify behavior
The demo invokes an authorized approval and a denied unauthorized approval.

## Status / observability
The demo prints audit event count and safe redaction status.

## Stop / unload
The demo deactivates and unloads the application.

## Upgrade / rollback, where applicable
Not applicable for this single-version pattern example; use the common application upgrade/rollback demo for lifecycle behavior.

## Expected output
Look for `phase5.5 banking authorized approver: alice` and `phase5.5 banking unauthorized denied: PERMISSION_DENIED`.

## Security notes
No real banking system, external IAM, production KMS, or certification claim is included.

## Cleanup
Temporary repositories are removed by the operating system temp cleanup policy.

## Known limitations
This is a security pattern demonstration only. It is not payment processing, ledgering, fraud detection, or a production approval workflow.
