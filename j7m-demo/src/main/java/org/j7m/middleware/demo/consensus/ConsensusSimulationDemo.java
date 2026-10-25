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

package org.j7m.middleware.demo.consensus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusCommandResult;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusSimulationReport;
import org.j7m.middleware.consensus.ConsensusSimulation;

/**
 * Phase 6.5 deterministic consensus simulation demo.
 */
public final class ConsensusSimulationDemo {
    // J7M teaching: this demo block is self-contained so acceptance smoke runs do not require external services.
    private ConsensusSimulationDemo() {
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     * @throws Exception on demo failure
     */
    public static void main(final String[] args) throws Exception {
        Path directory = Files.createTempDirectory("j7m-consensus-demo-");
        ConsensusNodeId n1 = new ConsensusNodeId("n1");
        ConsensusNodeId n2 = new ConsensusNodeId("n2");
        ConsensusNodeId n3 = new ConsensusNodeId("n3");
        try (ConsensusSimulation simulation = ConsensusSimulation.threeNode(directory, 6500L)) {
            // J7M teaching: the flow starts with leader election and a committed command
            // so the later adversarial steps can be compared against known-safe progress.
            simulation.elect(n1);
            ConsensusCommandResult first = simulation.submit(new ConsensusCommand("demo-1", "metadata-1".getBytes(StandardCharsets.UTF_8)));

            // J7M teaching: crash, re-election, and partition create an adversarial
            // simulation rather than a happy path; the majority side must keep progress
            // while the isolated stale leader is unable to commit.
            simulation.crash(n1);
            simulation.elect(n2);
            simulation.partition(Set.of(n2, n3), Set.of(n1));
            ConsensusCommandResult second = simulation.submit(new ConsensusCommand("demo-2", "metadata-2".getBytes(StandardCharsets.UTF_8)));
            ConsensusCommandResult stale = simulation.submitTo(n1, new ConsensusCommand("minority", "metadata-3".getBytes(StandardCharsets.UTF_8)));
            simulation.heal();

            // J7M teaching: the report is the evidence developers should inspect:
            // committed majority commands, rejected stale-leader work, and a safety invariant
            // result without claiming generic exactly-once or ACID behavior.
            ConsensusSimulationReport report = simulation.simulationReport();
            if (!first.committed() || !second.committed() || stale.committed() || !report.safetyReport().passed()) {
                throw new IllegalStateException("consensus simulation demo failed");
            }
            System.out.println("phase6.5 consensus directory: " + directory.getFileName());
            System.out.println("phase6.5 leader=" + simulation.currentLeaderId().value()
                    + ", firstCommitted=" + first.committed()
                    + ", secondCommitted=" + second.committed()
                    + ", minorityCommitted=" + stale.committed());
            System.out.println("phase6.5 messages sent=" + report.messagesSent()
                    + ", dropped=" + report.messagesDropped()
                    + ", crashes=" + report.crashes()
                    + ", restarts=" + report.restarts());
            System.out.println("phase6.5 safety=" + report.safetyReport().passed()
                    + ", trace=" + report.trace().size());
            System.out.println("phase6.5 non-goals: no repository replication, no saga, no generic exactly-once");
        }
    }
}
