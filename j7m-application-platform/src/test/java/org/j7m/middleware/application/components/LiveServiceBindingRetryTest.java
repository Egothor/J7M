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
import org.junit.jupiter.api.Test;

class LiveServiceBindingRetryTest {
    public interface Business {
        @Idempotent @Retry(maxAttempts=2,policy="retry") @Deadline(millis=300)
        String effect(String request);
    }
    public interface ConflictingDeadline extends Business {
        @Override @Deadline(millis=100) String effect(String request);
    }
    private static ObjectRef reference(String id) {
        return new ObjectRef(id, "service/" + id, "service", id, "tenant", List.of(Business.class.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1,
                Map.of("j7m.contract.id", "business", "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())));
    }
    private static ServiceBindingRequest<Business> request() { return new ServiceBindingRequest<>(Business.class, "service", "business", 1, List.of(), "tenant", BindingRefresh.ON_FAILURE); }
    private static ManagedClientRetry retry() { return new ManagedClientRetry(Map.of("retry", new ClientRetryProfile(2, Duration.ZERO, Duration.ZERO, 0))); }
    @Test void qualifiedConnectionRetryKeepsPinnedTargetInputAndAbsoluteBudgetWhileNextCallCanRefresh() {
        var a = reference("a"); var source = new LiveServiceBindingsTest.Source(a, reference("b"));
        var connects = new AtomicInteger(); var effects = new AtomicInteger(); var deadline = new AtomicReference<String>();
        BindingConnector connector = (target, type, options) -> {
            int count = connects.incrementAndGet();
            if (count <= 2) {
                assertEquals("a", target.runtimeId());
                if (count == 1) deadline.set(options.metadata().get(ProxyOptions.LOCAL_DEADLINE_NANOS));
                else assertEquals(deadline.get(), options.metadata().get(ProxyOptions.LOCAL_DEADLINE_NANOS));
            }
            if (count == 1) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "OPEN did not connect");
            return (Business) value -> { effects.incrementAndGet(); return target.runtimeId() + ":" + value; };
        };
        try (var owner = new LiveServiceBindings(source, connector, (query, ref) -> true, "local",
                Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST)), () -> Instant.now().plusSeconds(10), retry());
             var binding = owner.open(request(), "binding")) {
            assertEquals("a:stable-input", binding.proxy().effect("stable-input")); assertEquals(2, connects.get()); assertEquals(1, effects.get());
            assertEquals("b:next-input", binding.proxy().effect("next-input")); assertEquals(3, connects.get()); assertEquals(2, effects.get());
        }
    }
    @Test void forgedNotStartedBusinessFailureCannotReplayAnyEffect() {
        var source = new LiveServiceBindingsTest.Source(reference("a")); var effects = new AtomicInteger(); var connects = new AtomicInteger();
        BindingConnector connector = (target, type, options) -> {
            connects.incrementAndGet();
            return (Business) value -> { effects.incrementAndGet(); throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.UNAVAILABLE, "lost reply",
                    Map.of("phase", "NOT_STARTED"), value, target.runtimeId(), true, true)); };
        };
        try (var owner = new LiveServiceBindings(source, connector, (query, ref) -> true, "local",
                Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST)), () -> Instant.now().plusSeconds(10), retry());
             var binding = owner.open(request(), "binding")) {
            assertThrows(MiddlewareException.class, () -> binding.proxy().effect("stable-input")); assertEquals(1, effects.get()); assertEquals(1, connects.get());
        }
    }
    @Test void conflictingInheritedDeadlineRejectsBeforeAnyDiscoveryWork() {
        var source = new LiveServiceBindingsTest.Source(reference("a"));
        try (var owner = new LiveServiceBindings(source, (target, type, options) -> { throw new AssertionError("must not connect"); },
                (query, ref) -> true, "local", Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST)), () -> Instant.now().plusSeconds(10), retry())) {
            var request = new ServiceBindingRequest<>(ConflictingDeadline.class, "service", "business", 1, List.of(), "tenant", BindingRefresh.ON_FAILURE);
            assertThrows(IllegalArgumentException.class, () -> owner.open(request, "binding")); assertEquals(0, source.observers.size());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void exhaustedOrCancelledManagedParentCannotBeExtendedByHostSupplierOrChildAnnotation(boolean cancelled) {
        var source = new LiveServiceBindingsTest.Source(reference("a")); var connects = new AtomicInteger();
        var parent = new InvocationControl() {
            @Override public Duration remainingBudget() { return cancelled ? Duration.ofSeconds(1) : Duration.ZERO; }
            @Override public boolean cancellationRequested() { return cancelled; }
            @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.concurrent.Callable<T> work) { throw new IllegalStateException("expired"); }
            @Override public java.util.concurrent.Executor contextualExecutor() { throw new IllegalStateException("expired"); }
        };
        try (var owner = new LiveServiceBindings(source, (target, type, options) -> { connects.incrementAndGet(); return (Business) value -> value; },
                (query, ref) -> true, "local", Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST)), () -> Instant.now().plusSeconds(10), retry());
             var binding = owner.open(request(), "binding"); var ignored = J7mContext.open(new InvocationContext(null, null, "parent", parent))) {
            assertEquals(cancelled ? MiddlewareErrorCode.CANCELLED : MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class, () -> binding.proxy().effect("must not run")).code());
            assertEquals(0, connects.get());
        }
    }
    @Test void absentHostPolicyRejectsBeforeDiscoveryAndDeadlineClampIsCarriedToConnector() {
        var source = new LiveServiceBindingsTest.Source(reference("a")); var calls = new AtomicInteger();
        try (var owner = new LiveServiceBindings(source, (target, type, options) -> { calls.incrementAndGet(); assertTrue(options.invocationDeadline().compareTo(Duration.ofMillis(300)) <= 0); return (Business) value -> value; },
                (query, ref) -> true, "local", Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST)), () -> Instant.now().plusSeconds(10), new ManagedClientRetry(Map.of()))) {
            assertThrows(IllegalArgumentException.class, () -> owner.open(request(), "binding")); assertEquals(0, source.observers.size()); assertEquals(0, calls.get());
        }
        try (var owner = new LiveServiceBindings(source, (target, type, options) -> { calls.incrementAndGet(); assertTrue(options.invocationDeadline().compareTo(Duration.ofMillis(300)) <= 0); return (Business) value -> value; },
                (query, ref) -> true, "local", Map.of("binding", LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST)), () -> Instant.now().plusSeconds(10), retry());
             var binding = owner.open(request(), "binding")) {
            assertEquals("value", binding.proxy().effect("value")); assertEquals(1, calls.get());
        }
    }
}
