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

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;

/**
 * Host-installed finite retry for failures of the binding's read-only connector before business
 * entry. Once the private binding attempt reports business entry, every outcome is returned without
 * replay, including errors claiming NOT_STARTED. This is not a durable deduplication protocol.
 * Instances are immutable and thread-safe; waiting remains within the original local deadline.
 */
public final class ManagedClientRetry implements ClientCallInterceptor {
    private final Map<String, ClientRetryProfile> profiles;
    private final LongSupplier nanoTime;
    private final DoubleSupplier random;
    private final LongConsumer pause;

    /**
     * Registers finite retry policies explicitly; annotation presence grants no capability.
     * @param profiles at most256 bounded names mapped to immutable host profiles
     * @throws IllegalArgumentException for an invalid name, capacity or null profile
     * @throws NullPointerException if profiles is null
     */
    public ManagedClientRetry(Map<String, ClientRetryProfile> profiles) {
        this(profiles, System::nanoTime, () -> ThreadLocalRandom.current().nextDouble(), LockSupport::parkNanos);
    }

    ManagedClientRetry(Map<String, ClientRetryProfile> profiles, LongSupplier nanoTime, DoubleSupplier random, LongConsumer pause) {
        Objects.requireNonNull(profiles);
        if (profiles.size() > 256) throw new IllegalArgumentException("Retry policy capacity");
        for (var entry : profiles.entrySet()) if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_.-]{1,128}") || entry.getValue() == null) throw new IllegalArgumentException("Invalid retry policy");
        this.profiles = Map.copyOf(profiles); this.nanoTime = Objects.requireNonNull(nanoTime); this.random = Objects.requireNonNull(random); this.pause = Objects.requireNonNull(pause);
    }

    /**
     * {@inheritDoc}
     */
    @Override public void validate(Class<?> contract) {
        if (contract == null || !contract.isInterface() || contract.getMethods().length > 4096) throw new IllegalArgumentException("Invalid retry contract");
        for (Method method : contract.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                if (method.isAnnotationPresent(Retry.class) || method.isAnnotationPresent(Idempotent.class) || method.isAnnotationPresent(NonIdempotent.class)) throw new IllegalArgumentException("Static retry metadata is unsupported");
                continue;
            }
            var identity = new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                    Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
            rules(new ManagedServiceContract("client.validation", 1, identity), contract);
        }
    }

    private Rules rules(ManagedServiceContract contract, Class<?> type) {
        Set<Retry> retries = new HashSet<>();
        boolean idempotent = false, unsafe = false, oneWay = false;
        for (var declaration : ManagedMethodSurface.declarations(contract, type, type)) {
            Retry retry = declaration.getDeclaredAnnotation(Retry.class);
            if (retry != null) retries.add(retry);
            idempotent |= declaration.isAnnotationPresent(Idempotent.class);
            unsafe |= declaration.isAnnotationPresent(NonIdempotent.class);
            oneWay |= declaration.isAnnotationPresent(OneWay.class);
        }
        if (retries.size() > 1) throw new IllegalArgumentException("Conflicting retry declarations");
        Retry retry = retries.isEmpty() ? null : retries.iterator().next();
        if (idempotent && unsafe) throw new IllegalArgumentException("Conflicting idempotence contract");
        if (retry == null) return new Rules(0, null);
        if (!retry.policy().matches("[A-Za-z0-9_.-]{1,128}") || retry.maxAttempts() < 0 || retry.maxAttempts() > 16) throw new IllegalArgumentException("Invalid retry metadata");
        ClientRetryProfile profile = profiles.get(retry.policy());
        if (profile == null) throw new IllegalArgumentException("Unknown retry policy");
        if (oneWay || retry.maxAttempts() > 0 && (!idempotent || unsafe)) throw new IllegalArgumentException("Unsafe retry contract");
        return new Rules(Math.min(retry.maxAttempts(), profile.maximumAdditionalAttempts()), profile);
    }

    /**
     * {@inheritDoc}
     */
    @Override public Object invoke(ClientCall call, ClientAttempt attempt) throws Throwable {
        Objects.requireNonNull(call); Objects.requireNonNull(attempt); Rules rules = rules(call.contract(), call.contractType());
        int retries = 0;
        while (true) {
            remaining(call.deadlineNanos());
            try { return attempt.execute(); }
            catch (Throwable failure) {
                if (attempt.businessEntered() || retries >= rules.additional() || !transientConnectionFailure(failure)) throw failure;
                waitBackoff(call.deadlineNanos(), backoff(rules.profile(), retries++));
            }
        }
    }

    private static boolean transientConnectionFailure(Throwable failure) {
        return failure instanceof MiddlewareException error && switch (error.code()) {
            case UNAVAILABLE, TRANSPORT_ERROR, DEADLINE_EXCEEDED, OVERLOADED -> true;
            default -> false;
        };
    }

    private long backoff(ClientRetryProfile profile, int retry) {
        long cap = profile.maximumBackoff().toNanos(), value = profile.initialBackoff().toNanos();
        for (int index = 0; index < retry; index++) value = Math.min(cap, value * 2);
        double jitter = profile.jitterFraction();
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample > 1) throw new IllegalStateException("Invalid retry entropy");
        return Math.min(cap, (long) (value * (1 - jitter + 2 * jitter * sample)));
    }

    private void waitBackoff(long deadline, long nanos) {
        long until = nanoTime.getAsLong() + nanos;
        while (true) {
            long budget = remaining(deadline), wait = until - nanoTime.getAsLong();
            if (wait <= 0) return;
            pause.accept(Math.min(wait, budget));
        }
    }

    private long remaining(long deadline) {
        if (Thread.currentThread().isInterrupted() || J7mContext.currentInvocation().control().cancellationRequested()) throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "Client retry cancellation requested");
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Client retry deadline expired");
        return remaining;
    }

    private record Rules(int additional, ClientRetryProfile profile) { }
}
