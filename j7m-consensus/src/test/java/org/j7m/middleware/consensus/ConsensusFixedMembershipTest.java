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

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.j7m.middleware.api.consensus.AppendEntriesResponse;
import org.j7m.middleware.api.consensus.ConsensusGroupId;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusRole;
import org.j7m.middleware.api.consensus.ConsensusState;
import org.j7m.middleware.api.consensus.ConsensusTerm;
import org.j7m.middleware.api.consensus.LogIndex;
import org.j7m.middleware.api.consensus.RequestVoteRequest;
import org.j7m.middleware.api.consensus.RequestVoteResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class ConsensusFixedMembershipTest {
    @TempDir
    Path directory;

    @Test
    public void fixedMembershipRejectsUnknownCandidate() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            RequestVoteRequest request = new RequestVoteRequest(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(1L),
                    new ConsensusNodeId("unknown"),
                    new LogIndex(0L),
                    new ConsensusTerm(0L));
            assertFalse(simulation.node(ConsensusTestSupport.N2).onRequestVote(request).voteGranted());
            assertFalse(simulation.status().dynamicMembershipSupported());
        }
    }

    @Test
    public void invalidVoteMessagesCannotMutateTermOrVote() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            ConsensusState before = simulation.node(ConsensusTestSupport.N2).state();
            RequestVoteRequest foreignGroup = new RequestVoteRequest(
                    new ConsensusGroupId("foreign-consensus"),
                    new ConsensusTerm(before.term().value() + 10L),
                    ConsensusTestSupport.N1,
                    new LogIndex(0L),
                    new ConsensusTerm(0L));
            assertFalse(simulation.node(ConsensusTestSupport.N2).onRequestVote(foreignGroup).voteGranted());
            assertEquals(before.term(), simulation.node(ConsensusTestSupport.N2).state().term());
            assertEquals(before.votedFor(), simulation.node(ConsensusTestSupport.N2).state().votedFor());

            RequestVoteRequest unknownCandidate = new RequestVoteRequest(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(before.term().value() + 10L),
                    new ConsensusNodeId("unknown"),
                    new LogIndex(0L),
                    new ConsensusTerm(0L));
            assertFalse(simulation.node(ConsensusTestSupport.N2).onRequestVote(unknownCandidate).voteGranted());
            assertEquals(before.term(), simulation.node(ConsensusTestSupport.N2).state().term());
            assertEquals(before.votedFor(), simulation.node(ConsensusTestSupport.N2).state().votedFor());
        }
    }

    @Test
    public void invalidResponsesCannotMutateLeaderOrCandidateState() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            assertEquals(ConsensusRole.LEADER, simulation.node(ConsensusTestSupport.N1).role());
            ConsensusTerm leaderTerm = simulation.node(ConsensusTestSupport.N1).term();

            AppendEntriesResponse unknownAppendResponse = new AppendEntriesResponse(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(leaderTerm.value() + 10L),
                    new ConsensusNodeId("unknown"),
                    true,
                    new LogIndex(10L),
                    "FORGED");
            assertFalse(simulation.node(ConsensusTestSupport.N1).onAppendEntriesResponse(unknownAppendResponse));
            assertEquals(ConsensusRole.LEADER, simulation.node(ConsensusTestSupport.N1).role());
            assertEquals(leaderTerm, simulation.node(ConsensusTestSupport.N1).term());

            RequestVoteResponse foreignVoteResponse = new RequestVoteResponse(
                    new ConsensusGroupId("foreign-consensus"),
                    new ConsensusTerm(leaderTerm.value() + 10L),
                    ConsensusTestSupport.N2,
                    true,
                    "FORGED");
            assertFalse(simulation.node(ConsensusTestSupport.N3).startElection().isEmpty());
            ConsensusTerm candidateTerm = simulation.node(ConsensusTestSupport.N3).term();
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N3).role());
            assertTrue(simulation.node(ConsensusTestSupport.N3).onRequestVoteResponse(foreignVoteResponse).isEmpty());
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N3).role());
            assertEquals(candidateTerm, simulation.node(ConsensusTestSupport.N3).term());
        }
    }

    @Test
    public void unexpectedAndDuplicateVoteResponsesCannotMutateElectionState() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            RequestVoteResponse unexpected = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(1L),
                    ConsensusTestSupport.N2,
                    true,
                    "UNEXPECTED");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(unexpected).isEmpty());
            assertEquals(ConsensusRole.FOLLOWER, simulation.node(ConsensusTestSupport.N1).role());
            assertEquals(new ConsensusTerm(0L), simulation.node(ConsensusTestSupport.N1).term());

            assertFalse(simulation.node(ConsensusTestSupport.N1).startElection().isEmpty());
            ConsensusTerm candidateTerm = simulation.node(ConsensusTestSupport.N1).term();
            RequestVoteResponse rejected = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    candidateTerm,
                    ConsensusTestSupport.N2,
                    false,
                    "REJECTED");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(rejected).isEmpty());
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N1).role());

            RequestVoteResponse duplicateGrant = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    candidateTerm,
                    ConsensusTestSupport.N2,
                    true,
                    "DUPLICATE");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(duplicateGrant).isEmpty());
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N1).role());
            assertEquals(candidateTerm, simulation.node(ConsensusTestSupport.N1).term());
        }
    }

    @Test
    public void higherTermVoteResponseFromConfiguredPeerStepsCandidateDown() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            assertFalse(simulation.node(ConsensusTestSupport.N1).startElection().isEmpty());
            ConsensusTerm candidateTerm = simulation.node(ConsensusTestSupport.N1).term();

            RequestVoteResponse higherTerm = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(candidateTerm.value() + 1L),
                    ConsensusTestSupport.N2,
                    true,
                    "HIGHER_TERM");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(higherTerm).isEmpty());
            assertEquals(ConsensusRole.FOLLOWER, simulation.node(ConsensusTestSupport.N1).role());
            assertEquals(higherTerm.term(), simulation.node(ConsensusTestSupport.N1).term());
            assertNull(simulation.node(ConsensusTestSupport.N1).state().votedFor());
        }
    }

    @Test
    public void staleSelfUnknownAndForeignVoteResponsesCannotMutateCandidate() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            assertFalse(simulation.node(ConsensusTestSupport.N1).startElection().isEmpty());
            ConsensusTerm candidateTerm = simulation.node(ConsensusTestSupport.N1).term();

            RequestVoteResponse stale = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(0L),
                    ConsensusTestSupport.N2,
                    true,
                    "STALE");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(stale).isEmpty());
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N1).role());
            assertEquals(candidateTerm, simulation.node(ConsensusTestSupport.N1).term());

            RequestVoteResponse self = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    candidateTerm,
                    ConsensusTestSupport.N1,
                    true,
                    "SELF");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(self).isEmpty());
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N1).role());

            RequestVoteResponse unknown = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    candidateTerm,
                    new ConsensusNodeId("unknown"),
                    true,
                    "UNKNOWN");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(unknown).isEmpty());
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N1).role());

            RequestVoteResponse foreign = new RequestVoteResponse(
                    new ConsensusGroupId("foreign-consensus"),
                    candidateTerm,
                    ConsensusTestSupport.N2,
                    true,
                    "FOREIGN");
            assertTrue(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(foreign).isEmpty());
            assertEquals(ConsensusRole.CANDIDATE, simulation.node(ConsensusTestSupport.N1).role());
            assertEquals(candidateTerm, simulation.node(ConsensusTestSupport.N1).term());

            RequestVoteResponse valid = new RequestVoteResponse(
                    simulation.configuration().groupId(),
                    candidateTerm,
                    ConsensusTestSupport.N2,
                    true,
                    "GRANTED");
            assertFalse(simulation.node(ConsensusTestSupport.N1).onRequestVoteResponse(valid).isEmpty());
            assertEquals(ConsensusRole.LEADER, simulation.node(ConsensusTestSupport.N1).role());
        }
    }
}
