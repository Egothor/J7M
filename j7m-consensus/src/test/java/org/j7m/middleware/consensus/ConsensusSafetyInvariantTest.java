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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.j7m.middleware.api.consensus.ConsensusRole;
import org.j7m.middleware.api.consensus.ConsensusSafetyReport;
import org.j7m.middleware.api.consensus.ConsensusTerm;
import org.j7m.middleware.api.consensus.LogIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class ConsensusSafetyInvariantTest {
    @TempDir
    Path directory;

    @Test
    public void safetyInvariantReportCoversRequiredProperties() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            assertTrue(simulation.submit(ConsensusTestSupport.command("safe-1")).committed());
            simulation.partition(Set.of(ConsensusTestSupport.N2, ConsensusTestSupport.N3), Set.of(ConsensusTestSupport.N1));
            simulation.elect(ConsensusTestSupport.N2);
            simulation.submitTo(ConsensusTestSupport.N1, ConsensusTestSupport.command("stale-fails"));
            simulation.heal();
            simulation.elect(ConsensusTestSupport.N2);
            ConsensusSafetyReport report = simulation.safetyReport();
            assertTrue(report.electionSafety());
            assertTrue(report.leaderAppendOnly());
            assertTrue(report.logMatching());
            assertTrue(report.leaderCompleteness());
            assertTrue(report.stateMachineSafety());
            assertTrue(report.committedEntryDurability());
            assertTrue(report.staleLeaderRejection());
            assertTrue(report.evidence().stream().anyMatch(value -> value.startsWith("MINORITY_COMMIT_REJECTED")));
            assertTrue(report.passed(), report.violations().toString());
        }
    }

    @Test
    public void staleLeaderInvariantFailsWithoutEvidence() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            assertTrue(simulation.submit(ConsensusTestSupport.command("ordinary")).committed());
            ConsensusSafetyReport report = simulation.safetyReport();
            assertFalse(report.staleLeaderRejection());
            assertFalse(report.passed());
            assertTrue(report.violations().contains("stale leader rejection evidence was not observed"));
        }
    }

    @Test
    public void safetyReportUsesIntermediateDeliveredMessageHistory() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            ConsensusSafetyReport report = new SafetyInvariantChecker().check(
                    simulation.nodes(),
                    List.of("STALE_LEADER_REJECTED:diagnostic"),
                    List.of(
                            new ConsensusHistoryEvent(
                                    ConsensusHistoryEvent.Type.LEADER_ELECTED,
                                    ConsensusTestSupport.N1,
                                    new ConsensusTerm(1L),
                                    new LogIndex(0L),
                                    ""),
                            new ConsensusHistoryEvent(
                                    ConsensusHistoryEvent.Type.LEADER_ELECTED,
                                    ConsensusTestSupport.N2,
                                    new ConsensusTerm(1L),
                                    new LogIndex(0L),
                                    "")));
            assertFalse(report.electionSafety());
            assertTrue(report.violations().contains("multiple leaders in term 1"));
        }
    }

    @Test
    public void historicalCommittedEntryMissingFromTerminalStateFailsSafety() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);

            ConsensusSafetyReport report = new SafetyInvariantChecker().check(
                    simulation.nodes(),
                    List.of("STALE_LEADER_REJECTED:diagnostic"),
                    List.of(new ConsensusHistoryEvent(
                            ConsensusHistoryEvent.Type.ENTRY_COMMITTED,
                            ConsensusTestSupport.N1,
                            new ConsensusTerm(1L),
                            new LogIndex(1L),
                            "historical")));

            assertEquals(ConsensusRole.LEADER, simulation.node(ConsensusTestSupport.N1).role());
            assertFalse(report.leaderCompleteness());
            assertFalse(report.committedEntryDurability());
            assertTrue(report.violations().contains("leader missing committed entry 1"));
            assertTrue(report.violations().contains("historical committed entry absent at 1"));
        }
    }

    @Test
    public void historicalCommittedEntryContradictedByTerminalStateFailsSafety() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            assertTrue(simulation.submit(ConsensusTestSupport.command("terminal")).committed());

            ConsensusSafetyReport report = new SafetyInvariantChecker().check(
                    simulation.nodes(),
                    List.of("STALE_LEADER_REJECTED:diagnostic"),
                    List.of(new ConsensusHistoryEvent(
                            ConsensusHistoryEvent.Type.ENTRY_COMMITTED,
                            ConsensusTestSupport.N1,
                            new ConsensusTerm(1L),
                            new LogIndex(1L),
                            "historical")));

            assertFalse(report.leaderCompleteness());
            assertFalse(report.committedEntryDurability());
            assertTrue(report.violations().contains("terminal committed entry conflicts with baseline at 1"));
            assertTrue(report.violations().contains("leader completeness failed at 1"));
            assertTrue(report.violations().contains("committed entry durability failed at 1"));
        }
    }
}
