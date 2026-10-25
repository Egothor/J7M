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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Evidence-backed compatibility report for an adapter and semantic profile.
 *
 * <p>The record is immutable and thread-safe. It is the primary handoff between
 * the TCK and adapter maintainers. Counts, compatibility level, limitations,
 * security findings, failure-mode findings, redaction findings, timestamp, and
 * deterministic seed are explicit so unsupported semantics cannot be silently
 * upgraded to a passing result.</p>
 *
 * @param adapterId adapter provider identifier
 * @param adapterVersion adapter implementation version
 * @param tckVersion TCK version
 * @param semanticProfile semantic profile under test
 * @param scenariosRun scenario count
 * @param passed passed scenario count
 * @param failed failed scenario count
 * @param unsupported unsupported scenario count
 * @param compatibilityLevel overall compatibility level
 * @param limitations safe limitation descriptions
 * @param securityFindings safe security findings
 * @param failureModeFindings safe failure-mode findings
 * @param redactionFindings safe redaction findings
 * @param timestamp report timestamp
 * @param deterministicSeed deterministic seed used by the harness
 * @param safeMetadata redacted report metadata
 */
public record AdapterCompatibilityReport(
        AdapterProviderId adapterId,
        String adapterVersion,
        String tckVersion,
        AdapterSemanticProfile semanticProfile,
        int scenariosRun,
        int passed,
        int failed,
        int unsupported,
        AdapterCompatibilityLevel compatibilityLevel,
        List<String> limitations,
        List<String> securityFindings,
        List<String> failureModeFindings,
        List<String> redactionFindings,
        Instant timestamp,
        long deterministicSeed,
        Map<String, String> safeMetadata) {
    /**
     * Creates an adapter compatibility report.
     */
    public AdapterCompatibilityReport {
        adapterVersion = AdapterValues.requireText(adapterVersion, "adapter version");
        tckVersion = AdapterValues.requireText(tckVersion, "tck version");
        if (scenariosRun < 0 || passed < 0 || failed < 0 || unsupported < 0) {
            throw new IllegalArgumentException("scenario counts must be non-negative");
        }
        limitations = AdapterValues.safeTextList(limitations, "limitations");
        securityFindings = AdapterValues.safeTextList(securityFindings, "security findings");
        failureModeFindings = AdapterValues.safeTextList(failureModeFindings, "failure-mode findings");
        redactionFindings = AdapterValues.safeTextList(redactionFindings, "redaction findings");
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        safeMetadata = AdapterValues.safeMap(safeMetadata);
    }
}
