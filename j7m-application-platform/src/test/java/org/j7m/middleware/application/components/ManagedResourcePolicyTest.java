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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.IsolationProfile;
import org.j7m.middleware.annotations.Priority;
import org.j7m.middleware.annotations.ResourceRequirement;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.junit.jupiter.api.Test;

class ManagedResourcePolicyTest {
    @ResourceRequirement("shared") @Priority(0)
    interface Work {
        @Priority(10) String first();
        String second();
    }
    static class Worker implements Work { public String first() { return "first"; } public String second() { return "second"; } }
    @ResourceRequirement("unavailable-cpu-quota")
    interface Unsupported { String call(); }
    static class UnsupportedWorker implements Unsupported { public String call() { return "value"; } }
    @IsolationProfile("heap") interface Heap { String call(); }
    static class HeapWorker implements Heap { public String call() { return "value"; } }
    @IsolationProfile("per-app-os-sandbox") interface UnsupportedIsolation { String call(); }
    static class UnsupportedIsolatedWorker implements UnsupportedIsolation { public String call() { return "value"; } }
    @ResourceRequirement("shared") interface Async { CompletableFuture<String> call(); }
    static class AsyncWorker implements Async { public CompletableFuture<String> call() { return CompletableFuture.completedFuture("value"); } }

    @Test
    void differentMethodBindingsShareOneHostBudgetAndReleaseOnlyAfterActualMethodReturns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var policy = policy(10); var executor = Executors.newSingleThreadExecutor();
                var first = policy.bind(contract(Work.class, "first"), Worker.class, Work.class);
                var second = policy.bind(contract(Work.class, "second"), Worker.class, Work.class)) {
            policy.validate(contract(Work.class, "first"), Worker.class, Work.class);
            var running = executor.submit(() -> first.invoke(InvocationContext.empty(), new Object[0], () -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return "completed";
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var count = new AtomicInteger();
                var overloaded = assertThrows(MiddlewareException.class, () -> second.invoke(InvocationContext.empty(), new Object[0], count::incrementAndGet));
                assertEquals(MiddlewareErrorCode.OVERLOADED, overloaded.code());
                assertEquals(0, count.get());
                assertEquals(1, policy.status("shared").active());
            } finally { release.countDown(); }
            assertEquals("completed", running.get(10, TimeUnit.SECONDS));
            assertEquals(0, policy.status("shared").active());
            assertEquals("next", second.invoke(InvocationContext.empty(), new Object[0], () -> "next"));
        }
    }

    @Test
    void hostMaximumPriorityAndUnknownResourceRequirementsRejectDuringValidation() throws Exception {
        try (var policy = policy(9)) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(contract(Work.class, "first"), Worker.class, Work.class));
            policy.validate(contract(Work.class, "second"), Worker.class, Work.class);
            assertThrows(IllegalArgumentException.class, () -> policy.validate(contract(Unsupported.class, "call"), UnsupportedWorker.class, Unsupported.class));
            assertEquals(0, policy.status("shared").admitted());
        }
    }

    @Test
    void heapCapabilityChecksTheActualHostCeilingAndDoesNotAdvertisePerAppSandbox() throws Exception {
        try (var accepted = new ManagedResourcePolicy(Map.of(), Map.of("heap", new ManagedResourcePolicy.HostHeapProfile(Runtime.getRuntime().maxMemory())))) {
            accepted.validate(contract(Heap.class, "call"), HeapWorker.class, Heap.class);
            try (var binding = accepted.bind(contract(Heap.class, "call"), HeapWorker.class, Heap.class)) {
                assertEquals("value", binding.invoke(InvocationContext.empty(), new Object[0], () -> "value"));
            }
            assertThrows(IllegalArgumentException.class, () -> accepted.validate(contract(UnsupportedIsolation.class, "call"),
                    UnsupportedIsolatedWorker.class, UnsupportedIsolation.class));
        }
        try (var mismatched = new ManagedResourcePolicy(Map.of(), Map.of("heap", new ManagedResourcePolicy.HostHeapProfile(1)))) {
            assertThrows(IllegalArgumentException.class, () -> mismatched.validate(contract(Heap.class, "call"), HeapWorker.class, Heap.class));
        }
    }

    @Test
    void closingFactoryRejectsNewCallsAndAsyncInvocationRequiresPhysicalOwnership() throws Exception {
        var policy = policy(10);
        var binding = policy.bind(contract(Work.class, "second"), Worker.class, Work.class);
        policy.validate(contract(Async.class, "call"), AsyncWorker.class, Async.class);
        try (var async = policy.bind(contract(Async.class, "call"), AsyncWorker.class, Async.class)) {
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class,
                    () -> async.invoke(InvocationContext.empty(), new Object[0], () -> CompletableFuture.completedFuture("unsafe"))).code());
            assertEquals(0, policy.status("shared").active());
        }
        assertThrows(IllegalArgumentException.class, () -> policy.status(null));
        assertThrows(IllegalArgumentException.class, () -> policy.status("unknown"));
        binding.close(); binding.close();
        var count = new AtomicInteger();
        assertThrows(MiddlewareException.class, () -> binding.invoke(InvocationContext.empty(), new Object[0], count::incrementAndGet));
        var another = policy.bind(contract(Work.class, "second"), Worker.class, Work.class);
        policy.close(); policy.close();
        assertThrows(MiddlewareException.class, () -> another.invoke(InvocationContext.empty(), new Object[0], count::incrementAndGet));
        assertEquals(0, count.get());
        assertTrue(policy.status("shared").closed());
        another.close();
    }

    @Test
    void asyncPermitSurvivesLogicalReplyAndReleasesOnlyOnTrustedPhysicalCompletion() throws Exception {
        try (var policy = policy(10);
                var binding = policy.bind(contract(Async.class, "call"), AsyncWorker.class, Async.class)) {
            var physical = new CompletableFuture<Void>();
            var reply = CompletableFuture.completedFuture("logical value");
            var continuation = new ManagedInvocationPolicy.Proceed() {
                @Override public Object invoke() { return reply; }
                @Override public java.util.concurrent.CompletionStage<Void> physicalCompletion() { return physical.minimalCompletionStage(); }
            };
            assertSame(reply, binding.invoke(InvocationContext.empty(), new Object[0], continuation));
            assertEquals(1, policy.status("shared").active());
            assertThrows(MiddlewareException.class, () -> binding.invoke(InvocationContext.empty(), new Object[0], continuation));
            physical.complete(null);
            assertEquals(0, policy.status("shared").active());
        }
    }

    private static ManagedResourcePolicy policy(final int maximumPriority) {
        return new ManagedResourcePolicy(Map.of("shared", new ManagedResourcePolicy.ResourceProfile(1, 0,
                Duration.ZERO, Duration.ofMillis(10), maximumPriority)), Map.of());
    }

    private static ManagedServiceContract contract(final Class<?> api, final String name) throws NoSuchMethodException {
        var method = api.getMethod(name);
        return new ManagedServiceContract("resource.contract", 1, new ManagedMethod(method.getDeclaringClass().getName(), name,
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName()));
    }
}
