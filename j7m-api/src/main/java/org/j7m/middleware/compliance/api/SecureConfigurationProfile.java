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

/**
 * Production secure-configuration posture snapshot.
 *
 * <p>The record is immutable and thread-safe. It is intentionally descriptive:
 * it validates deployment posture but does not configure transports by
 * itself.</p>
 *
 * @param productionMode whether production mode is enabled
 * @param insecureGrpcAllowed whether insecure gRPC is explicitly allowed
 * @param mtlsRequired whether mTLS is required
 * @param mtlsConfigured whether mTLS is configured
 * @param strictRegistryTrust whether strict registry trust is enabled
 * @param registryVerifierConfigured whether a registry verifier is configured
 * @param replayProtectionEnabled whether replay protection is enabled
 * @param timestampValidationEnabled whether timestamp validation is enabled
 * @param signedRegistryRecordsRequired whether signed registry records are required
 * @param auditSinkConfigured whether an audit sink is configured
 * @param tamperEvidentAuditEnabled whether tamper-evident audit is enabled
 * @param sensitiveRedactionEnabled whether redaction is enabled
 * @param externalPdpConfigured whether an external PDP is configured
 * @param limitsConfigured whether payload/concurrency/queue limits are configured
 * @param resourcePolicyConfigured whether a resource policy is configured
 * @param adminStatusProtected whether admin/status is protected
 * @param evidenceExportEnabled whether evidence export is enabled
 * @param credentialRotationConfigured whether a credential rotation process is configured
 */
public record SecureConfigurationProfile(
        boolean productionMode,
        boolean insecureGrpcAllowed,
        boolean mtlsRequired,
        boolean mtlsConfigured,
        boolean strictRegistryTrust,
        boolean registryVerifierConfigured,
        boolean replayProtectionEnabled,
        boolean timestampValidationEnabled,
        boolean signedRegistryRecordsRequired,
        boolean auditSinkConfigured,
        boolean tamperEvidentAuditEnabled,
        boolean sensitiveRedactionEnabled,
        boolean externalPdpConfigured,
        boolean limitsConfigured,
        boolean resourcePolicyConfigured,
        boolean adminStatusProtected,
        boolean evidenceExportEnabled,
        boolean credentialRotationConfigured) {
    /**
     * Returns a conservative production baseline profile.
     *
     * @return production baseline profile
     */
    public static SecureConfigurationProfile productionBaseline() {
        return new SecureConfigurationProfile(true, false, true, true, true, true, true, true, true, true,
                true, true, true, true, true, true, true, true);
    }
}
