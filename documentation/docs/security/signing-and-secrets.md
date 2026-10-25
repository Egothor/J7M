# Application Signing and Secret Handling

Verify an application's content and publisher before executing its bootstrap code. Bundle checksums detect changed content; a trusted signature establishes an accepted publisher under the selected verification policy. Neither a filename nor a successful download is a substitute.

Use [strict production or regulated verification](../deployment/signed-applications.md) as appropriate. The regulated policy also requires SBOM and provenance metadata; the deployment owner remains responsible for their meaning. Keep the trusted signing-key inventory outside the artifact being evaluated.

The artifact repository adds a separate signed transfer manifest with full/chunk hashes and explicit tenant/capability authorization. Keep the same immutable coordinate and signed manifest when resuming an interrupted transfer. Changing a request ID to avoid a conflict can defeat the intended idempotency boundary.

## Secret sources

Keep TLS private keys, PINs, native peer keys, resource principal keys and database credentials in protected host configuration. Application-visible configuration should contain only the values the application is authorized to receive. A value redacted from diagnostics is still readable by application code that legitimately receives it.

Use secret providers or private files with appropriate process ownership. Do not include secrets in Java source, command arguments where they become process listings, registry records, manifests, metric labels, trace baggage or ordinary exception messages. File permissions, volume mounts and the host's secret-delivery mechanism remain part of the deployment's security boundary.

## Handling a suspected disclosure

Stop copying the exposed value into diagnostics. Identify the affected authority and revoke or rotate it through the real provider. Reestablish endpoint identity and authorization, then verify both accepted and rejected access. Preserve safe audit evidence without retaining the secret itself in tickets or documentation.

Revoking a registry publisher, a TLS client and an application signing key can have different consequences. Record which credential was compromised and which running or durable capabilities need to be withdrawn; do not assume one replacement string revokes every previously admitted operation.
