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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.Async;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.components.ManagedContainer;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.j7m.middleware.application.components.ManagedInvocationSettings;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.AsyncMethodDescriptor;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Actual gRPC logical payload and host physical-ownership boundaries.
 */
class KernelAsyncInvocationIT {
    public interface Service { @Async CompletionStage<String> call(); }
    public interface Synchronous { String call(); }
    public interface Shapes {
        CompletionStage<String> good();
        CompletableFuture<byte[]> bytes();
        CompletionStage raw();
        CompletionStage<?> wildcard();
        CompletionStage<CompletableFuture<String>> nested();
        CompletionStage<java.util.concurrent.Future> futurePayload();
    }

    @Test void finiteLogicalShapeRejectsFutureObjectsBeforeTransport() throws Exception {
        assertEquals(String.class, AsyncMethodDescriptor.fromMethod(Shapes.class.getMethod("good")).payloadType());
        assertEquals(byte[].class, AsyncMethodDescriptor.fromMethod(Shapes.class.getMethod("bytes")).payloadType());
        for (String method : List.of("raw", "wildcard", "nested", "futurePayload")) {
            assertThrows(IllegalArgumentException.class, () -> AsyncMethodDescriptor.fromMethod(Shapes.class.getMethod(method)));
        }
    }

    @Test void realGrpcCarriesLogicalPayloadAndRetainsOwnedCallAcrossCancellation() throws Exception {
        var store = new EmbeddedRegistryStore();
        var entered = new CountDownLatch(1);
        var leave = new CountDownLatch(1);
        var effects = new AtomicInteger();
        var releases = new AtomicInteger();
        var last = new AtomicReference<ManagedInvocationLifetime>();
        try (var container = ManagedContainer.activate(List.of());
                var workers = ManagedInvocationSettings.defaults().newExecutor();
                var server = runtime("async-server", store); var client = runtime("async-client", store)) {
            Service implementation = () -> J7mContext.currentInvocation().control().submit(() -> {
                entered.countDown(); assertTrue(leave.await(5, TimeUnit.SECONDS));
                assertFalse(J7mContext.currentInvocation().control() instanceof org.j7m.middleware.transport.api.InvocationCompletionScope);
                effects.incrementAndGet(); return "logical-result";
            });
            try (var prepared = server.prepareExport("//async", implementation, Service.class, options())) {
                assertEquals(MiddlewareErrorCode.CODEC_ERROR, assertThrows(MiddlewareException.class,
                        () -> server.encodeValue(InvocationContext.empty().control(), org.j7m.middleware.application.api.InvocationControl.class)).code());
                assertEquals(MiddlewareErrorCode.CODEC_ERROR, assertThrows(MiddlewareException.class,
                        () -> server.exportCallback(InvocationContext.empty().control(), org.j7m.middleware.application.api.InvocationControl.class)).code());
                prepared.setInvocationCompletionFactory(boundary -> {
                    var lifetime = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(),
                            boundary.security(), boundary.options().deadline(), workers, 16, 8, releases::incrementAndGet);
                    last.set(lifetime); return lifetime;
                });
                var registration = prepared.commit();
                assertThrows(MiddlewareException.class, () -> prepared.setInvocationCompletionFactory(boundary -> last.get()));
                Service proxy = client.reference(registration.ref(), Service.class, ProxyOptions.defaults());
                CompletableFuture<String> reply = proxy.call().toCompletableFuture();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertFalse(reply.isDone()); assertTrue(reply.cancel(false));
                assertFalse(last.get().physicalCompletion().toCompletableFuture().isDone());
                assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class, container::close).code());
                leave.countDown();
                last.get().physicalCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(1, effects.get()); assertEquals(1, releases.get());
                assertEquals("logical-result", proxy.call().toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertEquals(2, effects.get()); assertEquals(2, releases.get());
            } finally { leave.countDown(); }
        }
    }

    @Test void absentHostOwnershipFailsBeforeBusinessExecution() throws Exception {
        var store = new EmbeddedRegistryStore(); var effects = new AtomicInteger();
        try (var server = runtime("unsupported-server", store); var client = runtime("unsupported-client", store);
                var export = server.export("//unsupported", (Service) () -> {
                    effects.incrementAndGet(); return CompletableFuture.completedFuture("unsafe");
                }, Service.class, options())) {
            var proxy = client.reference(export.ref(), Service.class, ProxyOptions.defaults());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> proxy.call().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(0, effects.get());
        }
    }

    @Test void synchronousSignatureWaitsForActualUserWorkAndTrustedFinalizationBeforeEncoding() throws Exception {
        var store = new EmbeddedRegistryStore();
        var entered = new CountDownLatch(1); var leave = new CountDownLatch(1);
        var finalized = new AtomicInteger(); var released = new AtomicInteger();
        try (var container = ManagedContainer.activate(List.of());
                var workers = ManagedInvocationSettings.defaults().newExecutor();
                var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                var server = runtime("sync-finalizer-server", store); var client = runtime("sync-finalizer-client", store)) {
            Synchronous implementation = () -> {
                J7mContext.currentInvocation().control().submit(() -> {
                    entered.countDown(); assertTrue(leave.await(5, TimeUnit.SECONDS)); return "child";
                });
                return "raw";
            };
            try (var prepared = server.prepareExport("//finalized", implementation, Synchronous.class, options())) {
                prepared.setInvocationCompletionFactory(boundary -> {
                    var lifetime = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(),
                            boundary.security(), boundary.options().deadline(), workers, 16, 8, released::incrementAndGet);
                    lifetime.addResultFinalizer(result -> {
                        finalized.incrementAndGet(); assertNull(result.failure());
                        return CompletableFuture.completedFuture(org.j7m.middleware.transport.api.InvocationResult.success("final:" + result.value()));
                    });
                    return lifetime;
                });
                var registration = prepared.commit();
                var proxy = client.reference(registration.ref(), Synchronous.class, ProxyOptions.defaults());
                var result = callers.submit(proxy::call);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertFalse(result.isDone()); assertEquals(0, finalized.get()); assertEquals(0, released.get());
                leave.countDown();
                assertEquals("final:raw", result.get(3, TimeUnit.SECONDS));
                assertEquals(1, finalized.get()); assertEquals(1, released.get());
            } finally { leave.countDown(); }
        }
    }

    @Test void sameRuntimeLookupCannotBypassInstalledPhysicalOwnershipAndResultFinalizers() throws Exception {
        var factoryCalls = new AtomicInteger(); var effects = new AtomicInteger(); var released = new AtomicInteger();
        try (var container = ManagedContainer.activate(List.of()); var workers = ManagedInvocationSettings.defaults().newExecutor();
                var server = runtime("local-ownership", new EmbeddedRegistryStore())) {
            Synchronous body = () -> "raw:" + effects.incrementAndGet();
            try (var prepared = server.prepareExport("//local-owned", body, Synchronous.class, options())) {
                prepared.setInvocationCompletionFactory(boundary -> {
                    factoryCalls.incrementAndGet();
                    var lifetime = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(),
                            boundary.security(), boundary.options().deadline(), workers, 16, 8, released::incrementAndGet);
                    lifetime.addResultFinalizer(result -> CompletableFuture.completedFuture(
                            org.j7m.middleware.transport.api.InvocationResult.success("final:" + result.value())));
                    return lifetime;
                });
                prepared.commit();
                Synchronous local = server.lookup("//local-owned", Synchronous.class, LookupOptions.defaults());
                assertNotSame(body, local, "Controlled local exports must enter the trusted dispatch boundary");
                assertEquals("final:raw:1", local.call());
                assertEquals(1, factoryCalls.get()); assertEquals(1, released.get()); assertEquals(1, effects.get());
            }
            Synchronous legacy = () -> "legacy";
            try (var registration = server.export("//local-legacy", legacy, Synchronous.class, options())) {
                assertSame(legacy, server.lookup("//local-legacy", Synchronous.class, LookupOptions.defaults()));
            }
        }
    }

    @Test void sameRuntimeAsyncBoundaryPreservesTrustedLocalCallerInsteadOfCreatingANewPeer() throws Exception {
        var calls = new AtomicInteger(); var released = new AtomicInteger();
        try (var container = ManagedContainer.activate(List.of()); var workers = ManagedInvocationSettings.defaults().newExecutor();
                var server = runtime("local-async-caller", new EmbeddedRegistryStore())) {
            Service body = () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(org.j7m.middleware.security.api.SecurityContexts.current().principal().id());
            };
            try (var prepared = server.prepareExport("//local-async", body, Service.class, options())) {
                prepared.setInvocationCompletionFactory(boundary -> new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(),
                        boundary.security(), boundary.options().deadline(), workers, 16, 8, released::incrementAndGet));
                var registered = prepared.commit();
                var proxy = server.reference(registered.ref(), Service.class, ProxyOptions.defaults());
                var reply = org.j7m.middleware.security.api.SecurityContexts.call(
                        org.j7m.middleware.security.api.SecurityContext.system("local-user"), proxy::call);
                assertEquals("local-user", reply.toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertEquals(1, calls.get()); assertEquals(1, released.get());
            }
        }
    }

    @Test void oneWayAdmissionIsBoundedAndWorkersDoNotInheritApplicationState() throws Exception {
        var inherited = new InheritableThreadLocal<String>();
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader applicationLoader = new ClassLoader(originalLoader) { };
        var entered = new CountDownLatch(64);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1088);
        var leaked = new AtomicInteger();
        try (var runtime = runtime("oneway-bounds", new EmbeddedRegistryStore())) {
            inherited.set("application-secret");
            Thread.currentThread().setContextClassLoader(applicationLoader);
            Runnable work = () -> {
                try {
                    if (inherited.get() != null || Thread.currentThread().getContextClassLoader() == applicationLoader) leaked.incrementAndGet();
                    entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                finally { completed.countDown(); }
            };
            try {
                for (int i = 0; i < 64; i++) runtime.executeOneWay(work);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 1024; i++) runtime.executeOneWay(work);
                assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(MiddlewareException.class,
                        () -> runtime.executeOneWay(work)).code());
                assertEquals("1", runtime.status().security().get("oneWay.rejectedCount"));
            } finally {
                inherited.remove(); Thread.currentThread().setContextClassLoader(originalLoader); release.countDown();
            }
            assertTrue(completed.await(3, TimeUnit.SECONDS)); assertEquals(0, leaked.get());
        } finally { release.countDown(); inherited.remove(); Thread.currentThread().setContextClassLoader(originalLoader); }
    }

    private static org.j7m.middleware.core.DefaultMiddlewareRuntime runtime(String id, EmbeddedRegistryStore store) {
        return new MiddlewareRuntimeBuilder().runtimeId(id).registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
    }
    private static ExportOptions options() {
        return new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofSeconds(3), Duration.ofMillis(10));
    }
}
