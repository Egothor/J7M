# CP backup and recovery profile

`ConsensusBackup` is an in-process administrative library for a hosting application's Raft group. It exports a fresh quorum-consistent checkpoint and restores it into a fresh consensus generation. It does not copy a running WAL, export credentials, discover application schemas, fence external systems, or authorize a product release.

## Supported qualification profile

| Property | Development qualification setting |
| --- | --- |
| Platform | Linux, Java 25 |
| Consensus | CP, fixed 3 or 5 voting JVMs; majority required |
| Storage | Local durable disks; file/directory fsync and atomic rename on the same filesystem |
| Fault recovery | Within 30 seconds after sufficient voters return |
| Clock assumption | At most 500 ms measured uncertainty; operations reject unsafe time |
| Process isolation | One durable directory per voter; no shared live WAL directory |
| Soak resources | Concurrent 3-JVM map and 5-JVM queue groups, 256 MiB maximum heap per voter, 512 MiB harness |

The heap values describe qualification and are not general production sizing advice. Capacity planning must include retained request results, event retention, in-flight messages, prepared transactions, snapshots, backup staging and old backups. Provision retention and request capacity before admission reaches its documented limits; explicit overload is a safe refusal, not an availability success. Applications requiring AP operation must choose a separate conflict-resolution design.

## Export contract

Call `ConsensusBackup.export(node, freshDirectory, applicationSchema, deadline)` on an administrative control path. The node first obtains a fresh read-index majority barrier and captures application state, committed membership/joint configuration and retained request identities under its apply lock. Later mutations belong to a later checkpoint. No-quorum export fails; a stale isolated leader cannot produce a new authoritative export.

The published directory contains `checkpoint.bin` and `manifest.bin`. Publication writes and forces private staged files, forces the staged directory, atomically renames it and forces the parent. Export and restore require an existing, protected parent directory on a filesystem that supports these operations. Cooperating library calls reserve the same target name; callers must prevent unrelated processes from mutating that parent. Existing targets are refused. Failed staging is removed; successfully published backups remain immutable application assets. Keep parent permissions restrictive and grant backup access only to the operator role: snapshots contain application input, output, event payloads and prepared transaction data.

`ConsensusBackup.inspect(directory, expectedSchema)` checks bounded schema/version, SHA-256 checksums, metadata and checkpoint structure before returning immutable `Metadata`. Checksums detect corruption; they do not authenticate a maliciously replaced bundle. Use protected storage and independently authenticated provenance when transferring backups. The current bundle version is 1. Bounds are 16 KiB manifest, 512 MiB checkpoint and 256 MiB application payload, further constrained by the configured target snapshot limit. Unknown bundle versions and wrong application schema fail closed.

## Fresh-generation restore runbook

1. Stop or fence **all** old application endpoints, writers, coordinators and routes. Record the recovery incident, selected backup provenance/schema and minimum committed index required by the recovery objective. An in-process backup API cannot prove that an external endpoint or external side-effect handler has been fenced.
2. Run `inspect` and compare the returned group, membership, index and checksum against the approved recovery record. A backup preserves its captured prefix; it cannot recover acknowledgements made after that index. Stop writes at the chosen final barrier when zero loss at planned cutover is required.
3. Construct target `NetworkConsensusConfig` values with a **different groupId**, the exact captured stable voter identities, peers covering stable and joint voters, fresh per-node directories, and newly provisioned credentials. The fresh generation rejects old Raft group traffic. It does not independently change application ownership epochs or invalidate an external system's fencing token; preserve those application semantics and fence old routes first.
4. Call `ConsensusBackup.restore(backup, targetConfig, expectedSchema, minimumIndex, stateMachineFactory)` for each voter. The factory must return a new independent application instance compatible with that schema. Restore validates the full image by restoring that candidate before publishing any target directory. A wrong schema, insufficient index, bad checksum, invalid application snapshot, existing target or reused source generation is rejected. Candidate validation is mandatory; an arbitrary opaque snapshot has no universal application compatibility guarantee.
5. Start the restored voters, obtain a majority read barrier and verify application data, prepared transactions, retained event cursors and request-result identities. Expose no application traffic until these checks succeed. The `recovery-manifest.bin` retained in each new directory records source provenance; it is not a new backup bundle.
6. Publish the new authorized routes and resume workers. Resolve prepared transactions through their existing durable coordinator protocol. Resolve ambiguous external effects through application reconciliation, never automatic unprotected replay. Retained RPC/result and queue-delivery semantics remain those of their application snapshot.
7. Preserve the old fenced generation and immutable backup for the retention period. Rollback before routing consists of removing the unused fresh generation through the operator's normal storage process. After new writes are acknowledged, rollback requires a new deliberate recovery/cutover; do not overwrite old live directories or downgrade the snapshot format.

Restore preserves the captured electorate, joint transition, request identity hashes/results and the complete opaque application state. The application snapshot therefore preserves prepared locks, durable event/cursor state, leases and queue delivery state when its schema supports them. Each state machine's snapshot compatibility rules still apply. The focused process gates cover the production replicated map and partitioned queue, not just a counter.

## Reproducible acceptance

From the repository root, use the project's Gradle installation and Java 25:

```sh
./gradlew :j7m-consensus:test --tests '*ConsensusBackupIT' \
  :j7m-distributed-core:test --tests '*DistributedBackupRecoveryIT'
./gradlew :j7m-distributed-core:runDistributedAcceptanceSoak \
  -PacceptanceEvidence=/protected/fresh-soak-directory
./gradlew :j7m-distributed-core:validateDistributedAcceptance \
  -PacceptanceEvidence=/protected/completed-soak-directory
```

The explicit soak task runs both profiles for at least 3,600 overlapping active seconds after initialization; there is no shorter-duration option. It confirms real map changes and queue send/claim/ack effects while repeatedly killing leaders/minorities, removing quorum, restarting the full group and taking fresh backups. A quorum minority must refuse a new mutation. Each recovery is measured against the 30-second development profile. The end gate verifies all confirmed map values/versions, all confirmed queue result records, event projection continuity, prepared-lock preservation, full restart and fresh-generation restore.

The output retains a fault timeline, monotonic durations, per-profile confirmed-operation ledgers, child logs, final backup bundles and SHA-256 bindings to the consensus/distributed-core production sources and workload. `validateDistributedAcceptance` rejects missing, failed, shortened, modified-source or incomplete evidence. Writing `STOP` in the evidence directory cancels with failure; preserve failed-run evidence. Ordinary unit-test execution does not silently skip the soak and does not establish the hour gate. The soak exercises these CP core/resource boundaries; cluster-host lifecycle, deployment/canary, integrations and other plan gates require their own functional evidence before D-P18 is accepted.
