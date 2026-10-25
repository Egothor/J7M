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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.junit.jupiter.api.Test;

class ManagedOwnershipContextTest {
    interface Service { @LeaseGuard String work(@ShardKey String key); }
    static class Worker implements Service { public String work(String key) { return key; } }
    static ManagedOwnershipPolicy policy(OwnershipEndpoint endpoint) {
        return new ManagedOwnershipPolicy(Map.of("default", ManagedOwnershipProfileTest.profile(endpoint, Duration.ofMillis(100))), Set.of("alice", "bob"), context -> context.security().principal().id());
    }
    @Test void currentOwnedContextFencesNativeEffectsAndCannotEscapeOrRetargetAfterExit() throws Exception {
        var endpoint = new OwnershipEndpoint(); var captured = new AtomicReference<ManagedOwnershipContext>();
        try (var policy = policy(endpoint); var binding = policy.bind(TransactionInvocation.contract(Service.class), Worker.class, Service.class)) {
            assertEquals("done", TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> {
                var context = ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class); captured.set(context);
                assertFalse(context.revoked()); assertTrue(context.fence() > 0); assertTrue(context.leadershipEpoch() > 0); assertEquals(0, context.partitionEpoch());
                assertNull(context.read("key")); String value = "sensitive-\uD800-\uDC00-\uD800";
                long version = context.write("effect", "key", value); assertTrue(version > 0);
                assertEquals(value, context.read("key").value()); assertEquals(version, context.write("effect", "key", value));
                assertThrows(IllegalArgumentException.class, () -> context.write("effect", "key", "changed"));
                assertThrows(IllegalArgumentException.class, () -> context.write("new", "", "bad"));
                assertThrows(IllegalArgumentException.class, () -> context.write("new", "key", "x".repeat(4097)));
                assertEquals(1, endpoint.effects.get()); assertEquals(1, policy.status().activeClaims());
                return "done";
            }));
            assertTrue(captured.get().revoked()); assertEquals(0, policy.status().activeClaims());
            assertThrows(MiddlewareException.class, captured.get()::fence);
            assertThrows(MiddlewareException.class, () -> captured.get().write("late", "key", "bad"));
            assertEquals(1, endpoint.effects.get());
        }
    }
    @Test void renewalChangesFenceButARepeatedEffectReturnsItsOriginalReceiptAndRespectsQuota() throws Exception {
        var endpoint = new OwnershipEndpoint();
        var profile = new ManagedOwnershipProfile(ManagedOwnershipBackend.group(endpoint), "inventory", Duration.ofSeconds(4),
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(200), 1);
        try (var policy = new ManagedOwnershipPolicy(Map.of("default", profile), Set.of("alice"), context -> context.security().principal().id());
                var binding = policy.bind(TransactionInvocation.contract(Service.class), Worker.class, Service.class)) {
            assertEquals("done", TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> {
                var context = ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class);
                long initialFence = context.fence(); long receipt = context.write("stable", "key", "value");
                var renewed = endpoint.renewed.poll(2, TimeUnit.SECONDS); assertNotNull(renewed);
                assertTrue(context.fence() > initialFence);
                assertEquals(receipt, context.write("stable", "key", "value")); assertEquals(1, endpoint.effects.get());
                assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(MiddlewareException.class, () -> context.write("other", "key", "other")).code());
                assertEquals("value", context.read("key").value()); return "done";
            }));
        }
    }
    private static void eventually(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) fail("Controlled ownership transition deadline");
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
    @Test void unknownRenewalRevokesAuthorityWithoutReleasingActualHeldUserWork() throws Exception {
        var endpoint = new OwnershipEndpoint(); endpoint.loseRenew.set(true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var captured = new AtomicReference<ManagedOwnershipContext>();
        try (var policy = policy(endpoint); var binding = policy.bind(TransactionInvocation.contract(Service.class), Worker.class, Service.class);
                var callers = Executors.newSingleThreadExecutor()) {
            var call = callers.submit(() -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> {
                var context = ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class); captured.set(context); entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                assertEquals(MiddlewareErrorCode.STALE_FENCING_TOKEN, assertThrows(MiddlewareException.class, () -> context.write("old", "key", "bad")).code());
                return "must-not-be-success";
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(endpoint.renewalFailed.await(2, TimeUnit.SECONDS));
                eventually(() -> captured.get().revoked()); assertEquals(1, policy.status().activeClaims());
                assertThrows(IllegalStateException.class, policy::close); assertEquals(1, policy.status().activeClaims());
                assertEquals(0, endpoint.effects.get());
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class, () -> call.get(3, TimeUnit.SECONDS));
            assertEquals(0, policy.status().activeClaims()); assertEquals(0, endpoint.effects.get());
        } finally { release.countDown(); }
    }
    @Test void delayedOldRenewalCannotWriteAfterANewHigherFenceAcquiresTheExpiredLease() throws Exception {
        var endpoint = new OwnershipEndpoint(); var clock = new AtomicLong(); var captured = new AtomicReference<ManagedOwnershipContext>();
        var userEntered = new CountDownLatch(1); var releaseUser = new CountDownLatch(1);
        var renewEntered = new CountDownLatch(1); var releaseRenew = new CountDownLatch(1);
        var profile = ManagedOwnershipProfileTest.profile(endpoint, Duration.ofMillis(100));
        try (var oldPolicy = new ManagedOwnershipPolicy(Map.of("default", profile), Set.of("alice"), context -> "alice", clock::get);
                var newPolicy = policy(endpoint);
                var old = oldPolicy.bind(TransactionInvocation.contract(Service.class), Worker.class, Service.class);
                var next = newPolicy.bind(TransactionInvocation.contract(Service.class), Worker.class, Service.class);
                var callers = Executors.newSingleThreadExecutor()) {
            var call = callers.submit(() -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), old, frame -> {
                var context = ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class); captured.set(context);
                endpoint.renewRelease = releaseRenew; endpoint.renewEntered = renewEntered; userEntered.countDown();
                assertTrue(releaseUser.await(3, TimeUnit.SECONDS));
                assertThrows(MiddlewareException.class, () -> context.write("stale", "key", "bad")); return "old";
            }));
            try {
                assertTrue(userEntered.await(2, TimeUnit.SECONDS)); assertTrue(renewEntered.await(2, TimeUnit.SECONDS));
                var oldLease = endpoint.lease(endpoint.leaseKey, java.time.Instant.now().plusSeconds(1)); assertNotNull(oldLease);
                clock.addAndGet(Duration.ofSeconds(5).toNanos()); endpoint.advance(5000);
                // Only the old request is delayed; the new claimant can execute immediately.
                endpoint.renewEntered = null;
                assertEquals("new", TransactionInvocation.invokeAs(TransactionInvocation.caller(), next, frame -> {
                    var current = ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class);
                    assertTrue(current.fence() > oldLease.fence()); current.write("new", "key", "good");
                    assertEquals("CONFLICT", endpoint.execute("FENCED", "raw-old-probe", List.of(endpoint.leaseKey,
                            Long.toString(oldLease.fence()), Long.toString(oldLease.leadershipEpoch()), "forbidden-old", "bad"), java.time.Instant.now().plusSeconds(1)).status());
                    return "new";
                }));
                releaseRenew.countDown(); eventually(() -> captured.get().revoked());
                assertEquals(1, oldPolicy.status().activeClaims()); assertEquals(1, endpoint.effects.get());
            } finally { releaseRenew.countDown(); releaseUser.countDown(); }
            assertThrows(ExecutionException.class, () -> call.get(3, TimeUnit.SECONDS)); assertEquals(1, endpoint.effects.get());
        } finally { releaseRenew.countDown(); releaseUser.countDown(); }
    }
}
