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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.j7m.middleware.application.api.ApplicationBundle;

/**
 * Deterministic local metadata verifier for the regulated-enterprise example.
 *
 * <p>The verifier checks that the example bundle contains SBOM, provenance,
 * and local attestation metadata. It is intentionally not a production
 * certificate, CA, GRC, SIEM, KMS, or PDP integration.</p>
 */
public final class RegulatedExampleVerifier {
    /**
     * Bundle entry for the example SBOM.
     */
    public static final String SBOM_ENTRY = "META-INF/sbom.spdx.json";
    /**
     * Bundle entry for the local attestation fixture.
     */
    public static final String SIGNATURE_ENTRY = "META-INF/j7m-signature.json";

    /**
     * Creates a verifier.
     */
    public RegulatedExampleVerifier() {
    }

    /**
     * Verifies regulated example metadata.
     *
     * @param bundle bundle to verify
     * @return verification result
     */
    public RegulatedVerificationResult verify(final ApplicationBundle bundle) {
        List<String> findings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(bundle.path().toFile())) {
            this.require(zip, "META-INF/j7m-application.json", findings);
            this.require(zip, "META-INF/j7m-provenance.json", findings);
            this.require(zip, SBOM_ENTRY, findings);
            this.require(zip, SIGNATURE_ENTRY, findings);
        } catch (IOException exception) {
            findings.add("failed to read regulated example bundle");
        }
        if (findings.isEmpty()) {
            findings.add("regulated example metadata accepted");
            return new RegulatedVerificationResult(true, findings);
        }
        return new RegulatedVerificationResult(false, findings);
    }

    private void require(final ZipFile zip, final String entryName, final List<String> findings) {
        if (zip.getEntry(entryName) == null) {
            findings.add("missing " + entryName);
        }
    }
}
