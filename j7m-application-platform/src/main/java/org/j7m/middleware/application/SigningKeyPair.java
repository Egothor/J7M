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

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;

/**
 * Local bundle-signing key pair.
 *
 * <p>Instances own private key material and must never be serialized into
 * repository status, audit records, or evidence reports. The built-in
 * {@link #localDevelopment()} pair is deterministic test and demo material for
 * local verification only; it is not a production CA or KMS integration.</p>
 *
 * @param keyId safe public key identifier
 * @param signerId safe signer identity
 * @param privateKey private signing key
 * @param publicKey public verification key
 */
public record SigningKeyPair(SigningKeyId keyId, String signerId, PrivateKey privateKey, PublicKey publicKey) {
    /**
     * Creates a signing key pair descriptor.
     */
    public SigningKeyPair {
        if (keyId == null || privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("key id, private key, and public key are required");
        }
        signerId = signerId == null || signerId.isBlank() ? keyId.value() : signerId;
    }

    /**
     * Creates deterministic local development signing material.
     *
     * @return local development key pair
     */
    public static SigningKeyPair localDevelopment() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed("j7m-local-development-ed25519".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            generator.initialize(255, random);
            KeyPair keyPair = generator.generateKeyPair();
            return new SigningKeyPair(new SigningKeyId("local-dev-ed25519"), "local-dev-signer",
                    keyPair.getPrivate(), keyPair.getPublic());
        } catch (GeneralSecurityException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "Ed25519 signing material is unavailable", java.util.Map.of(), null, null, false, true),
                    exception);
        }
    }

    /**
     * Returns a redacted diagnostic string.
     *
     * <p>The representation intentionally omits private and public key object
     * details, encoded key material, providers, algorithms, and object identity
     * values. Callers that need key material must use the explicit accessors in
     * trusted signing or verification code.</p>
     *
     * @return redacted signing key pair summary
     */
    @Override
    public String toString() {
        return "SigningKeyPair[keyId=<redacted>, signerId=" + this.signerId + ", keyMaterial=<redacted>]";
    }
}
