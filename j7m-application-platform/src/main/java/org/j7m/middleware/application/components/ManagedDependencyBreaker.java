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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.j7m.middleware.annotations.CircuitBreaker;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.*;

/**
 * Host-owned tenant dependency registry surviving target refresh. Only connect/OPEN failures count.
 * The breaker wraps the optional retry delegate, counting at most one failed logical call. OPEN
 * rejection never enters retry. HALF_OPEN uses bounded readonly probes, never business health calls.
 * Instances own no worker threads; each tenant must receive its own explicitly scoped registry.
 */
public final class ManagedDependencyBreaker implements ClientCallInterceptor, AutoCloseable {
    /**
     * Observable host state; these states do not claim whether a remote business effect completed.
     */
    public enum Phase { /**
     * Normal calls admitted.
     */ CLOSED, /**
     * Calls rejected until reset interval.
     */ OPEN,
        /**
         * Bounded readonly recovery probes admitted.
         */ HALF_OPEN }
    /**
     * Immutable host diagnostic snapshot, with no application arguments or principal data.
     * @param phase current circuit state
     * @param failures consecutive failed logical connection calls
     * @param activeProbes actual unfinished readonly probes, including older state generations
     */
    public record Status(Phase phase, int failures, int activeProbes) { }
    private final Map<String, State> states;
    private final ClientCallInterceptor delegate;
    private final LongSupplier clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an isolated tenant registry with one attempt per logical call.
     * @param tenantNamespace explicit trusted tenant namespace, never inferred from application arguments
     * @param profiles at most64 fixed dependency policy names
     */
    public ManagedDependencyBreaker(String tenantNamespace, Map<String, CircuitBreakerProfile> profiles) {
        this(tenantNamespace, profiles, ClientCallInterceptor.singleAttempt());
    }
    /**
     * Creates a breaker outside the given retry/dependency delegate.
     * @param tenantNamespace explicit trusted tenant scope; separate instances isolate tenants
     * @param profiles finite immutable host registry
     * @param delegate inner interceptor, normally ManagedClientRetry; never called while OPEN
     */
    public ManagedDependencyBreaker(String tenantNamespace, Map<String, CircuitBreakerProfile> profiles, ClientCallInterceptor delegate) {
        this(tenantNamespace, profiles, delegate, System::nanoTime);
    }
    ManagedDependencyBreaker(String namespace, Map<String, CircuitBreakerProfile> profiles, ClientCallInterceptor delegate, LongSupplier clock) {
        identifier(namespace); Objects.requireNonNull(profiles);
        if (profiles.size() > 64) throw new IllegalArgumentException("Breaker registry capacity");
        var built = new java.util.HashMap<String, State>();
        profiles.forEach((name, profile) -> { identifier(name); built.put(name, new State(Objects.requireNonNull(profile))); });
        this.states = Map.copyOf(built); this.delegate = Objects.requireNonNull(delegate); this.clock = Objects.requireNonNull(clock);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validate(Class<?> contract) {
        Objects.requireNonNull(contract);
        if (!contract.isInterface() || contract.getMethods().length > 4096) throw new IllegalArgumentException("Invalid circuit contract");
        delegate.validate(contract);
        for (var method : contract.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            var identity = new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                    java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
            policy(new ManagedServiceContract("breaker.validation", 1, identity), contract);
        }
    }
    private String policy(ManagedServiceContract contract, Class<?> type) {
        var annotation = ManagedPolicyMetadata.scalar(contract, type, type, CircuitBreaker.class);
        if (annotation == null) return null;
        String name = identifier(annotation.value());
        if (!states.containsKey(name)) throw new IllegalArgumentException("Unknown circuit breaker policy");
        return name;
    }
    /**
     * {@inheritDoc}
     */
    @Override public Object invoke(ClientCall call, ClientAttempt attempt) throws Throwable {
        requireOpen();
        String policy = policy(call.contract(), call.contractType());
        if (policy == null) return delegate.invoke(call, attempt);
        State state = states.get(policy);
        budget(call);
        long generation = state.admit(clock.getAsLong());
        if (generation >= 0) {
            boolean healthy = false;
            try {
                budget(call); healthy = attempt.probe();
            } finally { state.probeFinished(generation, healthy, clock.getAsLong()); }
            if (!healthy) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Dependency recovery probe unavailable");
        }
        budget(call);
        long callGeneration = state.generation();
        try {
            Object result = delegate.invoke(call, attempt);
            state.connectionSucceeded(callGeneration); return result;
        } catch (Throwable failure) {
            if (attempt.businessEntered()) state.connectionSucceeded(callGeneration);
            else if (outage(failure)) state.connectionFailed(callGeneration, clock.getAsLong());
            throw failure;
        }
    }
    private void budget(ClientCall call) {
        requireOpen();
        var parent = J7mContext.currentInvocation().control();
        if (parent != InvocationControl.unavailable() && parent.cancellationRequested()) {
            throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "Dependency caller cancelled");
        }
        if (call.deadlineNanos() - clock.getAsLong() <= 0 || parent != InvocationControl.unavailable() && parent.remainingBudget().isZero()) {
            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Dependency caller budget expired");
        }
    }
    private static boolean outage(Throwable failure) {
        return failure instanceof MiddlewareException error && (error.code() == MiddlewareErrorCode.UNAVAILABLE
                || error.code() == MiddlewareErrorCode.TRANSPORT_ERROR || error.code() == MiddlewareErrorCode.DEADLINE_EXCEEDED);
    }
    private void requireOpen() { if (closed.get()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Dependency registry closed"); }
    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid dependency identity");
        return value;
    }
    /**
     * Returns a bounded dependency diagnostic without creating state for unknown names.
     * @param policy registered name
     * @return immutable state
     */
    public Status status(String policy) {
        State state = states.get(policy);
        if (state == null) throw new IllegalArgumentException("Unknown circuit breaker policy");
        return state.status();
    }
    /**
     * Rejects new calls; existing readonly probes retain their accounting until actual exit.
     */
    @Override public void close() { closed.set(true); }

    private static final class State {
        private final CircuitBreakerProfile profile;
        private Phase phase = Phase.CLOSED;
        private long openedAt;
        private long epoch;
        private int failures;
        private int probes;
        State(CircuitBreakerProfile profile) { this.profile = profile; }
        synchronized long admit(long now) {
            if (phase == Phase.OPEN) {
                if (now - openedAt < profile.openInterval().toNanos()) throw unavailable();
                phase = Phase.HALF_OPEN; epoch++;
            }
            if (phase == Phase.CLOSED) return -1;
            if (probes >= profile.maximumHalfOpenProbes()) throw unavailable();
            probes++; return epoch;
        }
        synchronized void probeFinished(long generation, boolean healthy, long now) {
            probes--;
            if (generation != epoch || phase != Phase.HALF_OPEN) return;
            if (healthy) { phase = Phase.CLOSED; failures = 0; epoch++; }
            else open(now);
        }
        synchronized long generation() { return epoch; }
        synchronized void connectionSucceeded(long generation) { if (epoch == generation && phase == Phase.CLOSED) failures = 0; }
        synchronized void connectionFailed(long generation, long now) {
            if (epoch == generation && phase == Phase.CLOSED && ++failures >= profile.failureThreshold()) open(now);
        }
        private void open(long now) { phase = Phase.OPEN; openedAt = now; epoch++; }
        synchronized Status status() { return new Status(phase, failures, probes); }
        private static MiddlewareException unavailable() { return MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Dependency circuit open"); }
    }
}
