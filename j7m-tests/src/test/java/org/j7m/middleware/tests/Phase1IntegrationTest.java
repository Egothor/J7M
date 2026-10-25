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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.admin.RuntimeAdmin;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Initialize;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.PreparedObjectRegistration;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.ExportDescriptor;
import org.j7m.middleware.transport.api.ExportedObject;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.RuntimeTransportContext;
import org.j7m.middleware.transport.api.TransportEndpoint;
import org.j7m.middleware.transport.api.TransportProvider;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.junit.jupiter.api.Test;

/**
 * End-to-end Phase 1 acceptance tests.
 */
public final class Phase1IntegrationTest {
    private static final String HA_TEST_TRANSPORT = "ha-test";
    private static final String DEADLINE_EPOCH_MILLIS = InvocationReliabilityOptions.PREFIX + "deadlineEpochMillis";

    @Test
    public void exportLookupListAndLifecycleWorkLocally() {
        TestRegistry registry = new TestRegistry();
        LifecycleGreeter greeter = new LifecycleGreeter("local");
        try (DefaultMiddlewareRuntime runtime = runtime("local-runtime", registry, "grpc")) {
            ObjectRegistration<Greeter> registration = runtime.export("greeter", greeter, Greeter.class, export("grpc", Map.of("zone", "local")));
            Greeter lookedUp = runtime.lookup("greeter", Greeter.class, LookupOptions.defaults());

            assertEquals("hello local bob", lookedUp.greet("bob"));
            assertTrue(greeter.events.contains("initialize"));
            assertTrue(greeter.events.contains("startup"));
            List<ObjectRef> refs = runtime.list("greeter", List.of(Greeter.class.getName()), Map.of("zone", "local"), LookupOptions.defaults());
            assertEquals(1, refs.size());
            assertEquals(registration.ref().objectId(), refs.getFirst().objectId());
        }
        assertTrue(greeter.events.contains("shutdown"));
    }

    @Test
    public void grpcRemoteInvocationPassesCallbackAndReturnReference() {
        TestRegistry registry = new TestRegistry();
        try (DefaultMiddlewareRuntime server = runtime("server-grpc", registry, "grpc");
             DefaultMiddlewareRuntime client = runtime("client-grpc", registry, "grpc")) {
            server.export("greeter", new LifecycleGreeter("grpc"), Greeter.class, export("grpc", Map.of()));
            assertTrue(server.status().transports().stream().anyMatch(status -> status.endpoint().startsWith("grpc://127.0.0.1:")));
            assertFalse(server.status().transports().stream().anyMatch(status -> status.endpoint().contains("in-process")));
            Greeter greeter = client.lookup("greeter", Greeter.class, LookupOptions.defaults());
            RecordingListener listener = new RecordingListener();

            assertTrue(Proxy.isProxyClass(greeter.getClass()));
            greeter.register(listener);
            greeter.fire("first");
            Listener returned = greeter.listener();
            returned.onMessage("second");

            assertEquals(List.of("first", "second"), listener.messages);
            assertEquals("hello grpc ana", greeter.greet("ana"));
            assertTrue(client.status().instances().stream().anyMatch(status -> status.ref().metadata().containsKey("callback.auto")));
        }
    }

    @Test
    public void rmiRemoteInvocationPassesCallbackAndReturnReference() {
        TestRegistry registry = new TestRegistry();
        try (DefaultMiddlewareRuntime server = runtime("server-rmi", registry, "rmi");
             DefaultMiddlewareRuntime client = runtime("client-rmi", registry, "rmi")) {
            server.export("greeter", new LifecycleGreeter("rmi"), Greeter.class, export("rmi", Map.of()));
            assertTrue(server.status().transports().stream().anyMatch(status -> status.endpoint().startsWith("rmi://127.0.0.1:")));
            assertFalse(server.status().transports().stream().anyMatch(status -> status.endpoint().contains("in-process")));
            Greeter greeter = client.lookup("greeter", Greeter.class, LookupOptions.defaults());
            RecordingListener listener = new RecordingListener();

            greeter.register(listener);
            greeter.fire("first");
            greeter.listener().onMessage("second");

            assertEquals(List.of("first", "second"), listener.messages);
            assertEquals("hello rmi bob", greeter.greet("bob"));
        }
    }

