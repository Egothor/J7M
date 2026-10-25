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
import java.util.concurrent.atomic.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedClientRetryTest {
    interface Safe { @Idempotent @Retry(maxAttempts=3,policy="retry") String run(); }
    interface Plain { String run(); }
    interface Inherited extends Safe { @Override String run(); }
    interface Diamond extends Plain, Safe { }
    interface Different { @Idempotent @Retry(maxAttempts=1,policy="retry") String run(); }
    interface ConflictingDiamond extends Safe, Different { }
    interface Unsafe { @NonIdempotent @Retry(maxAttempts=1,policy="retry") String run(); }
    interface Conflicting { @Idempotent @NonIdempotent String run(); }
    interface OneWayRetry { @Idempotent @OneWay @Retry(maxAttempts=1,policy="retry") void run(); }
    interface OneWayDisabledRetry { @OneWay @Retry(maxAttempts=0,policy="retry") void run(); }
    interface MissingPolicy { @Idempotent @Retry(maxAttempts=1,policy="missing") String run(); }
    private static ClientCall call(Class<?> type, long deadline) throws Exception {
        var method = type.getMethod("run");
        var identity = new ManagedMethod(method.getDeclaringClass().getName(), "run", List.of(), method.getReturnType().getName());
        var reference = new ObjectRef("object", "service/object", "service", "runtime", "tenant", List.of(type.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1, Map.of());
        return new ClientCall(new ManagedServiceContract("business", 1, identity), type, method, reference, deadline);
    }
    private static ManagedClientRetry retry(AtomicLong nanos, int cap, double jitter, double sample) {
        return new ManagedClientRetry(Map.of("retry", new ClientRetryProfile(cap, Duration.ofMillis(10), Duration.ofMillis(20), jitter)), nanos::get, () -> sample, nanos::addAndGet);
    }
    @Test void onlyPredeliveryConnectionFailuresRetryWithFiniteBackoffAndHostCap() throws Throwable {
        var nanos = new AtomicLong(); var attempts = new AtomicInteger(); var retry = retry(nanos, 2, 0, .5); retry.validate(Safe.class);
        var attempt = new ClientAttempt() {
            @Override public Object execute() { if (attempts.incrementAndGet() <= 2) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "connection unavailable"); return "ok"; }
            @Override public boolean businessEntered() { return false; }
        };
        assertEquals("ok", retry.invoke(call(Safe.class, Duration.ofSeconds(1).toNanos()), attempt));
        assertEquals(3, attempts.get()); assertEquals(Duration.ofMillis(30).toNanos(), nanos.get());
    }
    @Test void businessEntryPreventsReplayEvenWhenRemoteErrorClaimsNotStartedAndRetryable() throws Exception {
        var attempts = new AtomicInteger(); var nanos = new AtomicLong();
        var forged = new MiddlewareException(new RemoteError(MiddlewareErrorCode.UNAVAILABLE, "lost reply", Map.of("phase", "NOT_STARTED"), "operation", "runtime", true, true));
        var attempt = new ClientAttempt() {
            @Override public Object execute() { attempts.incrementAndGet(); throw forged; }
            @Override public boolean businessEntered() { return true; }
        };
        assertSame(forged, assertThrows(MiddlewareException.class, () -> retry(nanos, 3, 0, .5).invoke(call(Safe.class, 1000000000), attempt)));
        assertEquals(1, attempts.get()); assertEquals(0, nanos.get());
    }
    @Test void totalDeadlineIncludesBackoffAndNeverStartsAnotherExpiredAttempt() throws Exception {
        var nanos = new AtomicLong(); var attempts = new AtomicInteger();
        var attempt = new ClientAttempt() {
            @Override public Object execute() { attempts.incrementAndGet(); throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "connect"); }
            @Override public boolean businessEntered() { return false; }
        };
        var failure = assertThrows(MiddlewareException.class, () -> retry(nanos, 3, 0, .5).invoke(call(Safe.class, 5000000), attempt));
        assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, failure.code()); assertEquals(1, attempts.get()); assertEquals(5000000, nanos.get());
    }
    @Test void inheritedAndDiamondPoliciesUseDeclaredContractTypeAndScalarConflictsReject() throws Throwable {
        for (var type : List.of(Inherited.class, Diamond.class)) {
            var nanos = new AtomicLong(); var attempts = new AtomicInteger(); var policy = retry(nanos, 1, 0, .5); policy.validate(type);
            var attempt = new ClientAttempt() {
                @Override public Object execute() { if (attempts.incrementAndGet() == 1) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "connect"); return "ok"; }
                @Override public boolean businessEntered() { return false; }
            };
            assertEquals("ok", policy.invoke(call(type, 1000000000), attempt)); assertEquals(2, attempts.get());
        }
        assertThrows(IllegalArgumentException.class, () -> retry(new AtomicLong(), 3, 0, .5).validate(ConflictingDiamond.class));
    }
    @Test void missingUnsafeAndOneWayPoliciesRejectBeforeAnyAttempt() {
        var retry = retry(new AtomicLong(), 3, 0, .5);
        for (var type : List.of(Unsafe.class, Conflicting.class, OneWayRetry.class, OneWayDisabledRetry.class, MissingPolicy.class)) assertThrows(IllegalArgumentException.class, () -> retry.validate(type));
    }
    @ParameterizedTest @ValueSource(doubles={0,1})
    void jitterExtremesRemainWithinHostBackoffCap(double sample) throws Throwable {
        var nanos = new AtomicLong(); var attempts = new AtomicInteger();
        var attempt = new ClientAttempt() {
            @Override public Object execute() { if (attempts.incrementAndGet() == 1) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "connect"); return "ok"; }
            @Override public boolean businessEntered() { return false; }
        };
        assertEquals("ok", retry(nanos, 1, 1, sample).invoke(call(Safe.class, 1000000000), attempt));
        assertEquals(sample == 0 ? 0 : 20000000, nanos.get());
    }
    @Test void cooperativeParentCancellationDuringBackoffPreventsAnotherConnectionAttempt() throws Exception {
        var nanos = new AtomicLong(); var cancelled = new AtomicBoolean(); var attempts = new AtomicInteger();
        var control = new InvocationControl() {
            @Override public java.time.Duration remainingBudget() { return java.time.Duration.ofSeconds(1); }
            @Override public boolean cancellationRequested() { return cancelled.get(); }
            @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.concurrent.Callable<T> work) { throw new IllegalStateException("fixture"); }
            @Override public java.util.concurrent.Executor contextualExecutor() { throw new IllegalStateException("fixture"); }
        };
        var policy = new ManagedClientRetry(Map.of("retry", new ClientRetryProfile(3, Duration.ofMillis(10), Duration.ofMillis(20), 0)), nanos::get, () -> .5,
                delay -> { nanos.addAndGet(delay); cancelled.set(true); });
        var attempt = new ClientAttempt() {
            @Override public Object execute() { attempts.incrementAndGet(); throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "connect"); }
            @Override public boolean businessEntered() { return false; }
        };
        try (var ignored = J7mContext.open(new InvocationContext(null, null, "parent", control))) {
            assertEquals(MiddlewareErrorCode.CANCELLED, assertThrows(MiddlewareException.class, () -> policy.invoke(call(Safe.class, 1000000000), attempt)).code());
            assertEquals(1, attempts.get());
        }
    }
    @Test void defaultAndDeniedFailuresAreSingleAttemptAndInterruptedCallerStaysInterrupted() throws Exception {
        var attempts = new AtomicInteger(); var nanos = new AtomicLong();
        var attempt = new ClientAttempt() {
            @Override public Object execute() { attempts.incrementAndGet(); throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "denied"); }
            @Override public boolean businessEntered() { return false; }
        };
        assertThrows(MiddlewareException.class, () -> retry(nanos, 3, 0, .5).invoke(call(Safe.class, 1000000000), attempt)); assertEquals(1, attempts.get());
        assertThrows(MiddlewareException.class, () -> retry(nanos, 3, 0, .5).invoke(call(Plain.class, 1000000000), attempt)); assertEquals(2, attempts.get());
        Thread.currentThread().interrupt();
        try { assertEquals(MiddlewareErrorCode.CANCELLED, assertThrows(MiddlewareException.class, () -> retry(nanos, 3, 0, .5).invoke(call(Safe.class, 1000000000), attempt)).code()); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
