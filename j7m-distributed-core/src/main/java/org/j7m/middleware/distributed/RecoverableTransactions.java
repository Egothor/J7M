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
import java.util.*;

/**
 * Durable strict-2PL two-phase commit for participating replicated map resources only. Reads carry
 * versions and range reads lock the tenant's full key range; this conservative policy prevents write
 * skew and phantoms. Conflicting prepares select the requesting transaction as victim before a durable
 * decision. Prepared locks survive snapshots and never expire autonomously. Coordinator unavailability
 * leaves PREPARED participants blocked, not heuristically committed or aborted. External databases and
 * arbitrary side effects are not covered. Recovery is explicit and safely repeatable after any failure.
 */
public final class RecoverableTransactions {
    private final ResourceEndpoint coordinator;
    private final Map<String, ResourceEndpoint> participants;
    /**
     * Constructs a coordinator over independent quorum-authoritative resource groups.
     * @param coordinator durable coordinator journal resource
     * @param participants stable participant ids mapped to their current authoritative endpoints
     */
    public RecoverableTransactions(ResourceEndpoint coordinator, Map<String, ResourceEndpoint> participants) {
        this.coordinator = Objects.requireNonNull(coordinator); this.participants = Map.copyOf(participants);
    }
    /**
     * Begins a transaction with a durable PREPARING record.
     * @param tx globally unique transaction id within authorization scope
     * @param deadline absolute operation deadline
     * @return persisted result
     * @throws IOException on quorum failure or ambiguous completion
     */
    public ReplicatedResources.Result begin(String tx, Instant deadline) throws IOException {
        return coordinator.execute("BEGIN", tx + ":begin", List.of(tx), deadline);
    }
    /**
     * Durably enlists a participant before any prepare is sent.
     * @param tx transaction id
     * @param participant configured stable participant id
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on journal failure
     * @throws IllegalArgumentException if participant is unknown
     */
    public ReplicatedResources.Result enlist(String tx, String participant, Instant deadline) throws IOException {
        endpoint(participant);
        return coordinator.execute("ENLIST", tx + ":enlist:" + participant, List.of(tx, participant), deadline);
    }
    /**
     * Acquires a durable read lock before returning a transaction's read value. Repeated reads need
     * distinct request identities. Conflicts select this transaction as an abort victim.
     * @param tx transaction id
     * @param participant durably enlisted participant id
     * @param request unique read identity
     * @param key resource key
     * @param deadline absolute deadline
     * @return locked value in result payload, empty for absent
     * @throws IOException on quorum failure
     * @throws IllegalStateException if not enlisted
     */
    public ReplicatedResources.Result read(String tx, String participant, String request, String key,
            Instant deadline) throws IOException {
        return lock(tx, participant, request, key, "LOCK_READ", deadline);
    }
    /**
     * Acquires a durable conservative tenant-wide range lock before any range query.
     * @param tx transaction id
     * @param participant durably enlisted participant id
     * @param request unique range-read identity
     * @param prefix logical range prefix
     * @param deadline absolute deadline
     * @return persisted lock result; subsequent ordinary reads remain protected until decision
     * @throws IOException on quorum failure
     * @throws IllegalStateException if not enlisted
     */
    public ReplicatedResources.Result lockRange(String tx, String participant, String request, String prefix,
            Instant deadline) throws IOException {
        return lock(tx, participant, request, prefix, "LOCK_RANGE", deadline);
    }
    private ReplicatedResources.Result lock(String tx, String participant, String request, String key,
            String operation, Instant deadline) throws IOException {
        ReplicatedResources.Result status = requiredStatus(tx, deadline);
        if (!status.status().equals("PREPARING") || !coordinator.participants(tx, deadline).contains(participant))
            throw new IllegalStateException("Participant not enlisted before read");
        ReplicatedResources.Result result = endpoint(participant).execute(operation, request, List.of(tx, key), deadline);
        if (result.status().equals("CONFLICT")) abort(tx, deadline);
        return result;
    }
    /**
     * Prepares a participant using READ,key,version; WRITE,key,value; RANGE,prefix operations.
     * RANGE conservatively locks the entire tenant range. All observed reads must be included.
     * @param tx transaction id
     * @param participant configured participant id
     * @param operations encoded bounded operations
     * @param deadline absolute deadline
     * @return PREPARED or CONFLICT; conflict durably aborts coordinator before releasing any locks
     * @throws IOException on unavailable/unknown outcome; call recovery, never assume abort
     * @throws IllegalStateException if enlist failed or a decision already exists
     */
    public ReplicatedResources.Result prepare(String tx, String participant, List<String> operations,
            Instant deadline) throws IOException {
        ReplicatedResources.Result status = coordinator.transaction(tx, deadline);
        if (status == null || !(status.status().equals("PREPARING") || status.status().equals("SEALED"))
                || !coordinator.participants(tx, deadline).contains(participant)) {
            throw new IllegalStateException("Participant must be durably enlisted before prepare");
        }
        var args = new ArrayList<String>(); args.add(tx); args.addAll(operations);
        ReplicatedResources.Result result = endpoint(participant).execute("PREPARE", tx + ":prepare:" + participant,
                args, deadline);
        if (!result.status().equals("PREPARED")) abort(tx, deadline);
        return result;
    }
    /**
     * Writes COMMIT only after all durably enlisted participants report PREPARED, then delivers it.
     * @param tx transaction id
     * @param deadline absolute deadline; expiry never undoes durable COMMIT
     * @return durable coordinator status; may throw after COMMIT when delivery is interrupted
     * @throws IOException on unavailable quorum or ambiguous delivery
     * @throws IllegalStateException if a participant has not prepared or transaction is unknown
     */
    public ReplicatedResources.Result commit(String tx, Instant deadline) throws IOException {
        ReplicatedResources.Result current = requiredStatus(tx, deadline);
        if (current.status().equals("ABORTED")) return current;
        if (current.status().equals("PREPARING") || current.status().equals("SEALED")) {
            coordinator.execute("SEAL", tx + ":seal", List.of(tx), deadline);
            List<String> enlisted = coordinator.participants(tx, deadline);
            if (enlisted.isEmpty()) throw new IllegalStateException("No enlisted participants");
            for (String id : enlisted) {
                ReplicatedResources.Result status = endpoint(id).transaction(tx, deadline);
                if (status == null || !status.status().equals("PREPARED")) throw new IllegalStateException("Not all participants prepared");
            }
            coordinator.execute("DECIDE", tx + ":decision", List.of(tx, "COMMITTED"), deadline);
        }
        return recover(tx, deadline);
    }
    /**
     * Durably aborts only before a committed decision; committed transactions are completed instead.
     * @param tx transaction id
     * @param deadline absolute deadline
     * @return durable coordinator status
     * @throws IOException on quorum failure or ambiguous delivery
     * @throws IllegalStateException if transaction is unknown
     */
    public ReplicatedResources.Result abort(String tx, Instant deadline) throws IOException {
        ReplicatedResources.Result current = requiredStatus(tx, deadline);
        if (current.status().equals("PREPARING") || current.status().equals("SEALED")) {
            coordinator.execute("DECIDE", tx + ":abort-decision", List.of(tx, "ABORTED"), deadline);
        }
        return recover(tx, deadline);
    }
    /**
     * Replays only an authoritative durable decision to prepared participants. No decision means
     * IN_DOUBT and retains all locks; timeout alone never authorizes participant rollback.
     * @param tx transaction id
     * @param deadline absolute deadline
     * @return COMMITTED/ABORTED decision or IN_DOUBT
     * @throws IOException on unavailable coordinator or participant; retry recovery
     * @throws IllegalStateException if transaction is unknown or a participant contradicts the journal
     */
    public ReplicatedResources.Result recover(String tx, Instant deadline) throws IOException {
        ReplicatedResources.Result decision = requiredStatus(tx, deadline);
        if (decision.status().equals("PREPARING") || decision.status().equals("SEALED")) return new ReplicatedResources.Result("IN_DOUBT", "", decision.index());
        String operation = decision.status().equals("COMMITTED") ? "COMMIT" : "ROLLBACK";
        for (String id : coordinator.participants(tx, deadline)) {
            var participant = endpoint(id);
            ReplicatedResources.Result status = participant.transaction(tx, deadline);
            if (status == null) {
                if (operation.equals("COMMIT")) throw new IllegalStateException("Committed participant missing durable prepare");
                continue;
            }
            ReplicatedResources.Result delivered = participant.execute(operation, tx + ":deliver:" + decision.value(),
                    List.of(tx, decision.value()), deadline);
            if (!delivered.status().equals(decision.status())) throw new IllegalStateException("Contradictory participant decision");
        }
        return decision;
    }
    /**
     * Scans durable prepared locks and resolves them from the authoritative coordinator journal.
     * @param participant participant id to scan
     * @param deadline absolute deadline
     * @return immutable recovery outcomes
     * @throws IOException on unavailable coordinator/participant; locks remain durable
     */
    public List<ReplicatedResources.Result> recoverPrepared(String participant, Instant deadline) throws IOException {
        var results = new ArrayList<ReplicatedResources.Result>();
        for (String tx : endpoint(participant).prepared(deadline)) results.add(recover(tx, deadline));
        return List.copyOf(results);
    }
    /**
     * Reads authoritative coordinator status without interpreting a deadline as an abort.
     * @param tx transaction id
     * @param deadline absolute deadline
     * @return durable status or null
     * @throws IOException on unavailable quorum
     */
    public ReplicatedResources.Result status(String tx, Instant deadline) throws IOException {
        return coordinator.transaction(tx, deadline);
    }
    private ReplicatedResources.Result requiredStatus(String tx, Instant deadline) throws IOException {
        ReplicatedResources.Result current = status(tx, deadline);
        if (current == null) throw new IllegalStateException("Unknown transaction");
        return current;
    }
    private ResourceEndpoint endpoint(String id) {
        ResourceEndpoint endpoint = participants.get(id);
        if (endpoint == null) throw new IllegalArgumentException("Unknown participant");
        return endpoint;
    }
}
