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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.api.consensus.AppendEntriesRequest;
import org.j7m.middleware.api.consensus.AppendEntriesResponse;
import org.j7m.middleware.api.consensus.CommitIndex;
import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusGroupId;
import org.j7m.middleware.api.consensus.ConsensusLogEntry;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusState;
import org.j7m.middleware.api.consensus.ConsensusTerm;
import org.j7m.middleware.api.consensus.LogIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class ConsensusFollowerAppendValidationTest {
    @TempDir
    Path directory;

    @Test
    public void followerRejectsMismatchedPreviousLog() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            AppendEntriesRequest request = new AppendEntriesRequest(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(1L),
                    ConsensusTestSupport.N1,
                    new LogIndex(5L),
                    new ConsensusTerm(1L),
                    List.of(),
                    new CommitIndex(0L));
            AppendEntriesResponse response = simulation.node(ConsensusTestSupport.N2).onAppendEntries(request);
            assertFalse(response.success());
            assertEquals("LOG_MISMATCH", response.reason());
        }
    }

    @Test
    public void leaderRepairsConflictingFollowerLog() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.node(ConsensusTestSupport.N2).appendUncommittedDiagnosticEntry(new ConsensusLogEntry(
                    new LogIndex(1L),
                    new ConsensusTerm(99L),
                    new ConsensusCommand("conflict", new byte[] {9})));
            simulation.elect(ConsensusTestSupport.N1);
            assertTrue(simulation.submit(ConsensusTestSupport.command("repair")).committed());
            assertEquals("repair", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());
            assertTrue(simulation.safetyReport().logMatching());
        }
    }

    @Test
    public void invalidAppendMessagesCannotMutateConsensusState() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            assertTrue(simulation.submit(ConsensusTestSupport.command("committed-prefix")).committed());
            ConsensusState before = simulation.node(ConsensusTestSupport.N2).state();
            ConsensusLogEntry conflicting = new ConsensusLogEntry(
                    new LogIndex(1L),
                    new ConsensusTerm(before.term().value()),
                    new ConsensusCommand("conflicting-prefix", new byte[] {7}));

            AppendEntriesRequest foreignGroup = new AppendEntriesRequest(
                    new ConsensusGroupId("foreign-consensus"),
                    new ConsensusTerm(before.term().value() + 10L),
                    ConsensusTestSupport.N1,
                    new LogIndex(0L),
                    new ConsensusTerm(0L),
                    List.of(conflicting),
                    new CommitIndex(1L));
            AppendEntriesResponse foreignResponse = simulation.node(ConsensusTestSupport.N2).onAppendEntries(foreignGroup);
            assertFalse(foreignResponse.success());
            assertEquals(before.term(), simulation.node(ConsensusTestSupport.N2).term());
            assertEquals("committed-prefix", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());
            assertEquals(1L, simulation.node(ConsensusTestSupport.N2).commitIndexValue());

            AppendEntriesRequest unknownLeader = new AppendEntriesRequest(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(before.term().value() + 10L),
                    new ConsensusNodeId("unknown"),
                    new LogIndex(0L),
                    new ConsensusTerm(0L),
                    List.of(conflicting),
                    new CommitIndex(1L));
            AppendEntriesResponse unknownResponse = simulation.node(ConsensusTestSupport.N2).onAppendEntries(unknownLeader);
            assertFalse(unknownResponse.success());
            assertEquals(before.term(), simulation.node(ConsensusTestSupport.N2).term());
            assertEquals("committed-prefix", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());

            AppendEntriesRequest malformedBatch = new AppendEntriesRequest(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(before.term().value() + 10L),
                    ConsensusTestSupport.N1,
                    new LogIndex(0L),
                    new ConsensusTerm(0L),
                    List.of(new ConsensusLogEntry(
                            new LogIndex(2L),
                            new ConsensusTerm(before.term().value()),
                            new ConsensusCommand("gap", new byte[] {8}))),
                    new CommitIndex(1L));
            AppendEntriesResponse malformedResponse = simulation.node(ConsensusTestSupport.N2).onAppendEntries(malformedBatch);
            assertFalse(malformedResponse.success());
            assertEquals(before.term(), simulation.node(ConsensusTestSupport.N2).term());
            assertEquals(1L, simulation.node(ConsensusTestSupport.N2).logEntries().size());
        }
    }

    @Test
    public void committedEntriesAreNotOverwrittenOrTruncatedAcrossRestart() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            assertTrue(simulation.submit(ConsensusTestSupport.command("stable")).committed());
            ConsensusLogEntry stable = simulation.node(ConsensusTestSupport.N2).logEntries().getFirst();
            AppendEntriesRequest duplicatePrefix = new AppendEntriesRequest(
                    simulation.configuration().groupId(),
                    simulation.node(ConsensusTestSupport.N1).term(),
                    ConsensusTestSupport.N1,
                    new LogIndex(0L),
                    new ConsensusTerm(0L),
                    List.of(stable),
                    new CommitIndex(1L));
            AppendEntriesResponse duplicateResponse = simulation.node(ConsensusTestSupport.N2).onAppendEntries(duplicatePrefix);
            assertTrue(duplicateResponse.success());
            assertEquals("stable", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());

            AppendEntriesRequest committedConflict = new AppendEntriesRequest(
                    simulation.configuration().groupId(),
                    simulation.node(ConsensusTestSupport.N1).term(),
                    ConsensusTestSupport.N1,
                    new LogIndex(0L),
                    new ConsensusTerm(0L),
                    List.of(new ConsensusLogEntry(
                            new LogIndex(1L),
                            simulation.node(ConsensusTestSupport.N1).term(),
                            new ConsensusCommand("overwrite", new byte[] {9}))),
                    new CommitIndex(1L));
            AppendEntriesResponse conflictResponse = simulation.node(ConsensusTestSupport.N2).onAppendEntries(committedConflict);
            assertFalse(conflictResponse.success());
            assertEquals("COMMITTED_CONFLICT", conflictResponse.reason());
            assertEquals("stable", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());
            assertEquals(1L, simulation.node(ConsensusTestSupport.N2).commitIndexValue());

            simulation.crash(ConsensusTestSupport.N2);
            simulation.restart(ConsensusTestSupport.N2);
            assertEquals("stable", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());
            assertEquals(1L, simulation.node(ConsensusTestSupport.N2).commitIndexValue());
        }
    }

    @Test
    public void emptyAppendCannotCommitUnprovenLocalSuffixAcrossRestart() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.node(ConsensusTestSupport.N2).appendUncommittedDiagnosticEntry(new ConsensusLogEntry(
                    new LogIndex(1L),
                    new ConsensusTerm(99L),
                    new ConsensusCommand("divergent-uncommitted", new byte[] {4})));
            AppendEntriesRequest unprovenCommit = new AppendEntriesRequest(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(1L),
                    ConsensusTestSupport.N1,
                    new LogIndex(0L),
                    new ConsensusTerm(0L),
                    List.of(),
                    new CommitIndex(1L));

            AppendEntriesResponse response = simulation.node(ConsensusTestSupport.N2).onAppendEntries(unprovenCommit);

            assertFalse(response.success());
            assertEquals("INVALID_APPEND", response.reason());
            assertEquals(new ConsensusTerm(0L), simulation.node(ConsensusTestSupport.N2).term());
            assertEquals(0L, simulation.node(ConsensusTestSupport.N2).commitIndexValue());
            assertTrue(simulation.node(ConsensusTestSupport.N2).appliedEntries().isEmpty());
            assertEquals("divergent-uncommitted", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());

            simulation.crash(ConsensusTestSupport.N2);
            simulation.restart(ConsensusTestSupport.N2);
            assertEquals(new ConsensusTerm(0L), simulation.node(ConsensusTestSupport.N2).term());
            assertEquals(0L, simulation.node(ConsensusTestSupport.N2).commitIndexValue());
            assertTrue(simulation.node(ConsensusTestSupport.N2).appliedEntries().isEmpty());
            assertEquals("divergent-uncommitted", simulation.node(ConsensusTestSupport.N2).logEntries().getFirst().command().commandId());
        }
    }

    @Test
    public void forgedAppendEnvelopeCannotMutateFollower() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            ConsensusState before = simulation.node(ConsensusTestSupport.N2).state();
            AppendEntriesRequest forged = new AppendEntriesRequest(
                    simulation.configuration().groupId(),
                    new ConsensusTerm(before.term().value() + 10L),
                    ConsensusTestSupport.N1,
                    new LogIndex(0L),
                    new ConsensusTerm(0L),
                    List.of(new ConsensusLogEntry(
                            new LogIndex(1L),
                            new ConsensusTerm(before.term().value() + 10L),
                            new ConsensusCommand("forged", new byte[] {1}))),
                    new CommitIndex(0L));
            simulation.sendDiagnosticMessage(new ConsensusMessage.AppendRequest(ConsensusTestSupport.N3, ConsensusTestSupport.N2, forged));
            simulation.deliverAll();
            assertEquals(before.term(), simulation.node(ConsensusTestSupport.N2).term());
            assertEquals(before.lastLogIndex(), simulation.node(ConsensusTestSupport.N2).state().lastLogIndex());
        }
    }
}
