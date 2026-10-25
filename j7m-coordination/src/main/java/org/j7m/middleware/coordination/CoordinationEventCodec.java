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

package org.j7m.middleware.coordination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class CoordinationEventCodec {
    static final String SCHEMA_VERSION = "1";
    private static final Set<String> KNOWN_TYPES = Set.of(
            "MEMBER_JOINED",
            "MEMBER_LEFT",
            "HEARTBEAT_RECORDED",
            "MEMBER_LIVENESS_CHANGED",
            "LEASE_GRANTED",
            "LEASE_RENEWED",
            "LEASE_RELEASED",
            "LEASE_EXPIRED",
            "CAS_APPLIED",
            "SPLIT_BRAIN_SUSPECTED",
            "COORDINATION_CHECKPOINT");

    private CoordinationEventCodec() {
    }

    static byte[] encode(final CoordinationEvent event) {
        StringBuilder builder = new StringBuilder(256);
        builder.append("version=").append(encodeValue(SCHEMA_VERSION)).append('\n');
        builder.append("type=").append(encodeValue(event.type())).append('\n');
        event.fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(entry.getKey()).append('=').append(encodeValue(entry.getValue())).append('\n'));
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    static CoordinationEvent decode(final byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        String version = "";
        String type = "";
        String[] lines = text.split("\\n");
        for (String line : lines) {
            if (!line.isEmpty()) {
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalArgumentException("invalid coordination event");
                }
                String key = line.substring(0, separator);
                String value = decodeValue(line.substring(separator + 1));
                if ("version".equals(key)) {
                    version = value;
                } else if ("type".equals(key)) {
                    type = value;
                } else {
                    fields.put(key, value);
                }
            }
        }
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("unsupported coordination event version");
        }
        if (!KNOWN_TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown coordination event type");
        }
        return new CoordinationEvent(type, fields);
    }

    static EventMetadata metadata(final byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String version = "unknown";
        String type = "unknown";
        String[] lines = text.split("\\n");
        for (String line : lines) {
            if (!line.isEmpty()) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    String key = line.substring(0, separator);
                    String value = safeDecodeValue(line.substring(separator + 1));
                    if ("version".equals(key)) {
                        version = value;
                    } else if ("type".equals(key)) {
                        type = value;
                    }
                }
            }
        }
        return new EventMetadata(version, type);
    }

    static String encodeBytes(final byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
    }

    static byte[] decodeBytes(final String value) {
        return Base64.getDecoder().decode(required("bytes", value));
    }

    static String encodeStringMap(final Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(values.size() * 32);
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder
                        .append(encodeValue(entry.getKey()))
                        .append(':')
                        .append(encodeValue(entry.getValue()))
                        .append(';'));
        return builder.toString();
    }

    static Map<String, String> decodeStringMap(final String value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, String> decoded = new LinkedHashMap<>();
        String[] entries = value.split(";");
        for (String entry : entries) {
            if (!entry.isEmpty()) {
                int separator = entry.indexOf(':');
                if (separator <= 0 || separator == entry.length() - 1) {
                    throw new IllegalArgumentException("invalid coordination event metadata");
                }
                decoded.put(decodeValue(entry.substring(0, separator)), decodeValue(entry.substring(separator + 1)));
            }
        }
        return Map.copyOf(decoded);
    }

    private static String encodeValue(final String value) {
        String safe = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeValue(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String safeDecodeValue(final String value) {
        try {
            return decodeValue(value);
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    static String required(final String key, final String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("coordination event missing required field");
        }
        return value;
    }

    record EventMetadata(String version, String type) {
        EventMetadata {
            version = version == null || version.isBlank() ? "unknown" : version;
            type = type == null || type.isBlank() ? "unknown" : type;
        }
    }
}
