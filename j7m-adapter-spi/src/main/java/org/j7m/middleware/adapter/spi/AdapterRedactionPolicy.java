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

package org.j7m.middleware.adapter.spi;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Central redaction policy for adapter SPI and TCK public values.
 *
 * <p>The policy is stateless, immutable, and thread-safe. It is used by public
 * report, status, audit, evidence, result, and configuration records before
 * adapter-provided metadata is exposed to callers. The policy redacts both
 * sensitive metadata keys and credential-like values under otherwise harmless
 * keys. TCK runners can additionally detect the redaction marker to reject
 * adapters that attempted to place sensitive data in public compatibility
 * reports.</p>
 *
 * <p>The policy is deliberately vendor-neutral. It does not parse or validate
 * external product credentials and does not treat a redacted report as proof of
 * authorization. Implementations remain responsible for normalizing public
 * errors and for avoiding raw credential values in exception messages.</p>
 */
public final class AdapterRedactionPolicy {
    /**
     * Public marker used when a sensitive key or value is redacted.
     */
    public static final String REDACTED = "<redacted>";

    private AdapterRedactionPolicy() {
    }

    /**
     * Returns whether a metadata key is sensitive.
     *
     * @param key metadata key to inspect
     * @return {@code true} when the key must not expose its value
     */
    public static boolean isSensitiveKey(final String key) {
        final String lower = Objects.requireNonNull(key, "key").toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("passwd")
                || lower.contains("pwd")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("apikey")
                || lower.contains("api_key")
                || lower.contains("accesskey")
                || lower.contains("privatekey")
                || lower.contains("credential")
                || lower.contains("authorization")
                || lower.contains("cookie")
                || lower.contains("session")
                || lower.contains("bearer")
                || lower.contains("endpointcredential");
    }

    /**
     * Returns whether a text value contains a sentinel or credential-like value
     * that must not appear in public adapter output.
     *
     * @param value text value to inspect
     * @return {@code true} when the text contains sensitive data
     */
    public static boolean containsSensitiveValue(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("J7M_SENTINEL_SECRET_TOKEN")
                || value.contains("J7M_SENTINEL_PASSWORD")
                || value.contains("J7M_SENTINEL_API_KEY")
                || lower.contains("bearer j7m_sentinel_secret_token")
                || lower.contains("token=j7m_sentinel_secret_token")
                || lower.contains("password=j7m_sentinel_password")
                || lower.contains("apikey=j7m_sentinel_api_key")
                || lower.contains("api_key=j7m_sentinel_api_key");
    }

    /**
     * Redacts sensitive value patterns from public text.
     *
     * @param value public text candidate
     * @return redacted text, never {@code null}
     */
    public static String redactText(final String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        safe = safe.replace("J7M_SENTINEL_SECRET_TOKEN", REDACTED);
        safe = safe.replace("J7M_SENTINEL_PASSWORD", REDACTED);
        safe = safe.replace("J7M_SENTINEL_API_KEY", REDACTED);
        safe = replaceIgnoreCase(safe, "Bearer " + REDACTED, REDACTED);
        safe = replaceIgnoreCase(safe, "Bearer J7M_SENTINEL_SECRET_TOKEN", "Bearer " + REDACTED);
        safe = replaceIgnoreCase(safe, "token=J7M_SENTINEL_SECRET_TOKEN", "token=" + REDACTED);
        safe = replaceIgnoreCase(safe, "password=J7M_SENTINEL_PASSWORD", "password=" + REDACTED);
        safe = replaceIgnoreCase(safe, "apiKey=J7M_SENTINEL_API_KEY", "apiKey=" + REDACTED);
        safe = replaceIgnoreCase(safe, "api_key=J7M_SENTINEL_API_KEY", "api_key=" + REDACTED);
        return safe;
    }

    /**
     * Redacts a metadata map.
     *
     * @param values metadata map supplied by an adapter or TCK probe
     * @return immutable metadata map with sensitive keys and values redacted
     */
    public static Map<String, String> redactMetadata(final Map<String, String> values) {
        return AdapterValues.safeMap(values);
    }

    /**
     * Returns whether public text still contains a configured sensitive sentinel.
     *
     * @param value public value to inspect
     * @return {@code true} when a sensitive sentinel remains visible
     */
    public static boolean publicTextLeaksSensitiveValue(final Object value) {
        return containsSensitiveValue(String.valueOf(value));
    }

    /**
     * Returns whether public text contains the redaction marker.
     *
     * <p>The TCK uses this signal on scenario and failure-mode report metadata to
     * reject adapters that attempted to place sensitive data in compatibility
     * evidence. The marker itself is safe for callers.</p>
     *
     * @param value public value to inspect
     * @return {@code true} when the redaction marker is present
     */
    public static boolean containsRedactionMarker(final Object value) {
        return String.valueOf(value).contains(REDACTED);
    }

    private static String replaceIgnoreCase(
            final String input,
            final String target,
            final String replacement) {
        final String lowerInput = input.toLowerCase(Locale.ROOT);
        final String lowerTarget = target.toLowerCase(Locale.ROOT);
        final int start = lowerInput.indexOf(lowerTarget);
        if (start < 0) {
            return input;
        }
        return input.substring(0, start) + replacement + input.substring(start + target.length());
    }
}
