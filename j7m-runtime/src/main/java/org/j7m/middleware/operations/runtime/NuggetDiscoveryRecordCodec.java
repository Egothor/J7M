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

package org.j7m.middleware.operations.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.operations.api.NuggetDiscovery;

final class NuggetDiscoveryRecordCodec {
    static final String VERSION = "j7m-nugget-discovery-v1";
    static final int MAX_RECORD_BYTES = 4096;
    static final int MAX_LINE_BYTES = 512;
    static final int MAX_CAPABILITIES = 32;
    private static final List<String> V1_FIELDS = List.of(
            "version",
            "nuggetId",
            "nuggetName",
            "runtimeId",
            "transport",
            "scheme",
            "host",
            "port",
            "localId",
            "protocolMinimum",
            "protocolMaximum",
            "capabilities",
            "processId",
            "hostSummary",
            "startedAt",
            "lastSeenAt",
            "expiresAt",
            "source",
            "status");
    private static final Set<String> V1_FIELD_SET = Set.copyOf(V1_FIELDS);

    private NuggetDiscoveryRecordCodec() {
    }

    static byte[] encode(final NuggetDiscovery.Record record) {
        StringBuilder builder = new StringBuilder(1024);
        append(builder, "version", VERSION);
        append(builder, "nuggetId", record.nuggetId());
        append(builder, "nuggetName", record.nuggetName());
        append(builder, "runtimeId", record.runtimeId());
        append(builder, "transport", record.endpoint().transport());
        append(builder, "scheme", record.endpoint().scheme());
        append(builder, "host", record.endpoint().host());
        append(builder, "port", Integer.toString(record.endpoint().port()));
        append(builder, "localId", record.endpoint().localId());
        append(builder, "protocolMinimum", Integer.toString(record.protocolMinimum()));
        append(builder, "protocolMaximum", Integer.toString(record.protocolMaximum()));
        append(builder, "capabilities", String.join(",", record.capabilities()));
        append(builder, "processId", record.processId());
        append(builder, "hostSummary", record.hostSummary());
        append(builder, "startedAt", record.startedAt().toString());
        append(builder, "lastSeenAt", record.lastSeenAt().toString());
        append(builder, "expiresAt", record.expiresAt().toString());
        append(builder, "source", record.source().name());
        append(builder, "status", record.status().name());
        byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("discovery record outside policy");
        }
        return bytes;
    }

    static NuggetDiscovery.Record decode(final String text, final Instant now) {
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("discovery record rejected");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = text.split("\\R", -1);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                throw new IllegalArgumentException("discovery record rejected");
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("discovery record rejected");
            }
            String key = line.substring(0, equals);
            if (!V1_FIELD_SET.contains(key) || fields.containsKey(key)) {
                throw new IllegalArgumentException("discovery record rejected");
            }
            fields.put(key, decodeValue(line.substring(equals + 1)));
        }
        if (!fields.keySet().equals(new LinkedHashSet<>(V1_FIELDS))) {
            throw new IllegalArgumentException("discovery record rejected");
        }
        if (!VERSION.equals(fields.get("version"))) {
            throw new IllegalArgumentException("discovery record rejected");
        }
        NuggetDiscovery.EndpointDescriptor endpoint = new NuggetDiscovery.EndpointDescriptor(
                fields.get("transport"),
                fields.get("scheme"),
                fields.get("host"),
                parsePort(fields.get("port")),
                fields.get("localId"));
        Instant expiresAt = parseInstant(fields.get("expiresAt"));
        NuggetDiscovery.Status status = expiresAt.isAfter(now) ? NuggetDiscovery.Status.ACTIVE : NuggetDiscovery.Status.STALE;
        return new NuggetDiscovery.Record(
                fields.get("nuggetId"),
                fields.get("nuggetName"),
                fields.get("runtimeId"),
                endpoint,
                parseInt(fields.get("protocolMinimum")),
                parseInt(fields.get("protocolMaximum")),
                NuggetDiscovery.Compatibility.COMPATIBLE,
                capabilities(fields.get("capabilities")),
                fields.get("processId"),
                fields.get("hostSummary"),
                parseInstant(fields.get("startedAt")),
                parseInstant(fields.get("lastSeenAt")),
                expiresAt,
                NuggetDiscovery.Source.LOCAL_RECORD,
                status,
                List.of());
    }

    private static void append(final StringBuilder builder, final String key, final String value) {
        builder.append(key).append('=').append(encodeValue(value)).append('\n');
    }

    private static String encodeValue(final String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeValue(final String value) {
        if (value == null || value.length() > 2048) {
            throw new IllegalArgumentException("discovery record rejected");
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static int parseInt(final String value) {
        if (value == null || value.isBlank() || value.length() > 8 || value.startsWith("+") || value.startsWith("-")) {
            throw new IllegalArgumentException("discovery record rejected");
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new IllegalArgumentException("discovery record rejected");
            }
        }
        return Integer.parseInt(value);
    }

    private static int parsePort(final String value) {
        if ("-1".equals(value)) {
            return -1;
        }
        return parseInt(value);
    }

    private static Instant parseInstant(final String value) {
        if (value == null || value.length() > 64) {
            throw new IllegalArgumentException("discovery record rejected");
        }
        return Instant.parse(value);
    }

    private static List<String> capabilities(final String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            if (item.isBlank() || item.length() > 64 || values.size() >= MAX_CAPABILITIES || !seen.add(item)) {
                throw new IllegalArgumentException("discovery record rejected");
            }
            values.add(item);
        }
        return List.copyOf(values);
    }
}
