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
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

class LiveServiceBindingRecoveryIT {
    static final class Link implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0);
        final Set<Socket> connections = ConcurrentHashMap.newKeySet();
        final AtomicBoolean online = new AtomicBoolean(true), closed = new AtomicBoolean();
        final AtomicInteger rejected = new AtomicInteger();
        final InetSocketAddress upstream;
        final Thread acceptor;
        Link(URI upstream) throws IOException {
            this.upstream = new InetSocketAddress(upstream.getHost(), upstream.getPort());
            acceptor = Thread.ofVirtual().start(() -> {
                while (!closed.get()) {
                    try {
                        Socket inbound = listener.accept(); connections.add(inbound);
                        if (!online.get()) { rejected.incrementAndGet(); finish(inbound); continue; }
                        Socket outbound = new Socket(); connections.add(outbound); outbound.connect(this.upstream, 100);
                        Thread.ofVirtual().start(() -> pump(inbound, outbound));
                        Thread.ofVirtual().start(() -> pump(outbound, inbound));
                    } catch (IOException unavailable) { if (!closed.get()) rejected.incrementAndGet(); }
                }
            });
        }
        void pump(Socket from, Socket to) {
            try { from.getInputStream().transferTo(to.getOutputStream()); }
            catch (IOException disconnected) { rejected.incrementAndGet(); }
            finally { finish(from); finish(to); }
        }
        void finish(Socket socket) {
            connections.remove(socket);
            try { socket.close(); } catch (IOException disconnected) { rejected.incrementAndGet(); }
        }
        void available(boolean value) {
            online.set(value); if (!value) for (Socket socket : List.copyOf(connections)) finish(socket);
        }
        @Override public void close() throws IOException {
            closed.set(true); listener.close(); for (Socket socket : List.copyOf(connections)) finish(socket);
        }
    }
    static org.j7m.middleware.transport.api.TransportProvider forwardedGrpc(AtomicReference<Link> gate) {
        var grpc = new GrpcTransportProvider();
        return (org.j7m.middleware.transport.api.TransportProvider) java.lang.reflect.Proxy.newProxyInstance(
                LiveServiceBindingRecoveryIT.class.getClassLoader(), new Class<?>[] {org.j7m.middleware.transport.api.TransportProvider.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(grpc, args);
                        if (!method.getName().equals("start")) return value;
                        var endpoint = (org.j7m.middleware.transport.api.TransportEndpoint) value;
                        var link = new Link(URI.create(endpoint.endpoint())); gate.set(link);
                        return new org.j7m.middleware.transport.api.TransportEndpoint("grpc", "grpc://127.0.0.1:" + link.listener.getLocalPort());
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    @Test void actualGrpcLinkFailureAndHealRecoverSameIncarnationWithoutBusinessProbeOrReplay() throws Exception {
        var effects = new AtomicInteger(); var gate = new AtomicReference<Link>();
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("server").addTransport(forwardedGrpc(gate)).disableNuggetAdmin().build();
             var link = gate.get();
             var registration = server.export("service", (LiveServiceBindingsTest.Business) () -> { effects.incrementAndGet(); return "ok"; }, LiveServiceBindingsTest.Business.class,
                     new ExportOptions(false, "grpc", "tenant", List.of(), Map.of("j7m.contract.id", "business", "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())), Duration.ofMinutes(1), Duration.ofSeconds(10)));
             var client = new MiddlewareRuntimeBuilder().runtimeId("client").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            ObjectRef reference = registration.ref();
            var source = new LiveServiceBindingsTest.Source(reference);
            try (var owner = LiveServiceBindingsTest.owner(source, LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), new RuntimeBindingConnector(client), (request, ref) -> true);
                 var binding = owner.open(LiveServiceBindingsTest.request(BindingRefresh.ON_FAILURE), "policy")) {
                assertEquals("ok", binding.proxy().effect()); assertEquals(1, effects.get());
                link.available(false); assertThrows(MiddlewareException.class, binding.proxy()::effect); assertEquals(1, effects.get());
                assertTrue(binding.snapshot().selected().isEmpty());
                link.available(true);
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); long revision = 2;
                while (binding.snapshot().selected().isEmpty() && System.nanoTime() - limit < 0) {
                    source.push(revision++, reference);
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(30));
                }
                assertEquals(reference, binding.snapshot().selected().orElseThrow()); assertEquals(1, effects.get());
                assertEquals("ok", binding.proxy().effect()); assertEquals(2, effects.get());
            }
        }
    }
}
