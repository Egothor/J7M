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

import java.util.Map;
import org.j7m.middleware.compliance.api.ComplianceException;
import org.j7m.middleware.compliance.api.ComplianceReport;
import org.j7m.middleware.compliance.api.ControlAssessment;
import org.j7m.middleware.compliance.api.ControlEvidence;
import org.j7m.middleware.compliance.api.EvidenceExportFormat;
import org.j7m.middleware.compliance.api.EvidenceExporter;

/**
 * Exports compliance reports as JSON, Markdown, or CSV without external
 * dependencies.
 */
public final class DefaultEvidenceExporter implements EvidenceExporter {
    /**
     * Creates an evidence exporter.
     */
    public DefaultEvidenceExporter() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String export(final ComplianceReport report, final EvidenceExportFormat format) {
        if (report == null) {
            throw new ComplianceException("report must not be null");
        }
        EvidenceExportFormat safeFormat = format == null ? EvidenceExportFormat.JSON : format;
        return switch (safeFormat) {
            case JSON -> json(report);
            case MARKDOWN -> markdown(report);
            case CSV -> csv(report);
        };
    }

    private static String json(final ComplianceReport report) {
        StringBuilder builder = new StringBuilder(1024);
        builder.append('{');
        appendField(builder, "generatedAt", report.generatedAt().toString()).append(',');
        appendField(builder, "profile", report.profile().id()).append(',');
        appendField(builder, "disclaimer", report.profile().certificationDisclaimer()).append(',');
        builder.append("\"summary\":{");
        boolean first = true;
        for (Map.Entry<?, Long> entry : report.summary().entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            appendField(builder, String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        builder.append("},\"evidence\":[");
        first = true;
        for (ControlEvidence evidence : report.evidence()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('{');
            appendField(builder, "controlId", evidence.controlId().value()).append(',');
            appendField(builder, "runtimeId", evidence.runtimeId()).append(',');
            appendField(builder, "sourceModule", evidence.sourceModule()).append(',');
            appendField(builder, "result", evidence.result()).append(',');
            appendField(builder, "limitation", evidence.limitation());
            builder.append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private static String markdown(final ComplianceReport report) {
        StringBuilder builder = new StringBuilder(1024);
        builder.append("# Compliance Report\n\n");
        builder.append("Profile: `").append(escapeText(report.profile().id())).append("`\n\n");
        builder.append(report.profile().certificationDisclaimer()).append("\n\n");
        builder.append("| Control | Status | Limitation |\n| --- | --- | --- |\n");
        for (ControlAssessment assessment : report.assessments()) {
            builder.append("| `").append(escapeText(assessment.controlId().value())).append("` | ")
                    .append(assessment.status()).append(" | ")
                    .append(escapeText(assessment.limitation())).append(" |\n");
        }
        return builder.toString();
    }

    private static String csv(final ComplianceReport report) {
        StringBuilder builder = new StringBuilder(1024);
        builder.append("timestamp,runtimeId,environment,controlId,sourceModule,result,limitation\n");
        for (ControlEvidence evidence : report.evidence()) {
            builder.append(csv(evidence.timestamp().toString())).append(',')
                    .append(csv(evidence.runtimeId())).append(',')
                    .append(csv(evidence.environment())).append(',')
                    .append(csv(evidence.controlId().value())).append(',')
                    .append(csv(evidence.sourceModule())).append(',')
                    .append(csv(evidence.result())).append(',')
                    .append(csv(evidence.limitation())).append('\n');
        }
        return builder.toString();
    }

    private static StringBuilder appendField(final StringBuilder builder, final String key, final String value) {
        builder.append('"').append(escapeJson(key)).append("\":\"").append(escapeJson(value)).append('"');
        return builder;
    }

    private static String csv(final String value) {
        return '"' + escapeText(value).replace("\"", "\"\"") + '"';
    }

    private static String escapeJson(final String value) {
        return escapeText(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeText(final String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
