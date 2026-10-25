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

package org.j7m.middleware.security.defaults;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryRecordSigner;
import org.j7m.middleware.registry.api.RegistryRecordVerifier;

/**
 * Deterministic test signer/verifier for registry records.
 */
public final class DeterministicRegistryRecordSigner implements RegistryRecordSigner, RegistryRecordVerifier {
    private final String keyId;

    /**
     * Creates a signer.
     *
     * @param keyId public key id
     */
    public DeterministicRegistryRecordSigner(final String keyId) {
        this.keyId = keyId == null || keyId.isBlank() ? "test-key" : keyId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> sign(final RegistryRecord record) {
        return Map.of(
                "publisher", record.ref().runtimeId(),
                "algorithm", "J7M-TEST-SHA256",
                "keyId", this.keyId,
                "keyFingerprint", this.digestMaterial(this.keyId),
                "issuedAt", Instant.now().toString(),
                "signature", this.digest(record));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean verify(final RegistryRecord record, final Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        String expiresAt = metadata.get("expiresAt");
        try {
            if (expiresAt != null && !expiresAt.isBlank() && Instant.parse(expiresAt).isBefore(Instant.now())) {
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        return this.keyId.equals(metadata.get("keyId"))
                && "J7M-TEST-SHA256".equals(metadata.get("algorithm"))
                && this.digest(record).equals(metadata.get("signature"));
    }

    private String digest(final RegistryRecord record) {
        Map<String, String> relevantMetadata = new TreeMap<>(record.ref().metadata());
        String material = this.keyId + "|"
                + record.ref().registryContext() + "|"
                + record.ref().runtimeId() + "|"
                + record.ref().objectId() + "|"
                + record.ref().generation() + "|"
                + record.ref().serviceName() + "|"
                + this.canonicalList(record.ref().interfaces()) + "|"
                + record.ref().transport() + "|"
                + record.ref().endpoint() + "|"
                + record.ref().leaseUntil() + "|"
                + record.createdAtMillis() + "|"
                + record.lastHeartbeatMillis() + "|"
                + record.leaseUntilMillis() + "|"
                + record.state().name() + "|"
                + relevantMetadata + "|"
                + new TreeMap<>(record.parameters());
        return this.digestMaterial(material);
    }

    private String canonicalList(final List<String> values) {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(values.get(index));
        }
        builder.append(']');
        return builder.toString();
    }

    private String digestMaterial(final String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
