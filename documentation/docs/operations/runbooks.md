# Operational Runbooks

Use the procedure that matches the failed boundary. Increasing a timeout before identifying that boundary can hide a stale credential, missing route or lost ownership.

## A service is not discovered

1. Confirm the exporter is admitted and has a live record in the intended context.
2. Confirm the client uses multicast or network bootstrap, rather than an isolated embedded registry.
3. For multicast, check group/interface/port and network forwarding. For bootstrap, check seed URI, reachability, mutual TLS and context allowlist.
4. Inspect record signature/freshness/lease rejection and query capacity/deadline diagnostics.
5. Verify invocation reachability separately after discovery succeeds.

Do not disable record verification to make a rejected record appear healthy. A stale lease or replayed publication needs a valid fresh publisher update.

## Requests time out while the instance appears alive

Inspect active physical work, bulkhead/admission usage and the downstream resource. Determine whether the call entered application code. A qualified predispatch failure may permit policy-controlled retry; an uncertain entered effect requires its idempotency/reconciliation contract. Do not replay a non-idempotent operation with a fresh identity merely because its reply was lost.

## A resource cannot obtain quorum

Inspect actual authorized voters, peer reachability, group identity, storage health and TLS/peer credentials. Discovery of another process does not authorize it to vote. Keep minority mutations unavailable while quorum is absent. Do not copy a live directory, reuse a node identity concurrently or force stale state into the voting set.

## Lease, receipt or epoch is stale

Stop the obsolete worker's admission and inspect the current authority. A stale queue receipt cannot ACK a new delivery; an old fence cannot authorize a protected write; an old partition epoch cannot follow a handoff automatically. Let the documented takeover/handoff path establish new authority. Preserve the original logical operation identity where that path requires it.

## A message is in the dead-letter queue

Inspect safe delivery metadata and the handler failure category. Repair the actual cause, check the authorized redrive policy and use the explicit administrative redrive operation. A `@DeadLetterHandler` callback is observational; it is not permission to silently remove or acknowledge messages. Record the redrive decision and bound any retry.

## A saga reports UNKNOWN

Identify whether uncertainty concerns a forward step or its inverse. Gather trustworthy external evidence under the authorized reconciliation procedure. Submit a version-checked decision; a stale decision must not resolve a newer step or compensation. Do not label a missing compensation as successful undo.

## Disk or retained-state quota is exhausted

Stop increasing admission, identify which retained identities/snapshots/staging objects consume capacity, and preserve the committed state. Follow an explicit retention or offline maintenance policy. Deleting deduplication receipts to make room can turn a safe retry into a repeated effect.

If restoration is required, use [backup and restore](recovery.md). For prevention, review [capacity planning](capacity.md).
