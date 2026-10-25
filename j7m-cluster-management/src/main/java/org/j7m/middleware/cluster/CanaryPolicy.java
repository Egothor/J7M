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

package org.j7m.middleware.cluster;

import java.util.Objects;

/**
 * Immutable traffic and observation policy. Weights are basis points, not a customer SLO.
 * Routing is stable for one policy revision; changing revisions may deliberately reroute retries.
 * Applications must preserve their own idempotent request identities across deployment changes.
 * @param revision monotonically increasing policy revision
 * @param baseline compatible fallback release identity
 * @param canary candidate release identity
 * @param canaryWeight candidate exposure, 0..10000 basis points
 * @param minimumSamples required candidate observations, 1..1000000
 * @param maximumErrorRate tolerated candidate error fraction, 0..1
 * @param maximumMeanLatencyMillis tolerated arithmetic mean candidate latency, positive milliseconds
 * @param maximumProbeAgeMillis maximum observation age, 1..60000 milliseconds
 */
public record CanaryPolicy(long revision, String baseline, String canary, int canaryWeight,
        long minimumSamples, double maximumErrorRate, long maximumMeanLatencyMillis, long maximumProbeAgeMillis) {
    /**
     * Validates identities and finite thresholds before any mutation.
     * @param revision positive revision
     * @param baseline fallback release
     * @param canary distinct candidate release
     * @param canaryWeight basis points
     * @param minimumSamples candidate sample threshold
     * @param maximumErrorRate finite error fraction
     * @param maximumMeanLatencyMillis positive latency threshold
     * @param maximumProbeAgeMillis bounded freshness threshold
     * @throws IllegalArgumentException if an identity or bound is invalid
     */
    public CanaryPolicy {
        identity(baseline); identity(canary);
        if (revision < 1 || baseline.equals(canary) || canaryWeight < 0 || canaryWeight > 10000
                || minimumSamples < 1 || minimumSamples > 1000000 || !Double.isFinite(maximumErrorRate)
                || maximumErrorRate < 0 || maximumErrorRate > 1 || maximumMeanLatencyMillis < 1
                || maximumMeanLatencyMillis > 60000 || maximumProbeAgeMillis < 1 || maximumProbeAgeMillis > 60000)
            throw new IllegalArgumentException("Invalid canary policy bounds");
    }
    static void identity(String value) {
        Objects.requireNonNull(value);
        if (!value.matches("[A-Za-z0-9_.:/-]{1,256}")) throw new IllegalArgumentException("Invalid deployment identity");
    }
}
