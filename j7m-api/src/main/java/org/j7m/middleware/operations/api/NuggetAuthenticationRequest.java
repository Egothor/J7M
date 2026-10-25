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

package org.j7m.middleware.operations.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * UI-neutral metadata for one nugget PIN admission request.
 *
 * <p>The reusable credential is deliberately not stored in this public request
 * object. Runtime admission services accept the credential as a separate
 * shortest-scope argument so request diagnostics, {@link #toString()}, audit,
 * and JSON-style representations cannot expose it. Correlation and source
 * identifiers are normalized by the constructor before throttle bucketing or
 * audit use so hostile pre-authentication text cannot expand retained state or
 * leak secrets.</p>
 */
public final class NuggetAuthenticationRequest {
    private static final int MAX_CORRELATION_ID_UTF8_BYTES = 64;
    private static final int MAX_SOURCE_ID_UTF8_BYTES = 96;

    private final String correlationId;
    private final NuggetAuthenticationSource source;
    private final String sourceId;
    private final boolean trustedSource;
    private final Instant requestedAt;

    private NuggetAuthenticationRequest(
            final String correlationId,
            final NuggetAuthenticationSource source,
            final String sourceId,
            final boolean trustedSource,
            final Instant requestedAt) {
        this.correlationId = normalizeCorrelationId(correlationId);
        this.source = source == null ? NuggetAuthenticationSource.UNKNOWN : source;
        this.sourceId = normalizeSourceId(sourceId);
        this.trustedSource = trustedSource;
        this.requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
    }

    /**
     * Creates request metadata with a generated correlation id.
     *
     * @param source source class
     * @return request metadata
     */
    public static NuggetAuthenticationRequest from(final NuggetAuthenticationSource source) {
        return new NuggetAuthenticationRequest(UUID.randomUUID().toString(), source, "", false, Instant.now());
    }

    /**
     * Creates request metadata.
     *
     * @param correlationId caller-supplied audit correlation id; blank,
     *        overlong, malformed, or sensitive values are replaced
     * @param source source class
     * @param sourceId bounded endpoint or listener identifier; unsafe values
     *        are omitted
     * @param trustedSource whether the runtime owns and trusts {@code sourceId}
     * @param requestedAt request creation timestamp for diagnostics
     * @return request metadata
     */
    public static NuggetAuthenticationRequest of(
            final String correlationId,
            final NuggetAuthenticationSource source,
            final String sourceId,
            final boolean trustedSource,
            final Instant requestedAt) {
        return new NuggetAuthenticationRequest(correlationId, source, sourceId, trustedSource, requestedAt);
    }

    /**
     * Returns the audit correlation id.
     *
     * @return correlation id
     */
    public String correlationId() {
        return this.correlationId;
    }

    /**
     * Returns the coarse source class.
     *
     * @return source class
     */
    public NuggetAuthenticationSource source() {
        return this.source;
    }

    /**
     * Returns a bounded endpoint or listener identifier when it is safe.
     *
     * @return source identifier, or empty string
     */
    public String sourceId() {
        return this.sourceId;
    }

    /**
     * Returns whether {@link #sourceId()} is owned and trusted by the runtime.
     *
     * @return trusted-source flag
     */
    public boolean trustedSource() {
        return this.trustedSource;
    }

    /**
     * Returns the request timestamp.
     *
     * @return request timestamp
     */
    public Instant requestedAt() {
        return this.requestedAt;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NuggetAuthenticationRequest[correlationId=" + this.correlationId
                + ", source=" + this.source
                + ", sourceId=<redacted>"
                + ", trustedSource=" + this.trustedSource
                + ", requestedAt=" + this.requestedAt + "]";
    }

    private static String normalizeCorrelationId(final String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String token = boundedToken(value, MAX_CORRELATION_ID_UTF8_BYTES);
        if (token.isEmpty() || sensitive(token)) {
            return UUID.randomUUID().toString();
        }
        return Objects.requireNonNull(token, "correlationId");
    }

    private static String normalizeSourceId(final String value) {
        String token = boundedToken(value, MAX_SOURCE_ID_UTF8_BYTES);
        if (token.isEmpty() || sensitive(token)) {
            return "";
        }
        return token;
    }

    private static String boundedToken(final String value, final int maxBytes) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxBytes));
        int bytes = 0;
        boolean previousWhitespace = false;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return "";
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!builder.isEmpty() && !previousWhitespace) {
                    if (bytes + 1 > maxBytes) {
                        return "";
                    }
                    builder.append('-');
                    bytes++;
                    previousWhitespace = true;
                }
                continue;
            }
            if (!safeTokenCodePoint(codePoint)) {
                return "";
            }
            int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > maxBytes) {
                return "";
            }
            builder.appendCodePoint(codePoint);
            bytes += nextBytes;
            previousWhitespace = false;
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '-') {
            builder.setLength(length - 1);
        }
        return builder.isEmpty() ? "" : builder.toString();
    }

    private static boolean safeTokenCodePoint(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '.'
                || codePoint == '_'
                || codePoint == '-'
                || codePoint == ':';
    }

    private static int utf8Bytes(final int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private static boolean sensitive(final String value) {
        String normalized = normalizedSensitivityText(value);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("privatekey")
                || value.contains("://")
                || value.contains("..")
                || value.contains("\\\\")
                || windowsDrive(value);
    }

    private static String normalizedSensitivityText(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= 'A' && current <= 'Z') {
                builder.append((char) (current + ('a' - 'A')));
            } else if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')) {
                builder.append(current);
            }
        }
        return builder.toString();
    }

    private static boolean windowsDrive(final String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }
}
