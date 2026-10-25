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

package org.j7m.middleware.deployer;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.codec.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real ServiceLoader boundary verifies origin before provider construction and schema decoding.
 */
class ManagedSchemaAllowlistTest {
    @TempDir Path directory;
    private static final AtomicInteger providers = new AtomicInteger(), decoded = new AtomicInteger();
    @SchemaType(id="verified", version=1) public record Data(@WireField(id=1) String value) { }
    public static class Index implements SchemaIndex {
        public Index() { providers.incrementAndGet(); }
        public List<SchemaDescriptor<?>> schemas() {
            return List.of(new SchemaDescriptor<>("verified", 1, Data.class, List.of(new SchemaField("1", String.class, true, null)),
                    value -> Map.of("1", value.value()), fields -> { decoded.incrementAndGet(); return new Data((String) fields.get("1")); }));
        }
    }
    private URLClassLoader loader() throws Exception {
        var path = directory.resolve("META-INF/services/" + SchemaIndex.class.getName()); Files.createDirectories(path.getParent()); Files.writeString(path, Index.class.getName());
        return new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
                return name.equals("META-INF/services/" + SchemaIndex.class.getName()) ? findResources(name) : super.getResources(name);
            }
        };
    }
    private static CodecContext context() { return new CodecContext() {
        public Optional<org.j7m.middleware.api.ObjectRef> referenceFor(Object value, Class<?> declared) { return Optional.empty(); }
        public Object proxyFor(org.j7m.middleware.api.ObjectRef ref, Class<?> expected) { throw new AssertionError("No reference decoding"); }
    }; }
    @Test void verifiedProviderAndDataOriginProduceAnIsolatedAllowlist() throws Exception {
        providers.set(0); decoded.set(0); var origins = new ArrayList<Class<?>>();
        try (var loader = loader()) {
            var codec = ManagedSchemaAllowlist.codec(loader, null, origins::add);
            assertEquals(List.of(Index.class, Data.class), origins); assertEquals(1, providers.get()); assertEquals(0, decoded.get());
            var value = new Data("safe"); var frame = codec.encode(value, Data.class, context());
            assertEquals(value, codec.decode(frame, Data.class, context())); assertEquals(1, decoded.get());
            assertThrows(org.j7m.middleware.api.MiddlewareException.class, () -> new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec().decode(frame, Data.class, context()));
        }
    }
    @Test void rejectedProviderOriginNeverConstructsProviderAndRejectedDataNeverDecodes() throws Exception {
        providers.set(0); decoded.set(0);
        try (var loader = loader()) {
            assertThrows(SecurityException.class, () -> ManagedSchemaAllowlist.codec(loader, null, type -> { throw new SecurityException("Origin denied"); }));
            assertEquals(0, providers.get());
            assertThrows(SecurityException.class, () -> ManagedSchemaAllowlist.codec(loader, null, type -> { if (type == Data.class) throw new SecurityException("Data origin denied"); }));
            assertEquals(1, providers.get()); assertEquals(0, decoded.get());
        }
    }
}
