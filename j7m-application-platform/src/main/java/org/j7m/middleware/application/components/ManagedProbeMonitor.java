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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Host-owned periodic observations for finite application activations.
 * Application callbacks execute only in the bounded probe coordinator. One timer waits at most
 * the configured inspection budget per registration; it never executes application callbacks.
 * Published readiness must be installed with a finite expiry by the receiving host, since a delayed
 * timer cannot prove continued health. Publication callbacks are trusted, nonblocking host code
 * and must reject stale activation generations themselves. Close is advisory for physical work.
 */
public final class ManagedProbeMonitor implements AutoCloseable {
    private final ManagedProbeCoordinator coordinator;
    private final ScheduledThreadPoolExecutor timer;
    private final Duration interval;
    private final Duration budget;
    private final Duration freshness;
    private final LongSupplier nanoTime;
    private final boolean automatic;
    private final Set<Registration> registrations = new HashSet<>();
    private boolean closed;

    /**
     * Creates an automatically sampled monitor with finite execution and registration capacity.
     * @param maximumConcurrent maximum physical probe callbacks,1..64
     * @param maximumRegistrations maximum activations,1..1024
     * @param interval positive sampling delay of at most one minute
     * @param budget positive per-activation wait budget of at most one minute
     * @param freshness positive observation validity of at most one minute
     * @throws IllegalArgumentException for invalid bounds
     * @throws NullPointerException for null durations
     */
    public ManagedProbeMonitor(int maximumConcurrent, int maximumRegistrations,
            Duration interval, Duration budget, Duration freshness) {
        this(maximumConcurrent, maximumRegistrations, interval, budget, freshness, System::nanoTime, true);
    }

    ManagedProbeMonitor(int maximumConcurrent, int maximumRegistrations, Duration interval,
            Duration budget, Duration freshness, LongSupplier nanoTime, boolean automatic) {
        this.interval = duration(interval); this.budget = duration(budget); this.freshness = duration(freshness);
        this.nanoTime = Objects.requireNonNull(nanoTime); this.automatic = automatic;
        coordinator = new ManagedProbeCoordinator(maximumConcurrent, maximumRegistrations);
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).name("j7m-probe-monitor").unstarted(task); thread.setContextClassLoader(ManagedProbeMonitor.class.getClassLoader()); return thread;
        });
        timer.setRemoveOnCancelPolicy(true); timer.setKeepAliveTime(1, TimeUnit.SECONDS); timer.allowCoreThreadTimeOut(true);
    }

    private static Duration duration(Duration value) {
        Objects.requireNonNull(value, "duration");
        if (value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("invalid probe monitor duration");
        return value;
    }

    /**
     * Registers activation callbacks and starts from UNKNOWN; no traffic should be admitted yet.
     * @param checks immutable finite callbacks, at most64, with unique identities
     * @param hasReadiness whether at least one actual READINESS probe is present
     * @param publish nonblocking trusted host callback receiving true only for fresh successful probes
     * @return owned registration, to be closed and discarded on drain or failed activation
     * @throws IllegalArgumentException for invalid checks
     * @throws IllegalStateException for closed monitor or exhausted capacity
     * @throws NullPointerException for null input
     */
    public synchronized Registration register(List<ManagedProbeCoordinator.Check> checks,
            boolean hasReadiness, Consumer<Boolean> publish) {
        Objects.requireNonNull(publish, "publish");
        if (closed) throw new IllegalStateException("probe monitor closed");
        Registration registration = new Registration(coordinator.register(checks), hasReadiness, publish, checks.size());
        registrations.add(registration);
        if (automatic) registration.scheduled = timer.scheduleWithFixedDelay(registration::sample, 0, interval.toNanos(), TimeUnit.NANOSECONDS);
        return registration;
    }

    /**
     * Returns the finite validity that the receiving export readiness gate must enforce.
     * @return configured observation validity
     */
    public Duration freshness() { return freshness; }

    /**
     * Stops sampling and detaches callbacks without waiting for noncooperative application work.
     */
    @Override public void close() {
        List<Registration> owned;
        synchronized (this) { if (closed) return; closed = true; owned = List.copyOf(registrations); registrations.clear(); }
        for (Registration registration : owned) registration.close();
        timer.shutdownNow(); coordinator.close();
    }

    /**
     * Owned observation stream. A closed or stale stream reports UNKNOWN.
     */
    public final class Registration implements AutoCloseable {
        private final ManagedProbeCoordinator.Registration probes;
        private final boolean hasReadiness;
        private final Consumer<Boolean> publish;
        private final int total;
        private ManagedProbeCoordinator.Snapshot last;
        private long observed;
        private boolean sampled;
        private boolean detached;
        private ScheduledFuture<?> scheduled;
        private Registration(ManagedProbeCoordinator.Registration probes, boolean hasReadiness, Consumer<Boolean> publish, int total) {
            this.probes = probes; this.hasReadiness = hasReadiness; this.publish = publish; this.total = total;
        }

        /**
         * Returns the last complete observation only while its monotonic validity remains fresh.
         * Missing readiness, errors, expired samples and closed streams return UNKNOWN.
         * @return safe cached observation, never invoking application code
         */
        public synchronized ManagedProbeCoordinator.Snapshot snapshot() {
            if (detached || !hasReadiness || !sampled || nanoTime.getAsLong() - observed >= freshness.toNanos()) return unknown();
            return last;
        }

        private ManagedProbeCoordinator.Snapshot unknown() {
            return new ManagedProbeCoordinator.Snapshot(ManagedProbeCoordinator.State.UNKNOWN, 0, total);
        }

        void sample() {
            synchronized (this) { if (detached) return; }
            ManagedProbeCoordinator.Snapshot result = probes.inspect(budget);
            synchronized (this) {
                if (detached) return;
                last = result; observed = nanoTime.getAsLong(); sampled = true;
            }
            try { publish.accept(hasReadiness && result.state() == ManagedProbeCoordinator.State.HEALTHY); }
            catch (RuntimeException failure) {
                synchronized (this) { last = unknown(); }
            }
        }

        /**
         * Stops future sampling and forgets this registration in the owner.
         * A publication already in progress can race close; its host generation guard must reject it.
         * Running probes retain actual application lifetime accounting until they return.
         */
        @Override public void close() {
            ScheduledFuture<?> task;
            synchronized (this) { if (detached) return; detached = true; last = unknown(); task = scheduled; }
            if (task != null) task.cancel(false);
            probes.close();
            synchronized (ManagedProbeMonitor.this) { registrations.remove(this); }
        }
    }
}
