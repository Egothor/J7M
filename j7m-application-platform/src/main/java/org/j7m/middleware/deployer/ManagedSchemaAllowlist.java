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

import java.util.*;
import java.util.function.Consumer;
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;

/**
 * Loads only origin-verified schema providers before application construction and snapshots their
 * descriptors into one immutable allowlist. Wire class names never participate in class loading.
 */
final class ManagedSchemaAllowlist {
    private ManagedSchemaAllowlist() { }
    static ValueCodec codec(ClassLoader loader, ModuleLayer layer, Consumer<Class<?>> origin) {
        var providers = layer == null ? ServiceLoader.load(SchemaIndex.class, loader) : ServiceLoader.load(layer, SchemaIndex.class);
        var indexes = new ArrayList<SchemaIndex>(); var iterator = providers.stream().iterator();
        while (iterator.hasNext()) {
            if (indexes.size() >= 256) throw new IllegalArgumentException("Schema provider capacity");
            var provider = iterator.next(); origin.accept(provider.type());
            SchemaIndex index;
            try { index = provider.get(); }
            catch (ServiceConfigurationError failure) { throw org.j7m.middleware.application.components.ManagedInjectionException.preflight(provider.type(),
                    org.j7m.middleware.application.components.ManagedInjectionException.Reason.SCHEMA_PROVIDER_ACCESS); }
            int version = index.formatVersion(); var descriptors = List.copyOf(index.schemas());
            if (descriptors.size() > 128) throw new IllegalArgumentException("Schema descriptor capacity");
            for (var descriptor : descriptors) origin.accept(descriptor.javaType());
            indexes.add(new Snapshot(version, descriptors));
        }
        return new DefaultValueCodec(SchemaIndexes.registry(indexes));
    }
    private record Snapshot(int formatVersion, List<SchemaDescriptor<?>> schemas) implements SchemaIndex { }
}
