# Backup, Restart and Restore

An ordinary restart reopens the same exclusively owned durable directory and identity. A restore creates a new isolated consensus generation from verified backup data. Treat them as different procedures.

## Ordinary restart

Stop or isolate the failed process, preserve its private directory, correct the underlying storage/network/configuration problem and restart with the intended node/group identity. Recovery validates retained records and state. Corruption or an exhausted recovery budget must not be silently normalized into success.

The generic durable-log `RecoveryOptions.defaults()` fails on corruption and permits derived-index rebuild. `truncateTail()` is an explicit policy for a genuinely corrupt/incomplete tail. Exhausting segment/record scan bounds is not tail corruption and must not trigger destructive truncation.

## Export a consensus backup

`ConsensusBackup.export` captures a quorum-consistent committed image and publishes an immutable backup directory only after the required durable writes. Use a fresh destination with an existing parent and the exact resource schema identifier.

```java
ConsensusBackup.Metadata metadata = ConsensusBackup.export(
        node, backupDirectory, expectedSchema, Instant.now().plusSeconds(10));

ConsensusBackup.Metadata checked = ConsensusBackup.inspect(
        backupDirectory, expectedSchema);
```

This host fragment assumes a live authorized `NetworkConsensusNode`, an exact supported schema string and a fresh private destination. Retain the returned committed index, source group/node identity, schema and hashes with the backup inventory. An archive file copied from a running state directory is not equivalent evidence.

## Restore to a fresh generation

1. Fence the old service/routing generation so it cannot concurrently serve protected state.
2. Inspect the immutable backup and check its schema, integrity and required recovery index floor.
3. Prepare fresh offline target directories and authorized endpoint/credential configuration.
4. Choose a new `groupId`, distinct from the source generation, with the required electorate identities.
5. Invoke restore with a trusted factory that constructs a fresh state-machine validator.
6. Start the restored members, establish quorum and verify resource state before changing client routing.

```java
ConsensusBackup.Metadata restored = ConsensusBackup.restore(
        backupDirectory, targetConfig, expectedSchema,
        minimumAcceptedIndex, applicationFactory);
```

The target directory must not already exist. `targetConfig.groupId()` must differ from the backup's source group. The API validates electorate identity, application snapshot shape and size before durable publication. Do not infer a schema from unknown bytes or pass a live resource as a validation factory.

## Verify recovery

Compare committed state and retained request identities, not just whether a port opens. Check stale-owner rejection, queue receipts, event cursors, job checkpoints, saga uncertainty and original trace/correlation where relevant. An inspected checksum proves the inspected data's integrity; it is not by itself a live restore test.

Retain the old generation isolated until the recovery procedure and rollback decision are complete. Never allow two generations to serve the same unfenced protected namespace.

## Measure recovery objectives

Define an RTO (maximum time to restore the required service) and RPO (maximum acceptable committed-data loss) for each protected resource and application workflow. A process becoming reachable is not the end of RTO: include quorum restoration, resource validation, authentication and a successful representative operation. Measure RPO against acknowledged committed records/checkpoints, not only backup timestamps.

Use a controlled drill with an explicit failure, bounded observation period and a rollback/containment procedure. Test the actual electorate, storage, registry and transport configuration. Restore original routing and candidate state only after confirming that the old generation remains fenced.

| Drill record | Required observation |
|---|---|
| Scope | Application/resource, topology, schema, versions and safe identities. |
| Objectives | Required RTO/RPO and the point at which each measurement starts/ends. |
| Baseline | Last acknowledged committed index, request identities/checkpoints and verified backup reference. |
| Failure | Injected loss, wall-clock time, safe correlation and affected members. |
| Recovery | Quorum-ready time, validated resource-ready time and first successful authenticated operation. |
| Data check | Recovered index/state, missing acknowledged work if any, stale-owner/receipt rejection and replay result. |
| Outcome | Observed RTO/RPO, deviations, follow-up and restoration/rollback decision. |

Keep the redacted drill record with the deployment's operational evidence. A passing small integration test is useful behavioral evidence, but it does not measure recovery time on a different production topology or prove an external disaster-recovery orchestration service.

**Sources:** ConsensusBackup (repository source: `j7m-consensus/src/main/java/org/j7m/middleware/consensus/ConsensusBackup.java`), durable-log recovery policy (repository source: `j7m-api/src/main/java/org/j7m/middleware/api/durablelog/RecoveryOptions.java`).
