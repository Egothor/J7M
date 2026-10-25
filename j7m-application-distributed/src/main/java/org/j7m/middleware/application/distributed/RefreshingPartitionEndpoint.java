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

package org.j7m.middleware.application.distributed;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.distributed.*;

/**
 * Exact-call partition route binding over authorized topology and group clients. Binding refresh
 * never repeats execute/query: MOVED/FROZEN or an IOException mark the route for a subsequent call.
 * Native group clients retain their documented leader/idempotency protocol. The original request ID,
 * command, cursor/query bytes and deadline are passed unchanged. UNKNOWN remains UNKNOWN.
 */
public final class RefreshingPartitionEndpoint implements PartitionEndpoint, AutoCloseable {
    private final String partition;
    private final PartitionAdminClient topology;
    private final Map<String, PartitionEndpoint> groups;
    private final BindingRefresh refresh;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicLong refreshes = new AtomicLong();
    private volatile PartitionTopology.Route route;
    private volatile boolean closed;
    /**
     * Reads an initial authoritative route without changing the supplied clients' principals.
     * @param partition stable partition identity
     * @param topology trusted route-read client with actual configured authorization
     * @param groups finite authorized owner-group endpoints, at most256
     * @param refresh explicit binding mode
     * @param deadline initial route-read deadline
     * @throws IOException for unavailable topology or unauthorized/malformed ownership
     * @throws IllegalArgumentException for invalid configuration
     */
    public RefreshingPartitionEndpoint(String partition, PartitionAdminClient topology, Map<String, PartitionEndpoint> groups,
            BindingRefresh refresh, Instant deadline) throws IOException {
        if (partition == null || partition.isBlank() || partition.length() > 256 || groups == null || groups.isEmpty() || groups.size() > 256) throw new IllegalArgumentException("Invalid partition binding");
        this.partition = partition; this.topology = Objects.requireNonNull(topology); this.groups = Map.copyOf(groups); this.refresh = Objects.requireNonNull(refresh);
        update(deadline);
    }
    /**
     * Returns current cached ownership without a network operation.
     * @return immutable last accepted authoritative route
     */
    public PartitionTopology.Route ownership() { return route; }
    /**
     * Returns completed authoritative route reads, including initial preparation.
     * @return aggregate refresh count
     */
    public long refreshes() { return refreshes.get(); }
    private PartitionTopology.Route current(Instant deadline) throws IOException {
        if (closed) throw new IOException("Partition binding closed");
        if (!Instant.now().isBefore(deadline)) throw new IOException("Partition binding deadline");
        if (refresh == BindingRefresh.ON_TOPOLOGY_CHANGE || refresh == BindingRefresh.ON_FAILURE && dirty.getAndSet(false)) {
            try { update(deadline); } catch (IOException error) { failed(); throw error; }
        }
        return route;
    }
    private void update(Instant deadline) throws IOException {
        if (closed) throw new IOException("Partition binding closed");
        var candidate = topology.route(partition, deadline);
        if (!candidate.partition().equals(partition) || !groups.containsKey(candidate.ownerGroup())) throw new IOException("No authorized owner binding");
        synchronized (this) {
            if (closed) throw new IOException("Partition binding closed");
            if (route != null && (candidate.epoch() < route.epoch() || candidate.epoch() == route.epoch() && !candidate.ownerGroup().equals(route.ownerGroup()))) throw new IOException("Stale or conflicting ownership epoch");
            route = candidate; refreshes.incrementAndGet();
        }
    }
    private void failed() { if (refresh != BindingRefresh.NEVER) dirty.set(true); }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionReply execute(long ignoredEpoch, String requestId, byte[] command, byte[] query, Instant deadline) throws IOException {
        var selected = current(deadline);
        try {
            var result = groups.get(selected.ownerGroup()).execute(selected.epoch(), requestId, command, query, deadline);
            if (result.status().equals("MOVED") || result.status().equals("FROZEN")) failed(); return result;
        } catch (IOException error) { failed(); throw error; }
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionReply query(long ignoredEpoch, byte[] query, Instant deadline) throws IOException {
        var selected = current(deadline);
        try {
            var result = groups.get(selected.ownerGroup()).query(selected.epoch(), query, deadline);
            if (result.status().equals("MOVED") || result.status().equals("FROZEN")) failed(); return result;
        } catch (IOException error) { failed(); throw error; }
    }
    /**
     * {@inheritDoc}
     */
    @Override public QueueSession subscribeQueue(long ignoredEpoch, String queue, String group, String member, long visibilityMillis, boolean observational, Instant deadline) throws IOException {
        var selected = current(deadline);
        try { return groups.get(selected.ownerGroup()).subscribeQueue(selected.epoch(), queue, group, member, visibilityMillis, observational, deadline); }
        catch (IOException unavailable) { failed(); throw unavailable; }
    }
    /**
     * {@inheritDoc}
     */
    @Override public ExecutionSession subscribeExecution(long ignoredEpoch, String prefix, String kind, String owner, long leaseMillis, Instant deadline) throws IOException {
        var selected = current(deadline);
        try { return groups.get(selected.ownerGroup()).subscribeExecution(selected.epoch(), prefix, kind, owner, leaseMillis, deadline); }
        catch (IOException unavailable) { failed(); throw unavailable; }
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException {
        var selected = current(deadline);
        try { return groups.get(selected.ownerGroup()).status(deadline); }
        catch (IOException error) { failed(); throw error; }
    }
    /**
     * Revokes this binding without closing host-owned topology/group clients.
     */
    @Override public void close() { closed = true; }
}
