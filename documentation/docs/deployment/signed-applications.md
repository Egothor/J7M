# Signed Applications and Repositories

Application coordinates identify immutable releases. Bundle verification checks the manifest, content checksums and the selected signature policy before activation. Use an operator-provided trust inventory; an application cannot make its own signing key trusted by placing it in its bundle.

`SignaturePolicy` defines three policies:

| Policy | Required evidence |
| --- | --- |
| `DEVELOPMENT` | Content checksums; signatures, SBOM and provenance are informational |
| `STRICT_PRODUCTION` | A valid signature from a trusted signing key |
| `STRICT_REGULATED` | Trusted signature, SBOM and provenance metadata |

These are verification policies, not assertions of compliance with a particular regulation. The operator still owns the completeness and meaning of supplied evidence.

## Publish safely

1. Package a fixed coordinate and complete its checksum metadata.
2. Sign using protected signing material and record the signer identity.
3. Verify under the intended deployment policy with the intended trust inventory.
4. Publish to an application repository.
5. Plan and activate through the real host `ApplicationManager`; inspect the admitted result.

The network artifact repository adds signed manifests, chunk hashes, durable staging, resumable upload/download and controlled application import. `ArtifactRepositoryServer` binds authenticated principals to a tenant and explicit READ/UPLOAD/COMMIT/PUBLISH permissions. Application callers cannot ask the server to use a weaker verification policy.

An interrupted upload remains staging and cannot be activated. Complete assembly verification precedes atomic publication. Retry an uncertain transfer with the same signed manifest and chunk identities; do not promote staging files manually or overwrite a conflicting immutable coordinate.

Repository transport authentication and application bundle signing serve different purposes. The former authorizes transfer; the latter establishes the artifact publisher and content integrity. Preserve both through replication.

**Sources:** signature policies (repository source: `j7m-application-platform/src/main/java/org/j7m/middleware/application/SignaturePolicy.java`), network artifact contract (repository source: `j7m-application-platform/NETWORK-REPOSITORY.md`).
