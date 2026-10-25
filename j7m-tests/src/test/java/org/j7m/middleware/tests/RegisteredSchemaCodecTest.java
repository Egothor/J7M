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
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.junit.jupiter.api.Test;

class RegisteredSchemaCodecTest {
    record V1(String name, String removed) {}
    record V2(String name, Integer added) {}

    private SchemaDescriptor<V1> oldSchema() {
        return new SchemaDescriptor<>("customer", 1, V1.class,
                List.of(new SchemaField("name", String.class, true, null), new SchemaField("removed", String.class, false, "legacy")),
                v -> Map.of("name", v.name(), "removed", v.removed()),
                f -> new V1((String) f.get("name"), (String) f.get("removed")));
    }
    private SchemaDescriptor<V2> newSchema(AtomicInteger calls) {
        return new SchemaDescriptor<>("customer", 2, V2.class,
                List.of(new SchemaField("name", String.class, true, null), new SchemaField("added", Integer.class, false, 42)),
                v -> Map.of("name", v.name(), "added", v.added()),
                f -> { calls.incrementAndGet(); return new V2((String) f.get("name"), (Integer) f.get("added")); });
    }
    @Test void evolvesBothDirectionsAndAppliesDefaults() {
        var oldCodec = new DefaultValueCodec(new SchemaRegistry(List.of(oldSchema())));
        var codec = new DefaultValueCodec(new SchemaRegistry(List.of(newSchema(new AtomicInteger()))));
        assertEquals(new V2("Ada", 42), codec.decode(oldCodec.encode(new V1("Ada", "old"), V1.class, null), V2.class, null));
        assertEquals(new V1("Ada", "legacy"), oldCodec.decode(codec.encode(new V2("Ada", 9), V2.class, null), V1.class, null));
        assertEquals(new V2("Ada", 9), codec.decode(codec.encode(new V2("Ada", 9), V2.class, null), V2.class, null));
    }
    @Test void rejectsIncompatiblePayloadBeforeFactoryMutation() {
        AtomicInteger calls = new AtomicInteger();
        var codec = new DefaultValueCodec(new SchemaRegistry(List.of(oldSchema(), newSchema(calls))));
        EncodedValue invalid = new EncodedValue("registered-schema", "customer", Map.of("name", new EncodedValue("scalar", "java.lang.Integer", 1, Map.of("schemaRequired", "true", "schemaFieldType", String.class.getName()))), Map.of("schemaVersion", "2"));
        assertThrows(MiddlewareException.class, () -> codec.decode(invalid, V2.class, null));
        assertEquals(0, calls.get());
        EncodedValue missing = new EncodedValue("registered-schema", "customer", Map.of(), Map.of("schemaVersion", "2"));
        assertThrows(MiddlewareException.class, () -> codec.decode(missing, V2.class, null));
        assertEquals(0, calls.get());
    }
    @Test void rejectsUnknownSchemasVersionsFieldsAndUnregisteredRecord() {
        var codec = new DefaultValueCodec(new SchemaRegistry(List.of(oldSchema())));
        for (String version : List.of("0", "2", "invalid")) {
            assertThrows(MiddlewareException.class, () -> codec.decode(new EncodedValue("registered-schema", "customer", Map.of(), Map.of("schemaVersion", version)), V1.class, null));
        }
        assertThrows(MiddlewareException.class, () -> codec.encode(new V2("A", 1), V2.class, null));
        assertThrows(MiddlewareException.class, () -> codec.decode(new EncodedValue("registered-schema", "customer", Map.of("evil", new EncodedValue("null", "x", null, Map.of())), Map.of("schemaVersion", "1")), V1.class, null));
    }
    @Test void rejectsRequiredUnknownFieldAndChangedKnownFieldContractBeforeFactory() {
        AtomicInteger calls = new AtomicInteger();
        var codec = new DefaultValueCodec(new SchemaRegistry(List.of(newSchema(calls))));
        EncodedValue forgedField = new EncodedValue("scalar", String.class.getName(), "Ada",
                Map.of("schemaRequired", "true", "schemaFieldType", Integer.class.getName()));
        assertThrows(MiddlewareException.class, () -> codec.decode(new EncodedValue("registered-schema", "customer",
                Map.of("name", forgedField), Map.of("schemaVersion", "1")), V2.class, null));
        EncodedValue unknownRequired = new EncodedValue("scalar", String.class.getName(), "x",
                Map.of("schemaRequired", "true", "schemaFieldType", String.class.getName()));
        assertThrows(MiddlewareException.class, () -> codec.decode(new EncodedValue("registered-schema", "customer",
                Map.of("future", unknownRequired), Map.of("schemaVersion", "1")), V2.class, null));
        assertEquals(0, calls.get());
    }
    @Test void validatesRegistrationAndFieldBoundaries() {
        assertDoesNotThrow(() -> new SchemaRegistry(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistry(List.of(oldSchema(), oldSchema())));
        assertThrows(IllegalArgumentException.class, () -> new SchemaField("", String.class, false, null));
        assertThrows(IllegalArgumentException.class, () -> new SchemaField("x", Integer.class, false, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new SchemaField("x", int.class, true, null));
        var incompatible = new SchemaDescriptor<>("customer", 2, V2.class, List.of(new SchemaField("name", Integer.class, true, null)), v -> Map.of(), f -> new V2("", 0));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistry(List.of(oldSchema(), incompatible)));
    }
}
