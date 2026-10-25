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

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.*;
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.embedded.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.junit.jupiter.api.Test;

class RegisteredSchemaTransportIT {
    public record Customer(String name, Integer score) { }
    public interface CustomerService { Customer echo(Customer customer); }
    public static class Echo implements CustomerService {
        @Override public Customer echo(Customer customer) { return customer; }
    }
    private DefaultValueCodec codec(boolean current) {
        var fields = current ? List.of(new SchemaField("name", String.class, true, null), new SchemaField("score", Integer.class, false, 42))
                : List.of(new SchemaField("name", String.class, true, null));
        var descriptor = new SchemaDescriptor<>("customer", current ? 2 : 1, Customer.class, fields,
                c -> current ? Map.of("name", c.name(), "score", c.score()) : Map.of("name", c.name()),
                f -> new Customer((String) f.get("name"), (Integer) f.getOrDefault("score", -1)));
        return new DefaultValueCodec(new SchemaRegistry(List.of(descriptor)));
    }
    private DefaultMiddlewareRuntime runtime(String id, EmbeddedRegistryStore store, boolean current, String transport) {
        return new MiddlewareRuntimeBuilder().runtimeId(id).registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .codec(codec(current)).addTransport("grpc".equals(transport) ? new GrpcTransportProvider() : new RmiTransportProvider()).disableNuggetAdmin().build();
    }
    @Test void grpcUsesIndependentOldAndNewRegistriesInBothDirections() { verifyVersions("grpc"); }
    @Test void rmiUsesIndependentOldAndNewRegistriesInBothDirections() { verifyVersions("rmi"); }
    private void verifyVersions(String transport) {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (var current = runtime("current-" + transport, store, true, transport); var old = runtime("old-" + transport, store, false, transport)) {
            var export = new ExportOptions(false, transport, "default", List.of(), Map.of(), Duration.ofSeconds(30), Duration.ofSeconds(10));
            current.export("current-service", new Echo(), CustomerService.class, export);
            old.export("old-service", new Echo(), CustomerService.class, export);
            var oldToNew = old.lookup("current-service", CustomerService.class, LookupOptions.defaults());
            var newToOld = current.lookup("old-service", CustomerService.class, LookupOptions.defaults());
            assertEquals(new Customer("Ada", -1), oldToNew.echo(new Customer("Ada", 5)));
            assertEquals(new Customer("Lin", 42), newToOld.echo(new Customer("Lin", 5)));
        }
    }
}
