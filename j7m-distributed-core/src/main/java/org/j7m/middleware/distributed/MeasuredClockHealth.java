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

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Fail-closed wall/monotonic/reference clock monitor. A wall-clock jump or reference offset above
 * 500 ms makes the current sample unsafe. Recovery requires two consecutive stable reference-aligned
 * samples; the reference supplier must aggregate authenticated healthy peers or a trusted time source.
 * A single process's own wall-clock is not a valid reference. Methods are synchronized/thread-safe.
 */
public final class MeasuredClockHealth implements ReplicatedResourceClient.ClockHealth {
    private final LongSupplier wallMillis, monotonicNanos, referenceMillis, uncertaintyMillis;
    private long previousWall, previousMonotonic;
    private boolean initialized;
    private int stableSamples;
    /**
     * Constructs an initially unsafe monitor.
     * @param wallMillis wall clock milliseconds
     * @param monotonicNanos monotonic nanoseconds
     * @param referenceMillis authenticated reference milliseconds
     * @param uncertaintyMillis reference uncertainty milliseconds
     */
    public MeasuredClockHealth(LongSupplier wallMillis, LongSupplier monotonicNanos,
            LongSupplier referenceMillis, LongSupplier uncertaintyMillis) {
        this.wallMillis = Objects.requireNonNull(wallMillis); this.monotonicNanos = Objects.requireNonNull(monotonicNanos);
        this.referenceMillis = Objects.requireNonNull(referenceMillis); this.uncertaintyMillis = Objects.requireNonNull(uncertaintyMillis);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ReplicatedResourceClient.ClockMeasurement sample() {
        long wall = wallMillis.getAsLong(), monotonic = monotonicNanos.getAsLong();
        long reference = referenceMillis.getAsLong(), uncertainty = uncertaintyMillis.getAsLong();
        boolean aligned = wall >= 0 && reference >= 0 && uncertainty >= 0 && uncertainty <= 500
                && distance(wall, reference) <= 500;
        boolean elapsed = !initialized || monotonic >= previousMonotonic
                && distance(wall - previousWall, (monotonic - previousMonotonic) / 1_000_000) <= 500;
        stableSamples = aligned && elapsed ? Math.min(2, stableSamples + 1) : 0;
        previousWall = wall; previousMonotonic = monotonic; initialized = true;
        return new ReplicatedResourceClient.ClockMeasurement(wall, reference, uncertainty, stableSamples >= 2);
    }
    private static long distance(long first, long second) {
        try { return Math.abs(Math.subtractExact(first, second)); }
        catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }
}
