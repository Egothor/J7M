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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AdapterValues {
    static final String REDACTED = AdapterRedactionPolicy.REDACTED;
    static final int MAX_COLLECTION_ENTRIES = 256;
    static final int MAX_KEY_CHARS = 128;
    static final int MAX_TEXT_CHARS = 512;
    static final int MAX_AGGREGATE_TEXT_BYTES = 64 * 1024;

    private AdapterValues() {
    }

    static String requireId(final String value, final String name) {
        final String normalized = requireText(value, name);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must use letters, digits, dot, colon, dash, or underscore");
        }
        return normalized;
    }

    static String requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return requireBoundedText(value.trim(), name, MAX_TEXT_CHARS);
    }

    static <E extends Enum<E>> Set<E> immutableEnumSet(final Set<E> values, final String name) {
        Objects.requireNonNull(values, name);
        requireCollectionSize(values.size(), name);
        return Set.copyOf(values);
    }

    static <T> Set<T> immutableSet(final Set<T> values, final String name) {
        Objects.requireNonNull(values, name);
        requireCollectionSize(values.size(), name);
        return Set.copyOf(values);
    }

    static <T> List<T> immutableList(final List<T> values, final String name) {
        Objects.requireNonNull(values, name);
        requireCollectionSize(values.size(), name);
        return List.copyOf(values);
    }

    static List<String> safeTextList(final List<String> values, final String name) {
        Objects.requireNonNull(values, name);
        requireCollectionSize(values.size(), name);
        final ArrayList<String> safe = new ArrayList<>(values.size());
        long aggregateBytes = 0L;
        for (String value : values) {
            final String boundedValue = requireBoundedText(
                    Objects.requireNonNull(value, name + " value"),
                    name + " value",
                    MAX_TEXT_CHARS);
            aggregateBytes = requireAggregateBytes(aggregateBytes, boundedValue, name);
            safe.add(AdapterRedactionPolicy.redactText(boundedValue));
        }
        return List.copyOf(safe);
    }

    static <K, V> Map<K, V> immutableMap(final Map<K, V> values, final String name) {
        Objects.requireNonNull(values, name);
        requireCollectionSize(values.size(), name);
        return Map.copyOf(values);
    }

    static Map<String, String> safeMap(final Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        requireCollectionSize(values.size(), "metadata");
        final LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        long aggregateBytes = 0L;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            final String key = Objects.requireNonNull(entry.getKey(), "metadata key").trim();
            if (key.isBlank()) {
                throw new IllegalArgumentException("metadata key must not be blank");
            }
            final String safeKey = requireBoundedText(
                    key,
                    "metadata key",
                    MAX_KEY_CHARS);
            final String rawValue = entry.getValue() == null ? "" : entry.getValue();
            final String boundedValue = requireBoundedText(rawValue, "metadata value", MAX_TEXT_CHARS);
            aggregateBytes = requireAggregateBytes(aggregateBytes, safeKey, "metadata");
            aggregateBytes = requireAggregateBytes(aggregateBytes, boundedValue, "metadata");
            if (AdapterRedactionPolicy.isSensitiveKey(safeKey)) {
                safe.put(safeKey, REDACTED);
            } else {
                safe.put(safeKey, AdapterRedactionPolicy.redactText(boundedValue));
            }
        }
        return Map.copyOf(safe);
    }

    static boolean isSensitiveKey(final String key) {
        return AdapterRedactionPolicy.isSensitiveKey(key);
    }

    private static void requireCollectionSize(final int size, final String name) {
        if (size > MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException(name + " exceeds maximum entry count");
        }
    }

    private static String requireBoundedText(final String value, final String name, final int maximumChars) {
        if (value.length() > maximumChars) {
            throw new IllegalArgumentException(name + " exceeds maximum text length");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_AGGREGATE_TEXT_BYTES) {
            throw new IllegalArgumentException(name + " exceeds maximum encoded byte length");
        }
        return value;
    }

    private static long requireAggregateBytes(final long current, final String value, final String name) {
        final long next = current + (long) value.getBytes(StandardCharsets.UTF_8).length;
        if (next < current || next > MAX_AGGREGATE_TEXT_BYTES) {
            throw new IllegalArgumentException(name + " exceeds maximum aggregate encoded byte length");
        }
        return next;
    }
}
