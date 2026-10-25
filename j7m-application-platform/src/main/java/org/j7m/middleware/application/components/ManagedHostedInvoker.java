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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.transport.api.InvocationBoundary;
import org.j7m.middleware.transport.api.InvocationCompletionFactory;
import org.j7m.middleware.transport.api.InvocationCompletionScope;
import org.j7m.middleware.transport.api.MethodDescriptor;

/**
 * Trusted fixed-authority bridge from one finite hosted feature to its owned invocation boundary.
 * Callback values must already represent the validated policy chain and generated direct dispatch.
 * The borrowed completion factory owns admission, CALL/context, child tracking and physical cleanup.
 * Calls synchronously enter on the backend's bounded worker; no additional executor or timer exists.
 * This handle is retained only by a trusted backend, never injected into an application component.
 */
public final class ManagedHostedInvoker implements ManagedHostedResources.Invoker {
    /**
     * Host-prepared policy chain ending at one generated direct callback.
     */
    @FunctionalInterface
    public interface Callback {
        /**
         * Invokes one validated policy chain inside its owned CALL.
         * @param arguments independently decoded ordered application arguments
         * @return raw synchronous value or asynchronous application stage
         * @throws Exception if authorization, admission, application or policy code fails
         */
        Object invoke(Object[] arguments) throws Exception;
    }

    private final Map<ManagedMethod, Callback> callbacks;
    private final InvocationCompletionFactory completion;
    private final ManagedHostedResources.Entry profile;
    private final ObjectRef target;
    private final java.util.function.BooleanSupplier admission;

    /**
     * Binds a finite immutable callback allowlist to one host-owned logical boundary and authority.
     * @param callbacks exact generated methods with already validated policy chains,1..16 entries
     * @param completion actual host admission and physical ownership factory
     * @param profile fixed local worker identity and finite maximum budget
     * @param target unpublished host-owned target carrying internal hosted-boundary metadata
     * @throws NullPointerException for null input or callback
     * @throws IllegalArgumentException for an empty/excessive allowlist or noninternal target
     */
    public ManagedHostedInvoker(final Map<ManagedMethod, Callback> callbacks,
            final InvocationCompletionFactory completion, final ManagedHostedResources.Entry profile,
            final ObjectRef target) {
        this(callbacks, completion, profile, target, () -> true);
    }

    /**
     * Binds callbacks to the actual shared activation guard rather than standalone host readiness.
     * @param callbacks exact finite generated callback/policy plans
     * @param completion actual host admission and physical ownership factory
     * @param profile fixed local worker identity and finite budget
     * @param target unpublished host-owned logical target
     * @param admission shared host activation guard, observed by pure resource endpoints as well
     * @throws NullPointerException for null input
     * @throws IllegalArgumentException for invalid callback inventory or target
     */
    public ManagedHostedInvoker(final Map<ManagedMethod, Callback> callbacks,
            final InvocationCompletionFactory completion, final ManagedHostedResources.Entry profile,
            final ObjectRef target, final java.util.function.BooleanSupplier admission) {
        this.callbacks = Map.copyOf(callbacks);
        this.completion = Objects.requireNonNull(completion);
        this.profile = Objects.requireNonNull(profile);
        this.target = Objects.requireNonNull(target);
        this.admission = Objects.requireNonNull(admission);
        if (this.callbacks.isEmpty() || this.callbacks.size() > 16 || !target.transport().equals("hosted")
                || !"true".equals(target.metadata().get("j7m.hosted.internal"))) {
            throw new IllegalArgumentException("Invalid hosted invocation boundary");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override public boolean admissionOpen() { return admission.getAsBoolean(); }

    /**
     * {@inheritDoc}
     */
    @Override public CompletionStage<Object> invokeDecoded(final ManagedMethod method,
            final Callable<Object[]> decoder, final InvocationOptions options) {
        return invokeMeasured(method, decoder, options, -1);
    }

    /**
     * {@inheritDoc}
     */
    @Override public CompletionStage<Object> invokeDecoded(final ManagedMethod method,
            final Callable<Object[]> decoder, final InvocationOptions options, final int inboundBytes) {
        if (inboundBytes < 0) throw new IllegalArgumentException("Missing host input measurement");
        return invokeMeasured(method, decoder, options, inboundBytes);
    }

    private CompletionStage<Object> invokeMeasured(final ManagedMethod method, final Callable<Object[]> decoder,
            final InvocationOptions options, final int inboundBytes) {
        Callback callback = callbacks.get(method);
        Objects.requireNonNull(decoder); Objects.requireNonNull(options);
        if (callback == null || options.deadline().isNegative() || options.deadline().isZero()) {
            throw new IllegalArgumentException("Invalid hosted callback or budget");
        }
        Duration budget = options.deadline().compareTo(profile.maximumBudget()) > 0
                ? profile.maximumBudget() : options.deadline();
        var admitted = new InvocationOptions(options.invocationId(), budget, options.metadata());
        var identity = new MethodDescriptor(method.declaringType(), method.name(), method.parameterTypes(), method.returnType(), null);
        InvocationCompletionScope scope;
        try (var trace = org.j7m.middleware.application.api.InvocationTraceContext.open(admitted.metadata())) {
            scope = completion.open(new InvocationBoundary(target, identity, admitted, profile.worker(), inboundBytes));
        }
        if (!scope.supportsResultFinalizers()) {
            scope.abortBeforeTarget(); scope.close();
            throw new IllegalArgumentException("Hosted callbacks require actual owned finalization");
        }
        var reply = new CompletableFuture<Object>();
        var result = new AtomicReference<Object>();
        var error = new AtomicReference<Throwable>();
        scope.physicalCompletion().whenComplete((ignored, failure) -> {
            if (failure != null || error.get() != null) reply.completeExceptionally(safeFailure());
            else reply.complete(result.get());
        });
        scope.logicalResult().whenComplete((value, failure) -> {
            result.set(value); error.set(failure);
            scope.close();
        });
        try {
            scope.call(() -> callback.invoke(Objects.requireNonNull(decoder.call(), "decoded arguments")));
        } catch (Exception | Error failure) {
            // The mirror still owns finalization; no raw application failure escapes to a retrying runner.
            error.compareAndSet(null, safeFailure());
        }
        return reply.minimalCompletionStage();
    }

    private static MiddlewareException safeFailure() {
        return new MiddlewareException(new org.j7m.middleware.api.RemoteError(MiddlewareErrorCode.REMOTE_EXCEPTION,
                "Hosted invocation outcome is unavailable", Map.of("outcome", "UNKNOWN"), null, null, false, false));
    }
}
