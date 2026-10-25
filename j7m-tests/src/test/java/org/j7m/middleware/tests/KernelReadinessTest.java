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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Host-owned readiness controls actual discovery and dispatch, including stale/forged client refs.
 */
class KernelReadinessTest {
    public interface Service { String call(); }

    @Test
    void preparedGatePrecedesPublicationAndStableRemoteProxyRecoversWithoutChangingIdentity() throws Exception {
        var store = new EmbeddedRegistryStore();
        try (var server = runtime("readiness-server", store); var client = runtime("readiness-client", store)) {
            AtomicInteger calls = new AtomicInteger();
            try (var prepared = server.prepareExport("//ready", (Service) () -> Integer.toString(calls.incrementAndGet()), Service.class, options(Map.of()))) {
                prepared.setReadiness(false, Duration.ofSeconds(1));
                var registration = prepared.commit();
                assertTrue(server.list("//ready", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
                assertTrue(client.list("//ready", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
                var stable = client.reference(registration.ref(), Service.class, ProxyOptions.defaults());
                assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class, stable::call).code());
                assertEquals(0, calls.get());
                var original = registration.ref();
                registration.setReadiness(true, Duration.ofSeconds(1));
                assertEquals("1", stable.call());
                assertEquals(original.objectId(), registration.ref().objectId());
                assertEquals(original.generation(), registration.ref().generation());
                await(() -> client.list("//ready", List.of(), Map.of(), LookupOptions.defaults()).size() == 1);
                var local = server.lookup("//ready", Service.class, LookupOptions.defaults());
                registration.setReadiness(false, Duration.ofSeconds(1));
                assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class, local::call).code());
                var forged = client.reference(original.withMetadata(Map.of("j7m.readiness.ready", "true", "j7m.readiness.until", Long.toString(Long.MAX_VALUE))), Service.class, ProxyOptions.defaults());
                assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class, forged::call).code());
                assertEquals(1, calls.get());
                await(() -> client.list("//ready", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
            }
        }
    }

