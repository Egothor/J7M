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

package org.j7m.middleware.security.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable audit event with bounded public text.
 *
 * <p>Top-level string fields and metadata are defensively bounded before
 * retention. Secret-looking, path-like, endpoint-like, or otherwise unsafe
 * free-form values are omitted or replaced with stable redaction markers so
 * audit sinks cannot retain attacker-controlled payloads or credentials.
 * The {@code action} and {@code reason} fields retain only bounded structured
 * machine codes using the J7M audit-code character set.</p>
 *
 * @param timestamp event timestamp
 * @param runtimeId runtime id
 * @param principal principal id
 * @param action action name
 * @param resource resource id
 * @param decision authorization decision text
 * @param policyId policy id
 * @param correlationId correlation id
 * @param invocationId invocation id
 * @param remoteEndpoint remote endpoint
 * @param reason safe reason
 * @param transportId transport id
 * @param registryContext registry context
 * @param metadata redacted metadata
 */
public record AuditEvent(
        Instant timestamp,
        String runtimeId,
        String principal,
        String action,
        String resource,
        String decision,
        String policyId,
        String correlationId,
        String invocationId,
        String remoteEndpoint,
        String reason,
        String transportId,
        String registryContext,
        Map<String, String> metadata) {
    private static final String REDACTED = "redacted";
    private static final int MAX_METADATA_EXAMINED = 32;
    private static final int MAX_METADATA_RETAINED = 32;
    private static final int MAX_FIELD_UTF8_BYTES = 128;
    private static final int MAX_KEY_UTF8_BYTES = 64;
    private static final int MAX_VALUE_UTF8_BYTES = 256;

    /**
     * Creates immutable bounded and redacted metadata.
     */
    public AuditEvent {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        runtimeId = safeField(runtimeId, "", false);
        principal = safeField(principal, "anonymous", true);
        action = safeAuditCodeField(action, "", false);
        resource = safeField(resource, "", true);
        decision = safeField(decision, "", false);
        policyId = safeField(policyId, "", false);
        correlationId = safeField(correlationId, "", true);
        invocationId = safeField(invocationId, "", true);
        remoteEndpoint = safeField(remoteEndpoint, "", true);
        reason = safeAuditCodeField(reason, "", true);
        transportId = safeField(transportId, "", false);
        registryContext = safeField(registryContext, "default", false);
        metadata = metadata == null ? Map.of() : orderedCopy(metadata);
    }

    private static String safeField(final String value, final String fallback, final boolean pinSensitive) {
        String token = boundedToken(value, MAX_FIELD_UTF8_BYTES);
        if (token.isEmpty()) {
            return fallback;
        }
        return sensitiveTopLevel(token, pinSensitive) ? REDACTED : token;
    }

    private static String safeAuditCodeField(final String value, final String fallback, final boolean pinSensitive) {
        String token = boundedToken(value, MAX_FIELD_UTF8_BYTES);
        if (token.isEmpty()) {
            return fallback;
        }
        return sensitiveAuditCode(token, pinSensitive) ? REDACTED : token;
    }

    private static boolean sensitiveAuditCode(final String value, final boolean pinSensitive) {
        String normalized = normalizedSensitivityText(value);
        return !canonicalAuditCode(value)
                || (pinSensitive && normalized.contains("pin"))
                || exactSensitiveAuditLiteral(normalized, pinSensitive)
                || normalized.contains("stacktrace")
                || normalized.contains("exception")
                || value.contains("..")
                || value.contains("\\\\")
                || windowsDrive(value);
    }

    private static boolean exactSensitiveAuditLiteral(final String normalized, final boolean pinSensitive) {
        return (pinSensitive && ("pin".equals(normalized) || "admission".equals(normalized)))
                || "credential".equals(normalized)
                || "credentials".equals(normalized)
                || "password".equals(normalized)
                || "secret".equals(normalized)
                || "token".equals(normalized)
                || "bearer".equals(normalized)
                || "privatekey".equals(normalized)
                || "payload".equals(normalized)
                || "configuration".equals(normalized);
    }

    private static boolean canonicalAuditCode(final String value) {
        boolean previousSeparator = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean separator = current == '.' || current == '_' || current == '-' || current == ':';
            if (separator && previousSeparator) {
                return false;
            }
            previousSeparator = separator;
        }
        return !previousSeparator;
    }

    private static boolean sensitiveTopLevel(final String value, final boolean pinSensitive) {
        String normalized = normalizedSensitivityText(value);
        return (pinSensitive && normalized.contains("pin"))
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("privatekey")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || value.contains("://")
                || value.contains("..")
                || value.contains("\\\\")
                || windowsDrive(value);
    }

    private static Map<String, String> orderedCopy(final Map<String, String> source) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        boolean omitted = false;
        int examined = 0;
        try {
            java.util.Iterator<Map.Entry<String, String>> iterator = source.entrySet().iterator();
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
                    value = boundedAuditValue(entry.getValue());
                } catch (RuntimeException exception) {
                    omitted = true;
                    continue;
                }
                if (key.isEmpty() || value.isEmpty() || sensitiveKey(key) || sensitiveValue(value)) {
                    omitted = true;
                    continue;
                }
                if (copy.size() >= MAX_METADATA_RETAINED) {
                    omitted = true;
                    continue;
                }
                copy.putIfAbsent(key, value);
            }
            omitted = omitted || examined == MAX_METADATA_EXAMINED;
        } catch (RuntimeException exception) {
            omitted = true;
        }
        if (omitted && copy.size() < MAX_METADATA_RETAINED) {
            copy.putIfAbsent("detailsOmitted", "true");
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
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

    private static String boundedAuditValue(final String value) {
        if ("<redacted>".equals(value) || "[redacted]".equals(value)) {
            return value;
        }
        return boundedToken(value, MAX_VALUE_UTF8_BYTES);
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

    private static boolean sensitiveKey(final String value) {
        String normalized = normalizedSensitivityText(value);
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
                || normalized.contains("auth")
                || normalized.contains("caller")
                || normalized.contains("private")
                || normalized.contains("path")
                || normalized.contains("uri")
                || normalized.contains("url")
                || normalized.contains("endpoint")
                || normalized.contains("command")
                || normalized.contains("environment")
                || normalized.contains("manifest")
                || normalized.contains("config")
                || normalized.contains("payload")
                || normalized.contains("body")
                || normalized.contains("argument")
                || normalized.contains("stack")
                || normalized.contains("exception")
                || normalized.contains("throwable")
                || normalized.contains("cause")
                || normalized.contains("suppressed")
                || normalized.contains("implementation")
                || normalized.contains("classloader")
                || normalized.contains("modulelayer")
                || normalized.contains("process")
                || normalized.contains("certificate")
                || normalized.contains("cert")
                || normalized.contains("signature")
                || normalized.contains("key")
                || normalized.contains("digest")
                || normalized.contains("sbom")
                || normalized.contains("hook");
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
