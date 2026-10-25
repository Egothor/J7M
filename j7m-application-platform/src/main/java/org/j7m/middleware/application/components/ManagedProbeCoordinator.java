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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-owned bounded execution of application probes, with no queued work.
 * A timeout never cancels or completes physical application work. Concurrent inspections share
 * an already running check; blocked checks retain their worker until they actually return.
 * Callbacks must be side-effect-free and must account for their own application lifetime.
 * This coordinator owns its daemon workers and registrations, and is thread-safe.
 */
public final class ManagedProbeCoordinator implements AutoCloseable {
    /**
     * Aggregate result; UNKNOWN must not admit traffic.
     */
    public enum State {
        /**
         * All probes completed successfully and returned true.
         */ HEALTHY,
        /**
         * All probes completed and at least one returned false.
         */ UNHEALTHY,
        /**
         * Some work failed, was rejected, or did not finish within the inspection budget.
         */ UNKNOWN
    }
    /**
     * Immutable bounded observation without application exception messages.
     * @param state aggregate result
     * @param completed checks whose boolean result was observed
     * @param total number of registered checks
     */
    public record Snapshot(State state, int completed, int total) { }
    /**
     * Borrowed application callback; identity is local to its registration.
     * @param id nonblank diagnostic identity of at most256 characters
     * @param callback side-effect-free callback, returning a nonnull boolean
     */
    public record Check(String id, Callable<Boolean> callback) {
        /**
         * Validates callback metadata without invoking application code.
         * @param id bounded identity
         * @param callback borrowed callback
         * @throws IllegalArgumentException for invalid identity
         * @throws NullPointerException for a null argument
         */
        public Check {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(callback, "callback");
            if (id.isBlank() || id.length() > 256) throw new IllegalArgumentException("invalid probe identity");
        }
    }
    private final ThreadPoolExecutor workers;
    private final int maximumRegistrations;
    private final Set<Registration> registrations = new HashSet<>();
    private boolean closed;

    /**
     * Creates a finite worker pool with no waiting task queue.
     * @param maximumConcurrent maximum physically running probes,1..64
     * @param maximumRegistrations maximum live application registrations,1..1024
     * @throws IllegalArgumentException for an out-of-range bound
     */
    public ManagedProbeCoordinator(int maximumConcurrent, int maximumRegistrations) {
        if (maximumConcurrent < 1 || maximumConcurrent > 64 || maximumRegistrations < 1 || maximumRegistrations > 1024)
            throw new IllegalArgumentException("invalid probe capacity");
        this.maximumRegistrations = maximumRegistrations;
        AtomicInteger number = new AtomicInteger();
        workers = new ThreadPoolExecutor(maximumConcurrent, maximumConcurrent, 1, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).name("j7m-probe-" + number.incrementAndGet()).unstarted(task); thread.setContextClassLoader(ManagedProbeCoordinator.class.getClassLoader()); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    /**
     * Registers a finite activation's immutable callbacks without executing them.
     * @param checks zero to64 uniquely named callbacks
     * @return owned registration; close it before releasing the application's class loader
     * @throws IllegalArgumentException for duplicate identities or excessive checks
     * @throws IllegalStateException if closed or registration capacity is exhausted
     * @throws NullPointerException for null input
     */
    public synchronized Registration register(List<Check> checks) {
        List<Check> snapshot = List.copyOf(checks);
        if (snapshot.size() > 64) throw new IllegalArgumentException("too many probes");
        Set<String> ids = new HashSet<>();
        for (Check check : snapshot) if (!ids.add(check.id())) throw new IllegalArgumentException("duplicate probe identity");
        if (closed || registrations.size() >= maximumRegistrations) throw new IllegalStateException("probe coordinator unavailable");
        Registration registration = new Registration(snapshot); registrations.add(registration); return registration;
    }

    /**
     * Stops admission, detaches registrations, and requests interruption of running probes.
     * Interruption is advisory: callbacks that ignore it still retain their physical workers and
     * application lifetime accounting until return. This method does not wait for callbacks.
     */
    @Override public void close() {
        List<Registration> owned;
        synchronized (this) {
            if (closed) return; closed = true; owned = List.copyOf(registrations); registrations.clear();
        }
        for (Registration registration : owned) registration.close();
        workers.shutdownNow();
    }

    /**
     * Owned activation-specific group. Closing prevents new probes without claiming that running
     * callbacks have completed. A closed registration always reports UNKNOWN, including an empty one.
     */
    public final class Registration implements AutoCloseable {
        private final List<Slot> slots;
        private boolean detached;
        private Registration(List<Check> checks) { slots = checks.stream().map(Slot::new).toList(); }

        /**
         * Inspects all callbacks using one shared monotonic wait budget.
         * Saturated workers and callback failures produce UNKNOWN. Concurrent calls coalesce
         * unfinished callbacks; a subsequent inspection refreshes completed callbacks.
         * @param budget positive duration of at most one minute
         * @return safe aggregate; no callback arguments, results other than booleans, or errors
         * @throws IllegalArgumentException for an invalid budget
         * @throws NullPointerException for null budget
         */
        public Snapshot inspect(Duration budget) {
            Objects.requireNonNull(budget, "budget");
            if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0)
                throw new IllegalArgumentException("invalid probe budget");
            long started = System.nanoTime(), nanos = budget.toNanos();
            List<CompletableFuture<Boolean>> pending = new ArrayList<>();
            synchronized (this) {
                if (detached) return new Snapshot(State.UNKNOWN, 0, slots.size());
                for (Slot slot : slots) pending.add(slot.submit());
            }
            int completed = 0; boolean healthy = true, unknown = false;
            for (CompletableFuture<Boolean> result : pending) {
                try {
                    long remaining = nanos - (System.nanoTime() - started);
                    Boolean value = result.isDone() ? result.get() : result.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                    if (value == null) unknown = true;
                    else { completed++; healthy &= value; }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); unknown = true;
                } catch (ExecutionException | TimeoutException failure) { unknown = true; }
            }
            synchronized (this) { if (detached) unknown = true; }
            return new Snapshot(unknown ? State.UNKNOWN : healthy ? State.HEALTHY : State.UNHEALTHY, completed, slots.size());
        }

        /**
         * Detaches this activation; running callbacks are never reported as cancelled or complete.
         * New registrations cannot inherit the old callbacks or results.
         */
        @Override public void close() {
            synchronized (this) { if (detached) return; detached = true; }
            synchronized (ManagedProbeCoordinator.this) { registrations.remove(this); }
        }
    }

    private final class Slot {
        private final Check check;
        private CompletableFuture<Boolean> current;
        private Slot(Check check) { this.check = check; }
        private CompletableFuture<Boolean> submit() {
            if (current != null && !current.isDone()) return current;
            CompletableFuture<Boolean> result = new CompletableFuture<>(); current = result;
            try {
                workers.execute(() -> {
                    try { result.complete(check.callback().call()); }
                    catch (Exception failure) { result.completeExceptionally(new IllegalStateException("probe failed")); }
                    catch (Error failure) { result.completeExceptionally(new IllegalStateException("probe failed")); throw failure; }
                });
            } catch (RejectedExecutionException rejected) { result.complete(null); }
            return result;
        }
    }
}
