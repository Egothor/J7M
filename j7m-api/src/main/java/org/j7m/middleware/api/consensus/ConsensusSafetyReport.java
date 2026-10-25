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
 * Deterministic consensus safety invariant report.
 *
 * <p>The report is generated from actual node state, durable logs, commit
 * indexes, applied indexes, and deterministic simulation evidence. It is a
 * redacted value object for tests, demos, and operator evidence; it must never
 * include raw command payload bytes or private endpoint material. A required
 * invariant that was not exercised must be represented as a failure or safe
 * violation text by the implementation rather than as a silent pass.</p>
 *
 * @param electionSafety whether at most one leader was observed per term
 * @param leaderAppendOnly whether leaders preserved append-only local logs
 * @param logMatching whether logs match through equal index/term pairs
 * @param leaderCompleteness whether future leaders contain committed entries
 * @param stateMachineSafety whether applied entries agree at every index
 * @param committedEntryDurability whether committed entries survived recovery
 * @param staleLeaderRejection whether stale-leader rejection evidence was observed
 * @param evidence safe invariant evidence records, such as scenario and node ids
 * @param violations safe violation messages with no payload or credential data
 */
public record ConsensusSafetyReport(
        boolean electionSafety,
        boolean leaderAppendOnly,
        boolean logMatching,
        boolean leaderCompleteness,
        boolean stateMachineSafety,
        boolean committedEntryDurability,
        boolean staleLeaderRejection,
        List<String> evidence,
        List<String> violations) implements Serializable {
    /**
     * Creates a safety report.
     *
     * <p>Evidence and violation lists are defensively copied. The record is
     * immutable and thread-safe after construction. Implementations should use
     * stable reason text so failed acceptance evidence is reproducible.</p>
     */
    public ConsensusSafetyReport {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /**
     * Returns whether all invariants passed.
     *
     * @return {@code true} when every invariant is true and no violation exists
     */
    public boolean passed() {
        return this.electionSafety
                && this.leaderAppendOnly
                && this.logMatching
                && this.leaderCompleteness
                && this.stateMachineSafety
                && this.committedEntryDurability
                && this.staleLeaderRejection
                && this.violations.isEmpty();
    }
}
