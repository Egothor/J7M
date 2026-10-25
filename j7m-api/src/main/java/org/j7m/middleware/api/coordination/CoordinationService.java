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

package org.j7m.middleware.api.coordination;

import org.j7m.middleware.api.reliability.FencingToken;

/**
 * Local first-party coordination and membership service.
 *
 * <p>Implementations own local durable coordination state, membership metadata,
 * lease epochs, fencing tokens, coordinate values, and audit/status evidence.
 * Calls may block on local durable-log appends and recovery. Implementations
 * are expected to be thread-safe for concurrent callers unless a narrower
 * ownership model is explicitly documented. Public status, audit metadata, and
 * exception details must redact coordinate payloads, endpoint credentials, and
 * sensitive owner metadata.</p>
 *
 * <p>Phase 6.4 does not provide consensus, Raft leadership, replicated logs,
 * quorum commit, or linearizable cluster leadership. Leader leases are named
 * exclusive leases with fencing tokens. Stale epochs, stale fencing tokens,
 * missing members, malformed requests, recovery failures, and durable storage
 * failures must map to stable middleware error codes.</p>
 */
public interface CoordinationService extends AutoCloseable {
    /**
     * Returns a membership view.
     *
     * @param query view query; implementations may use defaults when appropriate
     * @return immutable membership view with redacted endpoint/status metadata
     */
    MembershipView membership(MembershipQuery query);

    /**
     * Joins a member.
     *
     * <p>The join decision is durable before the result is returned. Duplicate
     * joins and malformed member identities must be deterministic.</p>
     *
     * @param member member identity and safe endpoint metadata
     * @param options join options, such as initial status and timestamps
     * @return join result with stable decision and reason code
     */
    JoinResult join(MemberIdentity member, JoinOptions options);

    /**
     * Explicitly leaves membership.
     *
     * @param memberId member id to remove from active membership
     * @param options leave options and safe reason metadata
     * @return leave result with stable decision and reason code
     */
    LeaveResult leave(MemberId memberId, LeaveOptions options);

    /**
     * Records a heartbeat.
     *
     * <p>Heartbeat timestamps are interpreted by the implementation clock. The
     * operation is local and does not imply distributed liveness consensus.</p>
     *
     * @param memberId member id producing the heartbeat
     * @param heartbeat heartbeat payload with safe status metadata
     * @return heartbeat result and resulting member view status
     */
    HeartbeatResult heartbeat(MemberId memberId, Heartbeat heartbeat);

    /**
     * Acquires an exclusive lease.
     *
     * <p>Successful acquisition returns a monotonic epoch and fencing token.
     * Callers must pass the token to protected resources; the lease itself does
     * not make external side effects atomic.</p>
     *
     * @param name lease name identifying the protected coordination resource
     * @param owner owner principal with redacted metadata
     * @param options lease duration and acquisition options
     * @return lease grant containing epoch and fencing token
     */
    LeaseGrant acquireLease(LeaseName name, PrincipalRef owner, LeaseOptions options);

    /**
     * Renews a lease by id and expected epoch.
     *
     * @param leaseId lease id to renew
     * @param expectedEpoch caller's expected current epoch
     * @return renewal result, rejecting stale epochs with a stable error
     */
    LeaseRenewal renewLease(LeaseId leaseId, long expectedEpoch);

    /**
     * Returns the fencing token for an active lease.
     *
     * @param leaseId lease id to inspect
     * @return fencing token associated with the active lease
     */
    FencingToken fencingToken(LeaseId leaseId);

    /**
     * Releases a lease.
     *
     * @param leaseId lease id to release
     * @param options release options and safe reason metadata
     * @return release result with stable decision and reason code
     */
    LeaseReleaseResult releaseLease(LeaseId leaseId, ReleaseLeaseOptions options);

    /**
     * Applies compare-and-set metadata update.
     *
     * <p>The update is local, durable, and optionally fence protected. The
     * returned audit/status metadata must identify the coordinate and resulting
     * version without exposing sensitive coordinate values.</p>
     *
     * @param key coordinate key to update
     * @param expected expected current value or version
     * @param update replacement value to persist when the comparison matches
     * @param options CAS options, including optional lease/fencing metadata
     * @return CAS result with stable decision, reason code, and version metadata
     */
    CasResult compareAndSet(CoordinateKey key, CoordinateValue expected, CoordinateValue update, CasOptions options);

    /**
     * Returns a coordinate value.
     *
     * @param key coordinate key to read
     * @return current value, or {@code null} when absent
     */
    CoordinateValue get(CoordinateKey key);

    /**
     * Registers a best-effort runtime watcher.
     *
     * <p>Watch callbacks are local best-effort notifications. Implementations
     * must document callback threading and must not use watchers as a durable
     * delivery guarantee.</p>
     *
     * @param key coordinate key to watch
     * @param watcher watcher callback to invoke for local changes
     * @return closeable watch registration
     */
    WatchRegistration watch(CoordinateKey key, CoordinateWatcher watcher);

    /**
     * Returns redacted status.
     *
     * @return immutable status snapshot with safe membership, lease, and metric data
     */
    CoordinationStatus status();

    /**
     * Returns latest recovery report.
     *
     * @return redacted recovery report for the most recent service open/replay
     */
    CoordinationRecoveryReport recoveryReport();

    /**
     * Closes the service and releases local resources.
     *
     * <p>Close should be idempotent. After close, operations should fail with a
     * stable unavailable/closed error rather than leaking filesystem or durable
     * resource details.</p>
     */
    @Override
    void close();
}
