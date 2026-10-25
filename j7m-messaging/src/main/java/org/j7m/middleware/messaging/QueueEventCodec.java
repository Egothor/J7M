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

package org.j7m.middleware.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class QueueEventCodec {
    static final String SCHEMA_VERSION = "2";
    private static final String LEGACY_SCHEMA_VERSION = "1";
    private static final int MAX_EVENT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FIELDS = 96;
    private static final int MAX_FIELD_BYTES = 2 * 1024 * 1024;
    private static final Set<String> KNOWN_TYPES = Set.of(
            "MESSAGE_ENQUEUED",
            "MESSAGE_LEASED",
            "MESSAGE_ACKED",
            "MESSAGE_NACKED",
            "MESSAGE_DEAD_LETTERED",
            "OUTBOX_RECORDED",
            "OUTBOX_PUBLISHED",
            "OUTBOX_FAILED",
            "INBOX_RECORDED",
            "INBOX_DUPLICATE_SUPPRESSED",
            "CHECKPOINT");

    private QueueEventCodec() {
    }

    static byte[] encode(final QueueEvent event) {
        StringBuilder builder = new StringBuilder(256);
        builder.append("version=").append(encodeValue(SCHEMA_VERSION)).append('\n');
        builder.append("type=").append(encodeValue(event.type())).append('\n');
        TreeMap<String, String> sorted = new TreeMap<>(event.fields());
        validateSchema(SCHEMA_VERSION, event.type(), sorted);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            validateKey(entry.getKey());
            builder.append(entry.getKey()).append('=').append(encodeValue(entry.getValue())).append('\n');
        }
        byte[] encoded = builder.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("messaging event size is invalid");
        }
        return encoded;
    }

    static int maxEncodedEventBytes() {
        return MAX_EVENT_BYTES;
    }

    static int encodedSize(final QueueEvent event) {
        long size = encodedSize(event, MAX_EVENT_BYTES);
        if (size > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("messaging event size is invalid");
        }
        return (int) size;
    }

    static long encodedSize(final QueueEvent event, final long limit) {
        if (event == null || limit < 0L) {
            throw new IllegalArgumentException("messaging event size is invalid");
        }
        TreeMap<String, String> sorted = new TreeMap<>(event.fields());
        validateSchema(SCHEMA_VERSION, event.type(), sorted);
        long size = 0L;
        size = addEncodedLineSize(size, "version", SCHEMA_VERSION, limit);
        size = addEncodedLineSize(size, "type", event.type(), limit);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            validateKey(entry.getKey());
            size = addEncodedLineSize(size, entry.getKey(), entry.getValue(), limit);
            if (size > limit) {
                return limit + 1L;
            }
        }
        return size;
    }

    static Set<String> knownTypes() {
        return KNOWN_TYPES;
    }

    static QueueEvent decode(final byte[] bytes) {
        Map<String, String> parsed = parseLines(bytes, true);
        String version = "";
        String type = "";
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            if ("type".equals(entry.getKey())) {
                type = entry.getValue();
            } else if ("version".equals(entry.getKey())) {
                version = entry.getValue();
            } else {
                fields.put(entry.getKey(), entry.getValue());
            }
        }
        if (!SCHEMA_VERSION.equals(version) && !LEGACY_SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("unsupported messaging event version");
        }
        if (!KNOWN_TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown messaging event type");
        }
        validateSchema(version, type, fields);
        return new QueueEvent(type, fields);
    }

    static EventMetadata metadata(final byte[] bytes) {
        String version = "unknown";
        String type = "unknown";
        try {
            Map<String, String> parsed = parseLines(bytes, false);
            String parsedVersion = parsed.get("version");
            String parsedType = parsed.get("type");
            if (parsedVersion != null) {
                version = parsedVersion;
            }
            if (parsedType != null) {
                type = parsedType;
            }
        } catch (RuntimeException exception) {
            return new EventMetadata(version, type);
        }
        return new EventMetadata(version, type);
    }

    static String encodeStringMap(final Map<String, String> values) {
        StringBuilder builder = new StringBuilder(128);
        TreeMap<String, String> safeValues = new TreeMap<>(values == null ? Map.of() : values);
        for (Map.Entry<String, String> entry : safeValues.entrySet()) {
            builder.append(encodeValue(entry.getKey()))
                    .append('=')
                    .append(encodeValue(entry.getValue()))
                    .append('\n');
        }
        return builder.toString();
    }

    static Map<String, String> decodeStringMap(final String value) {
        Map<String, String> decoded = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) {
            return decoded;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int position = 0;
        int fields = 0;
        while (position < bytes.length) {
            int lineStart = position;
            while (position < bytes.length && bytes[position] != '\n') {
                position++;
            }
            if (position == lineStart) {
                position++;
                continue;
            }
            int separator = -1;
            for (int index = lineStart; index < position; index++) {
                if (bytes[index] == '=') {
                    separator = index;
                    break;
                }
            }
            if (separator <= lineStart) {
                throw new IllegalArgumentException("invalid messaging metadata");
            }
            fields++;
            if (fields > MAX_FIELDS) {
                throw new IllegalArgumentException("messaging metadata field limit exceeded");
            }
            String key = decodeValue(strictUtf8(bytes, lineStart, separator - lineStart));
            String decodedValue = decodeValue(strictUtf8(bytes, separator + 1, position - separator - 1));
            if (decoded.putIfAbsent(key, decodedValue) != null) {
                throw new IllegalArgumentException("duplicate messaging metadata key");
            }
            position++;
        }
        return decoded;
    }

    private static String encodeValue(final String value) {
        String safe = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeValue(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length > MAX_FIELD_BYTES) {
                throw new IllegalArgumentException("messaging event field is too large");
            }
            return strictUtf8(decoded, 0, decoded.length);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid messaging event base64 field", exception);
        }
    }

    private static Map<String, String> parseLines(final byte[] bytes, final boolean rejectDuplicate) {
        if (bytes == null || bytes.length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("messaging event size is invalid");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        int physicalFields = 0;
        int position = 0;
        while (position < bytes.length) {
            int lineStart = position;
            while (position < bytes.length && bytes[position] != '\n') {
                position++;
            }
            if (position == lineStart) {
                position++;
                continue;
            }
            int separator = -1;
            for (int index = lineStart; index < position; index++) {
                if (bytes[index] == '=') {
                    separator = index;
                    break;
                }
            }
            if (separator <= lineStart) {
                throw new IllegalArgumentException("invalid messaging event line");
            }
            physicalFields++;
            if (physicalFields > MAX_FIELDS) {
                throw new IllegalArgumentException("messaging event field limit exceeded");
            }
            String key = strictUtf8(bytes, lineStart, separator - lineStart);
            validateKey(key);
            String encodedValue = strictUtf8(bytes, separator + 1, position - separator - 1);
            String value = decodeValue(encodedValue);
            if (rejectDuplicate && fields.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate messaging event field");
            }
            if (!rejectDuplicate) {
                fields.putIfAbsent(key, value);
            }
            position++;
        }
        return fields;
    }

    private static long addEncodedLineSize(final long current, final String key, final String value, final long limit) {
        long size = current;
        size = addBounded(size, utf8Length(key), limit);
        size = addBounded(size, 1L, limit);
        size = addBounded(size, base64UrlWithoutPaddingLength(utf8Length(value == null ? "" : value)), limit);
        size = addBounded(size, 1L, limit);
        return size;
    }

    private static long addBounded(final long current, final long delta, final long limit) {
        if (current > limit || delta > limit || Long.MAX_VALUE - current < delta) {
            return limit + 1L;
        }
        long result = current + delta;
        return result > limit ? limit + 1L : result;
    }

    private static long base64UrlWithoutPaddingLength(final long byteLength) {
        long groups = byteLength / 3L;
        long remainder = byteLength - (groups * 3L);
        long result = groups * 4L;
        if (remainder == 1L) {
            result += 2L;
        } else if (remainder == 2L) {
            result += 3L;
        }
        return result;
    }

    private static long utf8Length(final String value) {
        String safe = value == null ? "" : value;
        long bytes = 0L;
        for (int index = 0; index < safe.length(); index++) {
            char current = safe.charAt(index);
            if (current <= 0x7F) {
                bytes++;
            } else if (current <= 0x7FF) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 < safe.length() && Character.isLowSurrogate(safe.charAt(index + 1))) {
                    bytes += 4L;
                    index++;
                } else {
                    bytes++;
                }
            } else if (Character.isLowSurrogate(current)) {
                bytes++;
            } else {
                bytes += 3L;
            }
        }
        return bytes;
    }

    private static String strictUtf8(final byte[] bytes, final int offset, final int length) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("malformed messaging event utf-8", exception);
        }
    }

    private static void validateKey(final String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("invalid messaging event field name");
        }
        if ("type".equals(key) || "version".equals(key)) {
            return;
        }
        for (int index = 0; index < key.length(); index++) {
            char current = key.charAt(index);
            if (!((current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '_' || current == '-' || current == '.')) {
                throw new IllegalArgumentException("invalid messaging event field name");
            }
        }
    }

    private static void validateSchema(final String version, final String type, final Map<String, String> fields) {
        if (!KNOWN_TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown messaging event type");
        }
        if (LEGACY_SCHEMA_VERSION.equals(version)
                && ("MESSAGE_ACKED".equals(type) || "MESSAGE_NACKED".equals(type) || "MESSAGE_DEAD_LETTERED".equals(type))) {
            throw new IllegalArgumentException("unsupported legacy messaging event owner schema");
        }
        Set<String> allowed = switch (type) {
            case "MESSAGE_ENQUEUED" -> Set.of("queue", "messageId", "partition", "sequence", "priority",
                    "notBefore", "headers", "payload", "idempotencyKey", "dedupeKey");
            case "MESSAGE_LEASED" -> Set.of("queue", "messageId", "leaseId", "consumerId", "leaseUntil", "consumerGroup");
            case "MESSAGE_ACKED" -> Set.of("queue", "messageId", "leaseId", "consumerId");
            case "MESSAGE_NACKED", "MESSAGE_DEAD_LETTERED" -> Set.of("queue", "messageId", "retryCount", "notBefore",
                    "reason", "leaseId", "consumerId");
            case "OUTBOX_RECORDED" -> Set.of("entryId", "queue", "payload", "headers", "priority", "partition",
                    "orderingKey", "idempotencyKey", "dedupeKey", "notBefore", "createdAt");
            case "OUTBOX_PUBLISHED" -> Set.of("entryId");
            case "OUTBOX_FAILED" -> Set.of("entryId", "attempts", "failed");
            case "INBOX_RECORDED" -> Set.of("dedupeKey", "result", "recordedAt");
            case "INBOX_DUPLICATE_SUPPRESSED" -> Set.of("dedupeKey", "decision");
            case "CHECKPOINT" -> Set.of("checkpointVersion", "eventCount", "checksum", "body");
            default -> throw new IllegalArgumentException("unknown messaging event type");
        };
        for (String key : fields.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown messaging event field");
            }
        }
        for (String key : allowed) {
            if (!fields.containsKey(key)) {
                throw new IllegalArgumentException("messaging event missing required field");
            }
        }
    }

    record EventMetadata(String version, String type) {
        EventMetadata {
            version = version == null || version.isBlank() ? "unknown" : version;
            type = type == null || type.isBlank() ? "unknown" : type;
        }
    }
}
