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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.j7m.middleware.security.api.Redactor;

/**
 * Default redactor for public diagnostics and audit metadata.
 */
public final class DefaultRedactor implements Redactor {
    private static final int MAX_METADATA_EXAMINED = 32;
    private static final int MAX_METADATA_RETAINED = 32;
    private static final int MAX_KEY_UTF8_BYTES = 64;
    private static final int MAX_VALUE_UTF8_BYTES = 256;

    /**
     * Creates a default redactor.
     */
    public DefaultRedactor() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String redact(final String key, final String value) {
        String boundedKey = boundedToken(key, MAX_KEY_UTF8_BYTES);
        if (boundedKey.isEmpty()) {
            return "<redacted>";
        }
        String safeKey = normalizedSensitivityText(boundedKey);
        if (safeKey.contains("password")
                || safeKey.contains("secret")
                || safeKey.contains("token")
                || safeKey.contains("sensitive")
                || safeKey.contains("pin")
                || safeKey.contains("verifier")
                || safeKey.contains("hash")
                || safeKey.contains("kdf")
                || safeKey.contains("credential")
                || safeKey.contains("authheader")
                || safeKey.contains("authorization")
                || safeKey.contains("privatekey")) {
            return "<redacted>";
        }
        return boundedValue(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> redactMetadata(final Map<String, String> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        LinkedHashMap<String, String> redacted = new LinkedHashMap<>();
        boolean omitted = false;
        int examined = 0;
        try {
            java.util.Iterator<Map.Entry<String, String>> iterator = metadata.entrySet().iterator();
            while (examined < MAX_METADATA_EXAMINED) {
                boolean hasNext;
                try {
                    hasNext = iterator.hasNext();
                } catch (RuntimeException exception) {
                    omitted = true;
                    break;
                }
                if (!hasNext) {
                    break;
                }
                Map.Entry<String, String> entry;
                try {
                    entry = iterator.next();
                } catch (RuntimeException exception) {
                    omitted = true;
                    break;
                }
                examined++;
                String key;
                String value;
                try {
                    key = boundedToken(entry.getKey(), MAX_KEY_UTF8_BYTES);
                    value = boundedValue(entry.getValue());
                } catch (RuntimeException exception) {
                    omitted = true;
                    continue;
                }
                if (key.isEmpty()) {
                    omitted = true;
                    continue;
                }
                if (redacted.size() >= MAX_METADATA_RETAINED) {
                    omitted = true;
                    continue;
                }
                redacted.putIfAbsent(key, this.redact(key, value));
            }
            omitted = omitted || examined == MAX_METADATA_EXAMINED;
        } catch (RuntimeException exception) {
            omitted = true;
        }
        if (omitted && redacted.size() < MAX_METADATA_RETAINED) {
            redacted.putIfAbsent("detailsOmitted", "true");
        }
        return redacted.isEmpty() ? Map.of() : Collections.unmodifiableMap(redacted);
    }

    private static String normalizedSensitivityText(final String value) {
        if (value == null) {
            return "";
        }
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
            if (!safeTokenCodePoint(codePoint)) {
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

    private static String boundedValue(final String value) {
        String token = boundedToken(value, MAX_VALUE_UTF8_BYTES);
        if (token.isEmpty() || sensitiveValue(token)) {
            return "<redacted>";
        }
        return token;
    }

    private static boolean sensitiveValue(final String value) {
        String normalized = normalizedSensitivityText(value);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("privatekey")
                || normalized.contains("stacktrace")
                || normalized.contains("exception")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || value.contains("://")
                || value.contains("..")
                || value.contains("\\\\")
                || windowsDrive(value);
    }

    private static boolean windowsDrive(final String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
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
}
