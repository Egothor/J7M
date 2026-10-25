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

package org.j7m.middleware.distributed;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Authorized resource-group endpoint, local or authenticated remote. Implementations resolve the
 * current leader for every request and preserve request identity on retries. Mutation permission is
 * operation-specific: COMMIT/ROLLBACK/DECIDE require the configured coordinator service identity,
 * not an ordinary application principal. Time/lease operations require measured clock health at the
 * hosting node. An IOException may mean UNKNOWN, never proof of nonexecution.
 */
public interface ResourceEndpoint {
    /**
     * Submits a version-one operation through the authoritative group's durable log.
     * @param operation documented resource operation
     * @param request unique idempotency key scoped to authenticated tenant/principal
     * @param arguments bounded operation arguments
     * @param deadline absolute deadline
     * @return persisted operation result
     * @throws IOException on transport, quorum, deadline or ambiguous completion
     * @throws SecurityException if operation-specific permission is denied
     */
    ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException;
    /**
     * Reads a map value after a current leader quorum barrier.
     * @param key resource key
     * @param deadline absolute deadline
     * @return value/version, or null
     * @throws IOException on unavailable quorum
     */
    ReplicatedResources.Value read(String key, Instant deadline) throws IOException;
    /**
     * Reads a live lease only after healthy committed time and a fresh quorum barrier.
     * @param key scope-bound lease key
     * @param deadline absolute deadline
     * @return live lease including version, fence and effective leadership epoch, or null
     * @throws IOException for quorum, transport or clock-health failure
     * @throws UnsupportedOperationException if leases are not supported
     */
    default ReplicatedResources.Lease lease(String key, Instant deadline) throws IOException {
        throw new UnsupportedOperationException("Lease capability unavailable");
    }
    /**
     * Commits host-generated measured logical time using an explicitly authorized time-service identity.
     * Client-provided wall time never becomes an expiry authority.
     * @param request diagnostic maintenance identity; time controls do not consume client dedupe slots
     * @param deadline absolute deadline
     * @return committed time result
     * @throws IOException for unavailable quorum or unhealthy host clock
     * @throws SecurityException when maintenance permission is absent
     * @throws UnsupportedOperationException if time maintenance is unsupported
     */
    default ReplicatedResources.Result advanceTime(String request, Instant deadline) throws IOException {
        throw new UnsupportedOperationException("Time maintenance capability unavailable");
    }
    /**
     * Reads durable transaction state.
     * @param tx transaction id
     * @param deadline absolute deadline
     * @return status/decision epoch, or null
     * @throws IOException on unavailable quorum
     */
    ReplicatedResources.Result transaction(String tx, Instant deadline) throws IOException;
    /**
     * Scans unfinished participant transactions holding durable locks.
     * @param deadline absolute deadline
     * @return immutable transaction ids
     * @throws IOException on unavailable quorum
     */
    List<String> prepared(Instant deadline) throws IOException;
    /**
     * Lists durable coordinator participant enrollment.
     * @param tx transaction id
     * @param deadline absolute deadline
     * @return immutable participant ids
     * @throws IOException on unavailable quorum
     */
    List<String> participants(String tx, Instant deadline) throws IOException;
    /**
     * Polls a previously committed leased subscription without acknowledging delivery.
     * @param subscription identity within this endpoint's scope
     * @param maximum scanned event positions, 1..256
     * @param deadline absolute deadline
     * @return bounded immutable event page
     * @throws IOException on unavailable quorum or expired retained cursor/subscription
     * @throws UnsupportedOperationException if this endpoint does not expose journals
     */
    default EventJournal.Page poll(String subscription, int maximum, Instant deadline) throws IOException {
        throw new UnsupportedOperationException("Event journal capability unavailable");
    }
    /**
     * Atomically captures the scope's map projection and the starting cursor for its tail.
     * @param deadline absolute deadline
     * @return immutable source projection and exact tail barrier
     * @throws IOException on unavailable quorum or projection capacity
     * @throws UnsupportedOperationException if this endpoint does not expose journals
     */
    default ReplicatedResources.SnapshotTail snapshotAndTail(Instant deadline) throws IOException {
        throw new UnsupportedOperationException("Snapshot-tail capability unavailable");
    }
}

