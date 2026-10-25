# Enterprise Security and Evidence Integrations

J7M exposes policy, evidence and cryptographic integration contracts. An integration point is not a configured vendor service or a certification claim. Operate the external authority, provider and retention system explicitly.

| Area | Available contract or implementation | Deployment responsibility |
|---|---|---|
| Control evidence | `EvidenceCollector`, `ControlEvidence`, `ComplianceReport`, built-in profile descriptions and `DefaultComplianceReportService` | Supply actual observations, scope and collection time; review missing evidence. |
| Report export | `EvidenceExporter`, `DefaultEvidenceExporter`, JSON/Markdown/CSV formats | Secure the export destination and redact source data. |
| SIEM / GRC | `SiemEvidenceExporter`, `GrcEvidenceRepository` | Implement and operate authenticated external delivery, retries and retention. |
| Crypto posture | `CryptoPolicy`, `CryptoProviderPolicy`, `FipsModePolicy`, `KeyRotationPolicy`, `CryptoPolicyEvaluator` | Install approved JCA providers, configure actual algorithms and validate the operating environment. |
| External key service | `ExternalKmsProvider` | Implement the external KMS/HSM binding, credentials, availability and key lifecycle. |
| External decisions | `ExternalPdpClient`, `ExternalPdpAuthorizationPolicy` | Authenticate requests/results and define timeout, failure and policy freshness behavior. |
| Emergency access | `BreakGlassPolicyHook` | Establish trusted approval, scope, expiry, monitoring and revocation. |
| Two-person approval | `FourEyesApprovalPolicy`, `FourEyesApproval` | Derive requester/approver evidence from trusted identities and approvals. |

## Export an observed report

The following integration fragment assumes `report` was built from collected evidence. The result is text for an approved archive or delivery adapter:

```java
String json = evidenceExporter.export(report, EvidenceExportFormat.JSON);
// Deliver json through the host's access-controlled evidence destination.
```

A populated report does not prove regulatory compliance. Preserve collection identity, scope, timestamps, failed controls and source references. Avoid treating a successful demonstration, an algorithm name or a declared FIPS flag as proof of a certified deployment.

## Trusted policy inputs

`FourEyesApprovalPolicy` wraps another authorization policy and requires distinct requester/approver metadata for configured action names after the delegate allows the request. Untrusted client-supplied `requestedBy` and `approvedBy` strings are not proof of two authenticated human approvals. A host must derive and protect those inputs.

Apply the same rule to break-glass and just-in-time privilege: bind the grant to an authenticated principal, exact actions/resources and a bounded lifetime. A local policy hook or expiry field is not an external privileged-access-management workflow. Audit the grant and its use; revoke it through the enforcing policy when the window ends.

Cryptographic posture evaluation checks declared requirements against supplied provider facts. KMS/HSM custody, a validated FIPS module, attestation and external PDP/GRC/SIEM operation remain explicit integrations. Verify their failures as well as their successful path before production use.
