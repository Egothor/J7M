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

package org.j7m.middleware.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.j7m.middleware.api.coordination.CasOptions;
import org.j7m.middleware.api.coordination.CoordinateKey;
import org.j7m.middleware.api.coordination.CoordinateValue;
import org.j7m.middleware.api.coordination.JoinOptions;
import org.j7m.middleware.api.coordination.LeaveOptions;
import org.j7m.middleware.api.coordination.MemberId;
import org.j7m.middleware.api.coordination.MembershipQuery;
import org.j7m.middleware.api.coordination.WatchEventType;
import org.j7m.middleware.api.coordination.WatchRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class CoordinationWatchNotifyTest {
    @TempDir
    Path directory;

    @Test
    public void watchNotifyCloseAndFailureIsolationWork() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        AtomicInteger count = new AtomicInteger();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            CoordinateKey key = new CoordinateKey("watched");
            WatchRegistration registration = service.watch(key, event -> count.incrementAndGet());
            service.watch(key, event -> {
                throw new IllegalStateException("callback failure");
            });
            service.compareAndSet(key, null, new CoordinateValue(new byte[] {1}), CasOptions.defaults());
            await(() -> count.get() == 1);
            assertEquals(1, count.get());
            registration.close();
            service.compareAndSet(key, new CoordinateValue(new byte[] {1}), new CoordinateValue(new byte[] {2}), CasOptions.defaults());
            assertEquals(1, count.get());
            assertTrue(service.status().metrics().watchNotifications() >= 1L);
        }
    }

    @Test
    public void membershipLivenessAndSplitBrainWatchNotificationsMatchDocumentedScope() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        AtomicInteger membershipEvents = new AtomicInteger();
        AtomicInteger splitBrainEvents = new AtomicInteger();
        AtomicInteger reentrantStatusCalls = new AtomicInteger();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            WatchRegistration membershipRegistration = service.watch(new CoordinateKey("membership"), event -> {
                assertEquals(WatchEventType.MEMBERSHIP_CHANGED, event.type());
                membershipEvents.incrementAndGet();
                service.membership(MembershipQuery.all());
                reentrantStatusCalls.incrementAndGet();
            });
            WatchRegistration splitBrainRegistration = service.watch(new CoordinateKey("split-brain"), event -> {
                assertEquals(WatchEventType.SPLIT_BRAIN_SUSPECTED, event.type());
                splitBrainEvents.incrementAndGet();
            });
            service.join(CoordinationTestSupport.member("node-watch-a", "local://watch-a"), JoinOptions.defaults());
            service.join(CoordinationTestSupport.member("node-watch-b", "local://watch-b"), JoinOptions.defaults());
            service.leave(new MemberId("node-watch-b"), LeaveOptions.defaults());
            clock.advance(Duration.ofSeconds(3L));
            service.status();
            service.join(CoordinationTestSupport.member("node-watch-c", "local://watch-a"), JoinOptions.defaults());
            await(() -> membershipEvents.get() >= 4 && splitBrainEvents.get() == 1 && reentrantStatusCalls.get() >= 1);
            assertTrue(membershipEvents.get() >= 4);
            assertEquals(1, splitBrainEvents.get());
            assertTrue(reentrantStatusCalls.get() >= 1);

            membershipRegistration.close();
            splitBrainRegistration.close();
            int membershipAfterClose = membershipEvents.get();
            int splitBrainAfterClose = splitBrainEvents.get();
            service.join(CoordinationTestSupport.member("node-watch-d", "local://watch-d"), JoinOptions.defaults());
            service.join(CoordinationTestSupport.member("node-watch-e", "local://watch-d"), JoinOptions.defaults());
            await(() -> true);
            assertEquals(membershipAfterClose, membershipEvents.get());
            assertEquals(splitBrainAfterClose, splitBrainEvents.get());
        }
    }

    @Test
    public void callbackFailureIsolationAppliesToSplitBrainAndLivenessNotifications() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        AtomicInteger membershipEvents = new AtomicInteger();
        AtomicInteger splitBrainEvents = new AtomicInteger();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            service.watch(new CoordinateKey("membership"), event -> {
                membershipEvents.incrementAndGet();
                throw new IllegalStateException("membership failure");
            });
            service.watch(new CoordinateKey("split-brain"), event -> {
                splitBrainEvents.incrementAndGet();
                throw new IllegalStateException("split failure");
            });
            service.join(CoordinationTestSupport.member("node-failure-a", "local://failure-a"), JoinOptions.defaults());
            clock.advance(Duration.ofSeconds(3L));
            service.status();
            service.join(CoordinationTestSupport.member("node-failure-b", "local://failure-a"), JoinOptions.defaults());
            await(() -> membershipEvents.get() >= 2 && splitBrainEvents.get() == 1);
            assertTrue(membershipEvents.get() >= 2);
            assertEquals(1, splitBrainEvents.get());
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("WATCH_CALLBACK_FAILED")
                    && event.metadata().get("eventType").equals(WatchEventType.MEMBERSHIP_CHANGED.name())));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("WATCH_CALLBACK_FAILED")
                    && event.metadata().get("eventType").equals(WatchEventType.SPLIT_BRAIN_SUSPECTED.name())));
        }
    }

    @Test
    public void slowWatcherDoesNotBlockMutationStatusOrClose() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        CountDownLatch entered = new CountDownLatch(1);
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            CoordinateKey key = new CoordinateKey("slow-watch");
            service.watch(key, event -> {
                entered.countDown();
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            service.compareAndSet(key, null, new CoordinateValue(new byte[] {1}), CasOptions.defaults());
            service.status();
            await(() -> entered.getCount() == 0L);
        }
    }

    @Test
    public void closeStopsIdleWatchDispatcher() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock);
        service.close();

        await(() -> !dispatcherAlive());
        assertTrue(!dispatcherAlive());
    }

    private static void await(final BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2L).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private static boolean dispatcherAlive() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("j7m-coordination-watch-dispatcher".equals(thread.getName()) && thread.isAlive()) {
                return true;
            }
        }
        return false;
    }
}
