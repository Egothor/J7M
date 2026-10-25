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
import java.util.Objects;

/**
 * Typed subscription facade over an authorized map resource endpoint. Subscription mutations and
 * acknowledgements commit to the same durable source group; poll is at least once. Callers must
 * deduplicate by the full cursor and acknowledge only after their own processing has completed.
 */
public final class EventSubscriptions {
    private final ResourceEndpoint endpoint;
    /**
     * Binds an authorized endpoint, local or remote.
     * @param endpoint scope-bound source endpoint
     */
    public EventSubscriptions(ResourceEndpoint endpoint) { this.endpoint = Objects.requireNonNull(endpoint); }
    /**
     * Creates a leased subscription from an exact source cursor.
     * @param request idempotency identity
     * @param id subscription identity
     * @param filter exact operation or empty
     * @param from last consumed source position
     * @param leaseMillis positive duration at most one day
     * @param deadline operation deadline
     * @return committed result, including explicit CURSOR_EXPIRED if retention no longer covers from
     * @throws IOException on transport/quorum/deadline failure; outcome can be unknown
     */
    public ReplicatedResources.Result subscribe(String request, String id, String filter, EventJournal.Cursor from,
            long leaseMillis, Instant deadline) throws IOException {
        return endpoint.execute("SUBSCRIBE", request, List.of(id, filter, from.partition(), Long.toString(from.epoch()), Long.toString(from.sequence()), Long.toString(leaseMillis)), deadline);
    }
    /**
     * Commits processed progress and renews the subscription lease.
     * @param request idempotency identity
     * @param id subscription identity
     * @param cursor last processed source position
     * @param leaseMillis renewed duration
     * @param deadline operation deadline
     * @return committed result; no source side effect is rolled back by delivery failure
     * @throws IOException on transport/quorum/deadline failure
     */
    public ReplicatedResources.Result ack(String request, String id, EventJournal.Cursor cursor, long leaseMillis, Instant deadline) throws IOException {
        return endpoint.execute("ACK", request, List.of(id, cursor.partition(), Long.toString(cursor.epoch()), Long.toString(cursor.sequence()), Long.toString(leaseMillis)), deadline);
    }
    /**
     * Renews an existing subscription without resetting durable progress.
     * @param request idempotency identity
     * @param id existing subscription
     * @param leaseMillis renewed duration
     * @param deadline operation deadline
     * @return committed result containing partition:epoch:sequence progress
     * @throws IOException on transport/quorum/deadline failure
     */
    public ReplicatedResources.Result resume(String request, String id, long leaseMillis, Instant deadline) throws IOException {
        return endpoint.execute("RESUME", request, List.of(id, Long.toString(leaseMillis)), deadline);
    }
    /**
     * Polls without acknowledging; retries return the same unacknowledged identities.
     * @param id subscription identity
     * @param maximum maximum scanned positions in [1,256]
     * @param deadline operation deadline
     * @return bounded matching event page
     * @throws IOException on quorum failure, CURSOR_EXPIRED or SUBSCRIPTION_EXPIRED
     */
    public EventJournal.Page poll(String id, int maximum, Instant deadline) throws IOException { return endpoint.poll(id, maximum, deadline); }
    /**
     * Captures the authorized map projection and exact source tail barrier atomically.
     * @param deadline operation deadline
     * @return source projection and cursor; subscribe from this cursor to tail subsequent changes
     * @throws IOException on transport/quorum/deadline failure
     */
    public ReplicatedResources.SnapshotTail snapshotAndTail(Instant deadline) throws IOException { return endpoint.snapshotAndTail(deadline); }
}
