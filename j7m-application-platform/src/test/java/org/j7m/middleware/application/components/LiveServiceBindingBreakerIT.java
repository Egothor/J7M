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
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

class LiveServiceBindingBreakerIT {
    public interface Business { @CircuitBreaker("dependency") String effect(); }
    @Test void realOpenOutageOpensStableCircuitAndNewTargetRecoversOnlyThroughReadonlyProbe() throws Exception {
        var effects = new AtomicInteger(); var gate = new AtomicReference<LiveServiceBindingRecoveryIT.Link>();
        var metadata = Map.of("j7m.contract.id", "business", "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of()));
        var options = new ExportOptions(false, "grpc", "tenant", List.of(), metadata, Duration.ofMinutes(1), Duration.ofSeconds(10));
        var clock = new AtomicLong(System.nanoTime());
        try (var first = new MiddlewareRuntimeBuilder().runtimeId("old").addTransport(LiveServiceBindingRecoveryIT.forwardedGrpc(gate)).disableNuggetAdmin().build();
                var link = gate.get(); var old = first.export("service", (Business) () -> "old:" + effects.incrementAndGet(), Business.class, options);
                var next = new MiddlewareRuntimeBuilder().runtimeId("new").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var replacement = next.export("service", (Business) () -> "new:" + effects.incrementAndGet(), Business.class, options);
                var client = new MiddlewareRuntimeBuilder().runtimeId("client").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var breaker = new ManagedDependencyBreaker("tenant", Map.of("dependency", new CircuitBreakerProfile(1, Duration.ofMillis(10), 1)),
                        ClientCallInterceptor.singleAttempt(), clock::get)) {
            var source = new LiveServiceBindingsTest.Source(old.ref());
            try (var owner = new LiveServiceBindings(source, new RuntimeBindingConnector(client), (request, target) -> true,
                    "client", Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.FAIL_FAST)),
                    () -> Instant.now().plusSeconds(2), breaker);
                    var binding = owner.open(new ServiceBindingRequest<>(Business.class, "service", "business", 1, List.of(), "tenant", BindingRefresh.ON_TOPOLOGY_CHANGE), "binding")) {
                link.available(false);
                assertThrows(MiddlewareException.class, binding.proxy()::effect);
                assertEquals(ManagedDependencyBreaker.Phase.OPEN, breaker.status("dependency").phase()); assertEquals(0, effects.get());
                source.push(2, replacement.ref());
                assertThrows(MiddlewareException.class, binding.proxy()::effect);
                assertEquals(0, effects.get(), "A target refresh must not reset or bypass the dependency circuit");
                clock.addAndGet(Duration.ofMillis(10).toNanos());
                assertEquals("new:1", binding.proxy().effect());
                assertEquals(ManagedDependencyBreaker.Phase.CLOSED, breaker.status("dependency").phase());
                assertEquals(1, effects.get(), "The half-open probe must not invoke application code");
            }
        }
    }
}