    @Test
    void heartbeatPublishesCurrentMetadataWithoutRenewingObservationAndExpiryIsFailClosed() throws Exception {
        var store = new EmbeddedRegistryStore();
        try (var server = runtime("expiry-server", store); var client = runtime("expiry-client", store);
                var registration = server.export("//expiry", (Service) () -> "called", Service.class, options(Map.of()))) {
            var stable = client.reference(registration.ref(), Service.class, ProxyOptions.defaults());
            assertEquals("called", stable.call());
            registration.setReadiness(true, Duration.ofMillis(40));
            String validity = registration.ref().metadata().get("j7m.readiness.until");
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!"false".equals(registration.ref().metadata().get("j7m.readiness.ready")) && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals("false", registration.ref().metadata().get("j7m.readiness.ready"));
            assertEquals(validity, registration.ref().metadata().get("j7m.readiness.until"));
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class, stable::call).code());
            assertTrue(server.list("//expiry", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
            assertTrue(client.list("//expiry", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
            registration.setReadiness(true, Duration.ofSeconds(1));
            assertEquals("called", stable.call());
        }
    }

    @Test
    void legacyExportsRemainReadyAndReservedMetadataOrInvalidFreshnessNeverGrantReadiness() {
        var store = new EmbeddedRegistryStore();
        try (var server = runtime("legacy-ready", store);
                var registration = server.export("//legacy", (Service) () -> "legacy", Service.class, options(Map.of()))) {
            assertFalse(registration.ref().metadata().containsKey("j7m.readiness.ready"));
            assertEquals("legacy", server.lookup("//legacy", Service.class, LookupOptions.defaults()).call());
            for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMinutes(6))) {
                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, () -> registration.setReadiness(true, invalid)).code());
            }
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, () -> server.prepareExport("//forged", (Service) () -> "bad", Service.class,
                    options(Map.of("j7m.readiness.ready", "true")))).code());
            registration.close();
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class, () -> registration.setReadiness(true, Duration.ofSeconds(1))).code());
        }
    }

    @Test
    void healthyUpdatesStayDiscoverableBeyondFreshnessWithoutWaitingForLongHeartbeat() throws Exception {
        var store = new EmbeddedRegistryStore();
        try (var server = runtime("steady-server", store); var client = runtime("steady-client", store);
                var registration = server.export("//steady", (Service) () -> "healthy", Service.class,
                        new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofSeconds(30), Duration.ofSeconds(10)))) {
            registration.setReadiness(true, Duration.ofMillis(250));
            await(() -> !client.list("//steady", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
            long until = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < until) {
                registration.setReadiness(true, Duration.ofMillis(250));
                Thread.sleep(30);
                assertEquals(1, client.list("//steady", List.of(), Map.of(), LookupOptions.defaults()).size());
            }
        }
    }

    @Test
    void blockedRegistryPublicationNeverBlocksOwnerStatusOrCoalescedReadinessUpdates() throws Exception {
        var store = new EmbeddedRegistryStore();
        var delegate = new EmbeddedRegistryClient(store, Clock.systemUTC());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger publications = new AtomicInteger();
        var registry = registry(delegate, entered, release, blocked, publications, new AtomicInteger());
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("blocked-registry").registry(registry)
                .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var registration = server.export("//blocked", (Service) () -> "called", Service.class, ExportOptions.defaults())) {
            var stable = server.reference(registration.ref(), Service.class, ProxyOptions.defaults());
            blocked.set(true);
            registration.setReadiness(true, Duration.ofSeconds(1));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            try {
                for (int index = 0; index < 100; index++) registration.setReadiness(index % 2 == 0, Duration.ofSeconds(1));
                assertEquals(1, publications.get(), "same-object observations must coalesce while publication is physically blocked");
                assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class, stable::call).code());
                assertEquals(1, server.status().instances().size());
            } finally { release.countDown(); }
            await(() -> delegate.lookup(registration.ref()).map(record -> "false".equals(record.ref().metadata().get("j7m.readiness.ready"))).orElse(false));
        } finally { release.countDown(); }
    }

    @Test
    void aLatePublicationCannotResurrectAnUnexportedObject() throws Exception {
        var store = new EmbeddedRegistryStore();
        var delegate = new EmbeddedRegistryClient(store, Clock.systemUTC());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger publications = new AtomicInteger();
        AtomicInteger unbindings = new AtomicInteger();
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("late-registry").registry(registry(delegate, entered, release, blocked, publications, unbindings))
                .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            var registration = server.export("//late", (Service) () -> "called", Service.class, ExportOptions.defaults());
            var ref = registration.ref();
            blocked.set(true); registration.setReadiness(true, Duration.ofSeconds(1));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            registration.close();
            release.countDown();
            await(() -> unbindings.get() >= 2 && delegate.lookup(ref).isEmpty());
            assertTrue(server.status().instances().isEmpty());
        } finally { release.countDown(); }
    }

    @Test
    void stalledHeartbeatAndFrozenWallClockCannotExtendAuthoritativeReadiness() throws Exception {
        Clock fixed = Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC);
        var store = new EmbeddedRegistryStore();
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("monotonic-ready").clock(fixed)
                .registry(new EmbeddedRegistryClient(store, fixed)).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var registration = server.export("//monotonic", (Service) () -> "called", Service.class,
                        new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofMinutes(30), Duration.ofHours(1)))) {
            var stable = server.reference(registration.ref(), Service.class, ProxyOptions.defaults());
            assertEquals("called", stable.call());
            registration.setReadiness(true, Duration.ofMillis(40));
            await(() -> server.list("//monotonic", List.of(), Map.of(), LookupOptions.defaults()).isEmpty());
            assertEquals("true", registration.ref().metadata().get("j7m.readiness.ready"), "no heartbeat has refreshed the stale hint");
            assertTrue(Long.parseLong(registration.ref().metadata().get("j7m.readiness.until")) > fixed.millis());
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class, stable::call).code());
        }
    }

    @Test
    void publicationFailureIsCountedWithoutLeakingDetailsAndNextObservationCanRecover() throws Exception {
        var store = new EmbeddedRegistryStore();
        var delegate = new EmbeddedRegistryClient(store, Clock.systemUTC());
        var failing = new java.util.concurrent.atomic.AtomicBoolean();
        var registry = (org.j7m.middleware.registry.api.RegistryClient) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.j7m.middleware.registry.api.RegistryClient.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("bind") && failing.get()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "SYNTHETIC_REGISTRY_DETAIL");
                    try { return method.invoke(delegate, arguments); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("failed-publication").registry(registry)
                .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var registration = server.export("//publication", (Service) () -> "called", Service.class, ExportOptions.defaults())) {
            failing.set(true); registration.setReadiness(true, Duration.ofMillis(40));
            await(() -> Long.parseLong(server.status().security().get("readinessPublicationFailures")) > 0);
            assertFalse(server.status().security().toString().contains("SYNTHETIC_REGISTRY_DETAIL"));
            failing.set(false); registration.setReadiness(true, Duration.ofSeconds(1));
            await(() -> delegate.lookup(registration.ref()).map(record -> "true".equals(record.ref().metadata().get("j7m.readiness.ready"))).orElse(false));
            assertEquals("called", server.reference(registration.ref(), Service.class, ProxyOptions.defaults()).call());
        }
    }

    @Test
    void readinessPublicationBackpressureBoundsClosedGenerationChurn() throws Exception {
        var delegate = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("bounded-publication")
                .registry(registry(delegate, entered, release, blocked, new AtomicInteger(), new AtomicInteger()))
                .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var first = server.export("//bounded", (Service) () -> "first", Service.class, ExportOptions.defaults())) {
            blocked.set(true); first.setReadiness(true, Duration.ofSeconds(1));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            try {
                for (int index = 0; index < 1100; index++) {
                    try (var temporary = server.export("//churn", (Service) () -> "temporary", Service.class, ExportOptions.defaults())) {
                        temporary.setReadiness(false, Duration.ofSeconds(1));
                    }
                }
                assertEquals("1024", server.status().security().get("readinessPublicationPending"));
                assertTrue(Long.parseLong(server.status().security().get("readinessPublicationDeferred")) > 0);
                assertEquals(1, server.status().instances().size());
            } finally { release.countDown(); }
            await(() -> "0".equals(server.status().security().get("readinessPublicationPending")));
        } finally { release.countDown(); }
    }

    private static org.j7m.middleware.registry.api.RegistryClient registry(final EmbeddedRegistryClient delegate,
            final java.util.concurrent.CountDownLatch entered, final java.util.concurrent.CountDownLatch release,
            final java.util.concurrent.atomic.AtomicBoolean blocked, final AtomicInteger publications, final AtomicInteger unbindings) {
        return (org.j7m.middleware.registry.api.RegistryClient) java.lang.reflect.Proxy.newProxyInstance(
                KernelReadinessTest.class.getClassLoader(), new Class<?>[]{org.j7m.middleware.registry.api.RegistryClient.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("bind") && blocked.get()
                            && ((org.j7m.middleware.registry.api.RegistryRecord) arguments[0]).ref().metadata().containsKey("j7m.readiness.ready")) {
                        publications.incrementAndGet(); entered.countDown();
                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("fixture registry release missing");
                    }
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("unbind")) unbindings.incrementAndGet();
                        return result;
                    }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static void await(final java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "readiness publication did not converge");
    }

    private static org.j7m.middleware.core.DefaultMiddlewareRuntime runtime(final String id, final EmbeddedRegistryStore store) {
        return new MiddlewareRuntimeBuilder().runtimeId(id).registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
    }

    private static ExportOptions options(final Map<String, String> metadata) {
        return new ExportOptions(false, "grpc", "default", List.of(), metadata, Duration.ofSeconds(3), Duration.ofMillis(10));
    }
}
