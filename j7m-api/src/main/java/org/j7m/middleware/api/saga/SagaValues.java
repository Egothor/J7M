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

package org.j7m.middleware.api.saga;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class SagaValues {
    private SagaValues() {
    }

    static String id(final String name, final String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank() || text.length() > 160 || text.contains("/") || text.contains("\\") || text.contains("..")) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return text;
    }

    static String code(final String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank() || text.length() > 128) {
            throw new IllegalArgumentException("invalid saga code");
        }
        return text;
    }

    static Map<String, String> safeMap(final Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, String> output = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            String safeKey = key == null ? "" : key.trim();
            if (!safeKey.isBlank() && safeKey.length() <= 128) {
                String safeValue = value == null ? "" : value.trim();
                output.put(safeKey, sensitiveKey(safeKey) ? "[REDACTED]" : truncate(safeValue));
            }
        });
        return Map.copyOf(output);
    }

    static String safeText(final String value) {
        return truncate(value == null ? "" : value.trim());
    }

    private static String truncate(final String value) {
        return value.length() > 256 ? value.substring(0, 256) : value;
    }

    private static boolean sensitiveKey(final String key) {
        String normalized = key.replace("-", "").replace("_", "").replace(".", "")
                .toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.equals("pwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("accesskey")
                || normalized.contains("privatekey")
                || normalized.contains("credential")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("bearer")
                || normalized.contains("endpointcredential");
    }
}
