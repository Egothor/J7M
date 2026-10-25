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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.codec.api.*;
import org.junit.jupiter.api.Test;

class SchemaRegistryTest {
    private SchemaDescriptor<String> schema(String id, int version) {
        return new SchemaDescriptor<>(id, version, String.class, List.of(new SchemaField("value", String.class, true, null)),
                s -> Map.of("value", s), f -> (String) f.get("value"));
    }
    @Test void lookupsReturnOnlyExplicitRegistrationsAndEmptyRegistryContainsNothing() {
        var schema = schema("text", 1);
        var registry = new SchemaRegistry(List.of(schema));
        assertSame(schema, registry.forType(String.class));
        assertSame(schema, registry.forVersion("text", 1));
        assertNull(registry.forType(Integer.class));
        assertNull(registry.forVersion("text", 2));
        assertNull(registry.forVersion("unknown", 1));
        var empty = new SchemaRegistry(List.of());
        assertNull(empty.forType(String.class));
        assertNull(empty.forVersion("text", 1));
    }
    @Test void compatibilityRequiresSameSchemaAndAtMostOneVersionDifference() {
        assertDoesNotThrow(() -> SchemaRegistry.checkCompatible(schema("text", 1), schema("text", 1)));
        assertDoesNotThrow(() -> SchemaRegistry.checkCompatible(schema("text", 1), schema("text", 2)));
        assertThrows(IllegalArgumentException.class, () -> SchemaRegistry.checkCompatible(schema("text", 1), schema("text", 3)));
        assertThrows(IllegalArgumentException.class, () -> SchemaRegistry.checkCompatible(schema("text", 1), schema("other", 1)));
        assertThrows(IllegalArgumentException.class, () -> SchemaRegistry.checkCompatible(schema("text", 1),
                new SchemaDescriptor<>("text", 2, String.class, List.of(), s -> Map.of(), f -> "")));
    }
}
