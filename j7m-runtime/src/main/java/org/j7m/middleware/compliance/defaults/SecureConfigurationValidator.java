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

import java.util.ArrayList;
import java.util.List;
import org.j7m.middleware.compliance.api.ControlId;
import org.j7m.middleware.compliance.api.SecureConfigurationFinding;
import org.j7m.middleware.compliance.api.SecureConfigurationProfile;
import org.j7m.middleware.compliance.api.SecureConfigurationSeverity;
import org.j7m.middleware.compliance.api.SecureConfigurationValidationResult;

/**
 * Validates production secure-configuration posture.
 *
 * <p>The validator is stateless and thread-safe. It produces safe findings
 * only and never inspects credential or key material.</p>
 */
public final class SecureConfigurationValidator {
    /**
     * Creates a validator.
     */
    public SecureConfigurationValidator() {
    }

    /**
     * Validates a secure configuration profile.
     *
     * @param profile profile
     * @return validation result
     */
    public SecureConfigurationValidationResult validate(final SecureConfigurationProfile profile) {
        SecureConfigurationProfile safe = profile == null
                ? new SecureConfigurationProfile(false, true, true, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false)
                : profile;
        List<SecureConfigurationFinding> findings = new ArrayList<>();
        errorIf(!safe.productionMode(), findings, "secure.production-mode", "production mode must be enabled");
        errorIf(safe.insecureGrpcAllowed(), findings, "secure.insecure-grpc", "insecure gRPC must be disabled");
        errorIf(safe.mtlsRequired() && !safe.mtlsConfigured(), findings, "secure.mtls", "mTLS is required but not configured");
        errorIf(safe.strictRegistryTrust() && !safe.registryVerifierConfigured(), findings, "secure.registry-trust", "strict registry trust requires a verifier");
        errorIf(safe.signedRegistryRecordsRequired() && !safe.registryVerifierConfigured(), findings, "secure.registry-signing", "signed registry records require a verifier");
        errorIf(!safe.replayProtectionEnabled(), findings, "secure.replay", "replay protection must be enabled");
        errorIf(!safe.timestampValidationEnabled(), findings, "secure.timestamp", "timestamp validation must be enabled");
        errorIf(!safe.auditSinkConfigured(), findings, "secure.audit", "audit sink must be configured");
        errorIf(!safe.tamperEvidentAuditEnabled(), findings, "secure.audit-chain", "tamper-evident audit should be enabled");
        errorIf(!safe.sensitiveRedactionEnabled(), findings, "secure.redaction", "sensitive redaction must be enabled");
        warningIf(!safe.externalPdpConfigured(), findings, "secure.external-pdp", "external PDP is recommended for regulated production");
        errorIf(!safe.limitsConfigured(), findings, "secure.limits", "payload/concurrency/queue limits must be configured");
        errorIf(!safe.resourcePolicyConfigured(), findings, "secure.resource-policy", "resource policy must be configured");
        errorIf(!safe.adminStatusProtected(), findings, "secure.admin", "admin/status must be protected");
        warningIf(!safe.evidenceExportEnabled(), findings, "secure.evidence", "compliance evidence export should be enabled");
        warningIf(!safe.credentialRotationConfigured(), findings, "secure.credential-rotation", "credential rotation process should be configured");
        boolean valid = findings.stream().noneMatch(item -> item.severity() == SecureConfigurationSeverity.ERROR);
        return new SecureConfigurationValidationResult(valid, findings);
    }

    private static void errorIf(
            final boolean condition,
            final List<SecureConfigurationFinding> findings,
            final String control,
            final String message) {
        if (condition) {
            findings.add(new SecureConfigurationFinding(new ControlId(control), SecureConfigurationSeverity.ERROR, message));
        }
    }

    private static void warningIf(
            final boolean condition,
            final List<SecureConfigurationFinding> findings,
            final String control,
            final String message) {
        if (condition) {
            findings.add(new SecureConfigurationFinding(new ControlId(control), SecureConfigurationSeverity.WARNING, message));
        }
    }
}
