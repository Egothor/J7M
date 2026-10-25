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

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;

/**
 * Bounded production discovery adapter over MiddlewareRuntime.list. It does not reinterpret
 * DiscoveryEvent diagnostics as a watch feed. Each subscription publishes full monotonically
 * revised snapshots; failures publish a non-authoritative observation. Runtime ownership remains
 * with the host. At most256 subscriptions share two daemon polling workers and finite timer slots.
 */
public final class PolledBindingCandidateSource implements BindingCandidateSource, AutoCloseable {
    private final MiddlewareRuntime runtime;
    private final Clock clock;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, task -> {
        var thread = Thread.ofPlatform().name("j7m-binding-discovery").daemon(true).inheritInheritableThreadLocals(false).unstarted(task);
        thread.setContextClassLoader(PolledBindingCandidateSource.class.getClassLoader());
        return thread;
    });
    private final Set<Subscription> subscriptions = new HashSet<>();
    private final AtomicLong polls = new AtomicLong(), failures = new AtomicLong();
    private boolean closed;
    /**
     * Aggregate source state, without references, caller identity or payloads.
     * @param subscriptions currently registered watchers
     * @param polls completed poll attempts
     * @param failures failed/non-authoritative poll attempts
     * @param closed whether the source owner has closed
     */
    public record Status(int subscriptions, long polls, long failures, boolean closed) { }
    /**
     * Creates a polling owner with a systemUTC expiration clock.
     * @param runtime borrowed registry-capable runtime
     */
    public PolledBindingCandidateSource(MiddlewareRuntime runtime) { this(runtime, Clock.systemUTC()); }
    /**
     * Creates an owner with an explicit expiration clock.
     * @param runtime borrowed registry-capable runtime
     * @param clock trusted expiration clock
     */
    public PolledBindingCandidateSource(MiddlewareRuntime runtime, Clock clock) { this.runtime = Objects.requireNonNull(runtime); this.clock = Objects.requireNonNull(clock); }
    /**
     * {@inheritDoc}
     */
    @Override public AutoCloseable watch(ServiceBindingRequest<?> request, BindingPolicy policy, Consumer<BindingCandidates> observations) {
        var subscription = new Subscription(Objects.requireNonNull(request), Objects.requireNonNull(policy), Objects.requireNonNull(observations));
        synchronized (this) {
            if (closed) throw new IllegalStateException("DISCOVERY_SOURCE_CLOSED");
            if (subscriptions.size() >= 256) throw new IllegalStateException("DISCOVERY_WATCH_CAPACITY");
            subscriptions.add(subscription);
        }
        subscription.poll();
        synchronized (this) {
            if (!closed && !subscription.closed) subscription.timer = executor.scheduleWithFixedDelay(subscription::poll,
                    policy.pollInterval().toNanos(), policy.pollInterval().toNanos(), TimeUnit.NANOSECONDS);
        }
        return subscription;
    }
    /**
     * Returns bounded aggregate discovery metrics.
     * @return immutable source counters
     */
    public synchronized Status status() { return new Status(subscriptions.size(), polls.get(), failures.get(), closed); }
    /**
     * Revokes watchers and cancels polling without closing the borrowed runtime.
     * In-flight bounded registry operations may finish; revoked bindings ignore their observations.
     */
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (var subscription : List.copyOf(subscriptions)) subscription.close();
        executor.shutdownNow();
    }
    private final class Subscription implements AutoCloseable {
        private final ServiceBindingRequest<?> request;
        private final BindingPolicy policy;
        private final Consumer<BindingCandidates> consumer;
        private volatile boolean closed;
        private ScheduledFuture<?> timer;
        private long revision;
        Subscription(ServiceBindingRequest<?> request, BindingPolicy policy, Consumer<BindingCandidates> consumer) {
            this.request = request; this.policy = policy; this.consumer = consumer;
        }
        void poll() {
            if (closed) return;
            List<ObjectRef> candidates; boolean authoritative = true;
            try {
                Map<String, String> required = Map.of("j7m.contract.id", request.contractId(), "j7m.contract.version", Integer.toString(request.contractVersion()),
                        "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(request.qualifiers()));
                candidates = runtime.list(request.serviceName(), List.of(request.type().getName()), required,
                        new LookupOptions(request.registryContext(), required, policy.discoveryTimeout(), false, false));
                if (candidates.size() > policy.maxCandidates()) throw new IllegalStateException("DISCOVERY_CANDIDATE_CAPACITY");
                long now = clock.millis(); candidates = candidates.stream().filter(ref -> ref.isLeaseActive(now)).toList();
            } catch (RuntimeException error) { failures.incrementAndGet(); candidates = List.of(); authoritative = false; }
            polls.incrementAndGet(); if (closed) return;
            try { consumer.accept(new BindingCandidates(++revision, candidates, authoritative)); }
            catch (RuntimeException error) { failures.incrementAndGet(); }
        }
        @Override public void close() {
            synchronized (PolledBindingCandidateSource.this) {
                if (closed) return; closed = true; if (timer != null) timer.cancel(true); subscriptions.remove(this);
            }
        }
    }
}
