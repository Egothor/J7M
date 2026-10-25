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

package org.j7m.middleware.isolation.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.j7m.middleware.api.isolation.StreamCapturePolicy;

/**
 * Internal redaction helper.
 */
public final class IsolationRedactor {
    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token", "apikey", "api_key", "accesskey",
            "privatekey", "credential", "authorization", "cookie", "session", "bearer", "endpointcredential");
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|authorization|cookie|session|bearer|endpointCredential)\\s*=\\s*[^\\s,;]+");

    /**
     * Creates a redactor.
     */
    public IsolationRedactor() {
    }

    /**
     * Returns whether a key is sensitive.
     *
     * @param key key value
     * @return {@code true} if sensitive
     */
    public boolean sensitiveKey(final String key) {
        if (key == null) {
            return false;
        }
        final String lower = key.replace("-", "").toLowerCase(Locale.ROOT);
        for (final String marker : SENSITIVE_KEYS) {
            if (lower.contains(marker.replace("_", ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Redacts a value by key.
     *
     * @param key key
     * @param value value
     * @return redacted or original value
     */
    public String redactValue(final String key, final String value) {
        if (this.sensitiveKey(key)) {
            return "<redacted>";
        }
        return value == null ? "" : this.redactText(value, StreamCapturePolicy.defaults());
    }

    /**
     * Redacts a map.
     *
     * @param input input map
     * @return redacted map
     */
    public Map<String, String> redactMap(final Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        final java.util.LinkedHashMap<String, String> output = new java.util.LinkedHashMap<>();
        input.forEach((key, value) -> output.put(key, this.redactValue(key, value)));
        return Map.copyOf(output);
    }

    /**
     * Redacts free text according to capture policy.
     *
     * @param text text
     * @param policy policy
     * @return redacted text
     */
    public String redactText(final String text, final StreamCapturePolicy policy) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        final StreamCapturePolicy actualPolicy = policy == null ? StreamCapturePolicy.defaults() : policy;
        if (actualPolicy.redactSensitivePatterns()) {
            result = ASSIGNMENT_PATTERN.matcher(result).replaceAll("$1=<redacted>");
            result = result.replaceAll("(?i)bearer\\s+[^\\s]+", "bearer <redacted>");
        }
        for (final String token : actualPolicy.additionalRedactionTokens()) {
            if (!token.isEmpty()) {
                result = result.replace(token, "<redacted>");
            }
        }
        return result;
    }

    /**
     * Redacts command arguments.
     *
     * @param command command list
     * @return redacted command summary
     */
    public String safeCommand(final List<String> command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        final List<String> safe = new ArrayList<>(command.size());
        boolean redactNext = false;
        for (final String item : command) {
            final String text = item == null ? "" : item;
            if (redactNext) {
                safe.add("<redacted>");
                redactNext = false;
                continue;
            }
            final int equals = text.indexOf('=');
            final String key = equals >= 0 ? text.substring(0, equals) : text;
            if (this.sensitiveKey(key)) {
                if (equals >= 0) {
                    safe.add(key + "=<redacted>");
                } else {
                    safe.add(text);
                    redactNext = true;
                }
            } else {
                safe.add(this.redactText(text, StreamCapturePolicy.defaults()));
            }
        }
        return String.join(" ", safe);
    }

    /**
     * Returns a safe path summary.
     *
     * @param path path
     * @param redact whether to redact
     * @return safe summary
     */
    public String pathSummary(final Path path, final boolean redact) {
        if (path == null) {
            return "";
        }
        if (redact) {
            final Path fileName = path.getFileName();
            return "<path:" + (fileName == null ? "root" : fileName.toString()) + ">";
        }
        return path.toAbsolutePath().normalize().toString();
    }
}
