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

package org.j7m.middleware.metrics;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.j7m.middleware.api.MiddlewareErrorCode;

/**
 * Thread-safe bounded sliding-window statistics collector.
 *
 * <p>The collector stores only the most recent {@code capacity} events and
 * aggregate counters. It is intended for runtime diagnostics and HP scoring,
 * not durable accounting.</p>
 */
public final class SlidingWindowStatistics {
    private static final int DEFAULT_MAX_COUNTERS = 1024;
    private final int capacity;
    private final long retentionMillis;
    private final LongSupplier clock;
    private final Object lock;
    private final Object counterLock;
    private final Deque<StatisticsEvent> events;
    private long successCount;
    private long failureCount;
    private long totalLatencyNanos;
    private final ConcurrentMap<String, AtomicLong> counters;
    private final int maxCounters;

    /**
     * Creates a bounded statistics window.
     *
     * @param capacity maximum retained events
     */
    public SlidingWindowStatistics(final int capacity) {
        this(capacity, Duration.ofMillis(Long.MAX_VALUE), System::currentTimeMillis);
    }

    /**
     * Creates a bounded statistics window with time-based retention.
     *
     * <p>The supplied clock is used only for stale-event eviction during
     * {@link #snapshot()} and {@link #successRate()}. Recorded event timestamps
     * remain caller supplied so deterministic runtimes and tests can use an
     * injected clock without relying on wall-clock time.</p>
     *
     * @param capacity maximum retained events
     * @param retentionDuration maximum age of retained events; non-positive
     *        values retain events for at least one millisecond
     * @param clock clock returning the current wall-clock timestamp in
     *        milliseconds
     */
    public SlidingWindowStatistics(final int capacity, final Duration retentionDuration, final LongSupplier clock) {
        this(capacity, retentionDuration, clock, DEFAULT_MAX_COUNTERS);
    }

    /**
     * Creates a bounded statistics window with time and counter-cardinality
     * limits.
     *
     * @param capacity maximum retained events
     * @param retentionDuration maximum age of retained events; non-positive
     *        values retain events for at least one millisecond
     * @param clock clock returning the current wall-clock timestamp in
     *        milliseconds
     * @param maxCounters maximum named counters retained by this collector
     */
    public SlidingWindowStatistics(
            final int capacity,
            final Duration retentionDuration,
            final LongSupplier clock,
            final int maxCounters) {
        this.capacity = Math.max(1, capacity);
        this.retentionMillis = retentionMillis(retentionDuration);
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.lock = new Object();
        this.counterLock = new Object();
        this.events = new ArrayDeque<>(this.capacity);
        this.counters = new ConcurrentHashMap<>();
        this.maxCounters = Math.max(1, maxCounters);
    }

    /**
     * Records one timed event.
     *
     * @param key event key, such as service or object id
     * @param timestampMillis event timestamp
     * @param latencyNanos latency in nanoseconds
     * @param success whether the event succeeded
     * @param errorCode optional error code
     */
    public void record(final String key, final long timestampMillis, final long latencyNanos, final boolean success, final MiddlewareErrorCode errorCode) {
        StatisticsEvent event = new StatisticsEvent(key, timestampMillis, Math.max(0L, latencyNanos), success, errorCode);
        synchronized (this.lock) {
            this.removeStale(timestampMillis);
            if (this.events.size() >= this.capacity) {
                this.removeFromWindow(this.events.removeFirst());
            }
            this.events.addLast(event);
            this.addToWindow(event);
        }
    }

    /**
     * Increments one named counter.
     *
     * @param name counter name
     */
    public void increment(final String name) {
        this.add(name, 1L);
    }

    /**
     * Adds a delta to one named counter.
     *
     * @param name counter name
     * @param delta counter delta
     */
    public void add(final String name, final long delta) {
        String safeName = name == null || name.isBlank() ? "unknown" : name;
        AtomicLong counter = this.counters.get(safeName);
        if (counter == null) {
            synchronized (this.counterLock) {
                counter = this.counters.get(safeName);
                if (counter == null) {
                    if (this.counters.size() >= this.maxCounters) {
                        return;
                    }
                    counter = new AtomicLong();
                    this.counters.put(safeName, counter);
                }
            }
        }
        counter.addAndGet(delta);
    }

    /**
     * Returns a point-in-time immutable snapshot.
     *
     * @return statistics snapshot
     */
    public StatisticsSnapshot snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : this.counters.entrySet()) {
            values.put(entry.getKey(), Long.valueOf(entry.getValue().get()));
        }
        long successes;
        long failures;
        long averageLatency;
        int retained;
        synchronized (this.lock) {
            this.removeStale(this.clock.getAsLong());
            successes = this.successCount;
            failures = this.failureCount;
            long total = successes + failures;
            averageLatency = total == 0L ? 0L : this.totalLatencyNanos / total;
            retained = this.events.size();
        }
        long total = successes + failures;
        return new StatisticsSnapshot(total, successes, failures, averageLatency, retained, values);
    }

    /**
     * Returns success rate for recent aggregate counters.
     *
     * @return success rate in {@code 0.0..1.0}
     */
    public double successRate() {
        synchronized (this.lock) {
            this.removeStale(this.clock.getAsLong());
            long total = this.successCount + this.failureCount;
            return total == 0L ? 1.0D : (double) this.successCount / (double) total;
        }
    }

    private void addToWindow(final StatisticsEvent event) {
        if (event.success()) {
            this.successCount++;
        } else {
            this.failureCount++;
        }
        this.totalLatencyNanos += event.latencyNanos();
    }

    private void removeFromWindow(final StatisticsEvent event) {
        if (event.success()) {
            this.successCount--;
        } else {
            this.failureCount--;
        }
        this.totalLatencyNanos -= event.latencyNanos();
    }

    private void removeStale(final long nowMillis) {
        long cutoff = nowMillis <= Long.MIN_VALUE + this.retentionMillis ? Long.MIN_VALUE : nowMillis - this.retentionMillis;
        Iterator<StatisticsEvent> iterator = this.events.iterator();
        while (iterator.hasNext()) {
            StatisticsEvent event = iterator.next();
            if (event.timestampMillis() < cutoff) {
                iterator.remove();
                this.removeFromWindow(event);
            }
        }
    }

    private static long retentionMillis(final Duration retentionDuration) {
        if (retentionDuration == null) {
            return Long.MAX_VALUE;
        }
        try {
            long millis = retentionDuration.toMillis();
            return millis <= 0L ? 1L : millis;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