    @Test
    public void multipleInstancesLocalPreferenceAndHaFailoverWork() {
        TestRegistry registry = new TestRegistry();
        try (DefaultMiddlewareRuntime runtimeA = runtime("runtime-a", registry, "grpc");
             DefaultMiddlewareRuntime runtimeB = runtime("runtime-b", registry, "grpc")) {
            runtimeA.export("greeter", new LifecycleGreeter("first"), Greeter.class, export("grpc", Map.of("role", "primary")));
            ObjectRegistration<Greeter> second = runtimeB.export("greeter", new LifecycleGreeter("second"), Greeter.class, export("grpc", Map.of("role", "primary")));

            Greeter local = runtimeB.lookup("greeter", Greeter.class, LookupOptions.defaults());
            assertEquals("hello second local", local.greet("local"));

            Greeter ha = runtimeB.lookup("greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));
            assertEquals("hello second one", ha.greet("one"));

            second.close();
            assertEquals("hello first two", ha.greet("two"));
            assertFalse(runtimeB.list("greeter", List.of(Greeter.class.getName()), Map.of("role", "primary"), LookupOptions.defaults()).isEmpty());
        }
    }

    @Test
    public void leaseExpirationAndShutdownUnbindAreReflectedInRegistry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T00:00:00Z"));
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        EmbeddedRegistryClient registryA = new EmbeddedRegistryClient(store, clock);
        EmbeddedRegistryClient registryB = new EmbeddedRegistryClient(store, clock);
        try (DefaultMiddlewareRuntime runtimeA = runtime("lease-a", registryA, clock, "grpc");
             DefaultMiddlewareRuntime runtimeB = runtime("lease-b", registryB, clock, "grpc")) {
            ObjectRegistration<Greeter> registration = runtimeA.export("greeter", new LifecycleGreeter("lease"), Greeter.class,
                    new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofMillis(100L), Duration.ofDays(1L)));
            assertEquals(1, runtimeB.list("greeter", List.of(Greeter.class.getName()), Map.of(), LookupOptions.defaults()).size());

            clock.advance(Duration.ofMillis(101L));
            assertEquals(0, runtimeB.list("greeter", List.of(Greeter.class.getName()), Map.of(), LookupOptions.defaults()).size());

            registration.close();
            assertEquals(0, runtimeB.status().registryRecords().size());
        }
    }

    @Test
    public void remoteFailuresAreWrappedAndStatusReportsStats() {
        TestRegistry registry = new TestRegistry();
        try (DefaultMiddlewareRuntime server = runtime("failure-server", registry, "grpc");
             DefaultMiddlewareRuntime client = runtime("failure-client", registry, "grpc")) {
            server.export("greeter", new LifecycleGreeter("fail"), Greeter.class, export("grpc", Map.of()));
            Greeter greeter = client.lookup("greeter", Greeter.class, LookupOptions.defaults());

            MiddlewareException exception = assertThrows(MiddlewareException.class, greeter::fail);
            assertEquals(MiddlewareErrorCode.REMOTE_EXCEPTION, exception.code());
            assertTrue(server.status().invocationFailureCount() >= 1L);
            assertTrue(server.status().recentInvocations().stream().anyMatch(invocation -> !invocation.success()));
            assertTrue(server.status().transports().stream().anyMatch(status -> status.transportId().equals("grpc") && status.running()));
            assertTrue(server.status().registryRecords().stream().anyMatch(ref -> ref.leaseUntil() > 0L));
            String adminText = new RuntimeAdmin(server).formatStatus();
            assertTrue(adminText.contains("instances=1"));
            assertFalse(server.status().registryRecords().isEmpty());
        }
    }

    @Test
    public void runtimeCloseUnbindsGlobalObjectsFromSharedRegistry() {
        TestRegistry registry = new TestRegistry();
        DefaultMiddlewareRuntime runtimeA = runtime("shutdown-a", registry, "grpc");
        try (DefaultMiddlewareRuntime runtimeB = runtime("shutdown-b", registry, "grpc")) {
            runtimeA.export("greeter", new LifecycleGreeter("shutdown"), Greeter.class, export("grpc", Map.of()));
            assertEquals(1, runtimeB.list("greeter", List.of(Greeter.class.getName()), Map.of(), LookupOptions.defaults()).size());

            runtimeA.close();

            assertEquals(0, runtimeB.list("greeter", List.of(Greeter.class.getName()), Map.of(), LookupOptions.defaults()).size());
        } finally {
            runtimeA.close();
        }
    }

    @Test
    public void failedPrepareExportRollsBackInitializedLifecycleWithoutVisibleReferences() {
        TestRegistry registry = new TestRegistry();
        PrepareFailureGreeter greeter = new PrepareFailureGreeter("startup");
        try (DefaultMiddlewareRuntime runtime = runtime("prepare-startup-failure", registry, new GrpcTransportProvider())) {
            MiddlewareException exception = assertThrows(MiddlewareException.class,
                    () -> runtime.prepareExport("prepare-startup", greeter, Greeter.class, export("grpc", Map.of())));

            assertEquals(MiddlewareErrorCode.INTERNAL, exception.code());
            assertEquals(List.of("initialize", "startup", "shutdown"), greeter.events);
            assertEquals(0, runtime.status().instances().size());
            assertEquals(0, runtime.status().registryRecords().size());
            assertEquals(0, runtime.list("prepare-startup", List.of(Greeter.class.getName()), Map.of(), LookupOptions.defaults()).size());
        }
    }

    @Test
    public void failedPrepareExportBeforeInitializationDoesNotRunShutdown() {
        TestRegistry registry = new TestRegistry();
        PrepareFailureGreeter greeter = new PrepareFailureGreeter("initialize");
        try (DefaultMiddlewareRuntime runtime = runtime("prepare-initialize-failure", registry, new GrpcTransportProvider())) {
            MiddlewareException exception = assertThrows(MiddlewareException.class,
                    () -> runtime.prepareExport("prepare-initialize", greeter, Greeter.class, export("grpc", Map.of())));

            assertEquals(MiddlewareErrorCode.INTERNAL, exception.code());
            assertEquals(List.of("initialize"), greeter.events);
            assertEquals(0, runtime.status().instances().size());
            assertEquals(0, runtime.status().registryRecords().size());
        }
    }

    @Test
    public void failedProviderExportIsUnexportedAndCanBeRetried() {
        TestRegistry registry = new TestRegistry();
        FailingPrepareTransportProvider provider = new FailingPrepareTransportProvider("prep", true, false);
        PrepareFailureGreeter first = new PrepareFailureGreeter("");
        PrepareFailureGreeter second = new PrepareFailureGreeter("");
        try (DefaultMiddlewareRuntime runtime = runtime("prepare-provider-failure", registry, provider)) {
            MiddlewareException exception = assertThrows(MiddlewareException.class,
                    () -> runtime.prepareExport("prepare-provider", first, Greeter.class, export("prep", Map.of())));

            assertEquals(MiddlewareErrorCode.INTERNAL, exception.code());
            assertEquals(1, provider.exportAttempts);
            assertEquals(1, provider.unexports);
            assertEquals(List.of("initialize", "startup", "shutdown"), first.events);
            assertEquals(0, runtime.status().instances().size());
            assertEquals(0, runtime.status().registryRecords().size());

            PreparedObjectRegistration<Greeter> prepared = runtime.prepareExport("prepare-provider", second, Greeter.class,
                    export("prep", Map.of()));
            try (ObjectRegistration<Greeter> registration = prepared.commit()) {
                assertEquals(2, provider.exportAttempts);
                assertEquals(1, provider.unexports);
                assertEquals(1, runtime.list("prepare-provider", List.of(Greeter.class.getName()), Map.of(), LookupOptions.defaults()).size());
                assertEquals(registration.ref().objectId(), runtime.status().instances().getFirst().ref().objectId());
            }
        }
    }

    @Test
    public void failedProviderExportPreservesPrimaryFailureAndInterruptCleanupSignal() {
        TestRegistry registry = new TestRegistry();
        FailingPrepareTransportProvider provider = new FailingPrepareTransportProvider("prep-cleanup", true, true);
        PrepareFailureGreeter greeter = new PrepareFailureGreeter("");
        boolean interruptedBefore = Thread.interrupted();
        assertFalse(interruptedBefore);
        try (DefaultMiddlewareRuntime runtime = runtime("prepare-cleanup-failure", registry, provider)) {
            MiddlewareException exception = assertThrows(MiddlewareException.class,
                    () -> runtime.prepareExport("prepare-cleanup", greeter, Greeter.class, export("prep-cleanup", Map.of())));

            assertEquals(MiddlewareErrorCode.INTERNAL, exception.code());
            assertInstanceOf(IllegalStateException.class, exception.getCause());
            assertEquals("primary export failure", exception.getCause().getMessage());
            assertEquals(1, exception.getSuppressed().length);
            assertFalse(exception.getMessage().contains("cleanup"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, runtime.status().instances().size());
            assertEquals(0, runtime.status().registryRecords().size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void dispatchReportsMethodNotFoundAndInterfaceMismatch() {
        TestRegistry registry = new TestRegistry();
        try (DefaultMiddlewareRuntime server = runtime("method-server", registry, "grpc")) {
            ObjectRegistration<Greeter> registration = server.export("greeter", new LifecycleGreeter("method"), Greeter.class, export("grpc", Map.of()));

            MiddlewareException missingMethod = assertThrows(MiddlewareException.class, () -> server.dispatch(
                    registration.ref(),
                    new MethodDescriptor(Greeter.class.getName(), "missing", List.of(), Void.TYPE.getName(), null),
                    List.of(),
                    InvocationOptions.defaults()));
            assertEquals(MiddlewareErrorCode.METHOD_NOT_FOUND, missingMethod.code());

            MiddlewareException badInterface = assertThrows(MiddlewareException.class, () -> server.dispatch(
                    registration.ref(),
                    new MethodDescriptor(OtherApi.class.getName(), "other", List.of(), Void.TYPE.getName(), null),
                    List.of(),
                    InvocationOptions.defaults()));
            assertEquals(MiddlewareErrorCode.INTERFACE_MISMATCH, badInterface.code());
        }
    }

    @Test
    public void inheritedInterfaceMethodsRemainInvocableThroughRemoteProxy() {
        TestRegistry registry = new TestRegistry();
        try (DefaultMiddlewareRuntime server = runtime("inherit-server", registry, "grpc");
             DefaultMiddlewareRuntime client = runtime("inherit-client", registry, "grpc")) {
            server.export("child", new ChildServiceImpl(), ChildService.class, export("grpc", Map.of()));

            ChildService service = client.lookup("child", ChildService.class, LookupOptions.defaults());

            assertEquals("parent", service.parentName());
            assertEquals("child", service.childName());
        }
    }

    @Test
    public void haDoesNotRetryNonIdempotentMethodAfterDirtyTargetFailure() {
        TestRegistry registry = new TestRegistry();
        EmbeddedRegistryClient clientRegistry = new EmbeddedRegistryClient(registry.store, Clock.systemUTC());
        CountingHaTransportProvider provider = new CountingHaTransportProvider();
        provider.fail("a-bad-object", "register", MiddlewareException.remote(
                MiddlewareErrorCode.UNAVAILABLE, "dirty target after dispatch", "dirty-non-idempotent",
                "a-bad-runtime", true, true));
        provider.fail("a-bad-object", "greet", MiddlewareException.remote(
                MiddlewareErrorCode.UNAVAILABLE, "dirty target after dispatch", "dirty-idempotent",
                "a-bad-runtime", true, true));
        ObjectRef badRef = haRef("a-bad-object", "a-bad-runtime", "primary");
        ObjectRef goodRef = haRef("z-good-object", "z-good-runtime", "primary");
        long nowMillis = System.currentTimeMillis();
        clientRegistry.bind(registryRecord(badRef, nowMillis));
        clientRegistry.bind(registryRecord(goodRef, nowMillis));
        try (DefaultMiddlewareRuntime client = runtime("client-runtime", clientRegistry, Clock.systemUTC(), provider)) {
            Greeter ha = client.lookup("greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            MiddlewareException nonIdempotentFailure = assertThrows(MiddlewareException.class, () -> ha.register(new RecordingListener()));
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, nonIdempotentFailure.code());
            assertEquals(List.of("a-bad-object:register"), provider.attempts());
            assertEquals(1, provider.invocations("a-bad-object"));
            assertEquals(0, provider.invocations("z-good-object"));

            assertEquals("hello good retry", ha.greet("retry"));
            assertEquals(List.of("a-bad-object:register", "z-good-object:greet"), provider.attempts());
            assertEquals(1, provider.invocations("a-bad-object"));
            assertEquals(1, provider.invocations("z-good-object"));
        }
    }

    @Test
    public void haRetriesIdempotentDirtyTargetOnlyWhenPolicyPermitsAndKeepsOriginalDeadline() {
        TestRegistry registry = new TestRegistry();
        EmbeddedRegistryClient clientRegistry = new EmbeddedRegistryClient(registry.store, Clock.systemUTC());
        CountingHaTransportProvider provider = new CountingHaTransportProvider();
        provider.fail("a-bad-object", "greet", MiddlewareException.remote(
                MiddlewareErrorCode.UNAVAILABLE, "dirty target after dispatch", "dirty-idempotent",
                "a-bad-runtime", true, true));
        ObjectRef badRef = haRef("a-bad-object", "a-bad-runtime", "primary");
        ObjectRef goodRef = haRef("z-good-object", "z-good-runtime", "primary");
        long nowMillis = System.currentTimeMillis();
        clientRegistry.bind(registryRecord(badRef, nowMillis));
        clientRegistry.bind(registryRecord(goodRef, nowMillis));
        try (DefaultMiddlewareRuntime client = runtime("client-runtime", clientRegistry, Clock.systemUTC(), provider)) {
            Greeter ha = client.lookup("greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofMillis(750L), true, true));

            assertEquals("hello good retry", ha.greet("retry"));

            assertEquals(List.of("a-bad-object:greet", "z-good-object:greet"), provider.attempts());
            assertEquals(1, provider.invocations("a-bad-object"));
            assertEquals(1, provider.invocations("z-good-object"));
            assertFalse(provider.deadlineEpochMillis("a-bad-object").isBlank());
            assertEquals(provider.deadlineEpochMillis("a-bad-object"), provider.deadlineEpochMillis("z-good-object"));
        }
    }

    @Test
    public void haDoesNotRetryCleanPreDispatchTransportFailure() {
        TestRegistry registry = new TestRegistry();
        EmbeddedRegistryClient clientRegistry = new EmbeddedRegistryClient(registry.store, Clock.systemUTC());
        CountingHaTransportProvider provider = new CountingHaTransportProvider();
        provider.fail("a-clean-object", "greet", MiddlewareException.remote(
                MiddlewareErrorCode.UNAVAILABLE, "clean transport failure before dispatch", "clean-pre-dispatch",
                "a-clean-runtime", true, false));
        ObjectRef cleanRef = haRef("a-clean-object", "a-clean-runtime", "primary");
        ObjectRef goodRef = haRef("z-good-object", "z-good-runtime", "primary");
        long nowMillis = System.currentTimeMillis();
        clientRegistry.bind(registryRecord(cleanRef, nowMillis));
        clientRegistry.bind(registryRecord(goodRef, nowMillis));
        try (DefaultMiddlewareRuntime client = runtime("client-runtime", clientRegistry, Clock.systemUTC(), provider)) {
            Greeter ha = client.lookup("greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> ha.greet("retry"));

            assertEquals(MiddlewareErrorCode.UNAVAILABLE, failure.code());
            assertEquals(List.of("a-clean-object:greet"), provider.attempts());
            assertEquals(1, provider.invocations("a-clean-object"));
            assertEquals(0, provider.invocations("z-good-object"));
        }
    }

    @Test
    public void haIdempotentDirtyTargetFailoverIsEquivalentForGrpcAndRmi() {
        assertIdempotentDirtyTargetFailoverForTransport("grpc");
        assertIdempotentDirtyTargetFailoverForTransport("rmi");
    }

    @Test
    public void coreAndRuntimeDoNotDependOnConcreteTransportModules() {
        assertFalse(importsConcreteTransport("j7m-kernel"));
        assertFalse(importsConcreteTransport("j7m-runtime"));
    }

    private static boolean importsConcreteTransport(final String moduleName) {
        java.nio.file.Path root = java.nio.file.Path.of(moduleName, "src", "main", "java");
        if (!java.nio.file.Files.exists(root)) {
            return false;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    String source = java.nio.file.Files.readString(path);
                    return source.contains("org.j7m.middleware.transport.grpc") || source.contains("org.j7m.middleware.transport.rmi");
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            });
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final TestRegistry registry, final String transport) {
        return runtime(id, new EmbeddedRegistryClient(registry.store, Clock.systemUTC()), Clock.systemUTC(), transport);
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final TestRegistry registry, final TransportProvider transport) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(registry.store, Clock.systemUTC()))
                .clock(Clock.systemUTC())
                .addTransport(transport)
                .disableNuggetAdmin()
                .build();
    }

    private static DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryClient registry,
            final Clock clock,
            final TransportProvider transport) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(registry)
                .clock(clock)
                .addTransport(transport)
                .disableNuggetAdmin()
                .build();
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final EmbeddedRegistryClient registry, final Clock clock, final String transport) {
        MiddlewareRuntimeBuilder builder = new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(registry)
                .clock(clock);
        if ("grpc".equals(transport)) {
            builder.addTransport(new GrpcTransportProvider());
        } else if ("rmi".equals(transport)) {
            builder.addTransport(new RmiTransportProvider());
        } else {
            throw new IllegalArgumentException(transport);
        }
        builder.disableNuggetAdmin();
        return builder.build();
    }

    private static void assertIdempotentDirtyTargetFailoverForTransport(final String transport) {
        TestRegistry registry = new TestRegistry();
        DirtyFailoverGreeter bad = new DirtyFailoverGreeter("bad", true);
        DirtyFailoverGreeter good = new DirtyFailoverGreeter("good", false);
        try (DefaultMiddlewareRuntime badRuntime = runtime("a-bad-runtime-" + transport, registry, transport);
             DefaultMiddlewareRuntime goodRuntime = runtime("z-good-runtime-" + transport, registry, transport);
             DefaultMiddlewareRuntime client = runtime("m-client-runtime-" + transport, registry, transport)) {
            badRuntime.export("greeter", bad, Greeter.class, export(transport, Map.of("role", "primary")));
            goodRuntime.export("greeter", good, Greeter.class, export(transport, Map.of("role", "primary")));
            Greeter ha = client.lookup("greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            assertEquals("hello good retry", ha.greet("retry"));

            assertEquals(1, bad.greetAttempts());
            assertEquals(1, good.greetAttempts());
        }
    }

    private static ObjectRef haRef(final String objectId, final String runtimeId, final String role) {
        long leaseUntil = System.currentTimeMillis() + 30_000L;
        Map<String, String> metadata = Map.of(
                "role", role,
                "trustStatus", "trusted",
                "fencingToken", "1",
                "topologyGeneration", "1");
        return new ObjectRef(objectId, "greeter/" + objectId, "greeter", runtimeId, "default",
                List.of(Greeter.class.getName()), HA_TEST_TRANSPORT, HA_TEST_TRANSPORT + "://" + objectId,
                leaseUntil, 1L, metadata);
    }

    private static org.j7m.middleware.registry.api.RegistryRecord registryRecord(final ObjectRef ref, final long nowMillis) {
        return new org.j7m.middleware.registry.api.RegistryRecord(
                ref, ref.metadata(), nowMillis, nowMillis, ref.leaseUntil(), org.j7m.middleware.api.ObjectState.EXECUTED);
    }

    private static ExportOptions export(final String transport, final Map<String, String> metadata) {
        return new ExportOptions(false, transport, "default", List.of(), metadata, Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    /**
     * Test service API.
     */
    public interface Greeter {
        /**
         * Greets a name.
         */
        @Idempotent
        String greet(String name);

        /**
         * Registers a listener callback.
         */
        @NonIdempotent
        void register(Listener listener);

        /**
         * Emits a callback message.
         */
        void fire(String message);

        /**
         * Returns the current listener by reference.
         */
        Listener listener();

        /**
         * Throws a remote failure.
         */
        void fail();
    }

    /**
     * Test callback API.
     */
    public interface Listener {
        /**
         * Receives a message.
         */
        void onMessage(String message);
    }

    /**
     * Interface used for mismatch dispatch tests.
     */
    public interface OtherApi {
        /**
         * Marker method not exposed by Greeter.
         */
        void other();
    }

    /**
     * Parent API used to verify inherited interface dispatch.
     */
    public interface ParentService {
        /**
         * Returns parent method value.
         */
        String parentName();
    }

    /**
     * Child API used to verify inherited interface dispatch.
     */
    public interface ChildService extends ParentService {
        /**
         * Returns child method value.
         */
        String childName();
    }

    private static final class ChildServiceImpl implements ChildService {
        @Override
        public String parentName() {
            return "parent";
        }

        @Override
        public String childName() {
            return "child";
        }
    }

    private static class LifecycleGreeter implements Greeter {
        private final String name;
        protected final List<String> events;
        private Listener listener;

        private LifecycleGreeter(final String name) {
            this.name = name;
            this.events = new ArrayList<>();
        }

        @SuppressWarnings("PMD.UnusedPrivateMethod")
        @Initialize
        private void initialize() {
            this.events.add("initialize");
        }

        @SuppressWarnings("PMD.UnusedPrivateMethod")
        @Startup
        private void startup() {
            this.events.add("startup");
        }

        @SuppressWarnings("PMD.UnusedPrivateMethod")
        @Shutdown
        private void shutdown() {
            this.events.add("shutdown");
        }

        @Override
        public String greet(final String caller) {
            return "hello " + this.name + " " + caller;
        }

        @Override
        public void register(final Listener newListener) {
            this.listener = newListener;
        }

        @Override
        public void fire(final String message) {
            this.listener.onMessage(message);
        }

        @Override
        public Listener listener() {
            return this.listener;
        }

        @Override
        public void fail() {
            throw new IllegalStateException("boom");
        }
    }

    private static final class PrepareFailureGreeter extends LifecycleGreeter {
        private final String failureStage;

        private PrepareFailureGreeter(final String failureStage) {
            super("prepare");
            this.failureStage = failureStage;
        }

        @SuppressWarnings("PMD.UnusedPrivateMethod")
        @Initialize
        private void initializeForPrepareFailure() {
            this.events.add("initialize");
            if ("initialize".equals(this.failureStage)) {
                throw new IllegalStateException("initialize failure");
            }
        }

        @SuppressWarnings("PMD.UnusedPrivateMethod")
        @Startup
        private void startupForPrepareFailure() {
            this.events.add("startup");
            if ("startup".equals(this.failureStage)) {
                throw new IllegalStateException("startup failure");
            }
        }

        @SuppressWarnings("PMD.UnusedPrivateMethod")
        @Shutdown
        private void shutdownForPrepareFailure() {
            this.events.add("shutdown");
        }
    }

    private static final class DirtyFailoverGreeter extends LifecycleGreeter {
        private final boolean failDirty;
        private final AtomicInteger greetAttempts;

        private DirtyFailoverGreeter(final String name, final boolean failDirty) {
            super(name);
            this.failDirty = failDirty;
            this.greetAttempts = new AtomicInteger();
        }

        @Override
        public String greet(final String caller) {
            this.greetAttempts.incrementAndGet();
            if (this.failDirty) {
                throw MiddlewareException.remote(MiddlewareErrorCode.UNAVAILABLE, "dirty target after dispatch",
                        "dirty-concrete-transport", "dirty-runtime", true, true);
            }
            return super.greet(caller);
        }

        private int greetAttempts() {
            return this.greetAttempts.get();
        }
    }

    private static final class CountingHaTransportProvider implements TransportProvider {
        private final Map<String, MiddlewareException> failures;
        private final Map<String, AtomicInteger> counts;
        private final Map<String, String> deadlineEpochMillis;
        private final List<String> attempts;
        private boolean running;

        private CountingHaTransportProvider() {
            this.failures = new java.util.HashMap<>();
            this.counts = new java.util.HashMap<>();
            this.deadlineEpochMillis = new java.util.HashMap<>();
            this.attempts = new ArrayList<>();
        }

        private void fail(final String objectId, final String methodName, final MiddlewareException failure) {
            this.failures.put(this.key(objectId, methodName), failure);
        }

        private List<String> attempts() {
            return List.copyOf(this.attempts);
        }

        private int invocations(final String objectId) {
            AtomicInteger count = this.counts.get(objectId);
            return count == null ? 0 : count.get();
        }

        private String deadlineEpochMillis(final String objectId) {
            return this.deadlineEpochMillis.getOrDefault(objectId, "");
        }

        @Override
        public String id() {
            return HA_TEST_TRANSPORT;
        }

        @Override
        public TransportEndpoint start(final RuntimeTransportContext context) {
            this.running = true;
            return new TransportEndpoint(HA_TEST_TRANSPORT, HA_TEST_TRANSPORT + "://client");
        }

        @Override
        public ExportedObject export(final ObjectRef ref, final Object target, final ExportDescriptor descriptor) {
            return new ExportedObject(ref, descriptor);
        }

        @Override
        public void unexport(final ObjectRef ref) {
        }

        @Override
        public Object invoke(
                final ObjectRef target,
                final MethodDescriptor method,
                final List<EncodedValue> arguments,
                final InvocationOptions options) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "test transport invoke is not used directly");
        }

        @Override
        public <T> T createProxy(final ObjectRef ref, final Class<T> api, final ProxyOptions options) {
            if (!this.running) {
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "test transport is not running");
            }
            Object proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, (Object ignored, java.lang.reflect.Method method, Object[] args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return this.objectMethod(ref, method, args);
                }
                this.record(ref, method.getName(), options);
                MiddlewareException failure = this.failures.get(this.key(ref.objectId(), method.getName()));
                if (failure != null) {
                    throw failure;
                }
                if ("greet".equals(method.getName())) {
                    return "hello good " + args[0];
                }
                if ("register".equals(method.getName()) || "fire".equals(method.getName())) {
                    return null;
                }
                if ("listener".equals(method.getName())) {
                    return null;
                }
                if ("fail".equals(method.getName())) {
                    throw new IllegalStateException("boom");
                }
                throw MiddlewareException.of(MiddlewareErrorCode.METHOD_NOT_FOUND, "test method is not implemented");
            });
            return api.cast(proxy);
        }

        @Override
        public void close() {
            this.running = false;
        }

        private void record(final ObjectRef ref, final String methodName, final ProxyOptions options) {
            this.attempts.add(ref.objectId() + ":" + methodName);
            this.counts.computeIfAbsent(ref.objectId(), ignored -> new AtomicInteger()).incrementAndGet();
            this.deadlineEpochMillis.putIfAbsent(ref.objectId(), options.metadata().getOrDefault(DEADLINE_EPOCH_MILLIS, ""));
        }

        private Object objectMethod(final ObjectRef ref, final java.lang.reflect.Method method, final Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "Counting HA proxy -> " + ref.objectId();
                case "hashCode" -> Integer.valueOf(ref.objectId().hashCode());
                case "equals" -> Boolean.valueOf(args != null && args.length == 1 && args[0] == this);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private String key(final String objectId, final String methodName) {
            return objectId + '\n' + methodName;
        }
    }

    private static final class FailingPrepareTransportProvider implements TransportProvider {
        private final String id;
        private boolean failNextExport;
        private final boolean failUnexport;
        private int exportAttempts;
        private int unexports;
        private boolean running;

        private FailingPrepareTransportProvider(
                final String id,
                final boolean failNextExport,
                final boolean failUnexport) {
            this.id = id;
            this.failNextExport = failNextExport;
            this.failUnexport = failUnexport;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public TransportEndpoint start(final RuntimeTransportContext context) {
            if (context == null) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "transport context is required");
            }
            this.running = true;
            return new TransportEndpoint(this.id, this.id + "://test-local/" + context.identity().runtimeId());
        }

        @Override
        public ExportedObject export(final ObjectRef ref, final Object target, final ExportDescriptor descriptor) {
            if (!this.running) {
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "transport is not running");
            }
            this.exportAttempts++;
            ExportedObject exported = new ExportedObject(ref, descriptor);
            if (this.failNextExport) {
                this.failNextExport = false;
                throw new IllegalStateException("primary export failure");
            }
            return exported;
        }

        @Override
        public void unexport(final ObjectRef ref) {
            this.unexports++;
            if (this.failUnexport) {
                throw new IllegalStateException("cleanup failure", new InterruptedException("cleanup interrupted"));
            }
        }

        @Override
        public Object invoke(
                final ObjectRef target,
                final MethodDescriptor method,
                final List<EncodedValue> arguments,
                final InvocationOptions options) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "test transport does not invoke");
        }

        @Override
        public <T> T createProxy(final ObjectRef ref, final Class<T> api, final ProxyOptions options) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "test transport does not create proxies");
        }

        @Override
        public void close() {
            this.running = false;
        }
    }

    private static final class RecordingListener implements Listener {
        private final List<String> messages;

        private RecordingListener() {
            this.messages = new ArrayList<>();
        }

        @Override
        public void onMessage(final String message) {
            this.messages.add(message);
        }
    }

    private static final class TestRegistry {
        private final EmbeddedRegistryStore store;

        private TestRegistry() {
            this.store = new EmbeddedRegistryStore();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(final Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }

        private void advance(final Duration duration) {
            this.instant = this.instant.plus(duration);
        }
    }
}
