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

package org.j7m.middleware.attestation;

import java.time.Instant;
import java.util.Map;

/**
 * Safe supply-chain and release attestation metadata.
 *
 * <p>The record carries references, digests, and signer identifiers only. It
 * never contains private keys, tokens, or SBOM contents.</p>
 *
 * @param sbomReference SBOM URI or file reference
 * @param sbomFormat SBOM format, such as SPDX or CycloneDX
 * @param artifactDigest artifact digest
 * @param buildProvenanceReference provenance reference
 * @param sourceRevision source revision
 * @param buildTimestamp build timestamp
 * @param signerIdentity safe signer identity
 * @param dependencyPolicyStatus dependency policy result
 * @param vexReference vulnerability/VEX reference
 * @param metadata additional safe metadata
 */
public record ReleaseAttestationMetadata(
        String sbomReference,
        String sbomFormat,
        String artifactDigest,
        String buildProvenanceReference,
        String sourceRevision,
        Instant buildTimestamp,
        String signerIdentity,
        String dependencyPolicyStatus,
        String vexReference,
        Map<String, String> metadata) {
    /**
     * Creates immutable safe metadata.
     */
    public ReleaseAttestationMetadata {
        sbomReference = sbomReference == null ? "" : sbomReference;
        sbomFormat = sbomFormat == null ? "" : sbomFormat;
        artifactDigest = artifactDigest == null ? "" : artifactDigest;
        buildProvenanceReference = buildProvenanceReference == null ? "" : buildProvenanceReference;
        sourceRevision = sourceRevision == null ? "" : sourceRevision;
        buildTimestamp = buildTimestamp == null ? Instant.EPOCH : buildTimestamp;
        signerIdentity = signerIdentity == null ? "" : signerIdentity;
        dependencyPolicyStatus = dependencyPolicyStatus == null ? "unknown" : dependencyPolicyStatus;
        vexReference = vexReference == null ? "" : vexReference;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Returns metadata that explicitly reports unavailable SBOM/provenance.
     *
     * @return missing metadata descriptor
     */
    public static ReleaseAttestationMetadata unavailable() {
        return new ReleaseAttestationMetadata("", "", "", "", "", Instant.EPOCH, "", "unknown", "", Map.of("limitation", "SBOM/provenance metadata not supplied"));
    }
}
