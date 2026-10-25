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
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.*;
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.embedded.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Each actual export owns its server allowlist, including calls made by local lookup.
 */
class ExportScopedCodecIT {
    public record Data(String value) { }
    public interface Service { Data echo(Data data); }
    private static DefaultValueCodec codec(String id) {
        return new DefaultValueCodec(new SchemaRegistry(List.of(new SchemaDescriptor<>(id, 1, Data.class,
                List.of(new SchemaField("value", String.class, true, null)), value -> Map.of("value", value.value()),
                fields -> new Data((String) fields.get("value"))))));
    }
    private static DefaultMiddlewareRuntime runtime(String id, EmbeddedRegistryStore store, ValueCodec codec) {
        return new MiddlewareRuntimeBuilder().runtimeId(id).registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .codec(codec).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
    }
    private static ExportOptions options() { return new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofSeconds(30), Duration.ofSeconds(10)); }
    @Test void distinctExportsUseIndependentSchemasAndLocalLookupCannotBypassTheScopedDecoder() {
        var store = new EmbeddedRegistryStore(); var callsA = new AtomicInteger(); var callsB = new AtomicInteger();
        Service targetA = data -> { callsA.incrementAndGet(); return data; };
        Service targetB = data -> { callsB.incrementAndGet(); return data; };
        try (var server = runtime("scoped-server", store, codec("alpha")); var alpha = runtime("alpha-client", store, codec("alpha"));
                var beta = runtime("beta-client", store, codec("beta"));
                var a = server.prepareExport("alpha", targetA, Service.class, options());
                var b = server.prepareExport("beta", targetB, Service.class, options())) {
            assertSame(targetA, a.instance()); assertSame(targetB, b.instance());
            a.setValueCodec(codec("alpha")); b.setValueCodec(codec("beta"));
            assertThrows(MiddlewareException.class, () -> a.setValueCodec(codec("alpha")));
            var exportedA = a.commit(); var exportedB = b.commit();
            assertThrows(MiddlewareException.class, () -> a.setValueCodec(codec("beta")));
            var value = new Data("payload");
            assertEquals(value, alpha.reference(exportedA.ref(), Service.class, ProxyOptions.defaults()).echo(value));
            assertEquals(value, beta.reference(exportedB.ref(), Service.class, ProxyOptions.defaults()).echo(value));
            assertEquals(value, server.lookup("alpha", Service.class, LookupOptions.defaults()).echo(value));
            assertEquals(2, callsA.get()); assertEquals(1, callsB.get());
            assertThrows(MiddlewareException.class, () -> alpha.reference(exportedB.ref(), Service.class, ProxyOptions.defaults()).echo(value));
            assertThrows(MiddlewareException.class, () -> server.lookup("beta", Service.class, LookupOptions.defaults()).echo(value));
            assertEquals(1, callsB.get(), "Unknown wire schema must be rejected before application entry");
            b.close(); assertThrows(MiddlewareException.class, () -> b.setValueCodec(codec("beta")));
            assertThrows(MiddlewareException.class, () -> beta.reference(exportedB.ref(), Service.class, ProxyOptions.defaults()).echo(value));
        }
    }
    @Test void closedUncommittedHandleRejectsCodecAndRollbackPublishesNoExport() {
        var store = new EmbeddedRegistryStore();
        try (var runtime = runtime("scoped-rollback", store, codec("alpha"))) {
            var prepared = runtime.prepareExport("rolled-back", (Service) value -> value, Service.class, options());
            assertThrows(NullPointerException.class, () -> prepared.setValueCodec(null));
            prepared.setValueCodec(codec("alpha")); prepared.close();
            assertThrows(MiddlewareException.class, () -> prepared.setValueCodec(codec("alpha")));
            assertTrue(runtime.list("rolled-back", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
        }
    }
    @Test void defaultCodecSelectionIsFrozenBeforeGroupedPublicationEntersRegistryIo() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var delegate = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC());
        var registry = (org.j7m.middleware.registry.api.RegistryClient) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.j7m.middleware.registry.api.RegistryClient.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("bind")) { entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    try { return method.invoke(delegate, arguments); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        try (var runtime = new MiddlewareRuntimeBuilder().runtimeId("codec-publication-race").registry(registry)
                    .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
                var prepared = runtime.prepareExport("codec-race", (Service) value -> value, Service.class, options())) {
            var commit = worker.submit(() -> runtime.commitPreparedExports(List.of(prepared)));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, () -> prepared.setValueCodec(codec("late"))).code());
            } finally { release.countDown(); }
            assertEquals(1, commit.get(5, java.util.concurrent.TimeUnit.SECONDS).size());
        } finally { release.countDown(); }
    }
}
