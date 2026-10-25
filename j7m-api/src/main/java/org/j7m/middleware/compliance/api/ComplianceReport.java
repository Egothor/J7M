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

package org.j7m.middleware.compliance.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable compliance evidence report.
 *
 * @param generatedAt report timestamp
 * @param profile profile used for mapping
 * @param scope assessed scope
 * @param assessments control assessments
 * @param evidence raw evidence records
 * @param summary summary counters
 * @param limitations known limitations
 */
public record ComplianceReport(
        Instant generatedAt,
        ComplianceProfile profile,
        ComplianceScope scope,
        List<ControlAssessment> assessments,
        List<ControlEvidence> evidence,
        Map<ControlStatus, Long> summary,
        List<String> limitations) {
    /**
     * Creates immutable report values.
     */
    public ComplianceReport {
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        profile = profile == null ? new ComplianceProfile("", "", List.of(), List.of(), List.of(), "") : profile;
        scope = scope == null ? ComplianceScope.local() : scope;
        assessments = assessments == null ? List.of() : List.copyOf(assessments);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        summary = summary == null ? Map.of() : Map.copyOf(summary);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
