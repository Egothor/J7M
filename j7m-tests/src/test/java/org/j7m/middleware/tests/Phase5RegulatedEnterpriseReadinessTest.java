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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.admin.ComplianceAdmin;
import org.j7m.middleware.api.RegistryStatus;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.attestation.DeterministicReleaseAttestationVerifier;
import org.j7m.middleware.attestation.ReleaseAttestationMetadata;
import org.j7m.middleware.attestation.ReleaseVerificationResult;
import org.j7m.middleware.compliance.api.ComplianceProfile;
import org.j7m.middleware.compliance.api.ComplianceReport;
import org.j7m.middleware.compliance.api.ComplianceScope;
import org.j7m.middleware.compliance.api.ControlEvidence;
import org.j7m.middleware.compliance.api.ControlStatus;
import org.j7m.middleware.compliance.api.EvidenceExportFormat;
import org.j7m.middleware.compliance.defaults.BuiltInComplianceProfiles;
import org.j7m.middleware.compliance.defaults.DefaultComplianceReportService;
import org.j7m.middleware.compliance.defaults.DefaultEvidenceExporter;
import org.j7m.middleware.crypto.policy.CryptoPolicy;
import org.j7m.middleware.crypto.policy.CryptoPolicyEvaluator;
import org.j7m.middleware.crypto.policy.CryptoPostureReport;
import org.j7m.middleware.crypto.policy.CryptoProviderPolicy;
import org.j7m.middleware.crypto.policy.FipsModePolicy;
import org.j7m.middleware.crypto.policy.KeyReference;
import org.j7m.middleware.crypto.policy.KeyRotationPolicy;
import org.j7m.middleware.crypto.policy.TestEnvelopeEncryptionService;
import org.j7m.middleware.crypto.policy.TestKeyManagementProvider;
import org.j7m.middleware.enterprise.iam.BreakGlassPolicyHook;
import org.j7m.middleware.enterprise.iam.DeterministicPdpClient;
import org.j7m.middleware.enterprise.iam.ExternalPdpAuthorizationPolicy;
import org.j7m.middleware.enterprise.iam.FourEyesApprovalPolicy;
import org.j7m.middleware.enterprise.iam.OidcJwtPrincipalResolver;
import org.j7m.middleware.evidence.OperationalResilienceEvidence;
import org.j7m.middleware.evidence.RuntimeEvidenceCollector;
import org.j7m.middleware.evidence.ValidationSummary;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.TamperEvidentAuditRecord;
import org.j7m.middleware.security.defaults.TamperEvidentAuditSink;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 regulated enterprise readiness tests.
 */
public final class Phase5RegulatedEnterpriseReadinessTest {
    @Test
    public void complianceProfilesEvidenceAndExportsAreConservative() {
        ComplianceProfile profile = BuiltInComplianceProfiles.profileById("dora");
        RuntimeStatus status = status();
        ReleaseAttestationMetadata release = new ReleaseAttestationMetadata("sbom.spdx.json", "SPDX", "sha256:test",
                "provenance", "abc123", Instant.now(), "test-signer", "passed", "vex.json",
                Map.of("attestationSignature", "deterministic-trusted"));
        CryptoPostureReport crypto = new CryptoPolicyEvaluator().report(new CryptoPolicy(
                new CryptoProviderPolicy(Set.of("SHA-256"), Set.of(), true), new FipsModePolicy(false, ""),
                new KeyRotationPolicy(Duration.ofDays(90L), true)), new TestKeyManagementProvider());
        OperationalResilienceEvidence resilience = new OperationalResilienceEvidence(Duration.ofMinutes(15L), Duration.ofMinutes(5L),
                "ha-enabled", "discovery-enabled", "external", "failover drill passed", "overload enabled",
                "healthy", "runbook.md", Instant.now(), Map.of());
        RuntimeEvidenceCollector collector = new RuntimeEvidenceCollector(status, List.of("j7m-kernel", "j7m-runtime"),
                release, crypto, resilience, new ValidationSummary(Instant.now(), 71L, 69L, 2L, 0L, Map.of()));

        List<ControlEvidence> evidence = collector.collect(new ComplianceScope("test", List.of("runtime-a"), List.of(), ""));
        ComplianceReport report = new DefaultComplianceReportService().createReport(profile, ComplianceScope.local(), evidence);
        String json = new DefaultEvidenceExporter().export(report, EvidenceExportFormat.JSON);
        String markdown = new DefaultEvidenceExporter().export(report, EvidenceExportFormat.MARKDOWN);
        String csv = new DefaultEvidenceExporter().export(report, EvidenceExportFormat.CSV);

        assertFalse(profile.certificationDisclaimer().isBlank());
        assertTrue(evidence.stream().anyMatch(item -> item.controlId().value().equals("supply-chain")));
        assertTrue(evidence.stream().anyMatch(item -> item.controlId().value().equals("dependency-boundary")));
        assertTrue(report.summary().getOrDefault(ControlStatus.IMPLEMENTED, 0L) > 0L);
        assertTrue(json.contains("\"profile\":\"dora\""));
        assertTrue(markdown.contains("Compliance Report"));
        assertTrue(csv.contains("controlId"));
        assertTrue(new ComplianceAdmin().format(report).contains("certification=false"));
    }

