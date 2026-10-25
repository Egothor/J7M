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

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/**
 * Refreshes authoritative ownership after MOVED/FROZEN and retries the exact business identity.
 * No application mutation is synthesized during refresh. Availability requires the independent
 * topology quorum and current owner quorum; a frozen unfinished handoff waits for its coordinator.
 */
public final class RoutedPartitionEndpoint implements PartitionEndpoint {
    private final String partition;
    private final PartitionAdminClient topology;
    private final Map<String, PartitionEndpoint> groups;
    /**
     * Binds a route to configured authorized group endpoints.
     * @param partition stable partition identity
     * @param topology topology read facade; its principal needs only route read permission
     * @param groups immutable group-to-resource endpoint bindings
     */
    public RoutedPartitionEndpoint(String partition, PartitionAdminClient topology, Map<String, PartitionEndpoint> groups) {
        PartitionStateMachine.identity(partition); this.partition = partition;
        this.topology = Objects.requireNonNull(topology); this.groups = Map.copyOf(groups);
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionReply execute(long ignoredEpoch, String requestId, byte[] command, byte[] query, Instant deadline) throws IOException {
        while (Instant.now().isBefore(deadline)) {
            checkCancellation(); var route = topology.route(partition, deadline);
            var result = endpoint(route.ownerGroup()).execute(route.epoch(), requestId, command, query, deadline);
            if (!Set.of("MOVED", "FROZEN").contains(result.status())) return result;
            LockSupport.parkNanos(10_000_000);
        }
        throw new IOException("Routed deadline; outcome UNKNOWN");
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionReply query(long ignoredEpoch, byte[] query, Instant deadline) throws IOException {
        while (Instant.now().isBefore(deadline)) {
            checkCancellation(); var route = topology.route(partition, deadline);
            var result = endpoint(route.ownerGroup()).query(route.epoch(), query, deadline);
            if (!Set.of("MOVED", "FROZEN").contains(result.status())) return result;
            LockSupport.parkNanos(10_000_000);
        }
        throw new IOException("Routed query deadline");
    }
    /**
     * {@inheritDoc}
     */
    @Override public QueueSession subscribeQueue(long ignoredEpoch, String queue, String group, String member, long visibilityMillis, boolean observational, Instant deadline) throws IOException {
        checkCancellation(); var route = topology.route(partition, deadline);
        return endpoint(route.ownerGroup()).subscribeQueue(route.epoch(), queue, group, member, visibilityMillis, observational, deadline);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ExecutionSession subscribeExecution(long ignoredEpoch, String prefix, String kind, String owner, long leaseMillis, Instant deadline) throws IOException {
        checkCancellation(); var route = topology.route(partition, deadline);
        return endpoint(route.ownerGroup()).subscribeExecution(route.epoch(), prefix, kind, owner, leaseMillis, deadline);
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException {
        var route = topology.route(partition, deadline); return endpoint(route.ownerGroup()).status(deadline);
    }
    private PartitionEndpoint endpoint(String owner) throws IOException {
        var endpoint = groups.get(owner); if (endpoint == null) throw new IOException("Owner has no authorized endpoint binding"); return endpoint;
    }
    private static void checkCancellation() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Routed operation cancelled; outcome UNKNOWN");
    }
}
