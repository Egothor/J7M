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

package org.j7m.middleware.adapter.spi;

import java.util.List;

/**
 * Deterministic probe implemented by adapters or TCK fixtures.
 *
 * <p>Implementations may block while exercising adapter operations, but they
 * must obey the TCK deadline supplied by the runner and map failures to safe
 * {@link AdapterTckResult} or {@link AdapterFailureModeResult} values.
 * Implementations must be thread-safe when the provider advertises concurrent
 * execution, must not leak sensitive payloads in result metadata, and must not
 * use vendor-specific public API types in method signatures.</p>
 */
public interface AdapterSemanticProbe {
    /**
     * Executes one semantic scenario.
     *
     * @param scenario scenario to execute
     * @return safe result for the scenario
     */
    AdapterTckResult runScenario(AdapterTckScenario scenario);

    /**
     * Executes runner-owned reference operations for a scenario.
     *
     * <p>The default implementation returns an empty outcome list so existing
     * probe implementations remain source-compatible. A probe that does not
     * override this method cannot produce an accepted pass for a scenario,
     * because the adapter TCK runner requires
     * independently verified observable reference outcomes in addition to the
     * scenario result.</p>
     *
     * @param scenario scenario under verification
     * @param operations deterministic operations issued by the TCK runner
     * @return safe observable outcomes in the same order as {@code operations}
     */
    default List<AdapterReferenceOutcome> runReferenceOperations(
            final AdapterTckScenario scenario,
            final List<AdapterReferenceOperation> operations) {
        return List.of();
    }

    /**
     * Executes runner-owned reference operations and records their observable effects.
     *
     * <p>The default implementation delegates to the legacy two-argument method
     * and therefore records no runner-owned effects. It is provided only for
     * source compatibility. A probe must override this method and call {@link
     * AdapterReferenceSession#recordEffect(AdapterReferenceOperation, String)}
     * for each operation to produce an accepted scenario pass.</p>
     *
     * @param scenario scenario under verification
     * @param operations deterministic operations issued by the TCK runner
     * @param session runner-owned effect recorder for this scenario
     * @return safe observable outcomes in the same order as {@code operations}
     */
    default List<AdapterReferenceOutcome> runReferenceOperations(
            final AdapterTckScenario scenario,
            final List<AdapterReferenceOperation> operations,
            final AdapterReferenceSession session) {
        return runReferenceOperations(scenario, operations);
    }

    /**
     * Exercises one failure mode.
     *
     * @param failureMode failure mode to exercise
     * @return safe failure-mode result
     */
    AdapterFailureModeResult runFailureMode(AdapterFailureMode failureMode);

    /**
     * Reports whether public adapter output leaks the supplied sentinel.
     *
     * @param sentinel sensitive sentinel value that must not appear in public output
     * @return {@code true} if the probe detected a redaction failure
     */
    boolean leaksSensitiveData(String sentinel);
}