    @Test
    public void tamperEvidentAuditDetectsModificationAndMissingEvent() {
        TamperEvidentAuditSink sink = new TamperEvidentAuditSink(16, 2);
        sink.record(event("lookup", "allowed"));
        sink.record(event("invoke", "denied"));
        sink.record(event("admin", "allowed"));

        assertTrue(sink.verify().valid());
        assertTrue(sink.chain().stream().anyMatch(record -> !record.checkpointHash().isBlank()));
        assertTrue(sink.exportChain().contains("invoke"));

        List<TamperEvidentAuditRecord> modified = new ArrayList<>(sink.chain());
        TamperEvidentAuditRecord second = modified.get(1);
        modified.set(1, new TamperEvidentAuditRecord(second.sequence(), event("invoke", "allowed"),
                second.previousHash(), second.currentHash(), second.checkpointHash()));
        assertFalse(TamperEvidentAuditSink.verify(modified).valid());

        List<TamperEvidentAuditRecord> missing = new ArrayList<>(sink.chain());
        missing.remove(1);
        assertFalse(TamperEvidentAuditSink.verify(missing).valid());
    }

    @Test
    public void cryptoKmsAndReleaseAttestationFixturesExposeSafeMetadata() {
        CryptoPolicy strict = new CryptoPolicy(new CryptoProviderPolicy(Set.of("SHA-256"), Set.of("SUN"), true),
                new FipsModePolicy(true, "MissingFipsProvider"), new KeyRotationPolicy(Duration.ofDays(30L), true));
        CryptoPolicyEvaluator evaluator = new CryptoPolicyEvaluator();
        CryptoPostureReport report = evaluator.report(strict, new TestKeyManagementProvider());
        assertFalse(report.fipsSatisfied());
        assertTrue(report.keyProviderStatus().contains("test-kms"));
        assertThrows(IllegalArgumentException.class, () -> evaluator.validate(strict, "MD5", "SUN"));

        KeyReference key = new TestKeyManagementProvider().keyReference("demo");
        byte[] plaintext = "safe".getBytes(StandardCharsets.UTF_8);
        TestEnvelopeEncryptionService service = new TestEnvelopeEncryptionService();
        assertArrayEquals(plaintext, service.decrypt(service.encrypt(plaintext, key), key));

        ReleaseAttestationMetadata metadata = new ReleaseAttestationMetadata("sbom", "SPDX", "sha256:abc", "prov",
                "rev", Instant.now(), "signer", "passed", "vex", Map.of("attestationSignature", "deterministic-trusted"));
        ReleaseVerificationResult verified = new DeterministicReleaseAttestationVerifier().verify(metadata);
        assertTrue(verified.trusted());
        assertFalse(new DeterministicReleaseAttestationVerifier().verify(ReleaseAttestationMetadata.unavailable()).trusted());
    }

