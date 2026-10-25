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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManagedProbeCoordinatorTest {
    private static final Duration BUDGET = Duration.ofSeconds(2);

    @Test void aggregatesBooleanResultsAndRefreshesCompletedChecks() {
        AtomicInteger calls = new AtomicInteger();
        try (var owner = new ManagedProbeCoordinator(2, 1);
             var registration = owner.register(List.of(new ManagedProbeCoordinator.Check("health", () -> calls.incrementAndGet() == 1)))) {
            assertEquals(ManagedProbeCoordinator.State.HEALTHY, known(registration).state());
            var changed = known(registration);
            assertEquals(ManagedProbeCoordinator.State.UNHEALTHY, changed.state());
            assertEquals(1, changed.completed()); assertEquals(1, changed.total()); assertEquals(2, calls.get());
        }
    }

    @Test void coalescesBlockedChecksAndNeverReleasesPhysicalCapacityOnTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), otherCalls = new AtomicInteger();
        try (var owner = new ManagedProbeCoordinator(1, 2);
             var registration = owner.register(List.of(new ManagedProbeCoordinator.Check("blocked", () -> {
                 calls.incrementAndGet(); entered.countDown(); release.await(); return true;
             })));
             var other = owner.register(List.of(new ManagedProbeCoordinator.Check("other", () -> { otherCalls.incrementAndGet(); return true; })))) {
            try {
                assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.inspect(Duration.ofNanos(1)).state());
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.inspect(Duration.ofNanos(1)).state());
                assertEquals(ManagedProbeCoordinator.State.UNKNOWN, other.inspect(BUDGET).state());
                assertEquals(1, calls.get()); assertEquals(0, otherCalls.get());
            } finally { release.countDown(); }
        }
    }

    @Test void callbackErrorsAndNullResultsBecomeUnknownWithoutExposingSecrets() {
        try (var owner = new ManagedProbeCoordinator(2, 2);
             var failing = owner.register(List.of(new ManagedProbeCoordinator.Check("failed", () -> { throw new IllegalStateException("secret-token"); })));
             var absent = owner.register(List.of(new ManagedProbeCoordinator.Check("null", () -> null)))) {
            var observation = failing.inspect(BUDGET);
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, observation.state());
            assertEquals(0, observation.completed()); assertFalse(observation.toString().contains("secret-token"));
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, absent.inspect(BUDGET).state());
        }
    }

    @Test void closingRegistrationInvalidatesConcurrentObservationAndReplacementCannotInheritIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var owner = new ManagedProbeCoordinator(1, 1); var executor = Executors.newSingleThreadExecutor()) {
            var registration = owner.register(List.of(new ManagedProbeCoordinator.Check("probe", () -> {
                entered.countDown(); release.await(); return true;
            })));
            var observation = executor.submit(() -> registration.inspect(BUDGET));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS)); registration.close();
                assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.inspect(BUDGET).state());
                try (var replacement = owner.register(List.of())) {
                    assertEquals(ManagedProbeCoordinator.State.HEALTHY, replacement.inspect(BUDGET).state());
                }
            } finally { release.countDown(); }
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, observation.get(2, TimeUnit.SECONDS).state());
        }
    }

    @Test void closeDoesNotWaitForNonCooperativeCallbackOrPretendItCompleted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicInteger interruptions = new AtomicInteger();
        var owner = new ManagedProbeCoordinator(1, 1);
        var registration = owner.register(List.of(new ManagedProbeCoordinator.Check("ignores-interruption", () -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException interrupted) { interruptions.incrementAndGet(); }
            }
            returned.countDown(); return true;
        })));
        try {
            registration.inspect(Duration.ofNanos(1)); assertTrue(entered.await(2, TimeUnit.SECONDS)); owner.close();
            assertEquals(1, returned.getCount());
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.inspect(BUDGET).state());
            assertThrows(IllegalStateException.class, () -> owner.register(List.of()));
        } finally { release.countDown(); owner.close(); }
        assertTrue(returned.await(2, TimeUnit.SECONDS));
        assertTrue(interruptions.get() <= 1);
    }

    @Test void interruptedInspectionPreservesInterruptAndDoesNotCancelCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var owner = new ManagedProbeCoordinator(1, 1);
             var registration = owner.register(List.of(new ManagedProbeCoordinator.Check("probe", () -> {
                 entered.countDown(); release.await(); return true;
             })))) {
            registration.inspect(Duration.ofNanos(1)); assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                Thread.currentThread().interrupt();
                assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.inspect(BUDGET).state());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); release.countDown(); }
        }
    }

    private static ManagedProbeCoordinator.Snapshot known(ManagedProbeCoordinator.Registration registration) {
        long until = System.nanoTime() + BUDGET.toNanos();
        ManagedProbeCoordinator.Snapshot result;
        do {
            result = registration.inspect(BUDGET);
            if (result.state() != ManagedProbeCoordinator.State.UNKNOWN) return result;
            java.util.concurrent.locks.LockSupport.parkNanos(1000);
        } while (System.nanoTime() < until);
        throw new AssertionError("finite side-effect-free probe did not become available");
    }

    @Test void enforcesBoundsDuplicateIdentityAndOwnerRegistrationCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbeCoordinator(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbeCoordinator(65, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbeCoordinator(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbeCoordinator(1, 1025));
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbeCoordinator.Check(" ", () -> true));
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbeCoordinator.Check("x".repeat(257), () -> true));
        var check = new ManagedProbeCoordinator.Check("same", () -> true);
        try (var owner = new ManagedProbeCoordinator(64, 1); var registration = owner.register(List.of())) {
            assertThrows(IllegalArgumentException.class, () -> owner.register(List.of(check, check)));
            assertThrows(IllegalArgumentException.class, () -> owner.register(java.util.stream.IntStream.range(0, 65)
                    .mapToObj(i -> new ManagedProbeCoordinator.Check("probe" + i, () -> true)).toList()));
            assertThrows(IllegalStateException.class, () -> owner.register(List.of(check)));
            assertThrows(IllegalArgumentException.class, () -> registration.inspect(Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> registration.inspect(Duration.ofNanos(-1)));
            assertThrows(IllegalArgumentException.class, () -> registration.inspect(Duration.ofMinutes(1).plusNanos(1)));
            assertEquals(ManagedProbeCoordinator.State.HEALTHY, registration.inspect(Duration.ofMinutes(1)).state());
        }
    }
}
