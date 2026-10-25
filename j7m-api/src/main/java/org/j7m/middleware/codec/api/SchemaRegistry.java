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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Immutable allowlist of application-owned schemas; safe for concurrent codec calls.
 */
public final class SchemaRegistry {
    private final List<SchemaDescriptor<?>> descriptors;

    /**
     * Creates a registry and rejects duplicate registrations and incompatible adjacent versions.
     * Adding/removing optional fields is compatible; field types and required fields cannot change.
     * @param descriptors explicitly trusted schema definitions; may be empty
     * @throws IllegalArgumentException for duplicate or incompatible schemas
     */
    public SchemaRegistry(final List<SchemaDescriptor<?>> descriptors) {
        this.descriptors = List.copyOf(descriptors);
        for (int i = 0; i < this.descriptors.size(); i++) {
            SchemaDescriptor<?> a = this.descriptors.get(i);
            for (int j = i + 1; j < this.descriptors.size(); j++) {
                SchemaDescriptor<?> b = this.descriptors.get(j);
                if (a.javaType().equals(b.javaType()) || (a.schemaId().equals(b.schemaId()) && a.version() == b.version())) {
                    throw new IllegalArgumentException("duplicate schema registration");
                }
                if (a.schemaId().equals(b.schemaId())) {
                    if (Math.abs(a.version() - b.version()) != 1) {
                        throw new IllegalArgumentException("only current and previous schema versions are supported");
                    }
                    checkCompatible(a, b);
                }
            }
        }
    }

    /**
     * Finds an explicitly trusted class without class loading.
     * @param type exact registered class
     * @return descriptor or null when unregistered
     */
    public SchemaDescriptor<?> forType(final Class<?> type) {
        return this.descriptors.stream().filter(d -> d.javaType().equals(type)).findFirst().orElse(null);
    }

    /**
     * Finds an explicitly registered wire version.
     * @param id stable schema identifier
     * @param version positive version
     * @return descriptor or null when unregistered
     */
    public SchemaDescriptor<?> forVersion(final String id, final int version) {
        return this.descriptors.stream().filter(d -> d.schemaId().equals(id) && d.version() == version).findFirst().orElse(null);
    }

    /**
     * Checks symmetric compatibility before decoding or registration, without invoking trusted functions.
     * @param a first descriptor
     * @param b second descriptor
     * @throws IllegalArgumentException if fields are incompatible
     */
    public static void checkCompatible(final SchemaDescriptor<?> a, final SchemaDescriptor<?> b) {
        if (!Objects.equals(a.schemaId(), b.schemaId()) || Math.abs(a.version() - b.version()) > 1) {
            throw new IllegalArgumentException("incompatible schema version");
        }
        Map<String, SchemaField> right = b.fields().stream().collect(Collectors.toMap(SchemaField::name, Function.identity()));
        for (SchemaField left : a.fields()) {
            SchemaField field = right.get(left.name());
            if (field == null ? left.required() : (!left.type().equals(field.type()) || left.required() != field.required())) {
                throw new IllegalArgumentException("incompatible schema field: " + left.name());
            }
        }
        for (SchemaField field : b.fields()) {
            if (field.required() && a.fields().stream().noneMatch(f -> f.name().equals(field.name()))) {
                throw new IllegalArgumentException("missing required schema field: " + field.name());
            }
        }
    }
}
