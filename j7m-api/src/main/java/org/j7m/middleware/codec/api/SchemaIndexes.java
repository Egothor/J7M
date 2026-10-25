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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.lang.reflect.Modifier;
import org.j7m.middleware.annotations.SchemaType;
import org.j7m.middleware.annotations.WireField;

/**
 * Explicit trusted index assembly; validates all descriptors before any value factory is invoked.
 * Does not discover classes from wire names. Provider discovery executes trusted provider code.
 */
public final class SchemaIndexes {
    private SchemaIndexes() { }

    /**
     * Builds a bounded immutable allowlist and validates versions, duplicates and compatibility.
     * @param indexes explicit trusted providers, at most 256
     * @return validated registry containing at most 4096 schemas
     * @throws IllegalArgumentException on unsupported format, duplicate, incompatible or oversized input
     * @throws NullPointerException on null input or member
     */
    public static SchemaRegistry registry(final Collection<? extends SchemaIndex> indexes) {
        Objects.requireNonNull(indexes, "indexes");
        if (indexes.size() > 256) { throw new IllegalArgumentException("too many schema indexes"); }
        List<SchemaDescriptor<?>> schemas = new ArrayList<>();
        for (SchemaIndex index : indexes) {
            if (Objects.requireNonNull(index, "index").formatVersion() != 1) {
                throw new IllegalArgumentException("unsupported schema index format");
            }
            List<SchemaDescriptor<?>> descriptors = List.copyOf(index.schemas());
            if (descriptors.size() > 128 || schemas.size() + descriptors.size() > 4096) {
                throw new IllegalArgumentException("too many indexed schemas");
            }
            for (SchemaDescriptor<?> descriptor : descriptors) {
                if (!descriptor.schemaId().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                    throw new IllegalArgumentException("invalid indexed schema identifier");
                }
                try { validateDescriptor(descriptor); }
                catch (NumberFormatException exception) {
                    // Parser messages include the original configuration value; causes must not escape either.
                    throw new IllegalArgumentException("invalid indexed default");
                }
                schemas.add(descriptor);
            }
        }
        SchemaRegistry registry = new SchemaRegistry(schemas);
        for (SchemaDescriptor<?> descriptor : schemas) {
            for (SchemaField field : descriptor.fields()) {
                Class<?> type = field.type();
                if (!scalar(type) && type != byte[].class && registry.forType(type) == null) {
                    throw new IllegalArgumentException("unregistered indexed field type");
                }
            }
        }
        return registry;
    }

    private static void validateDescriptor(final SchemaDescriptor<?> descriptor) {
        Class<?> type = descriptor.javaType();
        SchemaType annotation = type.getAnnotation(SchemaType.class);
        if (!type.isRecord() || !Modifier.isPublic(type.getModifiers()) || type.getTypeParameters().length != 0
                || annotation == null || !annotation.id().equals(descriptor.schemaId())
                || annotation.version() != descriptor.version()
                || descriptor.fields().size() != type.getRecordComponents().length) {
            throw new IllegalArgumentException("indexed schema does not match its public contract");
        }
        for (var component : type.getRecordComponents()) {
            WireField wire = component.getAnnotation(WireField.class);
            if (wire == null || wire.id() <= 0 || wire.required() && wire.hasDefault()
                    || component.getType().isPrimitive() && !wire.required()) {
                throw new IllegalArgumentException("invalid indexed wire field");
            }
            SchemaField field = descriptor.fields().stream()
                    .filter(candidate -> candidate.name().equals(Integer.toString(wire.id()))).findFirst().orElse(null);
            if (field == null || field.required() != wire.required() || field.type() != boxed(component.getType())
                    || !Objects.equals(field.defaultValue(), defaultValue(wire, field.type()))) {
                throw new IllegalArgumentException("indexed field does not match its public contract");
            }
        }
    }

    private static boolean scalar(final Class<?> type) {
        return List.of(String.class, Boolean.class, Byte.class, Short.class, Integer.class,
                Long.class, Character.class, Float.class, Double.class).contains(type);
    }

    private static Object defaultValue(final WireField field, final Class<?> type) {
        if (!field.hasDefault()) {
            if (!field.defaultValue().isEmpty()) { throw new IllegalArgumentException("undeclared indexed default"); }
            return null;
        }
        String value = field.defaultValue();
        if (type == String.class) { return value; }
        if (type == Boolean.class && ("true".equals(value) || "false".equals(value))) { return Boolean.valueOf(value); }
        if (type == Character.class && value.length() == 1) { return value.charAt(0); }
        if (type == Byte.class) { return Byte.valueOf(value); }
        if (type == Short.class) { return Short.valueOf(value); }
        if (type == Integer.class) { return Integer.valueOf(value); }
        if (type == Long.class) { return Long.valueOf(value); }
        if (type == Float.class && Float.isFinite(Float.parseFloat(value))) { return Float.valueOf(value); }
        if (type == Double.class && Double.isFinite(Double.parseDouble(value))) { return Double.valueOf(value); }
        throw new IllegalArgumentException("invalid indexed default");
    }

    private static Class<?> boxed(final Class<?> type) {
        if (!type.isPrimitive()) { return type; }
        return java.util.Map.of(boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class,
                int.class, Integer.class, long.class, Long.class, char.class, Character.class,
                float.class, Float.class, double.class, Double.class).get(type);
    }

    /**
     * Loads providers from an explicitly trusted module layer; no global opens are needed.
     * @param layer trusted module layer, never null
     * @return validated allowlist
     * @throws IllegalArgumentException on invalid descriptors or unsupported index formats
     * @throws java.util.ServiceConfigurationError on invalid provider configuration
     */
    public static SchemaRegistry load(final ModuleLayer layer) {
        List<SchemaIndex> indexes = new ArrayList<>();
        for (SchemaIndex index : ServiceLoader.load(Objects.requireNonNull(layer, "layer"), SchemaIndex.class)) {
            if (indexes.size() >= 256) { throw new IllegalArgumentException("too many schema indexes"); }
            indexes.add(index);
        }
        return registry(indexes);
    }
}
