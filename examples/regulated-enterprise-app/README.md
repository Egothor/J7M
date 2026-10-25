# Regulated Enterprise App Example

## Purpose
Demonstrates a deployable J7M application that exposes safe compliance evidence, crypto posture, tamper-evident local audit verification, signed strict-regulated bundle metadata, SBOM/provenance requirements, and an explicit no certification claim.

## Architecture flow
Manifest plus SBOM, provenance, and built-in Ed25519 signature metadata -> `.j7m` bundle -> strict-regulated verification -> local repository verification record -> classloader activation -> gRPC export -> proxy invocation -> evidence and audit status -> stop and unload.

## Prerequisites
Use Java 25 and the repository Gradle wrapper.

## Build
Executable validation: `./gradlew buildExamples --no-daemon`.

## Test
Executable validation: `./gradlew :j7m-tests:test --no-daemon --rerun-tasks`.

## Package
Executable validation: `./gradlew packageExamples --no-daemon`.

## Verify bundle
Executable validation: `./gradlew verifyExamples --no-daemon`.

## Deploy to local repository
Executable demo: `./gradlew :j7m-demo:runRegulatedEnterpriseAppExample --no-daemon`.

## Start / activate
The demo activates `org.j7m.examples:regulated-enterprise-app:1.0.0` in a local runtime.

## Invoke / verify behavior
The demo signs the bundle, verifies strict-regulated signature/SBOM/provenance policy, rejects unsigned and invalid bundles, exports evidence, reports crypto posture, verifies the local audit trail, processes a controlled request, and prints the no-certification claim.

## Status / observability
The demo prints regulated verification findings, strict verification status, evidence control count, crypto posture, audit verification, active application count, and registry cleanup after stop.

## Stop / unload
The demo deactivates and unloads the application, then verifies that the regulated service is no longer registered.

## Upgrade / rollback, where applicable
Use `./gradlew :j7m-demo:runApplicationUpgradeRollbackDemo --no-daemon` for the common application upgrade and rollback flow. This example ships one regulated-app version.

## Expected output
Look for `phase5.5 regulated strict verify accepted: true`, `phase5.5 regulated unsigned strict accepted: false`, `phase5.5 regulated missing sbom accepted: false`, `phase5.5 regulated certification claim: false`, and `phase5.5 regulated active after unload: 0`.

## Security notes
The example uses built-in local signing and trust-store fixtures and never prints private keys, credentials, or secrets. It does not integrate external GRC, SIEM, KMS, CA, PDP, or certificate authority vendors.

## Cleanup
The demo uses temporary directories and leaves no repository state under the project tree.

## Known limitations
This is a local compliance-evidence example, not formal certification. External CA/KMS/GRC/SIEM/PDP integrations and production assurance workflows remain environment responsibilities.
