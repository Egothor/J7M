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

package org.j7m.middleware.saga;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.saga.SagaException;

final class SagaEventCodec {
    static final String SCHEMA_VERSION = "1";
    private static final byte FIELD_SEPARATOR = (byte) '=';
    private static final byte LINE_SEPARATOR = (byte) '\n';
    private static final byte PADDING = (byte) '=';
    private static final byte[] TYPE_KEY = "type".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BODY_KEY = "body".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHECKPOINT_TYPE = encoded(SagaEventType.SAGA_CHECKPOINT.name())
            .getBytes(StandardCharsets.US_ASCII);

    byte[] encode(final SagaEvent event) {
        StringBuilder builder = new StringBuilder(256);
        builder.append("schemaVersion=").append(encoded(SCHEMA_VERSION)).append('\n');
        builder.append("type=").append(encoded(event.type().name())).append('\n');
        event.fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(entry.getKey()).append('=').append(encoded(entry.getValue())).append('\n'));
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    void requireCheckpointBodyWithinLimit(final byte[] bytes, final int maxCheckpointBytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        boolean checkpoint = false;
        int bodyValueStart = -1;
        int bodyValueEnd = -1;
        int lineStart = 0;
        while (lineStart < bytes.length) {
            int lineEnd = lineStart;
            while (lineEnd < bytes.length && bytes[lineEnd] != LINE_SEPARATOR) {
                lineEnd++;
            }
            int separator = lineStart;
            while (separator < lineEnd && bytes[separator] != FIELD_SEPARATOR) {
                separator++;
            }
            if (separator > lineStart && separator < lineEnd) {
                if (matches(bytes, lineStart, separator, TYPE_KEY)
                        && matches(bytes, separator + 1, lineEnd, CHECKPOINT_TYPE)) {
                    checkpoint = true;
                } else if (matches(bytes, lineStart, separator, BODY_KEY)) {
                    bodyValueStart = separator + 1;
                    bodyValueEnd = lineEnd;
                }
            }
            lineStart = lineEnd + 1;
        }
        if (checkpoint && bodyValueStart >= 0) {
            int decodedLength = decodedBase64Length(bytes, bodyValueStart, bodyValueEnd);
            if (decodedLength > maxCheckpointBytes) {
                throw failure("decode", SagaEventType.SAGA_CHECKPOINT.name(), SCHEMA_VERSION,
                        "saga checkpoint byte limit exceeded");
            }
        }
    }

    SagaEvent decode(final byte[] bytes) {
        Map<String, String> values = new LinkedHashMap<>();
        String text = new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw failure("decode", "unknown", "unknown", "malformed event field");
            }
            values.put(line.substring(0, separator), decoded(line.substring(separator + 1)));
        }
        String version = required(values, "schemaVersion", "decode", "unknown", "missing schema version");
        String typeText = required(values, "type", "decode", version, "missing event type");
        if (!SCHEMA_VERSION.equals(version)) {
            throw failure("decode", typeText, version, "unsupported schema version");
        }
        SagaEventType type;
        try {
            type = SagaEventType.valueOf(typeText);
        } catch (IllegalArgumentException exception) {
            throw failure("decode", typeText, version, "unknown event type");
        }
        values.remove("schemaVersion");
        values.remove("type");
        validate(type, values, version);
        return new SagaEvent(type, values);
    }

    private static void validate(final SagaEventType type, final Map<String, String> values, final String version) {
        switch (type) {
            case SAGA_DEFINITION_REGISTERED -> {
                require(values, "definitionId", type.name(), version);
                requireLong(values, "stepCount", type.name(), version);
            }
            case SAGA_STARTED -> {
                require(values, "executionId", type.name(), version);
                require(values, "definitionId", type.name(), version);
                require(values, "state", type.name(), version);
            }
            case SAGA_STEP_SCHEDULED, SAGA_STEP_STARTED, SAGA_STEP_SUCCEEDED, SAGA_STEP_FAILED, SAGA_STEP_TIMED_OUT,
                    SAGA_STEP_RETRY_SCHEDULED, SAGA_STEP_DEAD_LETTERED,
                    SAGA_COMPENSATION_SCHEDULED, SAGA_COMPENSATION_STARTED,
                    SAGA_COMPENSATION_SUCCEEDED, SAGA_COMPENSATION_FAILED,
                    SAGA_COMPENSATION_DEAD_LETTERED -> {
                require(values, "executionId", type.name(), version);
                require(values, "stepId", type.name(), version);
                requireLong(values, "attempts", type.name(), version);
            }
            case SAGA_COMPENSATION_RETRY_SCHEDULED -> {
                require(values, "executionId", type.name(), version);
                require(values, "stepId", type.name(), version);
                requireLong(values, "attempts", type.name(), version);
                requireLong(values, "nextAttemptAt", type.name(), version);
            }
            case SAGA_CANCEL_REQUESTED, SAGA_CANCELLED, SAGA_TIMED_OUT, SAGA_COMPLETED, SAGA_FAILED,
                    TRANSACTION_CONTEXT_PROPAGATED, SAGA_AUDIT_RECORDED -> require(values, "executionId", type.name(), version);
            case SAGA_CHECKPOINT -> {
                require(values, "checkpointVersion", type.name(), version);
                require(values, "checksum", type.name(), version);
                require(values, "body", type.name(), version);
            }
        }
    }

    private static void require(final Map<String, String> values, final String key, final String eventType, final String version) {
        if (!values.containsKey(key) || values.get(key).isBlank()) {
            throw failure("validate", eventType, version, "missing required field: " + key);
        }
    }

    static long requireLong(final Map<String, String> values, final String key, final String eventType, final String version) {
        require(values, key, eventType, version);
        try {
            return Long.parseLong(values.get(key));
        } catch (NumberFormatException exception) {
            throw failure("validate", eventType, version, "malformed numeric field: " + key);
        }
    }

    private static String required(
            final Map<String, String> values,
            final String key,
            final String stage,
            final String version,
            final String reason) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw failure(stage, values.getOrDefault("type", "unknown"), version, reason);
        }
        return value;
    }

    private static String encoded(final String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(final String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw failure("decode", "unknown", "unknown", "malformed encoded field");
        }
    }

    private static boolean matches(
            final byte[] bytes,
            final int start,
            final int end,
            final byte[] expected) {
        if (end - start != expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[start + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static int decodedBase64Length(final byte[] bytes, final int start, final int end) {
        int encodedLength = end - start;
        if (encodedLength == 0) {
            return 0;
        }
        if ((encodedLength & 3) != 0) {
            throw failure("decode", SagaEventType.SAGA_CHECKPOINT.name(), SCHEMA_VERSION, "malformed encoded field");
        }
        int padding = 0;
        if (bytes[end - 1] == PADDING) {
            padding++;
            if (encodedLength > 1 && bytes[end - 2] == PADDING) {
                padding++;
            }
        }
        long decoded = ((long) encodedLength / 4L) * 3L - padding;
        if (decoded > Integer.MAX_VALUE) {
            throw failure("decode", SagaEventType.SAGA_CHECKPOINT.name(), SCHEMA_VERSION,
                    "saga checkpoint byte limit exceeded");
        }
        return (int) decoded;
    }

    static SagaException failure(final String stage, final String eventType, final String version, final String reason) {
        return new SagaException(new RemoteError(MiddlewareErrorCode.RECOVERY_FAILED,
                "saga recovery failed",
                Map.of("stage", stage, "eventType", eventType, "schemaVersion", version,
                        "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name(), "reason", reason),
                null, null, false, true));
    }
}
