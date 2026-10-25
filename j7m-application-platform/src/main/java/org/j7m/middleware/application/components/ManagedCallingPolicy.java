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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.j7m.middleware.annotations.Async;
import org.j7m.middleware.annotations.Cancellable;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.LongRunning;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.application.api.ClientCallInterceptor;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.ManagedServiceContract;

/**
 * Trusted validation of managed calling semantics. Actual completion ownership is installed by
 * the manager's transport hook; this policy does not create server retries or durable deduplication.
 * Client-visible operational metadata must be declared on the public interface, not hidden solely
 * on an implementation. Cancellation is cooperative and LongRunning never extends caller budget.
 */
public final class ManagedCallingPolicy implements ManagedInvocationPolicy {
    private final ClientCallInterceptor retry;

    /**
     * Creates calling support without any retry capability.
     * Active Retry metadata then fails validation, even when it requests zero additional attempts.
     */
    public ManagedCallingPolicy() { this(null); }

    /**
     * Installs actual host-owned client retry validation, borrowed for the host's lifetime.
     * The same interceptor must be installed in the intended client binding setup to perform retry;
     * server acceptance itself never causes replay.
     * @param retry trusted interceptor with its actual finite policy registry, or null to reject Retry
     */
    public ManagedCallingPolicy(final ClientCallInterceptor retry) { this.retry = retry; }

    /**
     * {@inheritDoc}
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() {
        return Set.of(Async.class, OneWay.class, Deadline.class, Cancellable.class, LongRunning.class,
                Idempotent.class, NonIdempotent.class, Retry.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override public void validate(final ManagedServiceContract contract, final Class<?> implementation,
            final Class<?> businessInterface) {
        final Method exposed = ManagedMethodSurface.method(contract, businessInterface);
        final Set<Class<? extends Annotation>> wireVisible = Set.of(Async.class, OneWay.class, Deadline.class,
                Idempotent.class, NonIdempotent.class, Retry.class);
        boolean async = false;
        boolean oneWay = false;
        boolean idempotent = false;
        boolean unsafe = false;
        final Set<Long> deadlines = new java.util.HashSet<>();
        for (AnnotatedElement declaration : ManagedMethodSurface.declarations(contract, implementation, businessInterface)) {
            async |= declaration.isAnnotationPresent(Async.class);
            oneWay |= declaration.isAnnotationPresent(OneWay.class);
            idempotent |= declaration.isAnnotationPresent(Idempotent.class);
            unsafe |= declaration.isAnnotationPresent(NonIdempotent.class);
            for (Class<? extends Annotation> annotation : wireVisible) {
                final Annotation value = declaration.getDeclaredAnnotation(annotation);
                if (value != null && !publicAnnotation(exposed, businessInterface, annotation, value)) {
                    throw new IllegalArgumentException("IMPLEMENTATION_ONLY_CALL_POLICY");
                }
            }
            final Deadline deadline = declaration.getDeclaredAnnotation(Deadline.class);
            if (deadline != null) { deadlines.add(deadline.millis()); }
            if (deadline != null && (deadline.millis() < 1 || deadline.millis() > 86400000L)) {
                throw new IllegalArgumentException("INVALID_MANAGED_DEADLINE");
            }
        }
        if (deadlines.size() > 1) { throw new IllegalArgumentException("CONFLICTING_MANAGED_DEADLINE"); }
        if (async && oneWay || idempotent && unsafe) { throw new IllegalArgumentException("CONFLICTING_CALL_POLICY"); }
        final Class<?> result = exposed.getReturnType();
        if (ManagedMethodSurface.isHosted(contract, businessInterface)
                && (result == CompletionStage.class || result == CompletableFuture.class)) async = true;
        if (async) {
            if ((result != CompletionStage.class && result != CompletableFuture.class)
                    || !(exposed.getGenericReturnType() instanceof ParameterizedType parameterized)
                    || parameterized.getActualTypeArguments().length != 1
                    || !(parameterized.getActualTypeArguments()[0] instanceof Class<?> payload)
                    || payload == void.class || CompletionStage.class.isAssignableFrom(payload)
                    || java.util.concurrent.Future.class.isAssignableFrom(payload)) {
                throw new IllegalArgumentException("INVALID_MANAGED_ASYNC_SIGNATURE");
            }
        } else if (CompletionStage.class.isAssignableFrom(result) || java.util.concurrent.Future.class.isAssignableFrom(result)) {
            throw new IllegalArgumentException("ASYNC_MARKER_REQUIRED");
        }
        if (oneWay && result != void.class) { throw new IllegalArgumentException("INVALID_MANAGED_ONEWAY_SIGNATURE"); }
        boolean retryPresent = false;
        for (AnnotatedElement declaration : ManagedMethodSurface.declarations(contract, implementation, businessInterface)) {
            retryPresent |= declaration.isAnnotationPresent(Retry.class);
        }
        if (retryPresent) {
            if (oneWay) { throw new IllegalArgumentException("ONEWAY_RETRY_CONFLICT"); }
            if (retry == null) { throw new IllegalArgumentException("UNKNOWN_RETRY_POLICY"); }
            retry.validate(businessInterface);
        }
    }

    private static boolean publicAnnotation(final Method exposed, final Class<?> contract,
            final Class<? extends Annotation> annotation, final Annotation value) {
        for (Class<?> type : ManagedMethodSurface.types(contract)) {
            if (value.equals(type.getDeclaredAnnotation(annotation))) { return true; }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(exposed.getName())
                        && java.util.Arrays.equals(method.getParameterTypes(), exposed.getParameterTypes())
                        && value.equals(method.getDeclaredAnnotation(annotation))) { return true; }
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override public Binding bind(final ManagedServiceContract contract, final Class<?> implementation,
            final Class<?> businessInterface) {
        validate(contract, implementation, businessInterface);
        return new Binding() {
            private volatile boolean closed;
            @Override public Object invoke(final InvocationContext context, final Object[] arguments, final Proceed next) throws Exception {
                if (closed || ManagedInvocationLifetime.current() == null) {
                    throw new IllegalStateException("MANAGED_INVOCATION_LIFETIME_REQUIRED");
                }
                return next.invoke();
            }
            @Override public void close() { closed = true; }
        };
    }
}
