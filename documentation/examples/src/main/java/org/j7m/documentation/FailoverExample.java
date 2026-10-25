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

package org.j7m.documentation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Demonstrates live rebinding over real gRPC with three runtimes in one JVM.
 * The shared embedded registry is tutorial setup, not cross-process discovery.
 */
public final class FailoverExample {
    private FailoverExample() { }

    /**
     * Small public application contract.
     */
    public interface Business {
        /**
         * Returns the serving replica identity.
         * @return replica identity
         */
        String identify();
        /**
         * Records an effect before reporting a business failure.
         * @throws MiddlewareException always, after the effect counter increments
         */
        void effect();
    }
    /**
     * Application implementation with an observable business-effect counter.
     */
    public static final class Service implements Business {
        private final String id;
        private final AtomicInteger effects = new AtomicInteger();
        /**
         * Creates a named application replica.
         * @param id replica identity
         */
        public Service(String id) { this.id = id; }
        /**
         * {@inheritDoc}
         */
        @Override public String identify() { return id; }
        /**
         * {@inheritDoc}
         */
        @Override public void effect() {
            effects.incrementAndGet();
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Business effect already entered");
        }
    }
    /**
     * Runs discovery, topology refresh and failure refresh with bounded watch waits.
     * Administration is disabled and gRPC uses development security only for this isolated tutorial.
     * @param args unused arguments
     * @throws Exception if a bounded watch or resource close fails
     */
    public static void main(String[] args) throws Exception {
        var store = new EmbeddedRegistryStore();
        var serviceB = new Service("b");
        var serviceA = new Service("a");
        var options = new ExportOptions(false, "grpc", "default", List.of(), Map.of(
                "j7m.contract.id", "documentation.business", "j7m.contract.version", "1",
                "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())), null, null);
        try (var b = runtime("b", store); var client = runtime("client", store);
             var registrationB = b.export("business", serviceB, Business.class, options);
             var source = new PolledBindingCandidateSource(client);
             var bindings = new LiveServiceBindings(source, new RuntimeBindingConnector(client),
                     (request, ref) -> true, "client", Map.of("tutorial", new BindingPolicy(
                     BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.WAIT,
                     Duration.ofSeconds(2), Duration.ofMillis(30), Duration.ofMillis(300),
                     Duration.ofSeconds(2), 16)), () -> Instant.now().plusSeconds(2));
             var sticky = bindings.open(request(BindingRefresh.ON_FAILURE), "tutorial");
             var moving = bindings.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "tutorial")) {
            require("b".equals(sticky.proxy().identify()), "Initial replica was not b");
            try {
                sticky.proxy().effect();
                throw new AssertionError("Business failure was hidden");
            } catch (MiddlewareException expected) {
                require(serviceB.effects.get() == 1, "Entered effect was repeated");
            }
            try (var a = runtime("a", store);
                 var registrationA = a.export("business", serviceA, Business.class, options)) {
                await(sticky, snapshot -> snapshot.candidates().size() == 2);
                await(moving, snapshot -> selected(snapshot, "a"));
                require("b".equals(sticky.proxy().identify()), "ON_FAILURE moved merely on topology change");
                require("a".equals(moving.proxy().identify()), "ON_TOPOLOGY_CHANGE did not select lower identity");
                b.close();
                // ON_FAILURE may retain its selection until a call observes the transport failure.
                // Do not replay a business operation: this probe only reads replica identity.
                try {
                    require("a".equals(sticky.proxy().identify()), "Closed replica answered a new call");
                } catch (MiddlewareException observedTransportFailure) {
                    // A failure before dispatch allows the binding to select a for the next call.
                }
                await(sticky, snapshot -> selected(snapshot, "a"));
                require("a".equals(sticky.proxy().identify()), "Subsequent call did not use surviving replica");
                require(serviceB.effects.get() == 1 && serviceA.effects.get() == 0, "Failed business effect was replayed");
            }
        }
        System.out.println("FailoverExample OK: sticky b, topology a, failover a; effects b=1 a=0 (no replay)");
    }
    private static MiddlewareRuntime runtime(String id, EmbeddedRegistryStore store) {
        return new MiddlewareRuntimeBuilder().runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
    }
    private static ServiceBindingRequest<Business> request(BindingRefresh refresh) {
        return new ServiceBindingRequest<>(Business.class, "business", "documentation.business", 1,
                List.of(), "default", refresh);
    }
    private static boolean selected(BindingSnapshot snapshot, String id) {
        return snapshot.selected().map(ref -> ref.runtimeId().equals(id)).orElse(false);
    }
    private static void await(ServiceBinding<?> binding, Predicate<BindingSnapshot> condition) throws Exception {
        var done = new CountDownLatch(1);
        try (var watch = binding.watch(snapshot -> { if (condition.test(snapshot)) done.countDown(); })) {
            if (condition.test(binding.snapshot())) done.countDown();
            require(done.await(10, TimeUnit.SECONDS), "Binding watch timed out: " + binding.snapshot());
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
