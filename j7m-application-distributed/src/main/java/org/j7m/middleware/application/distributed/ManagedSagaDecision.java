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

package org.j7m.middleware.application.distributed;

import java.util.Objects;

/**
 * Independently established reconciliation proposal. This data never grants administrative rights.
 * @param outcome unresolved, established success or established no-effect failure
 * @param result established data result, encoded using the exact persisted step's declared schema
 */
public record ManagedSagaDecision(Outcome outcome, Object result) {
    /**
     * Distinguishes evidence from uncertainty; exceptions alone are not no-effect evidence.
     */
    public enum Outcome {
        /**
         * Leave UNKNOWN and do not automatically replay the business handler.
         */ UNRESOLVED,
        /**
         * Independently established successful external effect.
         */ SUCCEEDED,
        /**
         * Independently established absence of the external effect.
         */ FAILED_NO_EFFECT
    }
    /**
     * Requires an explicit outcome.
     * @throws NullPointerException for a missing outcome
     */
    public ManagedSagaDecision { Objects.requireNonNull(outcome); }
    /**
     * Omits application data from diagnostics.
     * @return bounded outcome label
     */
    @Override public String toString() { return "ManagedSagaDecision[" + outcome + "]"; }
}
