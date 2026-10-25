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

package org.j7m.middleware.admin;

import org.j7m.middleware.compliance.api.ComplianceReport;
import org.j7m.middleware.compliance.api.ControlStatus;

/**
 * Read-only formatter for compliance posture reports.
 *
 * <p>The formatter is stateless and thread-safe. It emits safe summary text
 * only and never includes secrets or key material.</p>
 */
public final class ComplianceAdmin {
    /**
     * Creates a compliance formatter.
     */
    public ComplianceAdmin() {
    }

    /**
     * Formats a compact compliance posture summary.
     *
     * @param report report to summarize
     * @return safe text summary
     */
    public String format(final ComplianceReport report) {
        if (report == null) {
            return "compliance=unavailable";
        }
        return "complianceProfile=" + report.profile().id()
                + ", implemented=" + report.summary().getOrDefault(ControlStatus.IMPLEMENTED, 0L)
                + ", partial=" + report.summary().getOrDefault(ControlStatus.PARTIAL, 0L)
                + ", gaps=" + report.summary().getOrDefault(ControlStatus.GAP, 0L)
                + ", limitations=" + report.limitations().size()
                + ", certification=false";
    }
}
