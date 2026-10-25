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

import java.util.Map;

/**
 * Result returned by an adapter probe for one TCK scenario.
 *
 * <p>The record is immutable and thread-safe. Results must contain stable
 * reason codes and redacted metadata. Required unsupported scenarios are not
 * treated as success by the TCK runner.</p>
 *
 * @param scenarioId scenario identifier
 * @param passed whether the scenario passed
 * @param unsupported whether the adapter explicitly reported unsupported behavior
 * @param reasonCode stable reason code
 * @param level result compatibility level
 * @param safeMetadata redacted result metadata
 */
public record AdapterTckResult(
        String scenarioId,
        boolean passed,
        boolean unsupported,
        String reasonCode,
        AdapterCompatibilityLevel level,
        Map<String, String> safeMetadata) {
    /**
     * Creates a TCK scenario result.
     */
    public AdapterTckResult {
        scenarioId = AdapterValues.requireId(scenarioId, "scenario id");
        reasonCode = AdapterValues.requireId(reasonCode, "reason code");
        safeMetadata = AdapterValues.safeMap(safeMetadata);
    }

    /**
     * Creates a passed scenario result.
     *
     * @param scenario scenario that passed
     * @return passed result
     */
    public static AdapterTckResult passed(final AdapterTckScenario scenario) {
        return new AdapterTckResult(
                scenario.id(),
                true,
                false,
                "PASSED",
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                Map.of());
    }

    /**
     * Creates an unsupported scenario result.
     *
     * @param scenario scenario that is unsupported
     * @param reasonCode stable reason code
     * @return unsupported result
     */
    public static AdapterTckResult unsupported(final AdapterTckScenario scenario, final String reasonCode) {
        return new AdapterTckResult(
                scenario.id(),
                false,
                true,
                reasonCode,
                AdapterCompatibilityLevel.UNSUPPORTED,
                Map.of());
    }

    /**
     * Creates a failed scenario result.
     *
     * @param scenario scenario that failed
     * @param reasonCode stable reason code
     * @return failed result
     */
    public static AdapterTckResult failed(final AdapterTckScenario scenario, final String reasonCode) {
        return new AdapterTckResult(
                scenario.id(),
                false,
                false,
                reasonCode,
                AdapterCompatibilityLevel.REJECTED,
                Map.of());
    }
}
