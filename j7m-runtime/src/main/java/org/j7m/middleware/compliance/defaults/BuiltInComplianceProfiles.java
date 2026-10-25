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
import org.j7m.middleware.compliance.api.ComplianceProfile;
import org.j7m.middleware.compliance.api.ControlFramework;
import org.j7m.middleware.compliance.api.ControlId;
import org.j7m.middleware.compliance.api.ControlMapping;
import org.j7m.middleware.compliance.api.ControlRequirement;

/**
 * Built-in conservative profile catalog for regulated-readiness evidence.
 *
 * <p>The catalog is immutable from the caller perspective and thread-safe. It
 * does not represent certification, attestation by an auditor, or legal
 * compliance. It provides machine-readable mappings so operators can collect
 * evidence and feed external GRC systems.</p>
 */
public final class BuiltInComplianceProfiles {
    private static final String DISCLAIMER = "J7M provides evidence support only; this profile is not formal certification.";

    private BuiltInComplianceProfiles() {
    }

    /**
     * Returns all built-in profiles.
     *
     * @return immutable profile list
     */
    public static List<ComplianceProfile> all() {
        List<ComplianceProfile> profiles = new ArrayList<>(8);
        profiles.add(profile("regulated-baseline", "Generic regulated baseline", "REG", "1"));
        profiles.add(profile("dora", "DORA-oriented operational resilience", "DORA", "baseline"));
        profiles.add(profile("nis2", "NIS2-oriented cybersecurity governance", "NIS2", "baseline"));
        profiles.add(profile("nist-csf", "NIST CSF-oriented cyber risk management", "NIST-CSF", "2.0"));
        profiles.add(profile("nist-ssdf", "NIST SSDF-oriented secure software development", "NIST-SSDF", "1.1"));
        profiles.add(profile("pci", "PCI-oriented payment-data environment", "PCI", "4.0"));
        profiles.add(profile("soc2", "SOC2-oriented trust services", "SOC2", "2017"));
        profiles.add(profile("iso27001", "ISO27001-oriented ISMS mapping", "ISO27001", "2022"));
        return List.copyOf(profiles);
    }

    /**
     * Finds a profile by id.
     *
     * @param id profile id
     * @return profile
     */
    public static ComplianceProfile profileById(final String id) {
        for (ComplianceProfile profile : all()) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return profile("regulated-baseline", "Generic regulated baseline", "REG", "1");
    }

    private static ComplianceProfile profile(final String id, final String name, final String frameworkId, final String version) {
        ControlFramework framework = new ControlFramework(frameworkId, name, version);
        List<ControlRequirement> requirements = List.of(
                requirement(framework, "transport-auth", "Authenticated transport", "Modern remote transports must support authenticated encrypted operation."),
                requirement(framework, "authorization", "Authorization enforcement", "Server-side authorization decisions must be enforced before sensitive actions."),
                requirement(framework, "audit-forensics", "Audit and forensics", "Audit events must be exportable and optionally tamper-evident."),
                requirement(framework, "crypto-posture", "Cryptographic posture", "Configured cryptographic policy and key-management posture must be reportable."),
                requirement(framework, "supply-chain", "Release attestation", "Release provenance and SBOM metadata must be represented when available."),
                requirement(framework, "resilience", "Operational resilience", "Failover, overload, recovery, and resilience evidence must be collectable."));
        List<ControlMapping> mappings = List.of(
                mapping("grpc-mtls", "transport-auth", "mTLS and production insecure guard provide transport evidence."),
                mapping("authorization-policy", "authorization", "AuthorizationPolicy and runtime checks provide enforcement evidence."),
                mapping("tamper-evident-audit", "audit-forensics", "Hash-chain audit sink provides forensic integrity evidence."),
                mapping("crypto-policy", "crypto-posture", "Crypto posture APIs report algorithm, provider, FIPS, and KMS status."),
                mapping("release-attestation", "supply-chain", "Attestation metadata records SBOM, digest, signer, and provenance references."),
                mapping("resilience-evidence", "resilience", "Runtime/admin status and attached drill metadata provide resilience evidence."));
        return new ComplianceProfile(id, name, List.of(framework), requirements, mappings, DISCLAIMER);
    }

    private static ControlRequirement requirement(
            final ControlFramework framework,
            final String suffix,
            final String title,
            final String description) {
        return new ControlRequirement(new ControlId(framework.id() + "." + suffix), framework, title, description);
    }

    private static ControlMapping mapping(final String implementationId, final String suffix, final String rationale) {
        return new ControlMapping(implementationId, List.of(new ControlId("*." + suffix)), rationale);
    }
}
