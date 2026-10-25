/*
 * BSD 4-Clause License
 *
 * Copyright (c) 2026, Leo Galambos and the Egothor team
 * Website of the Egothor team: https://www.egothor.org
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. All advertising materials mentioning features or use of this
 * software must display the following acknowledgement:
 *
 * This product includes software developed by Leo Galambos and the Egothor
 * team (https://www.egothor.org).
 *
 * 4. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.j7m.middleware.adapter.spi;

/**
 * Vendor-neutral J7M semantic that an adapter may claim or reject.
 *
 * <p>The enum is immutable and thread-safe. Each value represents behavior
 * defined by J7M reliability, repository, saga, or isolation contracts. A TCK
 * scenario may map one or more capabilities to concrete checks, and adapters
 * must report unsupported capabilities explicitly instead of silently
 * downgrading semantics.</p>
 */
public enum AdapterCapability {
    /**
     * Durable log append/read round trip.
     */
    LOG_APPEND_READ,
    /**
     * Durable log offsets are monotonic.
     */
    LOG_MONOTONIC_OFFSETS,
    /**
     * Durable log multi-record append is atomic where claimed.
     */
    LOG_MULTI_RECORD_ATOMICITY,
    /**
     * Durable log detects checksum or corruption failures.
     */
    LOG_CORRUPTION_DETECTION,
    /**
     * Durable log recovers and replays deterministically.
     */
    LOG_RECOVERY_REPLAY,
    /**
     * Durable queue enqueue, lease, acknowledge, and negative acknowledge semantics.
     */
    QUEUE_LEASE_ACK,
    /**
     * Durable queue visibility timeout semantics.
     */
    QUEUE_VISIBILITY_TIMEOUT,
    /**
     * Retry and dead-letter semantics.
     */
    RETRY_DEAD_LETTER,
    /**
     * Delayed delivery semantics.
     */
    DELAYED_DELIVERY,
    /**
     * Priority ordering semantics where claimed.
     */
    PRIORITY_ORDERING,
    /**
     * Idempotency and duplicate suppression semantics.
     */
    IDEMPOTENCY_DEDUPE,
    /**
     * Durable outbox crash-window semantics.
     */
    OUTBOX_CRASH_WINDOW,
    /**
     * Inbox duplicate suppression semantics.
     */
    INBOX_DUPLICATE_SUPPRESSION,
    /**
     * Backpressure and overload semantics.
     */
    BACKPRESSURE,
    /**
     * Membership and heartbeat semantics.
     */
    MEMBERSHIP_HEARTBEAT,
    /**
     * Lease and fencing token semantics.
     */
    LEASES_FENCING,
    /**
     * Compare-and-set semantics.
     */
    COMPARE_AND_SET,
    /**
     * Watch and notification semantics.
     */
    WATCH_NOTIFY,
    /**
     * Stale owner rejection semantics.
     */
    STALE_OWNER_REJECTION,
    /**
     * Leader election semantics.
     */
    LEADER_ELECTION,
    /**
     * Replicated log semantics.
     */
    CONSENSUS_LOG_REPLICATION,
    /**
     * Majority and minority safety behavior.
     */
    MAJORITY_SAFETY,
    /**
     * Stale leader rejection behavior.
     */
    STALE_LEADER_REJECTION,
    /**
     * Consensus safety report production.
     */
    SAFETY_REPORT,
    /**
     * Artifact digest preservation.
     */
    ARTIFACT_DIGEST_PRESERVATION,
    /**
     * Exact artifact byte preservation.
     */
    EXACT_BYTE_PRESERVATION,
    /**
     * Signature, SBOM, and provenance preservation.
     */
    SIGNATURE_SBOM_PROVENANCE,
    /**
     * Repository index snapshot and delta semantics.
     */
    INDEX_SNAPSHOT_DELTA,
    /**
     * Promotion and rollback verification gates.
     */
    PROMOTION_ROLLBACK_GATES,
    /**
     * Conflict detection semantics.
     */
    CONFLICT_DETECTION,
    /**
     * Repair and reconciliation semantics.
     */
    REPAIR_RECONCILIATION,
    /**
     * Saga definition semantics.
     */
    SAGA_DEFINITION,
    /**
     * Ordered saga step execution.
     */
    ORDERED_STEPS,
    /**
     * Reverse compensation semantics.
     */
    REVERSE_COMPENSATION,
    /**
     * Saga outbox integration semantics.
     */
    SAGA_OUTBOX_INTEGRATION,
    /**
     * Saga timeout and cancellation semantics.
     */
    TIMEOUT_CANCELLATION,
    /**
     * Saga retry policy semantics.
     */
    RETRY_POLICY,
    /**
     * Isolation provider capability declaration.
     */
    ISOLATION_CAPABILITY_DECLARATION,
    /**
     * Filesystem policy evaluation.
     */
    FILESYSTEM_POLICY,
    /**
     * Network egress policy evaluation.
     */
    NETWORK_POLICY,
    /**
     * Environment policy evaluation and redaction.
     */
    ENVIRONMENT_POLICY,
    /**
     * Process lifecycle semantics.
     */
    PROCESS_LIFECYCLE,
    /**
     * Output capture and redaction semantics.
     */
    OUTPUT_REDACTION,
    /**
     * Authorization hooks are enforced.
     */
    AUTHORIZATION_HOOKS,
    /**
     * Audit hooks are enforced.
     */
    AUDIT_HOOKS,
    /**
     * Public status, evidence, and failures are redacted.
     */
    REDACTION,
    /**
     * Public errors are normalized into stable J7M failure codes.
     */
    NORMALIZED_ERRORS
}
