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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

class ManagedDependencyBreakerTest {
    interface Service { @CircuitBreaker("dependency") @Idempotent @Retry(maxAttempts=2) String call(); }
    interface Unknown { @CircuitBreaker("missing") String call(); }
    static final long BUDGET = TimeUnit.SECONDS.toNanos(10);
    private static ClientCall call(Class<?> type, long generation) throws Exception {
        var method = type.getMethod("call");
        var identity = new ManagedMethod(type.getName(), "call", List.of(), "java.lang.String");
        return new ClientCall(new ManagedServiceContract("dependency", 1, identity), type, method,
                new ObjectRef("object-" + generation, "logical", "service", "runtime", "tenant", List.of(type.getName()),
                        "grpc", "grpc://127.0.0.1:1", Long.MAX_VALUE, generation, Map.of()), BUDGET);
    }
    static class Attempt implements ClientAttempt {
        int connections, effects, probes;
        boolean entered, healthy = true;
        MiddlewareErrorCode connectionFailure;
        MiddlewareErrorCode businessFailure;
        @Override public Object execute() {
            connections++; entered = false;
            if (connectionFailure != null) throw MiddlewareException.of(connectionFailure, "synthetic connection failure");
            entered = true; effects++;
            if (businessFailure != null) throw MiddlewareException.of(businessFailure, "synthetic business failure");
            return "value";
        }
        @Override public boolean probe() { probes++; return healthy; }
        @Override public boolean businessEntered() { return entered; }
    }
    @Test void logicalFailureCountWrapsRetriesAndOpenDoesNotRetryOrResetOnNewTarget() throws Throwable {
        var now = new AtomicLong();
        var retry = new ManagedClientRetry(Map.of("default", new ClientRetryProfile(2, Duration.ZERO, Duration.ZERO, 0)),
                now::get, () -> .5, ignored -> { });
        try (var breaker = new ManagedDependencyBreaker("tenant-A", Map.of("dependency",
                new CircuitBreakerProfile(2, Duration.ofMillis(10), 1)), retry, now::get)) {
            breaker.validate(Service.class);
            var attempt = new Attempt(); attempt.connectionFailure = MiddlewareErrorCode.UNAVAILABLE;
            assertThrows(MiddlewareException.class, () -> breaker.invoke(call(Service.class, 1), attempt));
            assertEquals(3, attempt.connections); assertEquals(1, breaker.status("dependency").failures());
            assertThrows(MiddlewareException.class, () -> breaker.invoke(call(Service.class, 2), attempt));
            assertEquals(6, attempt.connections); assertEquals(ManagedDependencyBreaker.Phase.OPEN, breaker.status("dependency").phase());
            assertThrows(MiddlewareException.class, () -> breaker.invoke(call(Service.class, 3), attempt));
            assertEquals(6, attempt.connections);
            now.set(TimeUnit.MILLISECONDS.toNanos(10)); attempt.connectionFailure = null;
            assertEquals("value", breaker.invoke(call(Service.class, 4), attempt));
            assertEquals(1, attempt.probes); assertEquals(1, attempt.effects);
            assertEquals(ManagedDependencyBreaker.Phase.CLOSED, breaker.status("dependency").phase());
        }
    }
    @Test void authenticationBusinessErrorsAndOtherTenantsDoNotBecomeDependencyOutage() throws Throwable {
        var clock = new AtomicLong(); var profile = Map.of("dependency", new CircuitBreakerProfile(1, Duration.ofMillis(1), 1));
        try (var a = new ManagedDependencyBreaker("A", profile, ClientCallInterceptor.singleAttempt(), clock::get);
                var b = new ManagedDependencyBreaker("B", profile, ClientCallInterceptor.singleAttempt(), clock::get)) {
            var auth = new Attempt(); auth.connectionFailure = MiddlewareErrorCode.UNAUTHENTICATED;
            assertThrows(MiddlewareException.class, () -> a.invoke(call(Service.class, 1), auth));
            assertEquals(ManagedDependencyBreaker.Phase.CLOSED, a.status("dependency").phase());
            var business = new Attempt(); business.businessFailure = MiddlewareErrorCode.UNAVAILABLE;
            assertThrows(MiddlewareException.class, () -> a.invoke(call(Service.class, 1), business));
            assertEquals(0, a.status("dependency").failures());
            var outage = new Attempt(); outage.connectionFailure = MiddlewareErrorCode.TRANSPORT_ERROR;
            assertThrows(MiddlewareException.class, () -> a.invoke(call(Service.class, 1), outage));
            assertEquals(ManagedDependencyBreaker.Phase.OPEN, a.status("dependency").phase());
            assertEquals("value", b.invoke(call(Service.class, 1), new Attempt()));
            assertEquals(ManagedDependencyBreaker.Phase.CLOSED, b.status("dependency").phase());
            assertThrows(IllegalArgumentException.class, () -> a.validate(Unknown.class));
        }
    }
    @Test void halfOpenFloodNeverExceedsReadonlyProbeCapacityAndBusinessIsNotAProbe() throws Throwable {
        var clock = new AtomicLong(); var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var probes = new AtomicInteger(); var effects = new AtomicInteger();
        try (var breaker = new ManagedDependencyBreaker("tenant", Map.of("dependency", new CircuitBreakerProfile(1, Duration.ofMillis(1), 2)),
                    ClientCallInterceptor.singleAttempt(), clock::get);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var outage = new Attempt(); outage.connectionFailure = MiddlewareErrorCode.UNAVAILABLE;
            assertThrows(MiddlewareException.class, () -> breaker.invoke(call(Service.class, 1), outage));
            clock.set(TimeUnit.MILLISECONDS.toNanos(1));
            ClientAttempt probe = new ClientAttempt() {
                @Override public boolean probe() {
                    probes.incrementAndGet(); entered.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    return true;
                }
                @Override public Object execute() { effects.incrementAndGet(); return "value"; }
                @Override public boolean businessEntered() { return false; }
            };
            List<Future<Object>> pending = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) pending.add(workers.submit(() -> invoke(breaker, call(Service.class, 2), probe)));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 20; i++) assertThrows(MiddlewareException.class, () -> breaker.invoke(call(Service.class, 3), probe));
                assertEquals(2, probes.get()); assertEquals(0, effects.get());
                assertEquals(2, breaker.status("dependency").activeProbes());
                release.countDown(); for (var result : pending) assertEquals("value", result.get(2, TimeUnit.SECONDS));
                assertEquals(2, effects.get()); assertEquals(0, breaker.status("dependency").activeProbes());
            } finally { release.countDown(); }
        }
    }
    private static Object invoke(ManagedDependencyBreaker breaker, ClientCall call, ClientAttempt attempt) throws Exception {
        try { return breaker.invoke(call, attempt); } catch (Exception failure) { throw failure; } catch (Throwable failure) { throw new AssertionError(failure); }
    }
}
