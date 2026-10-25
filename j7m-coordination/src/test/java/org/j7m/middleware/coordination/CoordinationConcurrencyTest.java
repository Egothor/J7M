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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.coordination.CasOptions;
import org.j7m.middleware.api.coordination.CasResult;
import org.j7m.middleware.api.coordination.CoordinateKey;
import org.j7m.middleware.api.coordination.CoordinateValue;
import org.j7m.middleware.api.coordination.CoordinationException;
import org.j7m.middleware.api.coordination.LeaseGrant;
import org.j7m.middleware.api.coordination.LeaseName;
import org.j7m.middleware.api.coordination.LeaseReleaseResult;
import org.j7m.middleware.api.coordination.LeaseRenewal;
import org.j7m.middleware.api.coordination.LeaseState;
import org.j7m.middleware.api.coordination.PrincipalRef;
import org.j7m.middleware.api.coordination.ReleaseLeaseOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class CoordinationConcurrencyTest {
    @TempDir
    Path directory;

    @Test
    public void concurrentLeaseAndCasAreDeterministic() throws Exception {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            List<Boolean> leases = runConcurrent(6, index -> {
                try {
                    service.acquireLease(new LeaseName("concurrent"), new PrincipalRef("owner-" + index), CoordinationTestSupport.leaseOptions());
                    return Boolean.TRUE;
                } catch (CoordinationException exception) {
                    return Boolean.FALSE;
                }
            });
            assertEquals(1L, leases.stream().filter(Boolean::booleanValue).count());

            CoordinateKey key = new CoordinateKey("concurrent/key");
            List<CasResult> results = runConcurrent(6, index -> service.compareAndSet(key, null,
                    new CoordinateValue(new byte[] {(byte) index}), CasOptions.defaults()));
            assertEquals(1L, results.stream().filter(CasResult::success).count());
        }
    }

    @Test
    public void concurrentRenewReleaseAndRaceKeepLeaseStateValid() throws Exception {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            LeaseGrant renewGrant = service.acquireLease(new LeaseName("concurrent-renew"), new PrincipalRef("owner"),
                    CoordinationTestSupport.leaseOptions());
            List<Boolean> renewals = runConcurrent(6, index -> {
                try {
                    LeaseRenewal renewal = service.renewLease(renewGrant.leaseId(), renewGrant.epoch().value());
                    return renewal.epoch().equals(renewGrant.epoch());
                } catch (CoordinationException exception) {
                    return Boolean.FALSE;
                }
            });
            assertEquals(6L, renewals.stream().filter(Boolean::booleanValue).count());
            assertEquals(LeaseState.ACTIVE, service.status().leases().stream()
                    .filter(status -> status.leaseName().value().equals("concurrent-renew"))
                    .findFirst().orElseThrow().state());

            LeaseGrant releaseGrant = service.acquireLease(new LeaseName("concurrent-release"), new PrincipalRef("owner"),
                    CoordinationTestSupport.leaseOptions());
            List<Boolean> releases = runConcurrent(6, index -> {
                try {
                    LeaseReleaseResult result = service.releaseLease(releaseGrant.leaseId(), new ReleaseLeaseOptions(0L));
                    return result.state() == LeaseState.RELEASED;
                } catch (CoordinationException exception) {
                    return Boolean.FALSE;
                }
            });
            assertEquals(1L, releases.stream().filter(Boolean::booleanValue).count());
            assertEquals(LeaseState.RELEASED, service.status().leases().stream()
                    .filter(status -> status.leaseName().value().equals("concurrent-release"))
                    .findFirst().orElseThrow().state());

            LeaseGrant raceGrant = service.acquireLease(new LeaseName("concurrent-race"), new PrincipalRef("owner"),
                    CoordinationTestSupport.leaseOptions());
            List<String> outcomes = runConcurrent(8, index -> {
                try {
                    if ((index & 1) == 0) {
                        service.renewLease(raceGrant.leaseId(), raceGrant.epoch().value());
                        return "renewed";
                    }
                    service.releaseLease(raceGrant.leaseId(), new ReleaseLeaseOptions(0L));
                    return "released";
                } catch (CoordinationException exception) {
                    return "rejected:" + exception.code().name();
                }
            });
            assertTrue(outcomes.stream().anyMatch("released"::equals));
            assertTrue(outcomes.stream().allMatch(outcome -> outcome.equals("renewed")
                    || outcome.equals("released") || outcome.equals("rejected:STALE_FENCING_TOKEN")));
            assertEquals(LeaseState.RELEASED, service.status().leases().stream()
                    .filter(status -> status.leaseName().value().equals("concurrent-race"))
                    .findFirst().orElseThrow().state());
        }
    }

    @Test
    public void concurrentWatchNotificationsDoNotCorruptDispatcher() throws Exception {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch deliveredLatch = new CountDownLatch(4);
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            CoordinateKey key = new CoordinateKey("concurrent/watch");
            for (int index = 0; index < 8; index++) {
                int safeIndex = index;
                service.watch(key, event -> {
                    if ((safeIndex & 1) == 0) {
                        throw new IllegalStateException("expected callback failure");
                    }
                    delivered.incrementAndGet();
                    deliveredLatch.countDown();
                });
            }
            List<Boolean> updates = runConcurrent(8, index -> service.compareAndSet(new CoordinateKey("concurrent/watch/" + index),
                    null, new CoordinateValue(new byte[] {(byte) index}), CasOptions.defaults()).success());
            assertEquals(8L, updates.stream().filter(Boolean::booleanValue).count());
            service.compareAndSet(key, null, new CoordinateValue(new byte[] {1}), CasOptions.defaults());
            assertTrue(deliveredLatch.await(5L, TimeUnit.SECONDS));
            assertEquals(4, delivered.get());
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("WATCH_CALLBACK_FAILED")));
        }
    }

    private static <T> List<T> runConcurrent(final int count, final ConcurrentAction<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int safeIndex = index;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    return action.run(safeIndex);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(10L, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface ConcurrentAction<T> {
        T run(int index);
    }
}
