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

package org.j7m.middleware.application.replication;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.replication.RepositoryReplicationEventType;
import org.j7m.middleware.application.api.replication.RepositoryReplicationException;

final class RepositoryReplicationEventCodec {
    static final String SCHEMA_VERSION = "1";

    byte[] encode(final RepositoryReplicationEvent event) {
        StringBuilder builder = new StringBuilder(256);
        builder.append("schemaVersion=").append(encodeValue(SCHEMA_VERSION)).append('\n');
        builder.append("type=").append(encodeValue(event.type().name())).append('\n');
        event.fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(entry.getKey()).append('=').append(encodeValue(entry.getValue())).append('\n'));
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    RepositoryReplicationEvent decode(final byte[] bytes) {
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
            values.put(line.substring(0, separator), decodeValue(line.substring(separator + 1)));
        }
        String version = required(values, "schemaVersion", "decode", "unknown", "missing schema version");
        String typeText = required(values, "type", "decode", version, "missing event type");
        if (!SCHEMA_VERSION.equals(version)) {
            throw failure("decode", typeText, version, "unsupported schema version");
        }
        RepositoryReplicationEventType type;
        try {
            type = RepositoryReplicationEventType.valueOf(typeText);
        } catch (IllegalArgumentException exception) {
            throw failure("decode", typeText, version, "unknown event type");
        }
        values.remove("schemaVersion");
        values.remove("type");
        validateRequired(type, values, version);
        return new RepositoryReplicationEvent(type, values);
    }

    private static void validateRequired(
            final RepositoryReplicationEventType type,
            final Map<String, String> values,
            final String version) {
        switch (type) {
            case ARTIFACT_DIGEST_ADVERTISED, ARTIFACT_TRANSFER_FAILED ->
                    require(values, "coordinate", type.name(), version);
            case ARTIFACT_TRANSFER_COMPLETED -> {
                require(values, "coordinate", type.name(), version);
                require(values, "digest", type.name(), version);
                requireLong(values, "sizeBytes", type.name(), version);
            }
            case ARTIFACT_TRANSFER_STARTED -> {
                require(values, "transferId", type.name(), version);
                require(values, "coordinate", type.name(), version);
                require(values, "digest", type.name(), version);
                requireLong(values, "sizeBytes", type.name(), version);
            }
            case ARTIFACT_CHUNK_TRANSFERRED -> {
                require(values, "transferId", type.name(), version);
                require(values, "coordinate", type.name(), version);
                requireLong(values, "sequence", type.name(), version);
                requireLong(values, "totalChunks", type.name(), version);
                require(values, "chunkDigest", type.name(), version);
                requireLong(values, "bytes", type.name(), version);
            }
            case INDEX_SNAPSHOT_PUBLISHED -> {
                requireLong(values, "sequence", type.name(), version);
                require(values, "digest", type.name(), version);
                require(values, "indexEntries", type.name(), version);
            }
            case INDEX_DELTA_PUBLISHED -> {
                requireLong(values, "previousSequence", type.name(), version);
                requireLong(values, "nextSequence", type.name(), version);
                require(values, "operation", type.name(), version);
                require(values, "coordinate", type.name(), version);
                require(values, "digest", type.name(), version);
                requireLong(values, "sizeBytes", type.name(), version);
            }
            case PROMOTION_RECORDED, DEMOTION_RECORDED -> {
                require(values, "environment", type.name(), version);
                require(values, "coordinate", type.name(), version);
            }
            case ROLLBACK_RECORDED -> {
                require(values, "from", type.name(), version);
                require(values, "to", type.name(), version);
            }
            case AUDIT_CHAIN_RECORD_REPLICATED -> require(values, "currentHash", type.name(), version);
            case CONFLICT_DETECTED -> require(values, "conflictType", type.name(), version);
            default -> {
                // Remaining events are safe lifecycle markers.
            }
        }
    }

    private static String required(
            final Map<String, String> values,
            final String key,
            final String stage,
            final String version,
            final String message) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw failure(stage, values.getOrDefault("type", "unknown"), version, message);
        }
        return value;
    }

    private static void require(
            final Map<String, String> values,
            final String key,
            final String eventType,
            final String version) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw failure("validate", eventType, version, "missing required field: " + key);
        }
    }

    private static void requireLong(
            final Map<String, String> values,
            final String key,
            final String eventType,
            final String version) {
        require(values, key, eventType, version);
        try {
            Long.parseLong(values.get(key));
        } catch (NumberFormatException exception) {
            throw failure("validate", eventType, version, "malformed numeric field: " + key);
        }
    }

    private static RepositoryReplicationException failure(
            final String stage,
            final String eventType,
            final String version,
            final String message) {
        return new RepositoryReplicationException(new RemoteError(MiddlewareErrorCode.RECOVERY_FAILED,
                "repository replication event recovery failed",
                Map.of("stage", stage, "eventType", eventType, "schemaVersion", version,
                        "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name(), "reason", message),
                null, null, false, true));
    }

    private static String encodeValue(final String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeValue(final String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw failure("decode", "unknown", "unknown", "malformed encoded field");
        }
    }
}
