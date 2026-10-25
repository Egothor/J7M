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

import java.time.Duration;
import java.util.Objects;

/**
 * Trusted host configuration for automatic managed probes; never application-selected policy.
 * Bounds apply to one manager's physical workers and activation registrations. Sampling uses a
 * shared monotonic per-activation budget, and export readiness must expire after freshness.
 * @param maximumConcurrent physically running callbacks,1..64
 * @param maximumRegistrations monitored activations,1..1024
 * @param interval sampling delay, positive and at most one minute
 * @param budget per-activation inspection wait, positive and at most one minute
 * @param freshness observation validity, one millisecond through one minute
 */
public record ManagedProbeSettings(int maximumConcurrent, int maximumRegistrations,
        Duration interval, Duration budget, Duration freshness) {
    /**
     * Validates finite capacities and intervals without starting workers.
     * @param maximumConcurrent physical callback bound
     * @param maximumRegistrations activation registration bound
     * @param interval sampling delay
     * @param budget total inspection wait
     * @param freshness observation validity
     * @throws IllegalArgumentException for an out-of-range value
     * @throws NullPointerException for a null duration
     */
    public ManagedProbeSettings {
        if (maximumConcurrent < 1 || maximumConcurrent > 64 || maximumRegistrations < 1 || maximumRegistrations > 1024)
            throw new IllegalArgumentException("invalid probe capacity");
        validate(interval); validate(budget); validate(freshness);
        if (freshness.compareTo(Duration.ofMillis(1)) < 0) throw new IllegalArgumentException("probe freshness below export precision");
    }

    private static void validate(Duration value) {
        Objects.requireNonNull(value, "probe duration");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("invalid probe duration");
    }

    /**
     * Returns the bounded development profile: four workers,64 activations,100ms sampling/wait,
     * and two-second observation validity. Hosts can install a different validated profile.
     * @return immutable default host settings
     */
    public static ManagedProbeSettings defaults() {
        return new ManagedProbeSettings(4, 64, Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    /**
     * Creates a new owned monitor honoring these settings; the caller owns its cleanup.
     * @return new monitor with no registered applications
     */
    public ManagedProbeMonitor newMonitor() {
        return new ManagedProbeMonitor(maximumConcurrent, maximumRegistrations, interval, budget, freshness);
    }
}
