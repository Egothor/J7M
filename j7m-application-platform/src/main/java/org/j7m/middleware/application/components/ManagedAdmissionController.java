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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;

/**
 * Host-local bounded admission with non-preemptive priority and ageing.
 *
 * <p>No worker threads are created. Waiting callers consume at most the configured queue capacity
 * and observe a monotonic time budget or interruption. Closing rejects queued/new callers but
 * does not cancel running work or release its permits. The owner must close each permit only when
 * physical work completes, including exceptional asynchronous completion. This is a JVM-local
 * budget, not a distributed semaphore or CPU scheduler. Instances are thread-safe.
 */
public final class ManagedAdmissionController implements AutoCloseable {
    private final int capacity;
    private final int maximumQueued;
    private final long ageingNanos;
    private final LongSupplier clock;
    private final List<Waiter> queued = new ArrayList<>();
    private int active;
    private long sequence;
    private long admitted;
    private long rejected;
    private boolean closed;

    private record Waiter(int priority, long sequence, long enqueued) { }

    /**
     * Creates a shared host budget.
     * @param capacity simultaneous active permits, 1 through 1000000
     * @param maximumQueued maximum waiting callers, 0 through 1024
     * @param ageingStep positive interval after which a waiting caller gains one relative priority unit
     * @throws IllegalArgumentException if bounds or the ageing interval are invalid
     * @throws NullPointerException if ageingStep is null
     */
    public ManagedAdmissionController(final int capacity, final int maximumQueued, final Duration ageingStep) {
        this(capacity, maximumQueued, ageingStep, System::nanoTime);
    }

    ManagedAdmissionController(final int capacity, final int maximumQueued, final Duration ageingStep, final LongSupplier clock) {
        Objects.requireNonNull(ageingStep);
        if (capacity < 1 || capacity > 1_000_000 || maximumQueued < 0 || maximumQueued > 1024
                || ageingStep.isNegative() || ageingStep.isZero() || ageingStep.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid managed admission profile");
        }
        this.capacity = capacity;
        this.maximumQueued = maximumQueued;
        this.ageingNanos = ageingStep.toNanos();
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Acquires one permit, preferring higher relative priority with FIFO ties and ageing.
     * New callers never bypass the queue through the immediate fast path. Once queued, a higher
     * priority may overtake lower priorities. Ageing improves fairness within the waiting budget;
     * it does not guarantee admission under sustained overload. A zero budget permits immediate acquisition
     * only; a saturated queue rejects immediately. Interruption rejects admission without clearing
     * the thread's interrupt status. An acquired permit remains active until explicitly closed.
     * @param priority relative priority, -1024 through 1024
     * @param timeout maximum monotonic waiting time, zero through one minute
     * @return owned idempotent permit
     * @throws IllegalArgumentException for an invalid priority or timeout
     * @throws MiddlewareException OVERLOADED for capacity rejection, DEADLINE_EXCEEDED for elapsed
     *         waiting time, CANCELLED for interruption, or UNAVAILABLE when closed
     */
    public synchronized Permit acquire(final int priority, final Duration timeout) {
        if (priority < -1024 || priority > 1024 || timeout == null || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Invalid managed admission request");
        }
        requireOpen();
        if (Thread.currentThread().isInterrupted()) {
            this.rejected++;
            throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "Managed admission interrupted");
        }
        if (this.active < this.capacity && this.queued.isEmpty()) {
            return granted();
        }
        if (timeout.isZero() || this.queued.size() >= this.maximumQueued) {
            this.rejected++;
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "Managed admission capacity exceeded");
        }
        final long start = this.clock.getAsLong();
        final long budget = timeout.toNanos();
        final Waiter waiter = new Waiter(priority, ++this.sequence, start);
        this.queued.add(waiter);
        notifyAll();
        try {
            while (true) {
                requireOpen();
                final long now = this.clock.getAsLong();
                final long elapsed = now - start;
                if (elapsed >= budget) {
                    this.rejected++;
                    throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Managed admission deadline exceeded");
                }
                if (this.active < this.capacity && first(now) == waiter) {
                    this.queued.remove(waiter);
                    notifyAll();
                    return granted();
                }
                final long remaining = budget - Math.max(0L, elapsed);
                try {
                    wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    this.rejected++;
                    throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "Managed admission interrupted");
                }
            }
        } finally {
            this.queued.remove(waiter);
            notifyAll();
        }
    }

    /**
     * Returns aggregate counters without request/argument information.
     * @return atomic host-local admission state
     */
    public synchronized Status status() {
        return new Status(this.active, this.queued.size(), this.admitted, this.rejected, this.closed);
    }

    /**
     * Rejects queued and future admissions; running permits retain their accounting until closed.
     */
    @Override
    public synchronized void close() {
        this.closed = true;
        notifyAll();
    }

    /**
     * Atomic aggregate host-local counters.
     * @param active physically outstanding permits
     * @param queued waiting callers
     * @param admitted total granted permits
     * @param rejected rejected/timed-out/interrupted requests
     * @param closed whether new admission is disabled
     */
    public record Status(int active, int queued, long admitted, long rejected, boolean closed) { }

    /**
     * Idempotent permit which may be released by another completion thread.
     */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private Permit() { }

        /**
         * Releases exactly one active slot; cancellation of a waiter does not call this implicitly.
         */
        @Override
        public void close() {
            if (this.released.compareAndSet(false, true)) {
                synchronized (ManagedAdmissionController.this) {
                    active--;
                    ManagedAdmissionController.this.notifyAll();
                }
            }
        }
    }

    private Permit granted() {
        this.active++;
        this.admitted++;
        return new Permit();
    }

    private Waiter first(final long now) {
        return this.queued.stream().min(Comparator.<Waiter>comparingLong(waiter -> -rank(waiter, now))
                .thenComparingLong(Waiter::sequence)).orElseThrow();
    }

    private long rank(final Waiter waiter, final long now) {
        final long age = Math.min(Long.MAX_VALUE - 1024, Math.max(0L, now - waiter.enqueued()) / this.ageingNanos);
        return waiter.priority() + age;
    }

    private void requireOpen() {
        if (this.closed) {
            this.rejected++;
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Managed admission is closed");
        }
    }
}
