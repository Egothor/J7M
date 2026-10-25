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

package org.j7m.middleware.distributed;

import java.io.IOException;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded automatic logical-time maintenance for a hosted resource group. Every tick first measures
 * clock safety and commits through quorum; followers/unhealthy clocks cannot expire leases. Expiry
 * visibility is derived from committed time, so no local timer authorizes a new owner. Default 250 ms
 * ticks with a one-second deadline support the development profile's five-second healthy expiry bound.
 * Closing cancels future ticks and cooperatively interrupts an outstanding proposal; its outcome may
 * remain UNKNOWN. Operators must monitor failure counters and resource journal capacity.
 */
public final class LeaseTimeMaintenance implements AutoCloseable {
    private final ScheduledExecutorService executor;
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong commits = new AtomicLong();
    /**
     * Starts one daemon maintenance thread.
     * @param resources authorized time-service facade, bound to the hosted group's leader resolver
     * @param interval positive interval at most one second
     * @throws IllegalArgumentException if interval is outside the supported profile
     */
    public LeaseTimeMaintenance(ReplicatedResourceClient resources, Duration interval) {
        this((ResourceEndpoint) resources, interval);
    }
    /**
     * Starts bounded measured-time maintenance over any authorized endpoint implementation.
     * @param resources raw, partition-wrapped or remote time-service endpoint
     * @param interval positive interval at most one second
     * @throws IllegalArgumentException for an invalid interval
     */
    public LeaseTimeMaintenance(ResourceEndpoint resources, Duration interval) {
        java.util.Objects.requireNonNull(resources);
        if (interval.isNegative() || interval.isZero() || interval.compareTo(Duration.ofSeconds(1)) > 0)
            throw new IllegalArgumentException("Maintenance interval must be in (0,1s]");
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "j7m-replicated-time"); thread.setDaemon(true); return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                var result = resources.advanceTime("time-maintenance:" + UUID.randomUUID(), Instant.now().plusSeconds(1));
                if (result.status().equals("OK")) commits.incrementAndGet(); else failures.incrementAndGet();
            } catch (IOException | RuntimeException e) { failures.incrementAndGet(); }
        }, 0, Math.max(1, interval.toMillis()), TimeUnit.MILLISECONDS);
    }
    /**
     * Returns committed maintenance ticks.
     * @return monotonic count
     */
    public long committedTicks() { return commits.get(); }
    /**
     * Returns rejected/failed maintenance ticks, including follower and clock-health failures.
     * @return monotonic count
     */
    public long failedTicks() { return failures.get(); }
    /**
     * {@inheritDoc}
     */
    @Override
    public void close() { executor.shutdownNow(); }
}
