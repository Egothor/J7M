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

class LiveServiceBindingRetryIT {
    public interface Business {
        @Idempotent @Retry(maxAttempts=3,policy="retry") @Deadline(millis=500)
        String effect();
    }
    public interface ShortBudget { @Deadline(millis=10) String effect(); }
    @Test void callerBudgetLookupCannotRestartMethodDeadlineOrStartExpiredWork() {
        var delayed = new AtomicBoolean(); var connects = new AtomicInteger();
        var reference = new ObjectRef("object", "service/object", "service", "runtime", "tenant", List.of(ShortBudget.class.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1,
                Map.of("j7m.contract.id", "business", "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())));
        var source = new LiveServiceBindingsTest.Source(reference);
        try (var owner = new LiveServiceBindings(source, (target, type, options) -> { connects.incrementAndGet(); return (ShortBudget) () -> "must not run"; }, (request, target) -> true,
                "local", Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.FAIL_FAST)), () -> {
                    if (delayed.get()) {
                        long end = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20);
                        while (end - System.nanoTime() > 0) java.util.concurrent.locks.LockSupport.parkNanos(end - System.nanoTime());
                    }
                    return Instant.now().plusSeconds(10);
                });
             var binding = owner.open(new ServiceBindingRequest<>(ShortBudget.class, "service", "business", 1, List.of(), "tenant", BindingRefresh.NEVER), "binding")) {
            delayed.set(true);
            assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class, binding.proxy()::effect).code());
            assertEquals(0, connects.get());
        }
    }
    @Test void actualReplyLossAfterEffectIsNeverReplayedDespiteIdempotentAndRetryAnnotations() throws Exception {
        var effects = new AtomicInteger(); var gate = new AtomicReference<LiveServiceBindingRecoveryIT.Link>();
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("retry-server").addTransport(LiveServiceBindingRecoveryIT.forwardedGrpc(gate)).disableNuggetAdmin().build();
             var link = gate.get();
             var registration = server.export("service", (Business) () -> { effects.incrementAndGet(); link.available(false); return "committed"; }, Business.class,
                     new ExportOptions(false, "grpc", "tenant", List.of(), Map.of("j7m.contract.id", "business", "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())), Duration.ofMinutes(1), Duration.ofSeconds(10)));
             var client = new MiddlewareRuntimeBuilder().runtimeId("retry-client").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            var source = new LiveServiceBindingsTest.Source(registration.ref());
            var retry = new ManagedClientRetry(Map.of("retry", new ClientRetryProfile(3, Duration.ZERO, Duration.ZERO, 0)));
            var policy = new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST,
                    Duration.ofSeconds(1), Duration.ofMillis(30), Duration.ofMillis(200), Duration.ofSeconds(1), 16);
            try (var owner = new LiveServiceBindings(source, new RuntimeBindingConnector(client), (request, target) -> true, "retry-client", Map.of("binding", policy), () -> Instant.now().plusSeconds(2), retry);
                 var binding = owner.open(new ServiceBindingRequest<>(Business.class, "service", "business", 1, List.of(), "tenant", BindingRefresh.ON_FAILURE), "binding")) {
                assertThrows(MiddlewareException.class, binding.proxy()::effect);
                assertEquals(1, effects.get(), "A lost reply after business entry is UNKNOWN, not a pre-delivery retry proof");
            }
        }
    }
}
