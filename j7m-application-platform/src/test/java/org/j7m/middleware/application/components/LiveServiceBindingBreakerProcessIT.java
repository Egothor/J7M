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
import java.net.DatagramSocket;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveServiceBindingBreakerProcessIT {
    @TempDir Path directory;
    @Test void threeServiceJvmsKeepCircuitOpenAcrossRealDiscoveryRefreshAndProbeWithoutBusinessReplay() throws Exception {
        int port; try (var socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        String context = "breaker-process-" + UUID.randomUUID(); var clock = new AtomicLong(System.nanoTime());
        var fixture = new LiveServiceBindingProcessIT(); fixture.directory = directory;
        try (var b = fixture.new Node("b", context, port); var c = fixture.new Node("c", context, port);
                var client = new MiddlewareRuntimeBuilder().runtimeId("breaker-client").registryContext(context)
                        .registry(LiveBindingNodeProcess.registry("breaker-client", context, port))
                        .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var source = new PolledBindingCandidateSource(client);
                var breaker = new ManagedDependencyBreaker("tenant", Map.of("dependency", new CircuitBreakerProfile(1, Duration.ofMillis(10), 1)),
                        ClientCallInterceptor.singleAttempt(), clock::get);
                var bindings = new LiveServiceBindings(source, new RuntimeBindingConnector(client),
                        (request, reference) -> Set.of("a", "b").contains(reference.runtimeId()), "breaker-client",
                        Map.of("network", new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.WAIT,
                                Duration.ofSeconds(2), Duration.ofMillis(30), Duration.ofMillis(300), Duration.ofSeconds(2), 16)),
                        () -> Instant.now().plusSeconds(2), breaker);
                var binding = bindings.open(new ServiceBindingRequest<>(LiveBindingNodeProcess.Business.class, "binding-service", "binding-business", 1,
                        List.of(), context, BindingRefresh.ON_TOPOLOGY_CHANGE), "network")) {
            LiveServiceBindingProcessIT.await(binding, snapshot -> snapshot.selected().map(reference -> reference.runtimeId().equals("b")).orElse(false));
            b.input.write("DISCONNECT\n"); b.input.flush(); assertEquals("DISCONNECTED", b.line());
            assertThrows(MiddlewareException.class, () -> binding.proxy().effect("ok"));
            assertEquals(ManagedDependencyBreaker.Phase.OPEN, breaker.status("dependency").phase());
            assertEquals(0, b.effects()); assertEquals(0, c.effects());
            try (var a = fixture.new Node("a", context, port)) {
                assertEquals(3, Set.of(a.process.pid(), b.process.pid(), c.process.pid()).size());
                System.out.println("A09_PROCESS_PIDS " + a.process.pid() + " " + b.process.pid() + " " + c.process.pid());
                LiveServiceBindingProcessIT.await(binding, snapshot -> snapshot.selected().map(reference -> reference.runtimeId().equals("a")).orElse(false));
                assertEquals(ManagedDependencyBreaker.Phase.OPEN, breaker.status("dependency").phase());
                assertThrows(MiddlewareException.class, () -> binding.proxy().effect("ok"));
                assertEquals(0, a.effects(), "A compatible JVM and discovery refresh cannot reset OPEN");
                clock.addAndGet(Duration.ofMillis(10).toNanos());
                assertEquals("a", binding.proxy().effect("ok"));
                assertEquals(ManagedDependencyBreaker.Phase.CLOSED, breaker.status("dependency").phase());
                assertEquals(1, a.effects(), "Readonly half-open probe cannot execute business code");
                assertEquals(0, b.effects()); assertEquals(0, c.effects());
            }
        }
    }
}
