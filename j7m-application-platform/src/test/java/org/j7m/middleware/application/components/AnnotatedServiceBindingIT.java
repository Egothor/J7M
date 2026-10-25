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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.processor.J7mProcessor;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotatedServiceBindingIT {
    @TempDir Path directory;
    private URLClassLoader compile(String policy) throws Exception { return compile(policy, false); }
    private URLClassLoader compile(String policy, boolean optional) throws Exception {
        Path classes = directory.resolve("classes"); Files.createDirectories(classes); Path source = directory.resolve("Consumer.java");
        Files.writeString(source, optional ? """
                package annotated;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.components.LiveBindingNodeProcess.Business;
                @J7mComponent public class Consumer {
                    @ServiceRef(name="annotated-service",policy="optional",refresh=BindingRefresh.ON_TOPOLOGY_CHANGE)
                    public ManagedProvider<java.util.Optional<Business>> optional;
                    public String call(){return optional.get().map(Business::identify).orElse("absent");}
                }
                """ : """
                package annotated;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.components.LiveBindingNodeProcess.Business;
                @J7mComponent
                public class Consumer {
                    public static int created;
                    public final Business direct;
                    public final ManagedProvider<Business> provider;
                    @ServiceRef(name="annotated-service",policy="%s") public Business pinned;
                    @Inject public Consumer(
                        @ServiceRef(name="annotated-service",refresh=BindingRefresh.ON_TOPOLOGY_CHANGE,policy="%s") Business direct,
                        @Inject(refresh=BindingRefresh.ON_FAILURE,policy="%s") @Named("annotated-service") ManagedProvider<Business> provider) {
                        this.direct=direct;this.provider=provider;created++;
                    }
                    public String call(){return direct.identify()+"/"+provider.get().identify()+"/"+pinned.identify();}
                }
                """.formatted(policy, policy, policy));
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics, List.of("-classpath", System.getProperty("j7m.artifact.test.classpath"), "-d", classes.toString()), null, manager.getJavaFileObjects(source));
            task.setProcessors(List.of(new J7mProcessor())); assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }
    private static List<ManagedComponentIndex> indexes(ClassLoader loader) { return ServiceLoader.load(ManagedComponentIndex.class, loader).stream().map(ServiceLoader.Provider::get).toList(); }
    private static BindingPolicy policy() { return new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.WAIT,
            Duration.ofSeconds(2), Duration.ofMillis(30), Duration.ofMillis(300), Duration.ofSeconds(2), 16); }
    @Test void actualGeneratedServiceRefAndInjectRefreshReachRemoteBackendAndPreserveDifferentPolicies() throws Exception {
        int port; try (var socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        String context = "annotated-" + UUID.randomUUID(); var fixture = new LiveServiceBindingProcessIT(); fixture.directory = directory;
        try (var b = fixture.new Node("b", context, port);
             var runtime = new MiddlewareRuntimeBuilder().runtimeId("client").registryContext(context).registry(LiveBindingNodeProcess.registry("client", context, port)).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
             var source = new PolledBindingCandidateSource(runtime);
             var owner = new LiveServiceBindings(source, new RuntimeBindingConnector(runtime), (request, ref) -> Set.of("a", "b").contains(ref.runtimeId()), "client", Map.of("network", policy()), () -> Instant.now().plusSeconds(2));
             var loader = compile("network")) {
            var consumer = loader.loadClass("annotated.Consumer"); var registry = new ManagedHostBindings(Map.of("service.name", new ServiceBindingFactory(owner, context)));
            try (var container = ManagedContainer.activate(indexes(loader), 4096, null, registry)) {
                Object instance = container.get(consumer, "", List.of()); assertEquals(1, consumer.getField("created").getInt(null)); assertEquals("b/b/b", consumer.getMethod("call").invoke(instance));
                Object stable = consumer.getField("direct").get(instance);
                try (var a = fixture.new Node("a", context, port)) {
                    // A separate direct observation waits for real discovery, without replaying effects.
                    var seen = new CountDownLatch(1);
                    try (var observation = source.watch(new ServiceBindingRequest<>(LiveBindingNodeProcess.Business.class, "annotated-service", LiveBindingNodeProcess.Business.class.getName(), 1, List.of(), context, org.j7m.middleware.annotations.BindingRefresh.NEVER), policy(), snapshot -> {
                        if (snapshot.candidates().stream().anyMatch(ref -> ref.runtimeId().equals("a"))) seen.countDown();
                    })) { assertTrue(seen.await(5, TimeUnit.SECONDS)); }
                    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String value;
                    do { value = (String) consumer.getMethod("call").invoke(instance); if (value.equals("a/b/b")) break; Thread.onSpinWait(); } while (System.nanoTime() < limit);
                    assertEquals("a/b/b", value); assertSame(stable, consumer.getField("direct").get(instance));
                }
            }
            assertEquals(0, owner.status().bindings()); assertEquals(0, source.status().subscriptions());
        }
    }
    @Test void generatedOptionalProviderRepresentsAbsenceAndLaterAuthorizedRemotePresence() throws Exception {
        int port; try (var socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        String context = "optional-" + UUID.randomUUID(); var fixture = new LiveServiceBindingProcessIT(); fixture.directory = directory;
        var optional = new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.OPTIONAL,
                Duration.ofSeconds(1), Duration.ofMillis(30), Duration.ofMillis(300), Duration.ofSeconds(1), 16);
        try (var runtime = new MiddlewareRuntimeBuilder().runtimeId("client").registryContext(context).registry(LiveBindingNodeProcess.registry("client", context, port)).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
             var source = new PolledBindingCandidateSource(runtime);
             var owner = new LiveServiceBindings(source, new RuntimeBindingConnector(runtime), (request, ref) -> ref.runtimeId().equals("b"), "client", Map.of("optional", optional), () -> Instant.now().plusSeconds(2));
             var loader = compile("unused", true)) {
            var consumer = loader.loadClass("annotated.Consumer");
            var registry = new ManagedHostBindings(Map.of("service.name", new ServiceBindingFactory(owner, context)));
            try (var container = ManagedContainer.activate(indexes(loader), 4096, null, registry)) {
                Object instance = container.get(consumer, "", List.of()); assertEquals("absent", consumer.getMethod("call").invoke(instance));
                try (var b = fixture.new Node("b", context, port)) {
                    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String value;
                    do { value = (String) consumer.getMethod("call").invoke(instance); if (value.equals("b")) break; Thread.onSpinWait(); } while (System.nanoTime() < limit);
                    assertEquals("b", value);
                }
            }
            assertEquals(0, owner.status().bindings());
        }
    }
    @Test void optionalProviderRejectsWrongHostPayloadAndOptionalPolicyRequiresExplicitProviderShape() throws Exception {
        try (var loader = compile("unused", true)) {
            var invalid = new ManagedHostBindings.Factory() {
                @Override public void validate(ManagedDependency edge, org.j7m.middleware.annotations.Scope.Kind scope) { assertTrue(edge.provider()); }
                @Override public ManagedHostBindings.BoundValue bind(ManagedDependency edge) {
                    return new ManagedHostBindings.BoundValue() {
                        @Override public Object get() { return Optional.of("wrong business type"); }
                        @Override public void close() { }
                    };
                }
            };
            try (var container = ManagedContainer.activate(indexes(loader), 4096, null, new ManagedHostBindings(Map.of("service.name", invalid)))) {
                Class<?> consumer = loader.loadClass("annotated.Consumer"); Object instance = container.get(consumer, "", List.of());
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> consumer.getMethod("call").invoke(instance));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }
        }
        var source = new LiveServiceBindingsTest.Source();
        var optional = new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.OPTIONAL,
                Duration.ofSeconds(1), Duration.ofMillis(30), Duration.ofMillis(300), Duration.ofSeconds(1), 16);
        try (var owner = new LiveServiceBindings(source, (reference, type, options) -> null, (request, ref) -> false, "local", Map.of("optional", optional), () -> Instant.now().plusSeconds(1))) {
            var factory = new ServiceBindingFactory(owner, "tenant");
            Map<String, String> attributes = Map.of("service.name", "service", "service.contract.id", "business", "service.contract.version", "1", "binding.refresh", "NEVER", "binding.policy", "optional", "binding.optional", "true");
            assertThrows(IllegalArgumentException.class, () -> factory.validate(new ManagedDependency(LiveBindingNodeProcess.Business.class, "", List.of(), false, attributes), org.j7m.middleware.annotations.Scope.Kind.ACTIVATION));
            assertEquals(0, source.observers.size());
        }
    }
    @Test void missingHostAdapterAndUnknownPolicyRejectBeforeAnyConsumerConstruction() throws Exception {
        try (var loader = compile("missing")) {
            Class<?> consumer = loader.loadClass("annotated.Consumer"); var indexes = indexes(loader);
            assertThrows(IllegalStateException.class, () -> ManagedContainer.activate(indexes)); assertEquals(0, consumer.getField("created").getInt(null));
            var fakeSource = new LiveServiceBindingsTest.Source();
            try (var owner = new LiveServiceBindings(fakeSource, (ref, type, options) -> null, (q, r) -> false, "local", Map.of("network", policy()), () -> Instant.now().plusSeconds(1))) {
                var registry = new ManagedHostBindings(Map.of("service.name", new ServiceBindingFactory(owner, "tenant")));
                assertThrows(IllegalStateException.class, () -> ManagedContainer.activate(indexes, 4096, null, registry));
                assertEquals(0, consumer.getField("created").getInt(null)); assertEquals(0, fakeSource.observers.size());
            }
        }
    }
}
