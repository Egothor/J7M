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
import java.util.function.*;
import org.j7m.middleware.consensus.NetworkConsensusNode;

/**
 * Scope-bound map/transaction/journal bridge for a partition-wrapped resource.
 * Expose this endpoint through {@link RemoteResourceServer}; its existing authenticated streaming
 * protocol supports complete snapshot+tail projections without increasing generic partition frames.
 * Configure a {@link RemoteResourceClient} with source and destination endpoints for the same
 * partition. Frozen sources and staged targets refuse every operation; only the active owner serves
 * the public {@link EventSubscriptions} and {@link RecoverableTransactions} facades.
 */
public final class PartitionResourceEndpoint implements ResourceEndpoint {
    private final NetworkConsensusNode node;
    private final PartitionStateMachine partition;
    private final ReplicatedResources resource;
    private final String scope;
    private final Predicate<String> authorization;
    private final ReplicatedResourceClient.ClockHealth clock;
    private final LocalPartitionEndpoint mutations;
    private final boolean maintenance;

    /**
     * Binds one authenticated principal to the exact wrapped resource.
     * @param node consensus host of partition
     * @param partition ownership wrapper
     * @param resource exact delegate owned by wrapper
     * @param scope immutable authenticated tenant/principal scope
     * @param authorization operation-specific permission predicate, rechecked on every call
     * @param maintenance whether this principal may deliver coordinator decisions and time controls
     * @param clock concrete trusted measured clock health provider
     * @throws IllegalArgumentException for incorrect hosting or delegate identity
     */
    public PartitionResourceEndpoint(NetworkConsensusNode node, PartitionStateMachine partition,
            ReplicatedResources resource, String scope, Predicate<String> authorization, boolean maintenance,
            ReplicatedResourceClient.ClockHealth clock) {
        if (!node.hosts(partition) || !partition.owns(resource)) throw new IllegalArgumentException("Wrong partition host/delegate");
        PartitionStateMachine.identity(scope); this.node = node; this.partition = partition; this.resource = resource;
        this.scope = scope; this.maintenance = maintenance; this.authorization = Objects.requireNonNull(authorization); this.clock = Objects.requireNonNull(clock);
        mutations = new LocalPartitionEndpoint(node, partition, scope, maintenance, clock);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
        authorize(operation);
        if (operation.equals("TIME")) {
            if (!maintenance) throw new SecurityException("Resource maintenance permission required");
            ReplicatedResources.validate(new ResourceCommand(operation, scope, request, arguments));
            var measured = healthyClock();
            if (Math.abs((double) Long.parseLong(arguments.get(0)) - measured.time()) > 500
                    || Math.abs((double) Long.parseLong(arguments.get(1)) - measured.reference()) > 500
                    || Long.parseLong(arguments.get(2)) < measured.uncertainty()) throw new IOException("CLOCK_UNSAFE: proposal lacks measured evidence");
            commitTrustedTime(measured, deadline);
            return query(deadline, index -> new ReplicatedResources.Result("OK", Long.toString(resource.logicalTime()), index));
        }
        byte[] command = new ResourceCommand(operation, scope, request, arguments).encode();
        byte[] resultQuery = new ResourceCommand("QUERY_RESULT", scope, "query", List.of(request)).encode();
        PartitionReply reply = mutations.execute(partition.status().epoch(), request, command, resultQuery, deadline);
        if (!reply.status().equals("OK")) throw new IOException("Partition " + reply.status() + "; outcome UNKNOWN");
        DataInputStream in = PartitionStateMachine.input(reply.payload());
        ReplicatedResources.Result result = in.readBoolean() ? new ReplicatedResources.Result(in.readUTF(), in.readUTF(), in.readLong()) : null;
        PartitionStateMachine.end(in); if (result == null) throw new IOException("Committed result unavailable; outcome UNKNOWN"); return result;
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Value read(String key, Instant deadline) throws IOException {
        authorize("READ"); return query(deadline, index -> resource.queryAtReadIndex(scope, key, index));
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Lease lease(String key, Instant deadline) throws IOException {
        authorize("LEASE"); advanceTrustedTime(deadline);
        return query(deadline, index -> resource.lease(scope, key, index));
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Result advanceTime(String request, Instant deadline) throws IOException {
        authorize("TIME"); PartitionStateMachine.identity(request);
        if (!maintenance) throw new SecurityException("Resource maintenance permission required");
        advanceTrustedTime(deadline);
        return query(deadline, index -> new ReplicatedResources.Result("OK", Long.toString(resource.logicalTime()), index));
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Result transaction(String tx, Instant deadline) throws IOException {
        authorize("TRANSACTION"); return query(deadline, index -> resource.transaction(scope, tx, index));
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<String> prepared(Instant deadline) throws IOException {
        authorize("PREPARED"); return query(deadline, index -> resource.prepared(scope, index));
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<String> participants(String tx, Instant deadline) throws IOException {
        authorize("PARTICIPANTS"); return query(deadline, index -> resource.participants(scope, tx, index));
    }
    /**
     * {@inheritDoc}
     */
    @Override public EventJournal.Page poll(String subscription, int maximum, Instant deadline) throws IOException {
        authorize("POLL"); advanceTrustedTime(deadline);
        return query(deadline, index -> resource.poll(scope, subscription, maximum, index));
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.SnapshotTail snapshotAndTail(Instant deadline) throws IOException {
        authorize("SNAPSHOT_TAIL"); return query(deadline, index -> resource.snapshotAndTail(scope, index));
    }
    private <T> T query(Instant deadline, LongFunction<T> query) throws IOException {
        long barrier = node.readIndex(deadline); long epoch = partition.status().epoch();
        try { return partition.guardedQuery(epoch, barrier, resource, ignored -> query.apply(partition.status().logicalIndex())); }
        catch (IllegalStateException unavailable) { throw new IOException(unavailable.getMessage(), unavailable); }
    }
    private void advanceTrustedTime(Instant deadline) throws IOException { commitTrustedTime(healthyClock(), deadline); }
    private ReplicatedResourceClient.ClockMeasurement healthyClock() throws IOException {
        var measured = clock.sample();
        if (!measured.monotonicStable() || measured.uncertainty() < 0 || measured.uncertainty() > 500
                || Math.abs((double) measured.time() - measured.reference()) > 500) throw new IOException("Unhealthy clock");
        return measured;
    }
    private void commitTrustedTime(ReplicatedResourceClient.ClockMeasurement measured, Instant deadline) throws IOException {
        long index = node.appendControl(PartitionStateMachine.mutation(partition.status().epoch(), resource.timeCommand(measured.time())), deadline);
        if (!partition.receipt(index).equals("OK")) throw new IOException("Partition MOVED");
    }
    private void authorize(String operation) { if (!authorization.test(operation)) throw new SecurityException("Resource operation not authorized"); }
}
