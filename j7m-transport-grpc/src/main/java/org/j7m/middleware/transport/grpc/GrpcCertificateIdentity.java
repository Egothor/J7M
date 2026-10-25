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

package org.j7m.middleware.transport.grpc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Safe identity extracted from an authenticated TLS peer certificate.
 *
 * <p>The record intentionally carries only public certificate metadata. It is
 * immutable and thread-safe.</p>
 *
 * @param principalId authenticated principal id
 * @param runtimeId caller runtime id derived from the certificate
 * @param fingerprint SHA-256 certificate fingerprint
 * @param metadata safe certificate metadata
 */
public record GrpcCertificateIdentity(String principalId, String runtimeId, String fingerprint, Map<String, String> metadata) {
    /**
     * Creates immutable metadata.
     */
    public GrpcCertificateIdentity {
        principalId = principalId == null || principalId.isBlank() ? "certificate-peer" : principalId;
        runtimeId = runtimeId == null || runtimeId.isBlank() ? principalId : runtimeId;
        fingerprint = fingerprint == null ? "" : fingerprint;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Extracts safe identity metadata from a certificate.
     *
     * @param certificate peer certificate
     * @return certificate identity
     */
    public static GrpcCertificateIdentity fromCertificate(final X509Certificate certificate) {
        if (certificate == null) {
            return new GrpcCertificateIdentity("certificate-peer", "certificate-peer", "", Map.of());
        }
        String subject = certificate.getSubjectX500Principal().getName();
        String commonName = commonName(subject);
        String fingerprint = fingerprint(certificate);
        return new GrpcCertificateIdentity(commonName, commonName, fingerprint,
                Map.of("sec.certificateSubject", subject, "sec.credentialFingerprint", fingerprint));
    }

    private static String commonName(final String subject) {
        if (subject == null || subject.isBlank()) {
            return "certificate-peer";
        }
        String[] parts = subject.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("cn=")) {
                String value = trimmed.substring(3).trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return subject.getBytes(StandardCharsets.UTF_8).length == 0 ? "certificate-peer" : subject;
    }

    private static String fingerprint(final X509Certificate certificate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(certificate.getEncoded()));
        } catch (CertificateEncodingException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("certificate fingerprint cannot be computed", exception);
        }
    }
}
