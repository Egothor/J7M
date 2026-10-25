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

package org.j7m.middleware.operations.runtime;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

final class NuggetAuthenticationThrottle {
    private static final String OVERFLOW_KEY = "overflow";
    private static final int MAX_FAILURES_PER_BUCKET = 64;
    private static final int MAX_CLEANUP_SCAN = 64;

    private final int maxBuckets;
    private final long windowNanos;
    private final long maxDelayNanos;
    private final Map<String, Bucket> buckets;

    NuggetAuthenticationThrottle(final int maxBuckets, final Duration window, final Duration maxDelay) {
        this.maxBuckets = Math.max(1, Math.min(maxBuckets, NuggetAdminConfiguration.MAX_THROTTLE_BUCKETS));
        this.windowNanos = Math.max(1L, Objects.requireNonNullElse(window, Duration.ofMinutes(15L)).toNanos());
        Duration configuredDelay = Objects.requireNonNullElse(maxDelay, Duration.ofSeconds(60L));
        Duration cappedDelay = configuredDelay.compareTo(NuggetAdminConfiguration.MAX_THROTTLE_DELAY) > 0
                ? NuggetAdminConfiguration.MAX_THROTTLE_DELAY : configuredDelay;
        this.maxDelayNanos = Math.max(0L, cappedDelay.toNanos());
        this.buckets = new HashMap<>();
    }

    synchronized Attempt beforeAttempt(final String key, final long nowNanos) {
        cleanup(nowNanos);
        String canonical = canonicalKey(key, nowNanos);
        Bucket bucket = this.buckets.get(canonical);
        if (bucket == null || bucket.blockedUntilNanos <= nowNanos) {
            return new Attempt(canonical, Duration.ZERO);
        }
        return new Attempt(canonical, Duration.ofNanos(bucket.blockedUntilNanos - nowNanos));
    }

    synchronized Duration recordFailure(final String key, final long nowNanos) {
        cleanup(nowNanos);
        String canonical = canonicalKey(key, nowNanos);
        Bucket bucket = this.buckets.computeIfAbsent(canonical, ignored -> new Bucket());
        if (bucket.failures < MAX_FAILURES_PER_BUCKET) {
            bucket.failures++;
        }
        bucket.lastFailureNanos = nowNanos;
        long delayNanos = delayFor(bucket.failures);
        bucket.blockedUntilNanos = nowNanos + delayNanos;
        return Duration.ofNanos(delayNanos);
    }

    synchronized void reset(final Attempt attempt, final long nowNanos) {
        Objects.requireNonNull(attempt, "attempt");
        cleanup(nowNanos);
        this.buckets.remove(attempt.canonicalBucket());
    }

    synchronized int bucketCount(final long nowNanos) {
        cleanup(nowNanos);
        return this.buckets.size();
    }

    private String canonicalKey(final String key, final long nowNanos) {
        String safe = key == null || key.isBlank() ? OVERFLOW_KEY : key;
        if (this.buckets.containsKey(safe) || this.buckets.size() < this.maxBuckets) {
            return safe;
        }
        cleanup(nowNanos);
        if (this.buckets.size() < this.maxBuckets) {
            return safe;
        }
        if (!this.buckets.containsKey(OVERFLOW_KEY)) {
            removeOneNonOverflowBucket();
        }
        return OVERFLOW_KEY;
    }

    private void removeOneNonOverflowBucket() {
        Iterator<String> iterator = this.buckets.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!OVERFLOW_KEY.equals(key)) {
                iterator.remove();
                return;
            }
        }
    }

    private void cleanup(final long nowNanos) {
        Iterator<Map.Entry<String, Bucket>> iterator = this.buckets.entrySet().iterator();
        int scanned = 0;
        while (iterator.hasNext() && scanned < MAX_CLEANUP_SCAN) {
            Map.Entry<String, Bucket> entry = iterator.next();
            Bucket bucket = entry.getValue();
            if (bucket.blockedUntilNanos <= nowNanos && nowNanos - bucket.lastFailureNanos > this.windowNanos) {
                iterator.remove();
            }
            scanned++;
        }
    }

    private long delayFor(final int failures) {
        if (failures < 3 || this.maxDelayNanos == 0L) {
            return 0L;
        }
        int exponent = Math.min(20, failures - 3);
        long seconds = 1L << exponent;
        long nanos = Duration.ofSeconds(seconds).toNanos();
        return Math.min(nanos, this.maxDelayNanos);
    }

    private static final class Bucket {
        private int failures;
        private long lastFailureNanos;
        private long blockedUntilNanos;
    }

    record Attempt(String canonicalBucket, Duration retryAfter) {
        Attempt {
            Objects.requireNonNull(canonicalBucket, "canonicalBucket");
            Objects.requireNonNull(retryAfter, "retryAfter");
        }
    }
}
