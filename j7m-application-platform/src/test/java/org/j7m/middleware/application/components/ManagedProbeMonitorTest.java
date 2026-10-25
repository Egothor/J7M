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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ManagedProbeMonitorTest {
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static ManagedProbeMonitor manual(AtomicLong clock) {
        return new ManagedProbeMonitor(1, 1, SECOND, SECOND, SECOND, clock::get, false);
    }

    @Test void startsUnknownPublishesFreshReadinessAndExpiresAtExactMonotonicBoundary() {
        AtomicLong clock = new AtomicLong(); AtomicBoolean ready = new AtomicBoolean();
        try (var monitor = manual(clock); var registration = monitor.register(List.of(new ManagedProbeCoordinator.Check("ready", () -> true)), true, ready::set)) {
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.snapshot().state());
            sampleKnown(registration); assertTrue(ready.get());
            clock.set(SECOND.toNanos() - 1);
            assertEquals(ManagedProbeCoordinator.State.HEALTHY, registration.snapshot().state());
            clock.incrementAndGet();
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.snapshot().state());
            sampleKnown(registration); assertEquals(ManagedProbeCoordinator.State.HEALTHY, registration.snapshot().state());
            assertEquals(SECOND, monitor.freshness());
        }
    }

    @Test void missingReadinessNeverPublishesHealthyEvenForSuccessfulHealthProbe() {
        AtomicBoolean published = new AtomicBoolean(true);
        try (var monitor = manual(new AtomicLong());
             var registration = monitor.register(List.of(new ManagedProbeCoordinator.Check("health", () -> true)), false, published::set)) {
            registration.sample(); assertFalse(published.get());
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.snapshot().state());
        }
    }

    @Test void publicationFailureBecomesUnknownAndNextSuccessfulSampleRecovers() {
        AtomicBoolean reject = new AtomicBoolean(true);
        try (var monitor = manual(new AtomicLong()); var registration = monitor.register(List.of(new ManagedProbeCoordinator.Check("ready", () -> true)), true,
                ignored -> { if (reject.get()) throw new IllegalStateException("secret-publication-detail"); })) {
            registration.sample(); assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.snapshot().state());
            reject.set(false); sampleKnown(registration);
            assertEquals(ManagedProbeCoordinator.State.HEALTHY, registration.snapshot().state());
            assertFalse(registration.snapshot().toString().contains("secret-publication-detail"));
        }
    }

    @Test void closedRegistrationsStopPublicationAndReleaseOwnerCapacity() {
        AtomicInteger publications = new AtomicInteger();
        try (var monitor = manual(new AtomicLong())) {
            var registration = monitor.register(List.of(), false, ignored -> publications.incrementAndGet());
            registration.close(); registration.close(); registration.sample();
            assertEquals(0, publications.get()); assertEquals(ManagedProbeCoordinator.State.UNKNOWN, registration.snapshot().state());
            try (var next = monitor.register(List.of(), false, ignored -> publications.incrementAndGet())) {
                next.sample(); assertEquals(1, publications.get());
            }
            monitor.close(); assertThrows(IllegalStateException.class, () -> monitor.register(List.of(), false, ignored -> { }));
        }
    }

    @Test void actualTimerRunsApplicationCheckOnSeparateWorkerAndPublishesObservation() throws Exception {
        CountDownLatch published = new CountDownLatch(1); AtomicBoolean separateWorker = new AtomicBoolean();
        try (var monitor = new ManagedProbeMonitor(1, 1, Duration.ofMillis(10), SECOND, SECOND);
             var registration = monitor.register(List.of(new ManagedProbeCoordinator.Check("ready", () -> {
                 separateWorker.set(Thread.currentThread().getName().startsWith("j7m-probe-")
                         && !Thread.currentThread().getName().equals("j7m-probe-monitor")); return true;
             })), true, ready -> { if (ready) published.countDown(); })) {
            assertTrue(published.await(2, TimeUnit.SECONDS)); assertTrue(separateWorker.get());
            assertEquals(ManagedProbeCoordinator.State.HEALTHY, registration.snapshot().state());
        }
    }

    @Test void timerAndWorkerNeverInheritAnApplicationClassLoaderFromRegistrationCaller() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader applicationLoader = new ClassLoader(previous) { };
        InheritableThreadLocal<ClassLoader> applicationContext = new InheritableThreadLocal<>();
        CountDownLatch published = new CountDownLatch(1);
        AtomicBoolean safeWorker = new AtomicBoolean(), safeTimer = new AtomicBoolean();
        try {
            Thread.currentThread().setContextClassLoader(applicationLoader); applicationContext.set(applicationLoader);
            try (var monitor = new ManagedProbeMonitor(1, 1, Duration.ofMillis(10), SECOND, SECOND);
                 var registration = monitor.register(List.of(new ManagedProbeCoordinator.Check("ready", () -> {
                     safeWorker.set(Thread.currentThread().getContextClassLoader() == ManagedProbeCoordinator.class.getClassLoader() && applicationContext.get() == null); return true;
                 })), true, ready -> {
                     safeTimer.set(Thread.currentThread().getContextClassLoader() == ManagedProbeMonitor.class.getClassLoader() && applicationContext.get() == null);
                     if (ready) published.countDown();
                 })) {
                assertTrue(published.await(2, TimeUnit.SECONDS)); assertTrue(safeWorker.get()); assertTrue(safeTimer.get());
                assertEquals(ManagedProbeCoordinator.State.HEALTHY, registration.snapshot().state());
            }
        } finally { applicationContext.remove(); Thread.currentThread().setContextClassLoader(previous); }
    }

    private static void sampleKnown(ManagedProbeMonitor.Registration registration) {
        long until = System.nanoTime() + SECOND.toNanos();
        do {
            registration.sample();
            if (registration.snapshot().state() != ManagedProbeCoordinator.State.UNKNOWN) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1000);
        } while (System.nanoTime() < until);
        fail("finite side-effect-free probe did not become available");
    }

    @Test void rejectsInvalidDurationsAndAcceptsDocumentedMaximum() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofMinutes(1).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class, () -> new ManagedProbeMonitor(1, 1, invalid, SECOND, SECOND));
            assertThrows(IllegalArgumentException.class, () -> new ManagedProbeMonitor(1, 1, SECOND, invalid, SECOND));
            assertThrows(IllegalArgumentException.class, () -> new ManagedProbeMonitor(1, 1, SECOND, SECOND, invalid));
        }
        try (var monitor = new ManagedProbeMonitor(64, 1024, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))) {
            assertEquals(Duration.ofMinutes(1), monitor.freshness());
        }
    }
}
