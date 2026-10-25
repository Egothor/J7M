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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ManagedAdmissionControllerTest {
    @Test
    void zeroQueueRejectsSaturationAndPermitCloseIsIdempotentAcrossThreads() throws Exception {
        try (var controller = new ManagedAdmissionController(1, 0, Duration.ofSeconds(1));
                var executor = Executors.newSingleThreadExecutor()) {
            var permit = controller.acquire(0, Duration.ZERO);
            var rejected = assertThrows(MiddlewareException.class, () -> controller.acquire(0, Duration.ZERO));
            assertEquals(MiddlewareErrorCode.OVERLOADED, rejected.code());
            assertEquals(1, controller.status().active());
            executor.submit(permit::close).get(5, TimeUnit.SECONDS);
            permit.close();
            assertEquals(0, controller.status().active());
            try (var next = controller.acquire(1024, Duration.ZERO)) {
                assertEquals(2, controller.status().admitted());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1,9,high,low", "5,5,low,high"})
    void queuedPriorityAndFifoTiesAreHonoredAndOverflowIsBounded(final int lowPriority,
            final int highPriority, final String expectedFirst, final String expectedSecond) throws Exception {
        try (var controller = new ManagedAdmissionController(1, 2, Duration.ofDays(1));
                var executor = Executors.newFixedThreadPool(2)) {
            var first = controller.acquire(0, Duration.ZERO);
            var order = Collections.synchronizedList(new ArrayList<String>());
            var low = executor.submit(() -> {
                try (var permit = controller.acquire(lowPriority, Duration.ofSeconds(5))) { order.add("low"); }
            });
            await(() -> controller.status().queued() == 1);
            var high = executor.submit(() -> {
                try (var permit = controller.acquire(highPriority, Duration.ofSeconds(5))) { order.add("high"); }
            });
            await(() -> controller.status().queued() == 2);
            var rejected = assertThrows(MiddlewareException.class, () -> controller.acquire(100, Duration.ofSeconds(1)));
            assertEquals(MiddlewareErrorCode.OVERLOADED, rejected.code());
            first.close();
            low.get(5, TimeUnit.SECONDS); high.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(expectedFirst, expectedSecond), order);
            assertEquals(0, controller.status().active());
        }
    }

    @Test
    void ageingAllowsAnOlderLowPriorityWaiterToBeatANewHighPriorityWaiter() throws Exception {
        var clock = new AtomicLong();
        try (var controller = new ManagedAdmissionController(1, 2, Duration.ofNanos(1), clock::get);
                var executor = Executors.newFixedThreadPool(2)) {
            var first = controller.acquire(0, Duration.ZERO);
            var order = Collections.synchronizedList(new ArrayList<String>());
            var older = executor.submit(() -> {
                try (var permit = controller.acquire(-1024, Duration.ofSeconds(5))) { order.add("older"); }
            });
            await(() -> controller.status().queued() == 1);
            clock.set(10_000);
            var newer = executor.submit(() -> {
                try (var permit = controller.acquire(1024, Duration.ofSeconds(5))) { order.add("newer"); }
            });
            await(() -> controller.status().queued() == 2);
            first.close();
            older.get(5, TimeUnit.SECONDS); newer.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("older", "newer"), order);
        }
    }

    @Test
    void queuedDeadlineAndCloseDoNotReleaseTheStillRunningPermit() throws Exception {
        var clock = new AtomicLong();
        var controller = new ManagedAdmissionController(1, 2, Duration.ofSeconds(1), clock::get);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var running = controller.acquire(0, Duration.ZERO);
            var timed = executor.submit(() -> assertThrows(MiddlewareException.class,
                    () -> controller.acquire(0, Duration.ofNanos(100))));
            await(() -> controller.status().queued() == 1);
            clock.set(101);
            var wake = executor.submit(() -> assertThrows(MiddlewareException.class,
                    () -> controller.acquire(0, Duration.ofSeconds(5))));
            assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, timed.get(5, TimeUnit.SECONDS).code());
            await(() -> controller.status().queued() == 1);
            controller.close();
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, wake.get(5, TimeUnit.SECONDS).code());
            assertEquals(1, controller.status().active());
            running.close();
            assertEquals(0, controller.status().active());
            assertTrue(controller.status().closed());
        } finally { controller.close(); }
    }

    @Test
    void interruptedAdmissionRejectsWithoutClearingTheInterruptFlag() {
        try (var controller = new ManagedAdmissionController(1, 1, Duration.ofSeconds(1))) {
            Thread.currentThread().interrupt();
            try {
                var failure = assertThrows(MiddlewareException.class, () -> controller.acquire(0, Duration.ofSeconds(1)));
                assertEquals(MiddlewareErrorCode.CANCELLED, failure.code());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, controller.status().active());
            } finally { Thread.interrupted(); }
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptedQueuedCallerReleasesOnlyItsQueuePosition() throws Exception {
        try (var controller = new ManagedAdmissionController(1, 1, Duration.ofSeconds(1));
                var running = controller.acquire(0, Duration.ZERO)) {
            var failure = new AtomicReference<MiddlewareException>();
            var preserved = new AtomicBoolean();
            Thread waiter = Thread.startVirtualThread(() -> {
                try (var permit = controller.acquire(0, Duration.ofSeconds(5))) {
                    throw new AssertionError("Queued caller unexpectedly admitted");
                } catch (MiddlewareException rejected) {
                    failure.set(rejected);
                    preserved.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                await(() -> controller.status().queued() == 1);
                waiter.interrupt();
                waiter.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(waiter.isAlive());
                assertEquals(MiddlewareErrorCode.CANCELLED, failure.get().code());
                assertTrue(preserved.get());
                assertEquals(0, controller.status().queued());
                assertEquals(1, controller.status().active());
            } finally {
                waiter.interrupt();
                waiter.join(TimeUnit.SECONDS.toMillis(5));
            }
        }
    }

    @Test
    void invalidBoundsCannotCreateAnUnboundedOrNegativeCapacityProfile() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedAdmissionController(0, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedAdmissionController(1_000_001, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedAdmissionController(1, 1025, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedAdmissionController(1, 1, Duration.ZERO));
        try (var maximum = new ManagedAdmissionController(1_000_000, 1024, Duration.ofDays(1));
                var permit = maximum.acquire(-1024, Duration.ofMinutes(1))) {
            assertEquals(1, maximum.status().active());
        }
        try (var controller = new ManagedAdmissionController(1, 1, Duration.ofSeconds(1))) {
            assertThrows(IllegalArgumentException.class, () -> controller.acquire(1025, Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> controller.acquire(0, Duration.ofMinutes(2)));
        }
    }

    private static void await(final BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) { Thread.onSpinWait(); }
        assertTrue(condition.getAsBoolean(), "Admission synchronization condition");
    }
}
