# Network consensus (D-P01–D-P03)

`NetworkConsensusNode` is an additive implementation for separate JVMs. Existing
`ConsensusSimulation` and its public deterministic API are unchanged. It owns a
single Raft group and a deterministic `ReplicatedStateMachine` instance.

## Contract

- `start()` binds the configured endpoint and starts randomized elections and
  heartbeats. Static bootstrap membership is used only for new storage.
- `append(requestId, command, deadline)` returns a durable, locally applied log
  position after quorum replication. Reusing an ID with identical bytes returns
  its original position; different bytes produce `CONFLICT`. Retrying an
  uncertain outcome must preserve both the ID and command bytes.
- `readIndex(deadline)` requires a committed current-term entry and a fresh
  authenticated quorum round. Its returned prefix is already locally applied.
  `status()` is diagnostic and does not establish a read barrier.
- `reconfigure(expectedEpoch, voters, deadline)` requires authorized, caught-up
  learners, then commits joint and final configurations. The joint quorum must
  satisfy majorities of both electorates. A replacement leader finishes a
  committed joint change. An uncommitted change may be overwritten by a lawful
  new leader. Discovery cannot add voters.
- `snapshot()` captures only the committed/applied prefix. Manifest schema,
  group identity, index, term, electorate, configuration epoch, committed joint
  transition and request journal survive compaction; the uncommitted suffix
  retains any pending transition. Transfer is bounded, SHA-256 verified, chunked
  and resumable after process restart. Only one partial transfer is retained.
- `appendControl(bytes, deadline)` is for trusted, deterministic maintenance
  commands with their own monotonic/replay-safe semantics. It does not retain a
  client request identity and is unsuitable for ordinary application requests.
- `UNAVAILABLE` means admission could not proceed, including expired quorum
  confirmation. `UNKNOWN` after admission does not prove nonexecution. A deadline
  or interrupted caller does not roll back committed effects. Application
  cancellation must be cooperative and carried in the application protocol.

Callbacks are serialized with application. `apply(index, term, bytes)` receives
an actual committed term; its default delegates to the original two-argument
callback. `appliedThrough(index)` also advances over internal metadata entries.
Callbacks must not call the node, read wall clocks, or perform network operations.
A query must synchronize its state access with callbacks after a read barrier.

## Configuration and security

Use the explicit `groupId` constructor for stable production group identities.
Compatibility constructors derive a group ID from the immutable authorized
endpoint roster, which must be identical on every member. A group ID and node ID
are authenticated in frames and persisted in storage. Opening a directory under
a different identity fails.

Non-loopback endpoints require an injected `SSLContext` with mutually trusted
certificates; the server requires client authentication. All requests and replies
also use HMAC-SHA256 with sender, recipient, group and a per-request nonce.
Authorized nodes share full peer authority; protect the shared key and TLS keys
outside source control. A null TLS context is restricted to loopback development
and provides authentication without encryption.

Frames, command bytes, snapshots, worker queues and transport timeouts are
bounded. Commands must fit one quarter of the frame limit. The persistent client
journal admits at most `maxSnapshotBytes / 128` request IDs for the group's
lifetime; provision it accordingly or start a new application group generation.
Control entries do not consume that journal. Snapshot size is also independently
checked and an oversized snapshot fails without replacing the durable state.

## Durability, recovery and rollback

Each term/vote, appended batch and commit marker is forced before the relevant
acknowledgement. Storage errors stop further admission and acknowledgement.
Recovery validates bounded CRC32C records, rejects corruption, and truncates only
an incomplete tail. A snapshot replacement is forced, atomically renamed and its
directory forced before publication. The filesystem must honor these primitives.
A directory lock prevents concurrent ownership.

This is an opt-in facade: rollback consists of stopping its nodes and restoring
previous wiring to the deterministic facade. Preserve new durable directories
for recovery; the simulator cannot interpret this WAL. No existing simulator
storage is rewritten or migrated. Deployment changes require their own change
approval; this module does not deploy itself.

## Evidence

Run from the repository root with the configured Java proxy when dependencies
need downloading:

```sh
./gradlew :j7m-consensus:test :j7m-consensus:pmdMain :j7m-consensus:javadoc
```

`NetworkConsensusNodeIT` uses separate JVMs and real TCP fault proxies: 10,000
concurrent commands, retained real-time/linearization history, majority/minority
partition, leader crash with in-flight requests, full restart, 3→5→3 membership,
concurrent change conflict, pending-joint snapshot restart, 100,000 commands,
compaction and interrupted/resumed snapshot catch-up.
`NetworkConsensusCrashBoundaryIT` kills JVMs paused before/after WAL force and
commit force, before returning a committed result, and at every snapshot force,
rename, directory-force and application-restore boundary. It verifies old/new
valid recovery and exactly-once retry after lost responses. Retained histories
and crash evidence are under `build/reports/network-consensus/`.
`NetworkConsensusTransportIT` checks mutual TLS, missing client certificates,
unknown identities, invalid MACs, frame limits, cross-group isolation, corrupted
and unsupported snapshots and bounded partial-transfer storage. Unit tests cover
request conflicts, ownership, callback watermarks, durable failure, torn/corrupt
WAL and the heartbeat matched-prefix commit rule.
