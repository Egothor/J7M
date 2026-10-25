# Application Repositories

An application repository stores immutable, verified `.j7m` bundles and their metadata. Publication makes an artifact available; activation remains a separate authorized runtime operation.

## Operations

| API operation | Purpose |
|---|---|
| `publish` | Stage and verify a bundle before publishing its exact coordinate. |
| `fetch` | Retrieve the stored bundle for an exact coordinate. |
| `listVersions` | Enumerate known versions under the repository's bounds. |
| `verify` | Recheck the artifact under the required verification policy. |
| `promote` / `demote` | Change environment promotion metadata without overwriting immutable bytes. |
| `status` | Obtain bounded safe repository/recovery summaries. |
| `LocalApplicationRepository.verifyAuditChain` | Explicitly stream and verify the persistent publication audit chain. |

This local operator command verifies that chain without starting an application:

```bash
j7m app repository verify-audit --repository build/j7m-repository
j7m app repository status --repository build/j7m-repository --json
```

Paths shown here are local example locations. Protect the real repository root with owner-only write access, disk-space monitoring and a backup policy.

## Publication and recovery

Publication stages bytes under the repository filesystem, applies bounded streaming digests and metadata validation, then publishes only complete state. A failed publication preserves previously valid releases. A complete coordinate cannot be silently republished with new bytes. Recovery removes abandoned staging and quarantines incomplete final state without discarding complete verified releases.

Repository metadata is bounded, versioned and parsed strictly. Unsupported shapes, duplicates, unknown fields and malformed/trailing JSON are rejected. Treat a degraded recovery status as an operational problem; a bounded status request does not recursively scan and verify every artifact.

Verification policies range from checksums in `DEVELOPMENT` to trusted Ed25519 signatures in `STRICT_PRODUCTION`, with required SBOM/provenance evidence in `STRICT_REGULATED`. Activation rechecks the accepted artifact digest so a verification record cannot authorize subsequently modified bytes.

## Promotion, audit and distribution

Promotion points to an existing verified artifact and uses validated environment identifiers. Keep rollback targets verified and retained. Runtime rollback and repository promotion are separate effects; do not assume a cross-system transaction.

The persistent repository audit chain links previous/current hashes and supports explicit streaming verification. Its normal status summary is deliberately bounded. A local chain is not independent WORM retention: export important evidence to the approved [audit destination](../security/audit-export.md).

For cross-repository state and conflict handling, see [repository replication](repository-replication.md). For the network bundle-transfer path, see [signed application distribution](signed-applications.md). Neither transfer path grants runtime activation permission.
