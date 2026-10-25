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

package org.j7m.middleware.operations.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Versioned non-reversible PIN verifier for nugget administration.
 */
final class NuggetPinVerifier implements AutoCloseable {
    static final String FORMAT_VERSION = "j7m-pin-v1";
    static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int SALT_BYTES = 16;
    static final int VERIFIER_BYTES = 32;
    static final int MAX_PIN_BYTES = 256;
    static final int MIN_ITERATIONS = 210_000;
    static final int MAX_ITERATIONS = 1_000_000;
    static final int FIELD_COUNT = 8;
    static final int MAX_RECORD_BYTES = 1_024;
    static final int SALT_BASE64_CHARS = 24;
    static final int VERIFIER_BASE64_CHARS = 44;

    private final String formatVersion;
    private final String algorithm;
    private final int iterations;
    private final byte[] salt;
    private final byte[] verifier;
    private final Instant createdAt;
    private final Instant lastUpgradedAt;
    private final String policyId;

    private NuggetPinVerifier(
            final String formatVersion,
            final String algorithm,
            final int iterations,
            final byte[] salt,
            final byte[] verifier,
            final Instant createdAt,
            final Instant lastUpgradedAt,
            final String policyId) {
        this.formatVersion = formatVersion;
        this.algorithm = algorithm;
        this.iterations = iterations;
        this.salt = Arrays.copyOf(salt, salt.length);
        this.verifier = Arrays.copyOf(verifier, verifier.length);
        this.createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        this.lastUpgradedAt = lastUpgradedAt == null ? this.createdAt : lastUpgradedAt;
        this.policyId = policyId == null || policyId.isBlank() ? "phase-7.1-baseline" : policyId;
        validate();
    }

    static NuggetPinVerifier create(final char[] pin) {
        return create(pin, MIN_ITERATIONS, new SecureRandom(), Instant.now(), "phase-7.1-baseline");
    }

