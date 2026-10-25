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

package org.j7m.middleware.application.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.api.ApplicationVerificationResult;

/**
 * Bundle verifier for manifests, checksums, signatures, and attestation policy.
 *
 * <p>Development mode allows unsigned bundles but still enforces manifests and
 * checksums. Strict production mode requires a trusted valid built-in
 * signature. Strict regulated mode also requires SBOM and provenance metadata.
 * Verification never executes application code.</p>
 */
public final class ApplicationBundleVerifier implements BundleVerifier {
    private final J7mBundleReader reader;
    private final ApplicationManifestValidator validator;
    private final PublicKeyTrustStore trustStore;
    private final ApplicationPlatformLimits limits;

    /**
     * Creates a verifier using the local-development trust store and default resource limits.
     */
    public ApplicationBundleVerifier() {
        this(PublicKeyTrustStore.localDevelopment());
    }

    /**
     * Creates a verifier with an explicit local trust store.
     *
     * @param trustStore public-key trust store; null installs an empty store, not development keys
     */
    public ApplicationBundleVerifier(final PublicKeyTrustStore trustStore) {
        this(trustStore, ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a verifier with an explicit trust store and bounded resource
     * limits.
     *
     * @param trustStore public-key trust store; null installs an empty store, not development keys
     * @param limits bundle and metadata limits; null selects the default finite limits
     */
    public ApplicationBundleVerifier(final PublicKeyTrustStore trustStore, final ApplicationPlatformLimits limits) {
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        this.reader = new J7mBundleReader(this.limits);
        this.validator = new ApplicationManifestValidator(this.limits);
        this.trustStore = trustStore == null ? new PublicKeyTrustStore(Map.of()) : trustStore;
    }

    /**
     * Verifies a bundle without executing application code.
     *
     * @param bundle bundle to verify
     * @param strictSignatureRequired whether unsigned bundles must be rejected
     * @return structured verification result
     */
    public ApplicationVerificationResult verify(final ApplicationBundle bundle, final boolean strictSignatureRequired) {
        return this.verify(bundle, strictSignatureRequired ? SignaturePolicy.STRICT_PRODUCTION : SignaturePolicy.DEVELOPMENT);
    }

    /**
     * Verifies the bundle manifest, checksums, signatures and policy-required evidence without activation.
     *
     * <p>A null policy selects development verification. Strict production requires a trusted
     * signature; strict regulated verification additionally requires SBOM and provenance evidence.
     * Ordinary malformed bundle or verification failures are returned as a redacted rejection,
     * not as successful admission. Verification does not execute application classes.</p>
     *
     * @param bundle nonnull bundle with its declared coordinate and storage location
     * @param policy verification profile, or null for development verification
     * @return structured acceptance or rejection with safe findings
     * @throws NullPointerException if bundle is null; no bundle coordinate can then be reported
     */
    @Override
    public ApplicationVerificationResult verify(final ApplicationBundle bundle, final SignaturePolicy policy) {
        SignaturePolicy safePolicy = policy == null ? SignaturePolicy.DEVELOPMENT : policy;
        List<String> findings = new ArrayList<>();
        try {
            ApplicationManifest manifest = this.reader.readManifest(bundle);
            ApplicationVerificationResult manifestResult = this.validator.validate(manifest, safePolicy.signatureRequired());
            findings.addAll(manifestResult.messages());
            ChecksumManifest expected = this.reader.readChecksums(bundle);
            ChecksumManifest actual = this.reader.computeChecksums(bundle);
            if (expected.entries().isEmpty()) {
                findings.add("checksums.sha256 is missing");
            }
            for (Map.Entry<String, String> entry : expected.entries().entrySet()) {
                String actualHash = actual.entries().get(entry.getKey());
                if (!entry.getValue().equals(actualHash)) {
                    findings.add("checksum mismatch");
                }
            }
            if ("JPMS".equals(manifest.moduleMode())) {
                Set<String> entries = actual.entries().keySet().stream().collect(Collectors.toUnmodifiableSet());
                for (ApplicationManifest.ModuleDeclaration module : manifest.modules()) {
                    if (!entries.contains(module.path())) {
                        findings.add("declared module JAR is missing");
                    }
                }
            }
            SignatureVerificationResult signature = this.verifySignature(bundle);
            findings.add("signature present=" + signature.present()
                    + " trusted=" + signature.trusted()
                    + " valid=" + signature.valid()
                    + " keyId=<redacted>"
                    + " signer=<redacted>"
                    + " reason=" + signature.reason());
            if (safePolicy.signatureRequired() && !signature.valid()) {
                findings.add("signature verification failed: " + safeToken(signature.reason()));
            }
            boolean sbomPresent = this.reader.hasEntry(bundle, J7mBundleReader.SBOM_ENTRY);
            boolean provenancePresent = this.reader.hasEntry(bundle, J7mBundleReader.PROVENANCE_ENTRY);
            findings.add("sbom present=" + sbomPresent);
            findings.add("provenance present=" + provenancePresent);
            if (safePolicy.sbomRequired() && !sbomPresent) {
                findings.add("SBOM is required by strict regulated verification");
            }
            if (safePolicy.provenanceRequired() && !provenancePresent) {
                findings.add("provenance is required by strict regulated verification");
            }
            boolean accepted = manifestResult.accepted()
                    && expected.entries().equals(actual.entries())
                    && (!safePolicy.signatureRequired() || signature.valid())
                    && (!safePolicy.sbomRequired() || sbomPresent)
                    && (!safePolicy.provenanceRequired() || provenancePresent);
            if (accepted) {
                findings.add("bundle accepted");
                return ApplicationVerificationResult.accepted(manifest.identity().coordinate(), safePolicy.signatureRequired(), findings);
            }
            return ApplicationVerificationResult.rejected(manifest.identity().coordinate(), safePolicy.signatureRequired(), findings);
        } catch (RuntimeException exception) {
            findings.add("bundle verification failed");
            findings.add(safeException(exception));
            return ApplicationVerificationResult.rejected(bundle.coordinate(), safePolicy.signatureRequired(), findings);
        }
    }

    /**
     * Verifies a bundle signature against the configured local trust store.
     *
     * @param bundle bundle to verify
     * @return safe signature verification summary
     */
    public SignatureVerificationResult verifySignature(final ApplicationBundle bundle) {
        ApplicationSignature applicationSignature;
        try {
            applicationSignature = this.reader.readSignature(bundle);
        } catch (RuntimeException exception) {
            return new SignatureVerificationResult(true, false, false, "", "", "malformed-signature");
        }
        if (applicationSignature == null) {
            return new SignatureVerificationResult(false, false, false, "", "", "missing-signature");
        }
        if (!ApplicationSignature.SCHEMA_VERSION.equals(applicationSignature.schemaVersion())) {
            return new SignatureVerificationResult(true, false, false, applicationSignature.keyId(),
                    applicationSignature.signer(), "unsupported-signature-schema");
        }
        if (!"Ed25519".equals(applicationSignature.algorithm())) {
            return new SignatureVerificationResult(true, false, false, applicationSignature.keyId(),
                    applicationSignature.signer(), "unsupported-signature-algorithm");
        }
        byte[] digest = BundleSignatureSupport.canonicalDigest(bundle, this.limits);
        String digestHex = J7mBundleReader.hex(digest);
        if (!digestHex.equals(applicationSignature.contentDigest())) {
            return new SignatureVerificationResult(true, false, false, applicationSignature.keyId(),
                    applicationSignature.signer(), "content-digest-mismatch");
        }
        SigningKeyId keyId = new SigningKeyId(applicationSignature.keyId());
        PublicKey publicKey = this.trustStore.find(keyId).orElse(null);
        if (publicKey == null) {
            return new SignatureVerificationResult(true, false, false, applicationSignature.keyId(),
                    applicationSignature.signer(), "unknown-signing-key");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(digest);
            boolean verified = verifier.verify(applicationSignature.signatureBytes());
            return new SignatureVerificationResult(true, true, verified, applicationSignature.keyId(),
                    applicationSignature.signer(), verified ? "verified" : "invalid-signature");
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return new SignatureVerificationResult(true, true, false, applicationSignature.keyId(),
                    applicationSignature.signer(), "signature-verification-error");
        }
    }

    /**
     * Reads and parses the manifest from a bundle.
     *
     * @param bundle bundle to inspect
     * @return parsed manifest
     */
    public ApplicationManifest manifest(final ApplicationBundle bundle) {
        return this.reader.readManifest(bundle);
    }

    /**
     * Converts public option values into a verifier policy.
     *
     * @param profile profile text
     * @param strict strict flag retained for compatibility
     * @return signature policy
     */
    public static SignaturePolicy policy(final String profile, final boolean strict) {
        String normalized = profile == null ? "" : profile.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "strict-regulated", "regulated", "production-regulated" -> SignaturePolicy.STRICT_REGULATED;
            case "strict-production", "production", "strict" -> SignaturePolicy.STRICT_PRODUCTION;
            default -> strict ? SignaturePolicy.STRICT_PRODUCTION : SignaturePolicy.DEVELOPMENT;
        };
    }

    private static String safeException(final RuntimeException exception) {
        if (exception instanceof org.j7m.middleware.api.MiddlewareException middlewareException) {
            return "verification error: " + middlewareException.code().name();
        }
        if (exception instanceof IllegalArgumentException) {
            return "verification error: INVALID_BUNDLE";
        }
        return "verification error: RUNTIME_FAILURE";
    }

    private static String safeToken(final String value) {
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 96));
        int limit = Math.min(value.length(), 96);
        for (int index = 0; index < limit; index++) {
            char character = value.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_' || character == ' ' || character == ':' || character == '.') {
                builder.append(character);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
