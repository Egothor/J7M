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

/**
 * Runner-owned recorder for adapter TCK reference-operation effects.
 *
 * <p>A session is created and owned by the TCK runner for one scenario
 * verification. Probe implementations receive the session only while executing
 * {@link AdapterSemanticProbe#runReferenceOperations(AdapterTckScenario, java.util.List, AdapterReferenceSession)}.
 * A probe must record each observable operation effect through this session in
 * the same order as the supplied operations. Returning a fabricated
 * {@link AdapterReferenceOutcome} without recording the corresponding effect is
 * rejected by the runner.</p>
 *
 * <p>Implementations supplied by the runner are thread-safe for the duration of
 * a scenario, bounded by the runner execution policy, and retain only redacted,
 * deterministic evidence. The session does not expose vendor SDK types,
 * external service handles, credentials, raw payloads, or endpoint details.</p>
 */
public interface AdapterReferenceSession {
    /**
     * Records an observable effect for a reference operation.
     *
     * @param operation reference operation that produced the effect
     * @param observedValue safe deterministic value observed after executing the operation
     * @return safe reference outcome to return from the probe
     * @throws IllegalArgumentException if the operation or observed value is invalid
     */
    AdapterReferenceOutcome recordEffect(AdapterReferenceOperation operation, String observedValue);
}
