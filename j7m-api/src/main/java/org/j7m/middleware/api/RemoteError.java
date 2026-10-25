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

package org.j7m.middleware.api;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public, redacted description of a local or remote middleware failure.
 *
 * <p>The record is immutable and thread-safe. Detail values are intended for
 * logs and diagnostics and must not contain secrets. The constructor preserves
 * the iteration order of the supplied detail map in an immutable defensive copy
 * so callers that publish canonical public details can produce deterministic
 * JSON and evidence output.</p>
 *
 * @param code stable middleware error code
 * @param message safe diagnostic message
 * @param details public diagnostic details
 * @param invocationId invocation or correlation identifier when available
 * @param remoteRuntimeId remote runtime identifier when known
 * @param retryable whether retrying the operation may succeed
 * @param dirtyTarget whether the current target should be considered suspect
 */
public record RemoteError(
        MiddlewareErrorCode code,
        String message,
        Map<String, String> details,
        String invocationId,
        String remoteRuntimeId,
        boolean retryable,
        boolean dirtyTarget) implements Serializable {
    private static final int MAX_DETAILS = 16;
    private static final int MAX_DETAIL_KEY_UTF8_BYTES = 64;
    private static final int MAX_DETAIL_VALUE_UTF8_BYTES = 256;

    /**
     * Creates a remote error with defensive immutable copies.
     */
    public RemoteError {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        message = message == null ? code.name() : message;
        details = details == null ? Map.of() : orderedCopy(details);
    }

    private static Map<String, String> orderedCopy(final Map<String, String> source) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        boolean omitted = false;
        int examined = 0;
        try {
            java.util.Iterator<Map.Entry<String, String>> iterator = source.entrySet().iterator();
            while (examined < MAX_DETAILS && iterator.hasNext()) {
                Map.Entry<String, String> entry = iterator.next();
                examined++;
                String rawKey = entry.getKey();
                String rawValue = entry.getValue();
                String key = boundedToken(rawKey, MAX_DETAIL_KEY_UTF8_BYTES);
                String value = stableRecoveryReasonDetail(key, rawValue)
                        ? rawValue
                        : boundedToken(rawValue, MAX_DETAIL_VALUE_UTF8_BYTES);
                if (key.isEmpty() || value.isEmpty() || sensitiveKey(key) || sensitiveValue(key, value)) {
                    omitted = true;
                    continue;
                }
                copy.putIfAbsent(key, value);
            }
            omitted = omitted || examined == MAX_DETAILS;
        } catch (RuntimeException exception) {
            omitted = true;
        }
        if (omitted && copy.size() < MAX_DETAILS) {
            copy.putIfAbsent("detailsOmitted", "true");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String boundedToken(final String value, final int maxBytes) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(maxBytes);
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return "";
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!safeCodePoint(codePoint)) {
                return "";
            }
            int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > maxBytes) {
                return "";
            }
            builder.appendCodePoint(codePoint);
            bytes += nextBytes;
        }
        return builder.isEmpty() ? "" : builder.toString();
    }

    private static boolean safeCodePoint(final int codePoint) {
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

    private static boolean sensitiveKey(final String token) {
        String normalized = normalizedSensitivityText(token);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("private")
                || normalized.contains("path")
                || normalized.contains("stack")
                || normalized.contains("exception")
                || normalized.contains("throwable")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || normalized.contains("certificate")
                || normalized.contains("signature")
                || normalized.contains("key");
    }

    private static boolean sensitiveValue(final String key, final String token) {
        String normalized = normalizedSensitivityText(token);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("private")
                || normalized.contains("stack")
                || normalized.contains("exception")
                || normalized.contains("throwable")
                || (normalized.contains("payload") && !stableReasonCodeDetail(key, token))
                || normalized.contains("configuration")
                || normalized.contains("beginprivatekey")
                || normalized.contains("begincertificate");
    }

    private static boolean stableReasonCodeDetail(final String key, final String token) {
        if (!"reasonCode".equals(key) || token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        if (first < 'A' || first > 'Z') {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (!((value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean stableRecoveryReasonDetail(final String key, final String token) {
        if (!"reason".equals(key) || token == null || token.isEmpty()
                || token.length() > MAX_DETAIL_VALUE_UTF8_BYTES) {
            return false;
        }
        return "malformed event field".equals(token)
                || "malformed encoded field".equals(token)
                || "unsupported schema version".equals(token)
                || "unknown event type".equals(token)
                || "saga checkpoint byte limit exceeded".equals(token)
                || stableRecoveryFieldReason(token, "missing required field: ")
                || stableRecoveryFieldReason(token, "malformed numeric field: ");
    }

    private static boolean stableRecoveryFieldReason(final String token, final String prefix) {
        if (!token.startsWith(prefix) || token.length() == prefix.length()) {
            return false;
        }
        for (int index = prefix.length(); index < token.length(); index++) {
            char value = token.charAt(index);
            if (!((value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '.'
                    || value == '_'
                    || value == '-')) {
                return false;
            }
        }
        return true;
    }

    private static String normalizedSensitivityText(final String token) {
        StringBuilder builder = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value >= 'A' && value <= 'Z') {
                builder.append((char) (value + ('a' - 'A')));
            } else if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
                builder.append(value);
            }
        }
        return builder.toString();
    }
}
