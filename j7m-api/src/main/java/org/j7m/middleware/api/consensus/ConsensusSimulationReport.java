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
 * Deterministic simulation evidence.
 *
 * @param seed deterministic seed
 * @param messagesSent sent messages
 * @param messagesDropped dropped messages
 * @param messagesDuplicated duplicated messages
 * @param messagesReordered reordered messages
 * @param crashes crashes
 * @param restarts restarts
 * @param trace safe simulation trace
 * @param safetyReport safety invariant report
 */
public record ConsensusSimulationReport(
        long seed,
        long messagesSent,
        long messagesDropped,
        long messagesDuplicated,
        long messagesReordered,
        long crashes,
        long restarts,
        List<String> trace,
        ConsensusSafetyReport safetyReport) implements Serializable {
    /**
     * Creates a simulation report.
     */
    public ConsensusSimulationReport {
        ConsensusValues.nonNegative("messages sent", messagesSent);
        ConsensusValues.nonNegative("messages dropped", messagesDropped);
        ConsensusValues.nonNegative("messages duplicated", messagesDuplicated);
        ConsensusValues.nonNegative("messages reordered", messagesReordered);
        ConsensusValues.nonNegative("crashes", crashes);
        ConsensusValues.nonNegative("restarts", restarts);
        trace = trace == null ? List.of() : List.copyOf(trace);
        if (safetyReport == null) {
            throw new IllegalArgumentException("safety report is required");
        }
    }
}
