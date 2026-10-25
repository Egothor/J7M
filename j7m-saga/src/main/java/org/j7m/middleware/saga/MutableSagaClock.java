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

package org.j7m.middleware.saga;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Mutable deterministic clock for saga tests and demos.
 */
public final class MutableSagaClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    /**
     * Creates a UTC clock at an instant.
     *
     * @param instant initial instant, or epoch when {@code null}
     */
    public MutableSagaClock(final Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    /**
     * Creates a clock at an instant and zone.
     *
     * @param instant initial instant, or epoch when {@code null}
     * @param zone time zone, or UTC when {@code null}
     */
    public MutableSagaClock(final Instant instant, final ZoneId zone) {
        this.instant = instant == null ? Instant.EPOCH : instant;
        this.zone = zone == null ? ZoneOffset.UTC : zone;
    }

    /**
     * Advances the clock.
     *
     * @param duration positive, zero, or {@code null} duration to add
     */
    public synchronized void advance(final java.time.Duration duration) {
        this.instant = this.instant.plus(duration == null ? java.time.Duration.ZERO : duration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ZoneId getZone() {
        return this.zone;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Clock withZone(final ZoneId zone) {
        return new MutableSagaClock(this.instant(), zone);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized Instant instant() {
        return this.instant;
    }
}
