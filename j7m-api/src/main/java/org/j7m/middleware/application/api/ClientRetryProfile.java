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

package org.j7m.middleware.application.api;

import java.time.Duration;

/**
 * Finite trusted-host limits for optional pre-delivery retry. No profile enables replay after
 * business entry, durable deduplication, or exactly-once external effects.
 * @param maximumAdditionalAttempts maximum retries after the initial call, from0 to16
 * @param initialBackoff initial nonnegative backoff, at most30 seconds
 * @param maximumBackoff backoff cap, at least the initial value and at most30 seconds
 * @param jitterFraction finite proportional jitter from0 to1; the cap still applies after jitter
 */
public record ClientRetryProfile(int maximumAdditionalAttempts, Duration initialBackoff, Duration maximumBackoff, double jitterFraction) {
    /**
     * Validates finite retry bounds without allocating workers.
     * @param maximumAdditionalAttempts retries after the first call
     * @param initialBackoff initial nonnegative delay
     * @param maximumBackoff finite maximum delay
     * @param jitterFraction finite value in the inclusive range0..1
     * @throws IllegalArgumentException for an invalid count, duration, cap or jitter value
     */
    public ClientRetryProfile {
        Duration limit = Duration.ofSeconds(30);
        if (maximumAdditionalAttempts < 0 || maximumAdditionalAttempts > 16 || initialBackoff == null || maximumBackoff == null
                || initialBackoff.isNegative() || maximumBackoff.compareTo(initialBackoff) < 0 || maximumBackoff.compareTo(limit) > 0
                || !Double.isFinite(jitterFraction) || jitterFraction < 0 || jitterFraction > 1) throw new IllegalArgumentException("Invalid client retry profile");
    }
}
