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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.integrations.Observability;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.*;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

class ManagedObservedPolicyIT {
    public interface Service { @Async @Observed("application") @Bulkhead("dependency") CompletionStage<String> call(); }
    static final class Body implements Service {
        private final Observability observer;
        private final List<String> carriers;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        Body(Observability observer, List<String> carriers, CountDownLatch entered, CountDownLatch release) {
            this.observer = observer; this.carriers = carriers; this.entered = entered; this.release = release;
        }
        @Override public CompletionStage<String> call() {
            return J7mContext.currentInvocation().control().submit(() -> {
                String trace = InvocationTraceContext.current().get("traceparent"); assertNotNull(trace);
                assertEquals(trace, observer.capture().traceparent()); carriers.add(trace);
                if (entered != null) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
                return "SecretPayload";
            });
        }
    }
    @Test void observedAsyncChildrenAndActualTargetRefreshPreserveOneTraceWithoutDuplicateRpcSpans() throws Exception {
        var bodies = new LinkedBlockingQueue<String>();
        var collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        collector.createContext("/v1/traces", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        }); collector.start();
        var carriers = new CopyOnWriteArrayList<String>();
        String parent = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
        try (var observer = new Observability(URI.create("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"),
                    new InetSocketAddress("127.0.0.1", 0), 16);
                var first = new Hosted("observed-first", observer, carriers); var second = new Hosted("observed-second", observer, carriers);
                var client = new MiddlewareRuntimeBuilder().runtimeId("observed-client").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            AtomicReference<Consumer<BindingCandidates>> watch = new AtomicReference<>();
            BindingCandidateSource source = (request, policy, consumer) -> {
                watch.set(consumer); consumer.accept(new BindingCandidates(1, List.of(first.registration.ref()), true));
                return () -> watch.set(null);
            };
            var policy = new BindingPolicy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.FAIL_FAST,
                    Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofSeconds(2), Duration.ofSeconds(2), 8);
            try (var bindings = new LiveServiceBindings(source, new RuntimeBindingConnector(client), (request, target) -> true,
                        "observed-client", Map.of("binding", policy), () -> Instant.now().plusSeconds(2));
                    var binding = bindings.open(new ServiceBindingRequest<>(Service.class, "service", "observed", 1, List.of(), "tenant",
                            BindingRefresh.ON_TOPOLOGY_CHANGE), "binding")) {
                String expectedParent;
                try (var root = InvocationTraceContext.open(Map.of("traceparent", parent, "tracestate", "SecretVendorToken"));
                        var rpc = observer.start(Observability.Operation.RPC)) {
                    expectedParent = rpc.carrier().traceparent().substring(36, 52);
                    var proxy = binding.proxy();
                    assertEquals("SecretPayload", proxy.call().toCompletableFuture().get(3, TimeUnit.SECONDS));
                    first.close(); watch.get().accept(new BindingCandidates(2, List.of(second.registration.ref()), true));
                    assertSame(proxy, binding.proxy());
                    assertEquals("SecretPayload", proxy.call().toCompletableFuture().get(3, TimeUnit.SECONDS));
                }
                assertEquals(2, carriers.size()); assertNotEquals(carriers.get(0), carriers.get(1));
                assertTrue(carriers.stream().allMatch(value -> value.substring(3, 35).equals(parent.substring(3, 35))));
                List<String> received = new ArrayList<>(); int spans = 0;
                while (spans < 3) {
                    String body = bodies.poll(3, TimeUnit.SECONDS); assertNotNull(body);
                    received.add(body); spans += body.split("\\\"traceId\\\"", -1).length - 1;
                }
                String exported = String.join("\n", received);
                var parents = Pattern.compile("\\\"parentSpanId\\\":\\\"([^\\\"]*)\\\",\\\"name\\\":\\\"application_handler\\\"").matcher(exported);
                int applicationSpans = 0;
                while (parents.find()) { assertEquals(expectedParent, parents.group(1)); applicationSpans++; }
                assertEquals(2, applicationSpans);
                assertTrue(observer.scrape().contains("j7m_operations_total{operation=\"application_handler\"} 2"));
                assertTrue(observer.scrape().contains("j7m_operations_total{operation=\"rpc\"} 1"));
                for (String secret : List.of("SecretPayload", "SecretVendorToken", "principal", "tracestate", "tenant")) assertFalse(exported.contains(secret));
                assertTrue(InvocationTraceContext.current().isEmpty());
            }
        } finally { collector.stop(0); }
    }

    @Test void realAsyncBulkheadRetainsItsSlotAfterCallerTimeoutUntilPhysicalWorkEnds() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var observer = new Observability(URI.create("http://127.0.0.1:9/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 8);
                var host = new Hosted("held-bulkhead", observer, new CopyOnWriteArrayList<>(), entered, release);
                var client = new MiddlewareRuntimeBuilder().runtimeId("held-client").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            try {
                var shortCaller = client.reference(host.registration.ref(), Service.class, new ProxyOptions(Duration.ofMillis(150), Map.of()));
                var reply = shortCaller.call().toCompletableFuture();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var expired = assertThrows(ExecutionException.class, () -> reply.get(2, TimeUnit.SECONDS));
                assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, ((MiddlewareException) expired.getCause()).code());
                assertEquals(1, host.resilience.status("tenant", "dependency").active());
                var next = client.reference(host.registration.ref(), Service.class, ProxyOptions.defaults()).call().toCompletableFuture();
                var overloaded = assertThrows(ExecutionException.class, () -> next.get(2, TimeUnit.SECONDS));
                assertEquals(MiddlewareErrorCode.OVERLOADED, ((MiddlewareException) overloaded.getCause()).code());
                assertFalse(host.lifetimes.getFirst().physicalCompletion().toCompletableFuture().isDone());
                release.countDown(); host.lifetimes.getFirst().physicalCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(0, host.resilience.status("tenant", "dependency").active());
                assertEquals(0, host.container.status().activeCalls());
            } finally { release.countDown(); }
        }
    }

    /**
     * Test host uses the public prepared-export ownership SPI and the real production policy.
     */
    private static final class Hosted implements AutoCloseable {
        final org.j7m.middleware.core.DefaultMiddlewareRuntime runtime;
        final ManagedContainer container = ManagedContainer.activate(List.of());
        final ThreadPoolExecutor workers = ManagedInvocationSettings.defaults().newExecutor();
        final ManagedInvocationPolicy.Binding binding;
        final ManagedInvocationPolicy.Binding admission;
        final ManagedResiliencePolicy resilience;
        final List<ManagedInvocationLifetime> lifetimes = new CopyOnWriteArrayList<>();
        final ObjectRegistration<Service> registration;
        final PreparedObjectRegistration<Service> prepared;
        final ThreadLocal<InvocationCompletionScope> current = new ThreadLocal<>();
        final AtomicBoolean closed = new AtomicBoolean();
        Hosted(String id, Observability observer, List<String> carriers) throws Exception { this(id, observer, carriers, null, null); }
        Hosted(String id, Observability observer, List<String> carriers, CountDownLatch entered, CountDownLatch release) throws Exception {
            runtime = new MiddlewareRuntimeBuilder().runtimeId(id).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
            var policy = new ManagedObservedPolicy(Map.of("application", observer));
            var contract = new ManagedServiceContract("observed", 1, new ManagedMethod(Service.class.getName(), "call", List.of(), CompletionStage.class.getName()));
            policy.validate(contract, Body.class, Service.class); binding = policy.bind(contract, Body.class, Service.class);
            resilience = new ManagedResiliencePolicy(Set.of("tenant"), ignored -> "tenant",
                    Map.of("dependency", new BulkheadProfile(1, 0, Duration.ZERO)), Map.of(), null);
            admission = resilience.bind(contract, Body.class, Service.class);
            var body = new Body(observer, carriers, entered, release);
            Service exported = (Service) Proxy.newProxyInstance(Service.class.getClassLoader(), new Class<?>[] {Service.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Observed test host";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
                InvocationCompletionScope owned = Objects.requireNonNull(current.get());
                return binding.invoke(J7mContext.currentInvocation(), new Object[0], new ManagedInvocationPolicy.Proceed() {
                    @Override public Object invoke() throws Exception {
                        return admission.invoke(J7mContext.currentInvocation(), new Object[0], new ManagedInvocationPolicy.Proceed() {
                            @Override public Object invoke() { owned.markApplicationEntry(); return body.call(); }
                            @Override public CompletionStage<Void> physicalCompletion() { return owned.physicalCompletion(); }
                        });
                    }
                    @Override public boolean supportsResultFinalizers() { return owned.supportsResultFinalizers(); }
                    @Override public void addResultFinalizer(InvocationResultFinalizer finalizer) { owned.addResultFinalizer(finalizer); }
                    @Override public CompletionStage<Void> physicalCompletion() { return owned.physicalCompletion(); }
                });
            });
            var options = new ExportOptions(false, "grpc", "tenant", List.of(), Map.of("j7m.contract.id", "observed", "j7m.contract.version", "1",
                    "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())), Duration.ofMinutes(1), Duration.ofSeconds(10));
            prepared = runtime.prepareExport("service", exported, Service.class, options);
            prepared.setInvocationCompletionFactory(boundary -> {
                var lifetime = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(),
                        boundary.security(), boundary.options().deadline(), workers, 32, 16, () -> { });
                lifetimes.add(lifetime); return wrap(lifetime);
            });
            registration = prepared.commit();
        }
        private InvocationCompletionScope wrap(InvocationCompletionScope delegate) {
            return new InvocationCompletionScope() {
                @Override public <T> T call(Callable<T> action) throws Exception {
                    return delegate.call(() -> { current.set(delegate); try { return action.call(); } finally { current.remove(); } });
                }
                @Override public <T> CompletionStage<T> continueInContext(Callable<T> action) { return delegate.continueInContext(action); }
                @Override public CompletionStage<Object> logicalResult() { return delegate.logicalResult(); }
                @Override public CompletionStage<Void> physicalCompletion() { return delegate.physicalCompletion(); }
                @Override public boolean supportsResultFinalizers() { return delegate.supportsResultFinalizers(); }
                @Override public void requestCancellation() { delegate.requestCancellation(); }
                @Override public void abortBeforeTarget() { delegate.abortBeforeTarget(); }
                @Override public void close() { delegate.close(); }
            };
        }
        @Override public void close() throws Exception {
            if (closed.compareAndSet(false, true)) { prepared.close(); binding.close(); admission.close(); resilience.close(); runtime.close(); workers.shutdown();
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS)); container.close(); }
        }
    }
}
