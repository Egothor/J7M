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

package org.j7m.middleware.application.repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Strict bounded JSON object reader for repository-owned metadata files.
 *
 * <p>The reader is immutable and thread-safe. It intentionally exposes only
 * string-valued top-level JSON objects, because repository metadata contracts
 * use explicit schema versions and string fields. The implementation checks the
 * file size before allocating a byte array, decodes UTF-8 with malformed-input
 * reporting, delegates JSON syntax, duplicate-key, nesting, and trailing-data
 * validation to {@link SimpleJson}, and rejects unknown, missing, {@code null},
 * and non-string fields.</p>
 *
 * <p>Returned maps are immutable snapshots. The reader does not log file
 * contents or include field values in exception messages.</p>
 */
public final class StrictMetadataJson {
    private StrictMetadataJson() {
    }

    /**
     * Reads and validates a schema-versioned string metadata object.
     *
     * @param path metadata path
     * @param limits metadata bounds
     * @param expectedSchemaVersion required schema version
     * @param requiredFields required field names, including {@code schemaVersion}
     * @param allowedFields complete allow-list of field names
     * @return immutable validated string map
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the document violates the requested
     * schema, bounds, UTF-8, JSON, field, or type contract
     */
    public static Map<String, String> readStringObject(
            final Path path,
            final ApplicationPlatformLimits limits,
            final String expectedSchemaVersion,
            final Set<String> requiredFields,
            final Set<String> allowedFields) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("metadata path is required");
        }
        ApplicationPlatformLimits effectiveLimits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("metadata file is not a regular file");
        }
        long size = Files.size(path);
        if (size > effectiveLimits.maxMetadataBytes()) {
            throw new IllegalArgumentException("metadata file exceeds configured limit");
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("metadata file exceeds supported in-memory size");
        }
        byte[] bytes = Files.readAllBytes(path);
        String text = decodeUtf8(bytes);
        return parseStringObject(text, effectiveLimits, expectedSchemaVersion, requiredFields, allowedFields);
    }

    /**
     * Parses and validates a schema-versioned string metadata object from text.
     *
     * @param text UTF-16 Java text already decoded from an explicitly selected
     * charset
     * @param limits metadata bounds
     * @param expectedSchemaVersion required schema version, or {@code null} to
     * skip schema value validation
     * @param requiredFields required field names
     * @param allowedFields complete allow-list of field names
     * @return immutable validated string map
     * @throws IllegalArgumentException if the document violates the requested
     * schema, bounds, JSON, field, or type contract
     */
    public static Map<String, String> parseStringObject(
            final String text,
            final ApplicationPlatformLimits limits,
            final String expectedSchemaVersion,
            final Set<String> requiredFields,
            final Set<String> allowedFields) {
        ApplicationPlatformLimits effectiveLimits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        Object parsed = SimpleJson.parse(text, effectiveLimits);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("metadata JSON root must be an object");
        }
        Set<String> safeAllowedFields = allowedFields == null ? Set.of() : Set.copyOf(allowedFields);
        Set<String> safeRequiredFields = requiredFields == null ? Set.of() : Set.copyOf(requiredFields);
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !safeAllowedFields.contains(key)) {
                throw new IllegalArgumentException("metadata JSON contains an unsupported field");
            }
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("metadata JSON field must be a string");
            }
            values.put(key, value);
        }
        for (String required : safeRequiredFields) {
            String value = values.get(required);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("metadata JSON is missing a required field");
            }
        }
        String schemaVersion = values.get("schemaVersion");
        if (expectedSchemaVersion != null && !expectedSchemaVersion.equals(schemaVersion)) {
            throw new IllegalArgumentException("metadata JSON schema version is unsupported");
        }
        return Map.copyOf(values);
    }

    private static String decodeUtf8(final byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}
