# Repository Replication and Repair

Repository replication preserves immutable application bytes and selected repository metadata. It is distinct from downloading a bundle over the network and from activating an application on another host.

`RepositoryReplicationService` exposes `advertiseDigest`, `replicate`, `replicatePromotion`, `replicateRollback`, `reconcile`, `repair` and safe `status`. The first-party local service coordinates peer repository instances and a durable replication log. The [network repository transport](signed-applications.md) supplies the separate authenticated artifact-transfer path; it does not turn every metadata operation into a remote transaction.

## Select the replicated state

This host fragment assumes a configured replication service and an exact existing `ApplicationCoordinate`:

```java
var artifactsOnly = RepositoryReplicationPlan.artifact(coordinate);
var result = replicationService.replicate(artifactsOnly);

var releaseState = new RepositoryReplicationPlan(
        List.of(coordinate), true, true, true, true);
// Flags: index, promotions, rollback metadata, source audit chain.
var releaseResult = replicationService.replicate(releaseState);
```

Inspect the result and reported conflicts before accepting a release. Leaving a metadata flag false preserves that target metadata; artifact-only replication does not promote an environment or change its rollback target.

## Verification and conflicts

Coordinates and SHA-256 digests are immutable. A different digest at the same complete coordinate is a conflict, not permission to overwrite. Replication preserves the exact bundle bytes, including signatures, SBOM and provenance. It applies the stronger source/target verification policy, so a development source cannot weaken a strict destination.

Promotion and rollback metadata require the referenced target artifact to be present and verified first. Missing rollback artifacts, incompatible promotion state, corrupt bytes, audit disagreement and stale index sequences must be reported explicitly. Repository rollback metadata does not perform runtime rollback atomically.

Bounded artifact transfer uses staged chunks, transfer identity, chunk ordering/count, per-chunk digests, aggregate-size bounds and a final SHA-256 verification. Private signing keys and credentials are not replicated. An accepted transfer must not expose half-written artifacts as verified releases.

## Reconciliation and repair

`reconcile()` compares safe local/peer state and returns missing, corrupt or conflicting items. Select a repair plan deliberately; repair retrieves bounded bytes from a verified peer and rechecks artifact and metadata policy before promotion eligibility. Do not resolve a conflict by deleting the destination's valid release without deciding which immutable coordinate is authoritative.

Replication event recovery uses checkpoints and a durable tail. A corrupt or missing checkpoint produces degraded bounded recovery, not fabricated completeness. Index deltas require the expected previous sequence. Audit transfer verifies ordered pages and continuation hashes, keeps source audit separate from local replication audit, and publishes a completed chain only after all required pages arrive.

Set artifact, chunk, in-flight, pending-transfer, reconciliation, delta and audit-page bounds to the deployment budget. `OVERLOADED`, verification failure or exhausted audit-page budgets must leave safe incomplete/degraded status rather than claim a completed replication. Observe safe counters, coordinates and digests; keep payload bytes and private repository paths out of diagnostics.
