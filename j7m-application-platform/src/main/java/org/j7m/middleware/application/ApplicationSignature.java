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

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bundle signature metadata stored in {@code META-INF/j7m-signature.json}.
 *
 * <p>The record contains only safe public metadata and the signature bytes. It
 * never contains private key material.</p>
 *
 * @param schemaVersion signature schema version
 * @param algorithm signature algorithm
 * @param keyId signing key id
 * @param signer signer identity
 * @param createdAt signature creation time
 * @param contentDigestAlgorithm digest algorithm for canonical bundle content
 * @param contentDigest canonical content digest
 * @param signature Base64-encoded signature
 */
public record ApplicationSignature(
        String schemaVersion,
        String algorithm,
        String keyId,
        String signer,
        Instant createdAt,
        String contentDigestAlgorithm,
        String contentDigest,
        String signature) {
    /**
     * Signature schema version supported by this implementation.
     */
    public static final String SCHEMA_VERSION = "j7m.signature/v1";

    /**
     * Creates immutable signature metadata.
     */
    public ApplicationSignature {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        algorithm = algorithm == null || algorithm.isBlank() ? "Ed25519" : algorithm;
        keyId = keyId == null ? "" : keyId;
        signer = signer == null ? "" : signer;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        contentDigestAlgorithm = contentDigestAlgorithm == null || contentDigestAlgorithm.isBlank()
                ? "SHA-256" : contentDigestAlgorithm;
        contentDigest = contentDigest == null ? "" : contentDigest;
        signature = signature == null ? "" : signature;
    }

    /**
     * Encodes this signature as deterministic JSON.
     *
     * @return UTF-8 JSON text
     */
    public String toJson() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schemaVersion", this.schemaVersion);
        values.put("algorithm", this.algorithm);
        values.put("keyId", this.keyId);
        values.put("signer", this.signer);
        values.put("createdAt", this.createdAt.toString());
        values.put("contentDigestAlgorithm", this.contentDigestAlgorithm);
        values.put("contentDigest", this.contentDigest);
        values.put("signature", this.signature);
        return SimpleJson.writeStringMap(values) + "\n";
    }

    /**
     * Parses signature JSON.
     *
     * @param json JSON text
     * @return parsed signature metadata
     */
    public static ApplicationSignature parse(final String json) {
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("signature root must be an object");
        }
        return new ApplicationSignature(
                string(map, "schemaVersion"),
                string(map, "algorithm"),
                string(map, "keyId"),
                string(map, "signer"),
                Instant.parse(string(map, "createdAt")),
                string(map, "contentDigestAlgorithm"),
                string(map, "contentDigest"),
                string(map, "signature"));
    }

    /**
     * Decodes signature bytes.
     *
     * @return signature bytes
     */
    public byte[] signatureBytes() {
        return Base64.getDecoder().decode(this.signature);
    }

    private static String string(final Map<?, ?> map, final String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }
}
