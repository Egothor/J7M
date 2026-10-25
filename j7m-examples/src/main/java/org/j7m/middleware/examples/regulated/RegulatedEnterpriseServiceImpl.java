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

package org.j7m.middleware.examples.regulated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.j7m.middleware.application.api.J7mContext;

/**
 * Thread-safe local implementation of {@link RegulatedEnterpriseService}.
 */
public final class RegulatedEnterpriseServiceImpl implements RegulatedEnterpriseService {
    private final List<String> auditEvents;

    /**
     * Creates an empty regulated-enterprise service.
     */
    public RegulatedEnterpriseServiceImpl() {
        this.auditEvents = new ArrayList<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ComplianceSummary complianceSummary() {
        return new ComplianceSummary("regulated-baseline-local", 4, false,
                "Evidence support only; no formal certification claim");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized EvidenceReport exportEvidence() {
        return new EvidenceReport(Instant.now(),
                List.of("secure-configuration", "sbom-present", "provenance-present", "audit-chain-local"),
                List.of("local fixture keyring only", "external GRC/SIEM/KMS/PDP integrations are environment responsibilities"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ExampleCryptoPosture cryptoPosture() {
        return new ExampleCryptoPosture(true, List.of("SHA-256", "Ed25519-fixture-metadata"), false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized AuditVerificationResult verifyAuditTrail() {
        String hash = this.auditEvents.isEmpty() ? "empty" : this.hash(String.join("|", this.auditEvents));
        return new AuditVerificationResult(true, this.auditEvents.size(), hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized String processControlledRequest(final String requestId) {
        String principal = J7mContext.currentInvocation().security().principal().id();
        this.auditEvents.add("principal=" + principal + ", request=<redacted>");
        return "controlled-request accepted for " + principal;
    }

    private String hash(final String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
