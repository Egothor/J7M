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
import org.j7m.middleware.security.api.*;
import org.junit.jupiter.api.Test;

class ManagedResiliencePolicyTest {
    interface Service {
        @Bulkhead("inventory") @Backpressure(maxConcurrent=1) String item();
        @Bulkhead("billing") String other();
        @RateLimited("rate") String limited();
        @RateLimited("rate") @Bulkhead("inventory") String mixed();
        @Bulkhead("inventory") CompletionStage<String> async();
    }
    static class Implementation implements Service {
        public String item() { return "item"; } public String other() { return "other"; }
        public String limited() { return "limited"; } public String mixed() { return "mixed"; }
        public CompletionStage<String> async() { return CompletableFuture.completedFuture("async"); }
    }
    interface Invalid { @Backpressure(maxConcurrent=2) String item(); }
    private static ManagedServiceContract contract(Class<?> type, String name) throws Exception {
        var method = type.getMethod(name);
        return new ManagedServiceContract("business", 1, new ManagedMethod(type.getName(), name, List.of(), method.getReturnType().getName()));
    }
    private static InvocationContext caller(String tenant) {
        return new InvocationContext(new InvocationSecurityContext(new Principal(tenant, tenant, Set.of(), Set.of()), Set.of(), new TrustDomain("realm")), null, "");
    }
    private static ManagedInvocationPolicy.Proceed physical(CompletableFuture<Void> done, AtomicInteger calls) {
        return new ManagedInvocationPolicy.Proceed() {
            public Object invoke() { calls.incrementAndGet(); return "value"; }
            public CompletionStage<Void> physicalCompletion() { return done.minimalCompletionStage(); }
        };
    }
    private static ManagedResiliencePolicy policy(AtomicLong clock) {
        var budget = new BulkheadProfile(1, 0, Duration.ZERO);
        return new ManagedResiliencePolicy(Set.of("A", "B"), context -> context.security().principal().id(),
                Map.of("inventory", budget, "billing", budget, "default", budget),
                Map.of("rate", new RateLimitProfile(2, Duration.ofSeconds(1), RateLimitProfile.Scope.LOCAL)), null, clock::get);
    }
    @Test void physicalBudgetSurvivesReplyTimeoutAndSeparatesTenantsAndDependencies() throws Exception {
        try (var policy = policy(new AtomicLong()); var item = policy.bind(contract(Service.class, "item"), Implementation.class, Service.class);
                var billing = policy.bind(contract(Service.class, "other"), Implementation.class, Service.class)) {
            var active = new CompletableFuture<Void>(); var calls = new AtomicInteger();
            assertEquals("value", item.invoke(caller("A"), new Object[0], physical(active, calls)));
            assertEquals(1, policy.status("A", "inventory").active());
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(MiddlewareException.class,
                    () -> item.invoke(caller("A"), new Object[0], physical(new CompletableFuture<>(), calls))).code());
            var second = new CompletableFuture<Void>();
            assertEquals("value", item.invoke(caller("B"), new Object[0], physical(second, calls)));
            assertEquals("value", billing.invoke(caller("A"), new Object[0], () -> "value"));
            active.complete(null); assertEquals(0, policy.status("A", "inventory").active());
            second.complete(null); assertEquals(0, policy.status("B", "inventory").active());
            assertEquals(2, calls.get());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                    () -> item.invoke(caller("unknown"), new Object[0], () -> "forbidden")).code());
        }
    }
    @Test void fixedWindowChargesRateAdmissionBeforeLaterBulkheadRejectionWithoutRefund() throws Exception {
        var now = new AtomicLong();
        try (var policy = policy(now); var mixed = policy.bind(contract(Service.class, "mixed"), Implementation.class, Service.class);
                var limited = policy.bind(contract(Service.class, "limited"), Implementation.class, Service.class)) {
            var hold = new CompletableFuture<Void>(); var calls = new AtomicInteger();
            mixed.invoke(caller("A"), new Object[0], physical(hold, calls));
            assertThrows(MiddlewareException.class, () -> mixed.invoke(caller("A"), new Object[0], physical(new CompletableFuture<>(), calls)));
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(MiddlewareException.class,
                    () -> limited.invoke(caller("A"), new Object[0], () -> "must not run")).code());
            assertEquals("B", limited.invoke(caller("B"), new Object[0], () -> "B"));
            now.set(Duration.ofSeconds(1).toNanos() - 1);
            assertThrows(MiddlewareException.class, () -> limited.invoke(caller("A"), new Object[0], () -> "too early"));
            now.incrementAndGet(); assertEquals("new", limited.invoke(caller("A"), new Object[0], () -> "new"));
            hold.complete(null); assertEquals(1, calls.get());
        }
    }
    @Test void unsupportedDistributedRateAndMismatchedBackpressureOrMissingPhysicalAsyncFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ManagedResiliencePolicy(Set.of("A"), ignored -> "A", Map.of(),
                Map.of("rate", new RateLimitProfile(1, Duration.ofSeconds(1), RateLimitProfile.Scope.DISTRIBUTED)), null));
        try (var policy = policy(new AtomicLong())) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(contract(Invalid.class, "item"), Invalid.class, Invalid.class));
            try (var async = policy.bind(contract(Service.class, "async"), Implementation.class, Service.class)) {
                var effects = new AtomicInteger();
                assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class,
                        () -> async.invoke(caller("A"), new Object[0], () -> { effects.incrementAndGet(); return CompletableFuture.completedFuture("unsafe"); })).code());
                assertEquals(0, effects.get());
            }
        }
    }
    @Test void metadataResolutionUsesMethodOverrideAndRejectsConflictingInterfaceDefaults() throws Exception {
        @Bulkhead("billing") class Override implements Service {
            @Bulkhead("inventory") public String item() { return "item"; }
            public String other() { return "other"; } public String limited() { return "limited"; }
            public String mixed() { return "mixed"; } public CompletionStage<String> async() { return CompletableFuture.completedFuture("value"); }
        }
        try (var policy = policy(new AtomicLong())) {
            policy.validate(contract(Service.class, "item"), Override.class, Service.class);
            assertThrows(IllegalArgumentException.class, () -> ManagedPolicyMetadata.method(contract(Invalid.class, "item"), Service.class));
        }
    }
}
