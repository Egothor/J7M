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

package org.j7m.middleware.codec.api;

import java.io.Serializable;
import java.util.Map;

/**
 * Transport-neutral encoded value.
 *
 * @param kind codec-specific value kind such as {@code null}, {@code scalar}, or {@code object-ref}
 * @param typeName Java type or descriptor name
 * @param value immutable or transport-owned payload
 * @param metadata public codec metadata
 */
public record EncodedValue(String kind, String typeName, Object value, Map<String, String> metadata) implements Serializable {
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_METADATA_BYTES = 8 * 1024;

    /**
     * Creates an encoded value with immutable metadata.
     */
    public EncodedValue {
        kind = kind == null ? "null" : kind;
        typeName = typeName == null ? "java.lang.Object" : typeName;
        metadata = boundedMetadata(metadata);
    }

    private static Map<String, String> boundedMetadata(final Map<String, String> candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return Map.of();
        }
        if (candidate.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("encoded value metadata entry count exceeds 32");
        }
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : candidate.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("encoded value metadata key must not be blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("encoded value metadata value must not be null");
            }
            totalBytes += utf8Bytes(key) + utf8Bytes(value);
            if (totalBytes > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("encoded value metadata exceeds 8192 bytes");
            }
        }
        return Map.copyOf(candidate);
    }

    private static int utf8Bytes(final String value) {
        int total = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                total++;
            } else if (character <= 0x7ff) {
                total += 2;
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                total += 4;
                index++;
            } else {
                total += 3;
            }
        }
        return total;
    }
}
