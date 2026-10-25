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

package org.j7m.middleware.application.distributed;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.j7m.middleware.annotations.DataLocality;
import org.j7m.middleware.annotations.OrderedBy;
import org.j7m.middleware.annotations.ShardKey;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.codec.api.SchemaDescriptor;
import org.j7m.middleware.codec.api.SchemaRegistry;

/**
 * Validated immutable routing metadata; record extraction uses only the trusted A01 allowlist.
 */
final class PartitionRouting {
    private final Class<?> commandType;
    private final SchemaDescriptor<Object> schema;
    private final String selector;
    private final boolean explicit;
    private final String locality;

    @SuppressWarnings("unchecked")
    PartitionRouting(final ManagedPartitionDefinition<?> program, final SchemaRegistry schemas) {
        commandType = program.commandType();
        var selectors = new ArrayList<String>(); var ordering = new ArrayList<String>(); var hints = new ArrayList<String>();
        for (Class<?> type : hierarchy(program.type())) {
            var shard = type.getDeclaredAnnotation(ShardKey.class); if (shard != null) selectors.add(shard.value());
            var ordered = type.getDeclaredAnnotation(OrderedBy.class); if (ordered != null) ordering.add(ordered.value());
            var hint = type.getDeclaredAnnotation(DataLocality.class); if (hint != null) hints.add(hint.value());
            if (type != program.type() && (shard != null && !shard.equals(program.type().getDeclaredAnnotation(ShardKey.class))
                    || ordered != null && !ordered.equals(program.type().getDeclaredAnnotation(OrderedBy.class))
                    || hint != null && !hint.equals(program.type().getDeclaredAnnotation(DataLocality.class)))) throw invalid();
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(ShardKey.class) || method.isAnnotationPresent(OrderedBy.class)
                        || method.isAnnotationPresent(DataLocality.class)) throw invalid();
                for (int index = 0; index < method.getParameters().length; index++) {
                    var parameter = method.getParameters()[index].getAnnotation(ShardKey.class);
                    if (parameter == null) continue;
                    if (!method.getName().equals("apply") || method.getParameterCount() != 2 || index != 1
                            || method.getParameterTypes()[1] != commandType) throw invalid();
                    selectors.add(parameter.value());
                }
            }
        }
        if (selectors.stream().distinct().count() > 1 || ordering.stream().distinct().count() > 1 || hints.stream().distinct().count() > 1) throw invalid();
        explicit = !selectors.isEmpty(); selector = explicit ? selectors.getFirst() : "";
        locality = hints.isEmpty() ? "" : hints.getFirst();
        if (!locality.isEmpty() && !locality.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw invalid();
        if (!ordering.isEmpty() && (!explicit || !ordering.getFirst().equals(selector))) throw invalid();
        if (!explicit) { schema = null; return; }
        if (selector.isEmpty()) {
            if (!scalar(commandType)) throw invalid(); schema = null;
        } else {
            if (!selector.matches("[1-9][0-9]{0,8}")) throw invalid();
            schema = (SchemaDescriptor<Object>) schemas.forType(commandType);
            var field = schema.fields().stream().filter(value -> value.name().equals(selector)).findFirst().orElseThrow(PartitionRouting::invalid);
            if (!scalar(field.type()) || !field.required()) throw invalid();
        }
    }
    boolean explicit() { return explicit; }
    String locality() { return locality; }
    String key(final Object command) {
        if (!explicit) throw new IllegalStateException("Partition has no declared shard selector");
        try {
            Object value = schema == null ? command : schema.encoder().apply(command).get(selector);
            if (value == null || !scalar(value.getClass())) throw invalid();
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeUTF(value.getClass().getName());
            if (value instanceof byte[] binary) {
                if (binary.length > 65536) throw invalid(); out.writeInt(binary.length); out.write(binary);
            } else {
                String text = value.toString(); if (text.length() > 65536) throw invalid();
                out.writeInt(text.length()); for (int index = 0; index < text.length(); index++) out.writeChar(text.charAt(index));
            }
            out.flush(); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception failure) { throw invalid(); }
    }
    private static List<Class<?>> hierarchy(final Class<?> root) {
        var pending = new java.util.ArrayDeque<Class<?>>(); pending.add(root);
        var visited = new java.util.LinkedHashSet<Class<?>>();
        while (!pending.isEmpty()) {
            Class<?> next = pending.removeFirst();
            if (next == Object.class || !visited.add(next)) continue;
            if (visited.size() > 128) throw invalid();
            if (next.getSuperclass() != null) pending.add(next.getSuperclass());
            pending.addAll(List.of(next.getInterfaces()));
        }
        return List.copyOf(visited);
    }
    private static boolean scalar(final Class<?> type) {
        return type.isPrimitive() && type != void.class || List.of(String.class, Boolean.class, Character.class, Byte.class,
                Short.class, Integer.class, Long.class, Float.class, Double.class, byte[].class).contains(type);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid partition routing declaration or key"); }
}
