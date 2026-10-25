# Network artifact repository — D-P14

Implementation: 2026-10-22. Public production APIs live in the exported
`org.j7m.middleware.application.repository` package; there is no dependency on runtime
service discovery or a concrete application.

- `ArtifactManifest.sign`: version-one Ed25519 signature over the tenant, normalized exact
  coordinate, complete SHA-256/length, fixed chunk size, ordered chunk hashes and signer ID.
- `ResumableArtifactStore`: exclusively owned durable staging, resume, immutable chunk
  upload, `commitManifest`, verified fetch/download and application import.
- `ArtifactRepositoryServer`: explicit authenticated endpoint with immutable principal →
  tenant and READ/UPLOAD/COMMIT/PUBLISH permissions. Publication verification policy belongs
  to the server. Application callers cannot select a weaker policy.
- `ArtifactRepositoryClient`: persistent upload acknowledgements/download chunks, resumable
  transfer and `replicateTo(target, manifestId, deadline)`. This composition downloads the
  verified source release, resumes destination upload and invokes destination application
  publication. Applications need no custom transport or replication glue.

For application replication, configure each store with its existing `ApplicationRepository`.
Upload and commit the signed artifact at the source, import it using `publishApplication`,
then invoke `sourceClient.replicateTo(targetClient, manifestId, deadline)`. The destination
uses `ApplicationRepository.publish`, retaining existing checksum, bundle-signature and
verification-record rules. A repeated import succeeds only for the identical artifact and
an accepted current verification policy. Conflicting immutable coordinates are rejected.
Generic non-application artifacts can use the same upload/download APIs without an application
repository; application import then explicitly reports that capability unavailable.

## Publication and recovery

1. Authenticate, bind tenant/permissions and verify the signed manifest before staging.
2. Reserve quota and atomically fsync the manifest. Hash/length verification precedes each
   chunk's fsync and atomic rename. Identical acknowledged chunks are safe to retry.
3. On resume, the server rechecks retained chunks; the client rechecks persisted download
   chunks. A corrupted chunk is explicit failure, never silently treated as verified.
4. Commit streams all chunks into an assembly file and verifies complete length and SHA-256.
   Only then does atomic rename plus an fsynced ready marker expose the release.
5. A crash after artifact rename but before the marker reuses the verified completed file.
   Staging is never fetchable, downloadable, importable or visible to `ApplicationManager`.
6. Downloads atomically replace the caller-selected local destination only after full verification.

Deadlines and cancellation can leave an UNKNOWN remote outcome. Retry the same manifest/chunk;
resume does not retransmit retained verified payloads. A successful publication response lost in
transit can be retried without overwriting a different application artifact. Local store/cache
file locks prevent concurrent process owners. Atomic rename and directory fsync are required;
unsupported filesystems fail explicitly. The declared validation profile uses private local disks.

## Security and bounds

- HMAC-SHA256 authenticates complete bounded request/reply frames, including principal, nonce
  and deadline. Correlation rejects reply replay. The per-process nonce cache is bounded to
  8192 entries and 30-second deadlines; durable idempotent mutations also survive restarts.
- Plaintext HMAC is allowed only on loopback development endpoints. Other endpoints require
  mutual TLS, client certificates and client-side server-hostname verification.
- Artifact coordinates/tenant identities are validated; storage directories derive from hashes.
  Request IDs never become paths. Symlink components inside private store/cache roots are
  rejected. Remote clients never supply server filesystem paths.
- Artifact length: 1 byte–64 MiB; chunk size: 4–256 KiB; at most 16384 chunks; manifest/frame:
  2 MiB. The maximum advertised 64 MiB / 4 KiB manifest is larger than 1 MiB and round-trips
  through the codec, TCP protocol and persistent store without mismatched limits.
- Four workers, sixteen queued sockets and bounded frame reads. Published GET verifies only
  its requested chunk against the signed manifest; full artifact hashes run at commit/fetch/import,
  avoiding a full-artifact read for every chunk.
- Store quotas cover logical payload bytes, metadata and recovery headroom: each artifact reserves
  `3*length + 2*encodedManifestLength + 4096`. A configured artifact count also applies. Orphaned staging directories and pending manifests left by a crash count against these quotas. These
  are logical byte reservations; filesystem allocation-block overhead is not measured.
- Client cache default: 256 MiB and 128 manifests; explicit constructor quotas are supported.
  Reservation is `3*length + 2*encodedManifestLength + 4096`, covering downloaded chunks, an existing
  replication assembly and its atomic replacement during a retry. Quota exhaustion fails before new transfer state is published.
- Publication diagnostics are bounded to 64 messages of at most 1024 characters. Counters expose
  actual uploaded/downloaded chunks and rejected connections; no authentication keys are logged.

## Validation and rollback

`ArtifactRepositoryProcessIT` uses separate server/client JVMs with independent persistent disks.
Every chunk is interrupted with a truncated authenticated frame, both JVMs are killed and restarted,
then every acknowledged upload and cached download is checked after another two-process restart.
Verified payloads are not retransmitted. A second test replicates a real application across two
independent repository-server JVMs, resumes a partial destination after both restart, verifies
public destination fetch and repeats publication idempotently.

`ArtifactRepositoryIT`, `ArtifactRepositoryTransportIT` and `ArtifactManifestTest` cover actual
application import and staging activation denial, complete/chunk corruption, forged signature,
wrong tenant/capability, path traversal, quotas, file ownership, replay/oversized frames, mutual TLS,
missing/untrusted certificates, hostname mismatch and maximum manifest bounds. Focused evidence
is retained separately from overwritten Gradle reports under `/tmp/dp14-run-*`; final aggregate
and independent review remain the acceptance authority.

Before adoption, rollback removes the additive endpoints. After publication, keep immutable
application artifacts and resume interrupted transfers with the same signed manifest. Do not
promote staging manually or downgrade verification policy. Wire/manifest version 1 is additive;
unknown versions fail closed. Offline cleanup of closed private caches is an operator action;
there is no automatic deletion of published releases.