    static NuggetPinVerifier create(
            final char[] pin,
            final int iterations,
            final SecureRandom random,
            final Instant timestamp,
            final String policyId) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations outside nugget PIN policy");
        }
        validatePinInput(pin, MAX_PIN_BYTES);
        byte[] salt = new byte[SALT_BYTES];
        Objects.requireNonNull(random, "random").nextBytes(salt);
        byte[] derived = derive(pin, salt, iterations, VERIFIER_BYTES);
        try {
            return new NuggetPinVerifier(FORMAT_VERSION, ALGORITHM, iterations, salt, derived, timestamp, timestamp, policyId);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(derived, (byte) 0);
        }
    }

    static NuggetPinVerifier parse(final String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("verifier record is invalid");
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != FIELD_COUNT) {
            throw new IllegalArgumentException("verifier record is invalid");
        }
        if (!FORMAT_VERSION.equals(parts[0]) || !ALGORITHM.equals(parts[1])) {
            throw new IllegalArgumentException("verifier record is unsupported");
        }
        int parsedIterations = parsePositiveInt(parts[2]);
        byte[] parsedSalt = decodeExact(parts[3], SALT_BASE64_CHARS, SALT_BYTES);
        byte[] parsedVerifier = decodeExact(parts[4], VERIFIER_BASE64_CHARS, VERIFIER_BYTES);
        Instant created = parseInstant(parts[5]);
        Instant upgraded = parseInstant(parts[6]);
        try {
            return new NuggetPinVerifier(parts[0], parts[1], parsedIterations, parsedSalt, parsedVerifier, created, upgraded, parts[7]);
        } finally {
            Arrays.fill(parsedSalt, (byte) 0);
            Arrays.fill(parsedVerifier, (byte) 0);
        }
    }

    String encodedRecord() {
        return String.join("|",
                this.formatVersion,
                this.algorithm,
                Integer.toString(this.iterations),
                Base64.getEncoder().encodeToString(this.salt),
                Base64.getEncoder().encodeToString(this.verifier),
                this.createdAt.toString(),
                this.lastUpgradedAt.toString(),
                this.policyId);
    }

    byte[] encodedRecordBytes() {
        return this.encodedRecord().getBytes(StandardCharsets.UTF_8);
    }

    boolean verify(final char[] pin) {
        validatePinInput(pin, MAX_PIN_BYTES);
        byte[] candidate = derive(pin, this.salt, this.iterations, VERIFIER_BYTES);
        try {
            return candidate.length == this.verifier.length && MessageDigest.isEqual(candidate, this.verifier);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    /**
     * Returns whether this verifier should be upgraded to a higher iteration count.
     *
     * @param desiredIterations desired policy iteration count
     * @return upgrade-needed flag
     */
    boolean needsUpgrade(final int desiredIterations) {
        return desiredIterations > this.iterations;
    }

    /**
     * Returns the verifier format version.
     *
     * @return format version
     */
    String formatVersion() {
        return this.formatVersion;
    }

    /**
     * Returns the derivation algorithm identifier.
     *
     * @return algorithm
     */
    String algorithm() {
        return this.algorithm;
    }

    /**
     * Returns the configured iteration count.
     *
     * @return iteration count
     */
    int iterations() {
        return this.iterations;
    }

    /**
     * Returns the derived verifier length.
     *
     * @return verifier length in bytes
     */
    int derivedLength() {
        return this.verifier.length;
    }

    /**
     * Returns the creation timestamp.
     *
     * @return creation timestamp
     */
    Instant createdAt() {
        return this.createdAt;
    }

    /**
     * Returns the last upgrade timestamp.
     *
     * @return last upgrade timestamp
     */
    Instant lastUpgradedAt() {
        return this.lastUpgradedAt;
    }

    /**
     * Returns the safe policy id.
     *
     * @return policy id
     */
    String policyId() {
        return this.policyId;
    }

    @Override
    public void close() {
        Arrays.fill(this.salt, (byte) 0);
        Arrays.fill(this.verifier, (byte) 0);
    }

    @Override
    public String toString() {
        return "NuggetPinVerifier[formatVersion=" + this.formatVersion
                + ", algorithm=" + this.algorithm
                + ", iterations=" + this.iterations
                + ", derivedLength=" + this.verifier.length
                + ", material=<redacted>]";
    }

    static void validatePinInput(final char[] pin, final int maxBytes) {
        if (pin == null || pin.length == 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("PIN is invalid");
        }
        int bytes = 0;
        boolean nonBlank = false;
        for (int index = 0; index < pin.length; index++) {
            char character = pin[index];
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                nonBlank = true;
            }
            int codePoint;
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= pin.length || !Character.isLowSurrogate(pin[index + 1])) {
                    throw new IllegalArgumentException("PIN is invalid");
                }
                codePoint = Character.toCodePoint(character, pin[++index]);
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("PIN is invalid");
            } else {
                codePoint = character;
            }
            if (codePoint <= 0x7F) {
                bytes++;
            } else if (codePoint <= 0x7FF) {
                bytes += 2;
            } else if (codePoint <= 0xFFFF) {
                bytes += 3;
            } else {
                bytes += 4;
            }
            if (bytes > maxBytes) {
                throw new IllegalArgumentException("PIN is invalid");
            }
        }
        if (!nonBlank) {
            throw new IllegalArgumentException("PIN is invalid");
        }
    }

    private void validate() {
        if (!FORMAT_VERSION.equals(this.formatVersion) || !ALGORITHM.equals(this.algorithm)) {
            throw new IllegalArgumentException("verifier record is unsupported");
        }
        if (this.iterations < MIN_ITERATIONS || this.iterations > MAX_ITERATIONS
                || this.salt.length != SALT_BYTES || this.verifier.length != VERIFIER_BYTES) {
            throw new IllegalArgumentException("verifier record is out of policy");
        }
    }

    private static byte[] derive(final char[] pin, final byte[] salt, final int iterations, final int lengthBytes) {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, iterations, lengthBytes * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PIN verifier unavailable");
        } finally {
            spec.clearPassword();
        }
    }

    private static int parsePositiveInt(final String value) {
        if (value == null || value.isEmpty() || value.length() > 9) {
            throw new IllegalArgumentException("verifier record is invalid");
        }
        long parsed = 0L;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException("verifier record is invalid");
            }
            parsed = (parsed * 10L) + (long) (ch - '0');
            if (parsed > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("verifier record is invalid");
            }
        }
        if (parsed <= 0L) {
            throw new IllegalArgumentException("verifier record is invalid");
        }
        return (int) parsed;
    }

    private static byte[] decodeExact(final String value, final int encodedLength, final int decodedLength) {
        if (value == null || value.length() != encodedLength) {
            throw new IllegalArgumentException("verifier record is invalid");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != decodedLength) {
                Arrays.fill(decoded, (byte) 0);
                throw new IllegalArgumentException("verifier record is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("verifier record is invalid", e);
        }
    }

    private static Instant parseInstant(final String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("verifier record is invalid", e);
        }
    }
}
