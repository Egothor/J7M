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
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveServiceBindingProcessIT {
    @TempDir Path directory;
    final class Node implements AutoCloseable {
        final String id; Process process; BufferedReader output; BufferedWriter input;
        Node(String id, String context, int port) throws Exception {
            this.id = id;
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx160m", "-cp",
                    System.getProperty("j7m.artifact.test.classpath"), LiveBindingNodeProcess.class.getName(), id, context, Integer.toString(port))
                    .redirectError(directory.resolve(id + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            try { assertEquals("READY " + id, line()); } catch (Exception | AssertionError error) { close(); throw error; }
        }
        String line() throws Exception { return CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException error) { throw new UncheckedIOException(error); } }).get(10, TimeUnit.SECONDS); }
        int effects() throws Exception { input.write("COUNT\n"); input.flush(); return Integer.parseInt(line().substring(6)); }
        @Override public void close() { if (process != null && process.isAlive()) { process.destroyForcibly(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } } }
    }
    static void await(ServiceBinding<?> binding, java.util.function.Predicate<BindingSnapshot> condition) throws Exception {
        var done = new CountDownLatch(1); try (var watch = binding.watch(snapshot -> { if (condition.test(snapshot)) done.countDown(); })) {
            if (condition.test(binding.snapshot())) done.countDown(); assertTrue(done.await(10, TimeUnit.SECONDS), binding.snapshot().toString());
        }
    }
    @Test void blackholeOpenHonorsShortInvocationBudgetWithoutBusinessDispatch() throws Exception {
        try (var socket = new ServerSocket(0);
             var client = new MiddlewareRuntimeBuilder().runtimeId("deadline-client").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            var reference = new ObjectRef("blackhole", "service/blackhole", "service", "blackhole", "tenant",
                    List.of(LiveServiceBindingsTest.Business.class.getName()), "grpc", "grpc://127.0.0.1:" + socket.getLocalPort(), 0, 1, Map.of());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(MiddlewareException.class,
                    () -> client.reference(reference, LiveServiceBindingsTest.Business.class, new ProxyOptions(Duration.ofMillis(100), Map.of()))));
        }
    }
    @Test void threeActualServiceJvmsDiscoverNewReplicaRejectUnauthorizedAndFailOverWithoutReplayingEffects() throws Exception {
        int port; try (var socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        String context = "binding-" + UUID.randomUUID(); var denied = new AtomicInteger();
        try (var b = new Node("b", context, port); var c = new Node("c", context, port);
             var client = new MiddlewareRuntimeBuilder().runtimeId("client").registryContext(context).registry(LiveBindingNodeProcess.registry("client", context, port))
                     .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
             var source = new PolledBindingCandidateSource(client);
             var bindings = new LiveServiceBindings(source, new RuntimeBindingConnector(client), (request, ref) -> {
                 if (ref.runtimeId().equals("c")) denied.incrementAndGet(); return Set.of("a", "b").contains(ref.runtimeId());
             }, "client", Map.of("network", new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.WAIT,
                     Duration.ofSeconds(2), Duration.ofMillis(30), Duration.ofMillis(300), Duration.ofSeconds(2), 16)), () -> Instant.now().plusSeconds(2))) {
            var contract = LiveBindingNodeProcess.Business.class;
            var request = new ServiceBindingRequest<>(contract, "binding-service", "binding-business", 1, List.of(), context, BindingRefresh.ON_FAILURE);
            try (var sticky = bindings.open(request, "network");
                 var moving = bindings.open(new ServiceBindingRequest<>(contract, "binding-service", "binding-business", 1, List.of(), context, BindingRefresh.ON_TOPOLOGY_CHANGE), "network");
                 var pinned = bindings.open(new ServiceBindingRequest<>(contract, "binding-service", "binding-business", 1, List.of(), context, BindingRefresh.NEVER), "network")) {
                assertEquals("b", sticky.proxy().identify()); assertEquals("b", pinned.proxy().identify());
                assertThrows(MiddlewareException.class, () -> sticky.proxy().effect("unavailable"));
                assertEquals(1, b.effects()); assertEquals(0, c.effects()); assertEquals("b", sticky.snapshot().selected().orElseThrow().runtimeId());
                try (var a = new Node("a", context, port)) {
                    assertEquals(3, Set.of(a.process.pid(), b.process.pid(), c.process.pid()).size());
                    await(sticky, snapshot -> snapshot.candidates().size() == 2); await(moving, snapshot -> snapshot.selected().map(ref -> ref.runtimeId().equals("a")).orElse(false));
                    assertEquals("b", sticky.proxy().identify()); assertEquals("a", moving.proxy().identify()); assertTrue(denied.get() > 0);
                    assertEquals(0, a.effects()); b.close();
                    assertThrows(MiddlewareException.class, () -> sticky.proxy().identify());
                    assertEquals("a", sticky.proxy().identify()); assertEquals(0, a.effects());
                    assertThrows(MiddlewareException.class, () -> pinned.proxy().identify());
                    assertEquals("b", pinned.snapshot().selected().orElseThrow().runtimeId());
                }
            }
            assertEquals(0, source.status().subscriptions()); assertEquals(0, bindings.status().bindings());
        }
    }
}
