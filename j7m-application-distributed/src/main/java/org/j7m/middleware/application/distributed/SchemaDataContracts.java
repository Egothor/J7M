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

import java.util.List;
import org.j7m.middleware.codec.api.SchemaRegistry;

/**
 * Shared finite A01 scalar/record validation for generated partition and hosted work data.
 */
final class SchemaDataContracts {
    private SchemaDataContracts() { }
    static void validate(final List<Class<?>> types,final SchemaRegistry schemas,final boolean allowVoid) {
        var pending = new java.util.ArrayDeque<Class<?>>();
        pending.addAll(types);
        var visited = new java.util.HashSet<Class<?>>();
        var descriptors = new java.util.ArrayList<org.j7m.middleware.codec.api.SchemaDescriptor<?>>();
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (allowVoid && (type == void.class || type == Void.class)) continue;
            if (!visited.add(type)) continue;
            if (visited.size() > 4096) throw new IllegalArgumentException("Invalid generated schema data contract");
            if (type.isPrimitive() && type != void.class || java.util.List.of(String.class, Boolean.class, Character.class,
                    Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, byte[].class).contains(type)) continue;
            if (!type.isRecord() || !type.isAnnotationPresent(org.j7m.middleware.annotations.SchemaType.class)) throw new IllegalArgumentException("Invalid generated schema data contract");
            var descriptor = schemas.forType(type); if (descriptor == null) throw new IllegalArgumentException("Invalid generated schema data contract");
            descriptors.add(descriptor);
            for (var field : descriptor.fields()) pending.add(field.type());
        }
        var indexes = new java.util.ArrayList<org.j7m.middleware.codec.api.SchemaIndex>();
        for (int offset = 0; offset < descriptors.size(); offset += 128) {
            var chunk = java.util.List.copyOf(descriptors.subList(offset, Math.min(offset + 128, descriptors.size())));
            indexes.add(() -> chunk);
        }
        // Reuse A01 descriptor validation before any component construction or data callback.
        try { org.j7m.middleware.codec.api.SchemaIndexes.registry(indexes); }
        catch (RuntimeException invalidMetadata) { throw new IllegalArgumentException("Invalid generated schema data contract"); }
    }
}
