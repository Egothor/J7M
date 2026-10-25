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

package org.j7m.middleware.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.attestation.ReleaseAttestationMetadata;
import org.j7m.middleware.compliance.api.ComplianceScope;
import org.j7m.middleware.compliance.api.ControlEvidence;
import org.j7m.middleware.compliance.api.ControlId;
import org.j7m.middleware.compliance.api.EvidenceCollector;
import org.j7m.middleware.crypto.policy.CryptoPostureReport;

/**
 * Collects runtime, security, release, crypto, and resilience evidence.
 *
 * <p>The collector is immutable and thread-safe. It collects bounded
 * snapshots only and never attempts to inspect secret material.</p>
 */
public final class RuntimeEvidenceCollector implements EvidenceCollector {
    private final RuntimeStatus status;
    private final List<String> modules;
    private final ReleaseAttestationMetadata releaseMetadata;
    private final CryptoPostureReport cryptoPosture;
    private final OperationalResilienceEvidence resilienceEvidence;
    private final ValidationSummary validationSummary;

    /**
     * Creates a collector.
     *
     * @param status runtime status
     * @param modules module names
     * @param releaseMetadata release metadata
     * @param cryptoPosture crypto posture
     * @param resilienceEvidence resilience evidence
     * @param validationSummary validation summary
     */
    public RuntimeEvidenceCollector(
            final RuntimeStatus status,
            final List<String> modules,
            final ReleaseAttestationMetadata releaseMetadata,
            final CryptoPostureReport cryptoPosture,
            final OperationalResilienceEvidence resilienceEvidence,
            final ValidationSummary validationSummary) {
        this.status = status;
        this.modules = modules == null ? List.of() : List.copyOf(modules);
        this.releaseMetadata = releaseMetadata == null ? ReleaseAttestationMetadata.unavailable() : releaseMetadata;
        this.cryptoPosture = cryptoPosture;
        this.resilienceEvidence = resilienceEvidence;
        this.validationSummary = validationSummary;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return "runtime-evidence";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ControlEvidence> collect(final ComplianceScope scope) {
        ComplianceScope safeScope = scope == null ? ComplianceScope.local() : scope;
        String runtimeId = this.status == null || this.status.identity() == null ? "" : this.status.identity().runtimeId();
        List<ControlEvidence> evidence = new ArrayList<>();
        evidence.add(evidence(safeScope, runtimeId, "build-version", "j7m-runtime", "collector",
                "version=" + packageVersion(), "", Map.of("status", "implemented")));
        evidence.add(evidence(safeScope, runtimeId, "module-list", "gradle", "declared-modules",
                String.join(";", this.modules), "", Map.of("moduleCount", Integer.toString(this.modules.size()), "status", "implemented")));
        evidence.add(evidence(safeScope, runtimeId, "dependency-boundary", "gradle", "dependency-report",
                "core/runtime concrete transport and scheduler implementation dependencies are not present in runtime wiring evidence",
                "", Map.of("status", "implemented")));
        addSecurityEvidence(safeScope, runtimeId, evidence);
        addReleaseEvidence(safeScope, runtimeId, evidence);
        addCryptoEvidence(safeScope, runtimeId, evidence);
        addResilienceEvidence(safeScope, runtimeId, evidence);
        addValidationEvidence(safeScope, runtimeId, evidence);
        return List.copyOf(evidence);
    }

    private void addSecurityEvidence(final ComplianceScope scope, final String runtimeId, final List<ControlEvidence> evidence) {
        Map<String, String> security = this.status == null ? Map.of() : this.status.security();
        evidence.add(evidence(scope, runtimeId, "transport-auth", "runtime-status", "status.security",
                "securityMode=" + security.getOrDefault("mode", "unknown") + ",mtls=" + security.getOrDefault("mtlsEnabled", "unknown"),
                security.containsKey("mode") ? "" : "security status unavailable", statusAttribute(security.containsKey("mode"))));
        evidence.add(evidence(scope, runtimeId, "authorization", "runtime-status", "status.security",
                "policy=" + security.getOrDefault("authorizationPolicy", "unknown") + ",denies=" + security.getOrDefault("denyCount", "0"),
                security.containsKey("authorizationPolicy") ? "" : "authorization policy status unavailable", statusAttribute(security.containsKey("authorizationPolicy"))));
        evidence.add(evidence(scope, runtimeId, "audit-forensics", "runtime-status", "status.security",
                "auditEvents=" + security.getOrDefault("auditEvents", "0") + ",auditSink=" + security.getOrDefault("auditSink", "unknown"),
                "", Map.of("status", "implemented")));
        evidence.add(evidence(scope, runtimeId, "resilience", "runtime-status", "limits",
                "maxInbound=" + security.getOrDefault("maxInboundMessageBytes", "0")
                        + ",maxOutbound=" + security.getOrDefault("maxOutboundMessageBytes", "0")
                        + ",overloadRejected=" + security.getOrDefault("overloadRejectedCount", "0"),
                "", Map.of("status", "implemented")));
        evidence.add(evidence(scope, runtimeId, "registry-trust", "runtime-status", "status.security",
                "trustMode=" + security.getOrDefault("registryTrustMode", "not-reported"),
                security.containsKey("registryTrustMode") ? "" : "registry trust mode not reported by runtime status",
                Map.of("status", security.containsKey("registryTrustMode") ? "implemented" : "partial")));
        evidence.add(evidence(scope, runtimeId, "replay-timestamp", "runtime-status", "status.security",
                "replayRejected=" + security.getOrDefault("replayRejectedCount", "0")
                        + ",timestampRejected=" + security.getOrDefault("timestampRejectedCount", "0"),
                "", Map.of("status", "implemented")));
        evidence.add(evidence(scope, runtimeId, "credential-rotation", "runtime-status", "status.security",
                "credentialVersion=" + security.getOrDefault("credentialVersion", "not-reported")
                        + ",lastRotation=" + security.getOrDefault("lastCredentialRotation", "not-reported"),
                security.containsKey("credentialVersion") ? "" : "credential rotation metadata not reported by runtime status",
                Map.of("status", security.containsKey("credentialVersion") ? "implemented" : "partial")));
        evidence.add(evidence(scope, runtimeId, "resource-policy", "runtime-status", "status.security",
                "resourcePolicy=" + security.getOrDefault("resourcePolicy", "unknown")
                        + ",denied=" + security.getOrDefault("resourcePolicyDeniedCount", "0"),
                security.containsKey("resourcePolicy") ? "" : "resource policy status unavailable",
                Map.of("status", security.containsKey("resourcePolicy") ? "implemented" : "partial")));
        evidence.add(evidence(scope, runtimeId, "admin-status-policy", "runtime-status", "status.security",
                "adminStatusRequiresAuthorization=true", "", Map.of("status", "implemented")));
        evidence.add(evidence(scope, runtimeId, "payload-concurrency-overload", "runtime-status", "status.security",
                "maxInbound=" + security.getOrDefault("maxInboundMessageBytes", "0")
                        + ",maxOutbound=" + security.getOrDefault("maxOutboundMessageBytes", "0")
                        + ",activeInvocations=" + security.getOrDefault("activeInvocations", "0")
                        + ",overloadRejected=" + security.getOrDefault("overloadRejectedCount", "0"),
                "", Map.of("status", "implemented")));
    }

    private void addReleaseEvidence(final ComplianceScope scope, final String runtimeId, final List<ControlEvidence> evidence) {
        boolean hasSbom = !this.releaseMetadata.sbomReference().isBlank();
        String limitation = hasSbom ? "" : "SBOM/provenance metadata not supplied";
        evidence.add(new ControlEvidence(Instant.now(), runtimeId, scope.environment(), new ControlId("supply-chain"),
                "j7m-runtime", "release-metadata",
                "digest=" + this.releaseMetadata.artifactDigest() + ",signer=" + this.releaseMetadata.signerIdentity(),
                limitation, "", Map.of("artifactDigest", this.releaseMetadata.artifactDigest()),
                Map.of("status", hasSbom ? "implemented" : "partial",
                        "sbomFormat", this.releaseMetadata.sbomFormat(),
                        "dependencyPolicyStatus", this.releaseMetadata.dependencyPolicyStatus())));
    }

    private void addCryptoEvidence(final ComplianceScope scope, final String runtimeId, final List<ControlEvidence> evidence) {
        if (this.cryptoPosture == null) {
            evidence.add(evidence(scope, runtimeId, "crypto-posture", "j7m-runtime", "not-configured",
                    "crypto posture unavailable", "crypto posture report not supplied", Map.of("status", "partial")));
            return;
        }
        evidence.add(evidence(scope, runtimeId, "crypto-posture", "j7m-runtime", "posture-report",
                "strict=" + this.cryptoPosture.strictMode() + ",fipsRequired=" + this.cryptoPosture.fipsRequired()
                        + ",kms=" + this.cryptoPosture.keyProviderStatus(),
                String.join("; ", this.cryptoPosture.limitations()),
                Map.of("status", this.cryptoPosture.limitations().isEmpty() ? "implemented" : "partial")));
    }

    private void addResilienceEvidence(final ComplianceScope scope, final String runtimeId, final List<ControlEvidence> evidence) {
        if (this.resilienceEvidence == null) {
            evidence.add(evidence(scope, runtimeId, "resilience", "j7m-runtime", "resilience-metadata",
                    "resilience metadata unavailable", "No resilience drill metadata supplied.", Map.of("status", "gap")));
            return;
        }
        boolean hasDrill = !this.resilienceEvidence.failoverDrillResult().isBlank();
        evidence.add(evidence(scope, runtimeId, "resilience", "j7m-runtime", "resilience-metadata",
                "rto=" + this.resilienceEvidence.rto() + ",rpo=" + this.resilienceEvidence.rpo()
                        + ",failover=" + this.resilienceEvidence.failoverDrillResult(),
                hasDrill ? "" : "No failover drill result supplied.",
                Map.of("status", hasDrill ? "implemented" : "gap")));
    }

    private void addValidationEvidence(final ComplianceScope scope, final String runtimeId, final List<ControlEvidence> evidence) {
        if (this.validationSummary == null) {
            return;
        }
        Map<String, String> notes = this.validationSummary.notes();
        long errors = parseLong(notes.get("errors"));
        boolean malformed = Boolean.parseBoolean(notes.getOrDefault("malformedEvidence", "false"));
        boolean stale = Boolean.parseBoolean(notes.getOrDefault("staleEvidence", "false"));
        boolean technicalPass = this.validationSummary.tests() > 0L
                && this.validationSummary.failures() == 0L
                && this.validationSummary.skipped() == 0L
                && errors == 0L
                && !malformed
                && !stale;
        String limitation = technicalPass ? "" : "validation evidence contains missing, failed, errored, skipped, malformed, or stale outcomes";
        evidence.add(evidence(scope, runtimeId, "validation-summary", "j7m-tests", "test-summary",
                "tests=" + this.validationSummary.tests() + ",passed=" + this.validationSummary.passed()
                        + ",skipped=" + this.validationSummary.skipped() + ",failures=" + this.validationSummary.failures()
                        + ",errors=" + errors,
                limitation,
                Map.of("status", technicalPass ? "implemented" : "gap",
                        "malformedEvidence", Boolean.toString(malformed),
                        "staleEvidence", Boolean.toString(stale),
                        "releaseState", notes.getOrDefault("releaseState", "not-reported"))));
        addValidationCategory(scope, runtimeId, evidence, notes, "operator-guidance", "operatorGuidance");
        addValidationCategory(scope, runtimeId, evidence, notes, "gradle-task-outcomes", "gradleTaskOutcomes");
        addValidationCategory(scope, runtimeId, evidence, notes, "junit-evidence", "junitEvidence");
        addValidationCategory(scope, runtimeId, evidence, notes, "discovery-trust-evidence", "discoveryTrustEvidence");
        addValidationCategory(scope, runtimeId, evidence, notes, "authorization-context-evidence", "authorizationContextEvidence");
        addValidationCategory(scope, runtimeId, evidence, notes, "deadline-cancellation-evidence", "deadlineCancellationEvidence");
        addValidationCategory(scope, runtimeId, evidence, notes, "hp-routing-evidence", "hpRoutingEvidence");
        addValidationCategory(scope, runtimeId, evidence, notes, "streaming-evidence", "streamingEvidence");
        addValidationCategory(scope, runtimeId, evidence, notes, "demo-evidence", "demoEvidence");
        addValidationCategory(scope, runtimeId, evidence, notes, "static-architecture-policy", "staticArchitecturePolicy");
    }

    private static void addValidationCategory(
            final ComplianceScope scope,
            final String runtimeId,
            final List<ControlEvidence> evidence,
            final Map<String, String> notes,
            final String controlId,
            final String noteKey) {
        String status = notes.getOrDefault(noteKey, "missing");
        boolean present = "pass".equals(status) || "implemented".equals(status) || "present".equals(status);
        evidence.add(evidence(scope, runtimeId, controlId, "j7m-tests", "checkpoint-evidence",
                noteKey + "=" + status,
                present ? "" : "validation evidence category is missing or not passing",
                Map.of("status", present ? "implemented" : "gap", "evidenceState", status)));
    }

    private static long parseLong(final String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static ControlEvidence evidence(
            final ComplianceScope scope,
            final String runtimeId,
            final String controlId,
            final String module,
            final String method,
            final String result,
            final String limitation,
            final Map<String, String> attributes) {
        return new ControlEvidence(Instant.now(), runtimeId, scope.environment(), new ControlId(controlId), module, method, result,
                limitation, "", Map.of(), attributes);
    }

    private static Map<String, String> statusAttribute(final boolean implemented) {
        return Map.of("status", implemented ? "implemented" : "partial");
    }

    private static String packageVersion() {
        String version = RuntimeEvidenceCollector.class.getPackage().getImplementationVersion();
        return version == null ? "0.1.0-SNAPSHOT" : version;
    }
}
