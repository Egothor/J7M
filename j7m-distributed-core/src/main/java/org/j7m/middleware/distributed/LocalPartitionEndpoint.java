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
import java.util.Objects;
import org.j7m.middleware.consensus.NetworkConsensusNode;

/**
 * Server-bound local adapter; every mutation is epoch-fenced in the hosted state machine.
 * Admission validates tenant binding before append. The resource must itself provide durable business
 * deduplication: the consensus transport identity intentionally excludes mutable routing epochs.
 */
public final class LocalPartitionEndpoint implements PartitionEndpoint {
    private final NetworkConsensusNode node;
    private final PartitionStateMachine state;
    private final String scope;
    private final boolean maintenance;
    private final boolean queueAdministrative;
    private final ReplicatedResourceClient.ClockHealth clock;
    /**
     * Creates an immutable principal binding.
     * @param node consensus node hosting exactly state
     * @param state partition wrapper
     * @param scope authenticated tenant/principal scope
     * @param maintenance permission to submit resource-specific maintenance controls
     * @param clock concrete measured clock health provider
     * @throws IllegalArgumentException for incorrect hosting or invalid scope
     */
    public LocalPartitionEndpoint(NetworkConsensusNode node, PartitionStateMachine state, String scope,
            boolean maintenance, ReplicatedResourceClient.ClockHealth clock) {
        this(node, state, scope, maintenance, maintenance, clock);
    }
    LocalPartitionEndpoint(NetworkConsensusNode node, PartitionStateMachine state, String scope,
            boolean maintenance, boolean queueAdministrative, ReplicatedResourceClient.ClockHealth clock) {
        if (!node.hosts(state)) throw new IllegalArgumentException("Wrong hosted partition");
        PartitionStateMachine.identity(scope); this.node = node; this.state = state; this.scope = scope;
        this.maintenance = maintenance; this.queueAdministrative = queueAdministrative; this.clock = Objects.requireNonNull(clock);
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionReply execute(long epoch, String requestId, byte[] command, byte[] resultQuery, Instant deadline) throws IOException {
        PartitionStateMachine.identity(requestId); state.validateQuery(resultQuery, scope);
        if (!requestId.equals(state.requestIdentity(command))) throw new IllegalArgumentException("Request identity mismatch");
        try {
            boolean redrive = state.queueResource() != null && ResourceCommand.decode(command).operation().equals("Q_REDRIVE");
            if (redrive && !queueAdministrative) throw new SecurityException("Queue redrive authority required");
            state.validate(command, scope, redrive ? queueAdministrative : maintenance);
        }
        catch (IllegalStateException capacity) {
            if (state.queueResource() != null && capacity.getMessage() != null && capacity.getMessage().startsWith("OVERLOADED"))
                return new PartitionReply("OK", state.status().epoch(), "OVERLOADED".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw capacity;
        }
        if (state.identityConflict(command)) throw new IllegalArgumentException("Request identity conflict");
        if (state.timeControl(command)) throw new SecurityException("Logical time controls are host-generated only");
        if (state.timeSensitive(command)) {
            var measured = clock.sample();
            if (!measured.monotonicStable() || measured.uncertainty() < 0 || measured.uncertainty() > 500
                    || Math.abs((double) measured.time() - measured.reference()) > 500) throw new IOException("Unhealthy clock");
            node.appendControl(PartitionStateMachine.mutation(epoch, state.timeCommand(measured.time())), deadline);
        }
        // Resource deduplication owns business identity. CONTROL permits retries after epoch refresh
        // without changing the exact bytes of a previously journalled consensus client request.
        long index = node.appendControl(PartitionStateMachine.mutation(epoch, command), deadline);
        String receipt = state.receipt(index);
        if (!receipt.equals("OK")) return new PartitionReply(receipt, state.status().epoch(), new byte[0]);
        long barrier = node.readIndex(deadline);
        try { return new PartitionReply("OK", state.status().epoch(), state.query(epoch, resultQuery, barrier)); }
        catch (IllegalStateException moved) { return new PartitionReply("MOVED", state.status().epoch(), new byte[0]); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) throws IOException {
        state.validateQuery(query, scope); long barrier = node.readIndex(deadline);
        try { return new PartitionReply("OK", state.status().epoch(), state.query(epoch, query, barrier)); }
        catch (IllegalStateException moved) { return new PartitionReply("MOVED", state.status().epoch(), new byte[0]); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException { node.readIndex(deadline); return state.status(); }
}
