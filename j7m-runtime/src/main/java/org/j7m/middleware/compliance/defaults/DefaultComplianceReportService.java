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

package org.j7m.middleware.compliance.defaults;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.compliance.api.ComplianceProfile;
import org.j7m.middleware.compliance.api.ComplianceReport;
import org.j7m.middleware.compliance.api.ComplianceScope;
import org.j7m.middleware.compliance.api.ControlAssessment;
import org.j7m.middleware.compliance.api.ControlEvidence;
import org.j7m.middleware.compliance.api.ControlRequirement;
import org.j7m.middleware.compliance.api.ControlStatus;

/**
 * Builds conservative compliance reports from collected evidence.
 *
 * <p>The service is stateless and thread-safe. It treats missing evidence as a
 * gap or integration boundary rather than silently passing controls.</p>
 */
public final class DefaultComplianceReportService {
    /**
     * Creates a report service.
     */
    public DefaultComplianceReportService() {
    }

    /**
     * Creates a report for one profile and evidence set.
     *
     * @param profile profile
     * @param scope report scope
     * @param evidence collected evidence
     * @return immutable report
     */
    public ComplianceReport createReport(
            final ComplianceProfile profile,
            final ComplianceScope scope,
            final List<ControlEvidence> evidence) {
        List<ControlEvidence> safeEvidence = evidence == null ? List.of() : List.copyOf(evidence);
        List<ControlAssessment> assessments = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        for (ControlRequirement requirement : profile.requirements()) {
            List<ControlEvidence> matching = matchingEvidence(requirement, safeEvidence);
            ControlStatus status = matching.isEmpty() ? ControlStatus.GAP : statusFor(matching);
            String limitation = matching.isEmpty() ? "No collected evidence for " + requirement.controlId().value() : limitationFor(matching);
            if (!limitation.isBlank()) {
                limitations.add(requirement.controlId().value() + ": " + limitation);
            }
            assessments.add(new ControlAssessment(requirement.controlId(), status, matching, limitation));
        }
        return new ComplianceReport(Instant.now(), profile, scope, assessments, safeEvidence, summarize(assessments), limitations);
    }

    private static List<ControlEvidence> matchingEvidence(
            final ControlRequirement requirement,
            final List<ControlEvidence> evidence) {
        List<ControlEvidence> matching = new ArrayList<>();
        String suffix = suffix(requirement.controlId().value());
        for (ControlEvidence item : evidence) {
            if (item.controlId().value().equals(requirement.controlId().value())
                    || item.controlId().value().endsWith("." + suffix)
                    || item.controlId().value().equals(suffix)) {
                matching.add(item);
            }
        }
        return List.copyOf(matching);
    }

    private static String suffix(final String value) {
        int index = value.lastIndexOf('.');
        return index < 0 ? value : value.substring(index + 1);
    }

    private static ControlStatus statusFor(final List<ControlEvidence> evidence) {
        boolean hasGap = false;
        boolean hasPartial = false;
        for (ControlEvidence item : evidence) {
            String status = item.attributes().getOrDefault("status", "implemented");
            if ("gap".equals(status)) {
                hasGap = true;
            } else if ("partial".equals(status) || !item.limitation().isBlank()) {
                hasPartial = true;
            }
        }
        if (hasGap) {
            return ControlStatus.GAP;
        }
        if (hasPartial) {
            return ControlStatus.PARTIAL;
        }
        return ControlStatus.IMPLEMENTED;
    }

    private static String limitationFor(final List<ControlEvidence> evidence) {
        for (ControlEvidence item : evidence) {
            if (!item.limitation().isBlank()) {
                return item.limitation();
            }
        }
        return "";
    }

    private static Map<ControlStatus, Long> summarize(final List<ControlAssessment> assessments) {
        EnumMap<ControlStatus, Long> values = new EnumMap<>(ControlStatus.class);
        for (ControlAssessment assessment : assessments) {
            values.put(assessment.status(), values.getOrDefault(assessment.status(), 0L) + 1L);
        }
        return Map.copyOf(values);
    }
}
