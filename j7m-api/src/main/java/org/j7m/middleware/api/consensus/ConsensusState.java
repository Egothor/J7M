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

package org.j7m.middleware.api.consensus;

import java.io.Serializable;
import java.util.List;

/**
 * Immutable, redacted consensus node state snapshot.
 *
 * <p>The snapshot is a status value, not a live node object. It is safe to
 * publish through operator status endpoints because log entries are represented
 * by {@link ConsensusLogEntryStatus} metadata rather than full replicated
 * commands. Implementations create this value from local durable state and must
 * preserve the identity and ordering semantics of indexes and terms. The record
 * is immutable and thread-safe after construction.</p>
 *
 * @param nodeId node id represented by this snapshot
 * @param role node role observed when the snapshot was built
 * @param term current durable term
 * @param votedFor voted-for candidate in the current term, if any
 * @param leaderId observed leader id, if any
 * @param commitIndex highest log index known to be committed
 * @param lastApplied highest log index applied to the local state machine
 * @param lastLogIndex highest log index present in the local log
 * @param logEntries redacted log entry status metadata, ordered by log index
 */
public record ConsensusState(
        ConsensusNodeId nodeId,
        ConsensusRole role,
        ConsensusTerm term,
        ConsensusNodeId votedFor,
        ConsensusNodeId leaderId,
        CommitIndex commitIndex,
        LogIndex lastApplied,
        LogIndex lastLogIndex,
        List<ConsensusLogEntryStatus> logEntries) implements Serializable {
    /**
     * Creates a state snapshot.
     *
     * <p>Required identity, term, role, and index fields must be present.
     * Log-entry metadata is defensively copied. Equality is value based and is
     * suitable for comparing deterministic simulation snapshots.</p>
     *
     * @throws IllegalArgumentException when a required state field is missing
     */
    public ConsensusState {
        if (nodeId == null || role == null || term == null || commitIndex == null || lastApplied == null || lastLogIndex == null) {
            throw new IllegalArgumentException("consensus state fields are required");
        }
        logEntries = logEntries == null ? List.of() : List.copyOf(logEntries);
    }
}
