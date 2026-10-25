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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.RegistryStatus;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.attestation.ReleaseAttestationMetadata;
import org.j7m.middleware.compliance.api.ComplianceReport;
import org.j7m.middleware.compliance.api.ComplianceScope;
import org.j7m.middleware.compliance.api.ControlEvidence;
import org.j7m.middleware.compliance.api.EvidenceExportFormat;
import org.j7m.middleware.compliance.api.SecureConfigurationProfile;
import org.j7m.middleware.compliance.api.SecureConfigurationSeverity;
import org.j7m.middleware.compliance.api.SecureConfigurationValidationResult;
import org.j7m.middleware.compliance.defaults.BuiltInComplianceProfiles;
import org.j7m.middleware.compliance.defaults.DefaultComplianceReportService;
import org.j7m.middleware.compliance.defaults.DefaultEvidenceExporter;
import org.j7m.middleware.compliance.defaults.NoopEnterpriseIntegrations;
import org.j7m.middleware.compliance.defaults.SecureConfigurationValidator;
import org.j7m.middleware.evidence.RuntimeEvidenceCollector;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.defaults.TamperEvidentAuditRecord;
import org.j7m.middleware.security.defaults.TamperEvidentAuditSink;
import org.junit.jupiter.api.Test;

/**
 * Phase 5.1 enterprise handoff and certification-readiness audit tests.
 */
public final class Phase51EnterpriseReadinessAuditTest {
    @Test
    public void secureConfigurationValidatorRejectsUnsafeProductionProfiles() {
        SecureConfigurationValidator validator = new SecureConfigurationValidator();
        assertTrue(validator.validate(SecureConfigurationProfile.productionBaseline()).valid());

        SecureConfigurationProfile unsafe = new SecureConfigurationProfile(true, true, true, false, true, false,
                true, true, true, false, false, true, false, true, true, true, true, true);
        SecureConfigurationValidationResult result = validator.validate(unsafe);
        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(item -> item.controlId().value().equals("secure.insecure-grpc")));
        assertTrue(result.findings().stream().anyMatch(item -> item.controlId().value().equals("secure.mtls")));
        assertTrue(result.findings().stream().anyMatch(item -> item.controlId().value().equals("secure.registry-trust")));
        assertTrue(result.findings().stream().anyMatch(item -> item.controlId().value().equals("secure.audit")));
        assertTrue(result.findings().stream().anyMatch(item -> item.severity() == SecureConfigurationSeverity.WARNING
                && item.controlId().value().equals("secure.external-pdp")));
    }

    @Test
    public void enterpriseIntegrationFixturesAreExplicitNoops() {
        ComplianceReport report = report();
        assertTrue(NoopEnterpriseIntegrations.grcRepository().status().available());
        assertTrue(NoopEnterpriseIntegrations.grcRepository().store(report).startsWith("noop-grc:"));
        assertEquals(report.evidence().size(), NoopEnterpriseIntegrations.siemExporter().export(report.evidence()));
        assertTrue(NoopEnterpriseIntegrations.soarPublisher().publish("incident", Map.of("severity", "low")).startsWith("noop-soar:"));
        assertEquals(1L, NoopEnterpriseIntegrations.cmdbExporter().export(List.of(Map.of("service", "j7m"))));
        assertEquals(1L, NoopEnterpriseIntegrations.telemetryExporter().export(Map.of("events", 1L)));
        assertEquals("not-configured", NoopEnterpriseIntegrations.vulnerabilityMetadataProvider().metadata().get("sbom"));
    }

    @Test
    public void evidenceExportsAreStableParseableAndDoNotClaimCertificationOrLeakSecrets() {
        ComplianceReport report = report();
        DefaultEvidenceExporter exporter = new DefaultEvidenceExporter();
        String json = exporter.export(report, EvidenceExportFormat.JSON);
        String markdown = exporter.export(report, EvidenceExportFormat.MARKDOWN);
        String csv = exporter.export(report, EvidenceExportFormat.CSV);

        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertEquals(count(json, '{'), count(json, '}'));
        assertTrue(json.contains("\"disclaimer\""));
        assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains("certified"));
        assertFalse(json.contains("secret-value"));
        assertTrue(markdown.contains("Limitation"));
        assertTrue(markdown.contains("not formal certification") || markdown.contains("not certification"));
        assertTrue(csv.startsWith("timestamp,runtimeId,environment,controlId,sourceModule,result,limitation"));
    }

    @Test
    public void missingControlEvidenceIsGapAndAuditExportVerifiesAfterRoundTrip() {
        ComplianceReport report = new DefaultComplianceReportService().createReport(
                BuiltInComplianceProfiles.profileById("regulated-baseline"), ComplianceScope.local(), List.of());
        assertTrue(report.assessments().stream().allMatch(item -> item.status().name().equals("GAP")));

        TamperEvidentAuditSink sink = new TamperEvidentAuditSink(8, 2);
        sink.record(event("lookup"));
        sink.record(event("invoke"));
        sink.record(event("admin"));
        List<TamperEvidentAuditRecord> exported = new ArrayList<>(sink.chain());
        assertTrue(TamperEvidentAuditSink.verify(exported).valid());
        exported.remove(1);
        assertFalse(TamperEvidentAuditSink.verify(exported).valid());
    }

    private static ComplianceReport report() {
        RuntimeEvidenceCollector collector = new RuntimeEvidenceCollector(status(), List.of("j7m-kernel"),
                ReleaseAttestationMetadata.unavailable(), null, null, null);
        List<ControlEvidence> evidence = collector.collect(ComplianceScope.local());
        return new DefaultComplianceReportService().createReport(
                BuiltInComplianceProfiles.profileById("regulated-baseline"), ComplianceScope.local(), evidence);
    }

    private static RuntimeStatus status() {
        return new RuntimeStatus(new RuntimeIdentity("runtime-a", "default", Map.of()), List.of(), List.of(), List.of(),
                new RegistryStatus(0, 0), 0L, 0L, List.of(), Map.of(
                "mode", "PRODUCTION",
                "mtlsEnabled", "true",
                "authorizationPolicy", "allow",
                "auditEvents", "1",
                "auditSink", "tamper-evident",
                "resourcePolicy", "strict",
                "maxInboundMessageBytes", "1048576",
                "maxOutboundMessageBytes", "1048576"));
    }

    private static AuditEvent event(final String action) {
        return new AuditEvent(Instant.now(), "runtime-a", "alice", action, "resource", "allowed", "policy",
                "corr", "inv", "local", "safe", "grpc", "default", Map.of("secret", "<redacted>"));
    }

    private static int count(final String value, final char expected) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == expected) {
                count++;
            }
        }
        return count;
    }
}
