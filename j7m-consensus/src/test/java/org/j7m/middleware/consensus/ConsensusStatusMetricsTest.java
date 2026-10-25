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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusLogEntryStatus;
import org.j7m.middleware.api.consensus.ConsensusSafetyReport;
import org.j7m.middleware.api.consensus.ConsensusStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class ConsensusStatusMetricsTest {
    @TempDir
    Path directory;

    @Test
    public void statusAndMetricsAreVisible() {
        try (ConsensusSimulation simulation = ConsensusTestSupport.simulation(this.directory)) {
            simulation.elect(ConsensusTestSupport.N1);
            byte[] secretPayload = "secret-consensus-payload".getBytes(StandardCharsets.UTF_8);
            assertTrue(simulation.submit(new ConsensusCommand("metrics", secretPayload)).committed());
            ConsensusStatus status = simulation.status();
            assertEquals(3, status.states().size());
            assertEquals(ConsensusTestSupport.N1, status.leaderId());
            assertTrue(status.metrics().elections() >= 1L);
            assertTrue(status.metrics().leadersElected() >= 1L);
            assertTrue(status.metrics().appendRequests() >= 1L);
            assertTrue(status.metrics().commandsCommitted() >= 1L);
            assertTrue(status.states().stream().allMatch(state -> state.commitIndex().value() >= 1L));
            ConsensusLogEntryStatus entryStatus = status.states().getFirst().logEntries().getFirst();
            assertEquals("metrics", entryStatus.commandId());
            assertEquals(secretPayload.length, entryStatus.payloadBytes());
            assertFalse(status.toString().contains("secret-consensus-payload"));
            ConsensusSafetyReport report = simulation.safetyReport();
            assertFalse(report.toString().contains("secret-consensus-payload"));
        }
    }
}