    @Test
    public void externalPdpPrincipalResolverBreakGlassAndFourEyesAreEnforced() {
        ExternalPdpAuthorizationPolicy pdp = new ExternalPdpAuthorizationPolicy(new DeterministicPdpClient(Set.of("blocked")));
        assertTrue(pdp.evaluate(context("alice"), request("allowed", Map.of())).allowed());
        assertFalse(pdp.evaluate(context("alice"), request("blocked", Map.of())).allowed());
        boolean[] executed = new boolean[1];
        if (pdp.evaluate(context("alice"), request("blocked", Map.of())).allowed()) {
            executed[0] = true;
        }
        assertFalse(executed[0]);

        Principal principal = new OidcJwtPrincipalResolver().resolve(Map.of("sub", "alice", "roles", "admin,auditor", "tags", "employee"));
        assertEquals("alice", principal.id());
        assertTrue(principal.roles().contains("admin"));

        InMemoryAuditSink audit = new InMemoryAuditSink(8);
        new BreakGlassPolicyHook().activate(context("alice"), "incident-response", audit);
        assertEquals(1L, audit.eventCount());
        assertTrue(audit.recentEvents().getFirst().metadata().containsKey("mandatory"));

        AuthorizationPolicy allow = new AuthorizationPolicy() {
            @Override
            public String id() {
                return "allow";
            }

            @Override
            public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
                return AuthorizationDecision.allow("allow");
            }
        };
        FourEyesApprovalPolicy fourEyes = new FourEyesApprovalPolicy(allow, Set.of(PermissionAction.KILL.name()));
        assertFalse(fourEyes.evaluate(context("alice"), request("dangerous", Map.of())).allowed());
        assertTrue(fourEyes.evaluate(context("alice"), request("dangerous",
                Map.of("requestedBy", "alice", "approvedBy", "bob"))).allowed());
    }

    @Test
    public void missingResilienceAndSbomEvidenceAreReportedAsLimitations() {
        RuntimeEvidenceCollector collector = new RuntimeEvidenceCollector(status(), List.of(), ReleaseAttestationMetadata.unavailable(),
                null, null, null);
        ComplianceReport report = new DefaultComplianceReportService().createReport(
                BuiltInComplianceProfiles.profileById("regulated-baseline"), ComplianceScope.local(), collector.collect(ComplianceScope.local()));
        assertTrue(report.limitations().stream().anyMatch(value -> value.contains("SBOM")));
        assertTrue(report.limitations().stream().anyMatch(value -> value.contains("resilience")));
    }

    private static RuntimeStatus status() {
        return new RuntimeStatus(new RuntimeIdentity("runtime-a", "default", Map.of()), List.of(), List.of(), List.of(),
                new RegistryStatus(0, 0), 10L, 1L, List.of(), Map.of(
                "mode", "PRODUCTION",
                "mtlsEnabled", "true",
                "authorizationPolicy", "allow",
                "auditEvents", "3",
                "auditSink", "tamper-evident",
                "denyCount", "1",
                "maxInboundMessageBytes", "1048576",
                "maxOutboundMessageBytes", "1048576",
                "overloadRejectedCount", "0"));
    }

    private static AuditEvent event(final String action, final String decision) {
        return new AuditEvent(Instant.now(), "runtime-a", "alice", action, "resource", decision, "policy",
                "corr", "inv", "local", "safe", "grpc", "default", Map.of("secret", "<redacted>"));
    }

    private static PermissionRequest request(final String resource, final Map<String, String> metadata) {
        return new PermissionRequest(PermissionAction.KILL, resource, "default", "runtime-a", "", "", "", "", metadata);
    }

    private static SecurityContext context(final String principal) {
        return new SecurityContext(new Principal(principal, principal, Set.of("admin"), Set.of()), "runtime-a",
                new TrustDomain("default"), new Credential("test", "1", Map.of()), "corr", "inv", "local", true,
                TransportSecurityLevel.AUTHENTICATED_ENCRYPTED, Set.of("admin"), Map.of(), Instant.now());
    }
}
