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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

class LiveServiceBindingsTest {
    public interface Business { String effect(); }
    static class Source implements BindingCandidateSource {
        final Set<Consumer<BindingCandidates>> observers = new CopyOnWriteArraySet<>();
        List<ObjectRef> initial; final AtomicInteger closed = new AtomicInteger();
        Source(ObjectRef... initial) { this.initial = List.of(initial); }
        @Override public AutoCloseable watch(ServiceBindingRequest<?> request, BindingPolicy policy, Consumer<BindingCandidates> observer) {
            observers.add(observer); observer.accept(new BindingCandidates(1, initial, true)); return () -> { if (observers.remove(observer)) closed.incrementAndGet(); };
        }
        void push(long revision, ObjectRef... candidates) { for (var observer : observers) observer.accept(new BindingCandidates(revision, List.of(candidates), true)); }
    }
    static ObjectRef ref(String id, long generation) {
        return new ObjectRef(id, "service/" + id, "service", id, "tenant", List.of(Business.class.getName()), "grpc", "grpc://127.0.0.1:1", 0, generation,
                Map.of("j7m.contract.id", "business", "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())));
    }
    static BindingPolicy policy(BindingPolicy.Selection selection, BindingPolicy.Unavailable unavailable) {
        return new BindingPolicy(selection, unavailable, Duration.ofMillis(200), Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(1), 256);
    }
    static ServiceBindingRequest<Business> request(BindingRefresh refresh) { return new ServiceBindingRequest<>(Business.class, "service", "business", 1, List.of(), "tenant", refresh); }
    static LiveServiceBindings owner(Source source, BindingPolicy policy, BindingConnector connector, BindingAdmission admission) {
        return new LiveServiceBindings(source, connector, admission, "local", Map.of("policy", policy), () -> Instant.now().plusSeconds(1));
    }
    @Test void failureNeverReplaysSameEffectAndNextCallSwitchesWhileProxyIdentityStaysStable() {
        var a = ref("a", 1); var b = ref("b", 1); var source = new Source(a, b); var effects = new AtomicInteger(); var calls = new AtomicInteger();
        BindingConnector connector = (reference, type, options) -> (Business) () -> {
            calls.incrementAndGet(); effects.incrementAndGet();
            assertEquals("NO_REPLAY", options.metadata().get("j7m.reliability.replayPolicy"));
            if (reference.runtimeId().equals("a")) throw MiddlewareException.remote(MiddlewareErrorCode.UNAVAILABLE, "lost reply", "request", "a", true, true);
            return reference.runtimeId();
        };
        try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), connector, (q, r) -> true);
             var binding = owner.open(request(BindingRefresh.ON_FAILURE), "policy")) {
            Business proxy = binding.proxy(); int hash = proxy.hashCode(); assertEquals("LiveServiceProxy[stable]", proxy.toString()); assertTrue(proxy.equals(proxy));
            assertThrows(MiddlewareException.class, proxy::effect); assertEquals(1, effects.get()); assertEquals(1, calls.get());
            assertEquals("b", proxy.effect()); assertSame(proxy, binding.proxy()); assertEquals(hash, proxy.hashCode());
            source.push(2, a, b.withLeaseUntil(System.currentTimeMillis() + 60000));
            assertEquals("b", proxy.effect()); // Healthy ON_FAILURE is sticky even when a lower identity appears.
        }
        assertEquals(1, source.closed.get());
    }
    @Test void repeatedDiscoveryCannotResurrectFailedIncarnationAndHigherGenerationRecovers() {
        var source = new Source(ref("a", 1)); var calls = new AtomicInteger();
        BindingConnector connector = (reference, type, options) -> (Business) () -> {
            calls.incrementAndGet();
            if (reference.generation() % 2 == 1) throw MiddlewareException.remote(MiddlewareErrorCode.UNAVAILABLE, "lost reply", "request", "a", true, true);
            return reference.runtimeId();
        };
        try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), connector, (q, r) -> true);
             var binding = owner.open(request(BindingRefresh.ON_FAILURE), "policy")) {
            for (long generation = 1; generation <= 1000; generation++) {
                source.push(generation * 2, ref("a", generation));
                if (generation % 2 == 1) {
                    assertThrows(MiddlewareException.class, binding.proxy()::effect);
                    source.push(generation * 2 + 1, ref("a", generation));
                    assertTrue(binding.snapshot().selected().isEmpty());
                    int before = calls.get();
                    assertThrows(MiddlewareException.class, binding.proxy()::effect);
                    assertEquals(before, calls.get());
                } else assertEquals("a", binding.proxy().effect());
            }
            assertEquals(1000, calls.get());
        }
    }
    @Test void connectionTimeAndLongerParentDeadlineCannotExtendSingleInvocationBudget() {
        var source = new Source(ref("a", 1)); var effects = new AtomicInteger();
        var context = (org.j7m.middleware.transport.api.RuntimeTransportContext) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.j7m.middleware.transport.api.RuntimeTransportContext.class}, (proxy, method, args) -> null);
        var provider = (org.j7m.middleware.transport.api.TransportProvider) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.j7m.middleware.transport.api.TransportProvider.class}, (proxy, method, args) -> {
                    assertEquals("invoke", method.getName());
                    var invocation = (InvocationOptions) args[3];
                    assertTrue(invocation.deadline().compareTo(Duration.ofMillis(150)) < 0);
                    assertFalse(invocation.metadata().containsKey(ProxyOptions.LOCAL_DEADLINE_NANOS));
                    effects.incrementAndGet(); return "a";
                });
        BindingConnector connector = (reference, type, options) -> {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(160);
            while (System.nanoTime() - end < 0) java.util.concurrent.locks.LockSupport.parkNanos(end - System.nanoTime());
            return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    new org.j7m.middleware.transport.api.RemoteProxyInvocationHandler(reference, type, options, context, provider));
        };
        var budget = new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST,
                Duration.ofMillis(300), Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofMillis(300), 16);
        var parent = new org.j7m.middleware.security.api.SecurityContext(null, "parent", null, null, "", "", "", true, null, Set.of(),
                Map.of("j7m.reliability.deadlineEpochMillis", Long.toString(System.currentTimeMillis() + 60000), ProxyOptions.LOCAL_DEADLINE_NANOS, "parent-local-value-must-not-leak"), Instant.now());
        try (var owner = owner(source, budget, connector, (q, r) -> true); var binding = owner.open(request(BindingRefresh.ON_FAILURE), "policy")) {
            assertEquals("a", org.j7m.middleware.security.api.SecurityContexts.call(parent, binding.proxy()::effect));
            assertEquals(1, effects.get());
        }
    }
    @Test void positiveBoundedReadOnlyProbeCanRecoverSameGenerationWithoutReplayingBusiness() {
        var source = new Source(ref("a", 1)); var effects = new AtomicInteger(); var probes = new AtomicInteger(); var healthy = new AtomicBoolean();
        BindingConnector connector = new BindingConnector() {
            @Override public Object connect(ObjectRef reference, Class<?> contract, ProxyOptions options) {
                return (Business) () -> {
                    effects.incrementAndGet();
                    if (!healthy.get()) throw MiddlewareException.remote(MiddlewareErrorCode.UNAVAILABLE, "lost", "request", "a", true, true);
                    return "a";
                };
            }
            @Override public boolean probe(ObjectRef reference, Class<?> contract, ProxyOptions options) {
                assertEquals(Duration.ofMillis(50), options.invocationDeadline()); probes.incrementAndGet(); return healthy.get();
            }
        };
        try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), connector, (q, r) -> true);
             var binding = owner.open(request(BindingRefresh.ON_FAILURE), "policy")) {
            assertThrows(MiddlewareException.class, binding.proxy()::effect);
            for (long revision = 2; revision < 100; revision++) source.push(revision, ref("a", 1));
            assertEquals(0, probes.get()); assertEquals(1, effects.get());
            healthy.set(true);
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1100);
            while (System.nanoTime() - end < 0) java.util.concurrent.locks.LockSupport.parkNanos(end - System.nanoTime());
            source.push(100, ref("a", 1));
            assertEquals(1, probes.get()); assertEquals(1, effects.get());
            assertEquals("a", binding.proxy().effect()); assertEquals(2, effects.get());
        }
    }
    @Test void heartbeatLeaseRefreshDuringFailedConnectionCannotHideSameIncarnationFailure() {
        for (var code : List.of(MiddlewareErrorCode.UNAVAILABLE, MiddlewareErrorCode.DEADLINE_EXCEEDED)) {
            var a = ref("a", 1); var b = ref("b", 1); var source = new Source(a, b); var calls = new AtomicInteger();
            BindingConnector connector = (reference, type, options) -> {
                calls.incrementAndGet();
                if (reference.runtimeId().equals("a")) {
                    source.push(2, a.withLeaseUntil(System.currentTimeMillis() + 60000), b);
                    throw MiddlewareException.of(code, "connection failed after heartbeat");
                }
                return (Business) reference::runtimeId;
            };
            try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), connector, (q, r) -> true);
                 var binding = owner.open(request(BindingRefresh.ON_FAILURE), "policy")) {
                assertThrows(MiddlewareException.class, binding.proxy()::effect); assertEquals(1, calls.get());
                assertEquals("b", binding.proxy().effect()); assertEquals(2, calls.get());
            }
        }
    }
    @Test void businessUnavailablePermissionAndNotFoundDoNotEvictOrReplay() {
        for (var code : List.of(MiddlewareErrorCode.UNAVAILABLE, MiddlewareErrorCode.NOT_FOUND, MiddlewareErrorCode.PERMISSION_DENIED, MiddlewareErrorCode.REMOTE_EXCEPTION)) {
            var source = new Source(ref("a", 1), ref("b", 1)); var calls = new AtomicInteger();
            try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), (r, t, o) -> (Business) () -> { calls.incrementAndGet(); throw MiddlewareException.of(code, "business outcome"); }, (q, r) -> true);
                 var binding = owner.open(request(BindingRefresh.ON_FAILURE), "policy")) {
                assertThrows(MiddlewareException.class, binding.proxy()::effect); assertEquals(1, calls.get()); assertEquals("a", binding.snapshot().selected().orElseThrow().runtimeId());
            }
        }
    }
    @Test void neverPinsInitialTargetAndTopologyModeChangesOnlyOnNewEligibleSnapshots() {
        var a = ref("a", 1); var b = ref("b", 1); var source = new Source(b);
        try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), (r, t, o) -> (Business) r::runtimeId, (q, r) -> !r.runtimeId().equals("denied"));
             var pinned = owner.open(request(BindingRefresh.NEVER), "policy"); var moving = owner.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "policy")) {
            source.push(3, b, a, ref("denied", 1)); assertEquals("b", pinned.proxy().effect()); assertEquals("a", moving.proxy().effect());
            long published = moving.snapshot().generation(); source.push(2, b); assertEquals(published, moving.snapshot().generation()); assertEquals("a", moving.proxy().effect());
            source.push(4, ref("a", 1), ref("a", 2)); assertEquals(2, moving.snapshot().selected().orElseThrow().generation());
            source.push(5, ref("a", 1), b); assertEquals("b", moving.proxy().effect()); assertEquals("b", pinned.proxy().effect());
        }
    }
    @Test void contractVersionQualifiersReadinessAndTrustAreRequiredAndNoAllowAllDefaultExists() {
        var good = ref("good", 1); var incompatible = new ObjectRef("wrong", "wrong", "service", "wrong", "tenant", good.interfaces(), "grpc", good.endpoint(), 0, 1,
                Map.of("j7m.contract.id", "business", "j7m.contract.version", "2", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())));
        var source = new Source(ref("denied", 1), incompatible, good); var checked = new AtomicInteger();
        try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), (r, t, o) -> (Business) r::runtimeId,
                (q, r) -> { checked.incrementAndGet(); return r.runtimeId().equals("good"); }); var binding = owner.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "policy")) {
            assertEquals("good", binding.proxy().effect()); assertEquals(List.of(good), binding.snapshot().candidates()); assertTrue(checked.get() > 0);
        }
        assertThrows(NullPointerException.class, () -> owner(new Source(good), policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.FAIL_FAST), (r, t, o) -> null, null));
    }
    @Test void optionalAbsenceIsExplicitAndWaitHonorsCallerDeadlineWithoutProxyNulls() {
        var source = new Source();
        try (var owner = owner(source, policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.OPTIONAL), (r, t, o) -> (Business) r::runtimeId, (q, r) -> true);
             var binding = owner.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "policy")) {
            assertTrue(binding.optionalProvider().get().isEmpty()); assertThrows(IllegalStateException.class, binding::proxy);
            source.push(2, ref("live", 1)); assertEquals("live", binding.optionalProvider().get().orElseThrow().effect());
        }
        try (var owner = new LiveServiceBindings(new Source(ref("a", 1)), (r, t, o) -> (Business) r::runtimeId, (q, r) -> true, "local",
                Map.of("policy", policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.WAIT)), () -> Instant.MIN)) {
            assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class, () -> owner.open(request(BindingRefresh.ON_FAILURE), "policy")).code());
        }
    }
    @Test void waitingCallWakesForNewSnapshotAndCloseUnblocksWaitersWithoutResurrection() throws Exception {
        var source = new Source(ref("a", 1));
        try (var owner = owner(source, policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.WAIT), (r, t, o) -> (Business) r::runtimeId, (q, r) -> true);
             var binding = owner.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "policy"); var executor = Executors.newSingleThreadExecutor()) {
            source.push(2); var started = new CountDownLatch(1);
            Future<String> result = executor.submit(() -> { started.countDown(); return binding.proxy().effect(); }); assertTrue(started.await(1, TimeUnit.SECONDS));
            source.push(3, ref("b", 1)); assertEquals("b", result.get(1, TimeUnit.SECONDS));
            source.push(4); Future<?> closing = executor.submit(() -> binding.proxy().effect()); binding.close();
            assertThrows(ExecutionException.class, () -> closing.get(1, TimeUnit.SECONDS));
            source.push(5, ref("a", 10)); assertEquals("CLOSED", binding.snapshot().status());
        }
    }
    @Test void listenerFailuresAreContainedCleanupIsIdempotentAndWatchCapacityIsBounded() throws Exception {
        var source = new Source(ref("a", 1));
        var owner = owner(source, policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.FAIL_FAST), (r, t, o) -> (Business) r::runtimeId, (q, r) -> true);
        var binding = owner.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "policy"); var observed = new CountDownLatch(1);
        binding.watch(snapshot -> { throw new IllegalArgumentException("observer failed"); });
        binding.watch(snapshot -> observed.countDown()); source.push(2, ref("b", 1)); assertTrue(observed.await(1, TimeUnit.SECONDS)); assertEquals(1, owner.status().callbackFailures());
        var registrations = new ArrayList<AutoCloseable>(); for (int i = 0; i < 30; i++) registrations.add(binding.watch(snapshot -> { }));
        assertThrows(IllegalStateException.class, () -> binding.watch(snapshot -> { }));
        for (var registration : registrations) registration.close(); owner.close(); owner.close(); assertEquals(1, source.closed.get()); assertEquals(0, owner.status().bindings());
        assertThrows(IllegalStateException.class, () -> binding.proxy().effect());
    }
    @Test void discoveryFailureFailsClosedAndGenerationHistoryCapacityIsExplicit() {
        var source = new Source(ref("a", 1));
        var constrained = new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST,
                Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        try (var owner = owner(source, constrained, (r, t, o) -> (Business) r::runtimeId, (q, r) -> true);
             var binding = owner.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "policy")) {
            for (var observer : source.observers) observer.accept(new BindingCandidates(2, List.of(), false));
            assertThrows(MiddlewareException.class, () -> binding.proxy().effect()); assertEquals("DISCOVERY_UNKNOWN", binding.snapshot().status());
            source.push(3, ref("a", 2)); assertEquals("a", binding.proxy().effect());
            source.push(4, ref("b", 1)); assertEquals("GENERATION_CAPACITY", binding.snapshot().status());
            assertThrows(MiddlewareException.class, () -> binding.proxy().effect());
        }
    }
    @Test void inFlightCallKeepsItsExactReferenceDuringConcurrentTopologyRefresh() throws Exception {
        var source = new Source(ref("b", 1)); var connected = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var owner = owner(source, policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST), (r, t, o) -> (Business) () -> {
            if (r.runtimeId().equals("b")) { connected.countDown(); try { assertTrue(release.await(2, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
            return r.runtimeId();
        }, (q, r) -> true); var binding = owner.open(request(BindingRefresh.ON_TOPOLOGY_CHANGE), "policy"); var pool = Executors.newSingleThreadExecutor()) {
            Future<String> old = pool.submit(() -> binding.proxy().effect()); assertTrue(connected.await(1, TimeUnit.SECONDS));
            source.push(2, ref("a", 1)); release.countDown(); assertEquals("b", old.get(1, TimeUnit.SECONDS)); assertEquals("a", binding.proxy().effect());
        }
    }
}
