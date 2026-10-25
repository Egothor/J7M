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

package org.j7m.middleware.transport.api;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;

/**
 * Invocation handler used by generic transport providers.
 *
 * <p>The handler is immutable and thread-safe. It converts Java interface
 * method calls to {@link MethodDescriptor} invocations and lets the owning
 * provider perform transport dispatch.</p>
 */
public final class RemoteProxyInvocationHandler implements InvocationHandler {
    private static final int MAX_CALL_DEPTH = 32;
    private static final int MAX_NESTED_CALLS = 1024;
    private static final String DEADLINE_EPOCH_MILLIS = InvocationReliabilityOptions.PREFIX + "deadlineEpochMillis";
    private static final String CALL_DEPTH = InvocationReliabilityOptions.PREFIX + "callDepth";
    private static final String NESTED_CALL_COUNT = InvocationReliabilityOptions.PREFIX + "nestedCallCount";
    private static final String CORRELATION_ID = InvocationReliabilityOptions.PREFIX + "correlationId";
    private static final String CORRELATION_INVOCATION_ID = InvocationReliabilityOptions.PREFIX + "correlationInvocationId";
    private static final String CORRELATION_PARENT_INVOCATION_ID = InvocationReliabilityOptions.PREFIX + "correlationParentInvocationId";

    private final ObjectRef ref;
    private final Class<?> api;
    private final ProxyOptions options;
    private final RuntimeTransportContext context;
    private final TransportProvider provider;
    private final Long localDeadlineNanos;

    /**
     * Creates a proxy invocation handler.
     *
     * @param ref target reference
     * @param api required Java interface
     * @param options proxy options
     * @param context runtime transport context
     * @param provider owning provider
     */
    public RemoteProxyInvocationHandler(
            final ObjectRef ref,
            final Class<?> api,
            final ProxyOptions options,
            final RuntimeTransportContext context,
            final TransportProvider provider) {
        this.ref = ref;
        this.api = api;
        this.options = options == null ? ProxyOptions.defaults() : options;
        this.context = context;
        this.provider = provider;
        String localDeadline = this.options.metadata().get(ProxyOptions.LOCAL_DEADLINE_NANOS);
        try { this.localDeadlineNanos = localDeadline == null ? null : Long.valueOf(localDeadline); }
        catch (NumberFormatException invalid) { throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "invalid local deadline"); }
    }

    /**
     * Returns the represented object reference.
     *
     * @return object reference
     */
    public ObjectRef ref() {
        return this.ref;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return this.invokeObjectMethod(proxy, method, args);
        }
        long enteredNanos = System.nanoTime();
        Map<String, String> metadata = new LinkedHashMap<>(this.options.metadata());
        SecurityContext contextSecurity = SecurityContexts.current();
        if (contextSecurity != null) metadata.putAll(contextSecurity.toMetadata());
        var traceHeaders = org.j7m.middleware.application.api.InvocationTraceContext.current();
        if (traceHeaders.isEmpty()) traceHeaders = org.j7m.middleware.application.api.InvocationTraceContext.sanitized(metadata);
        metadata.remove("traceparent"); metadata.remove("tracestate");
        metadata.putAll(traceHeaders);
        metadata.remove(ProxyOptions.LOCAL_DEADLINE_NANOS);
        long parentEpoch = parseLong(metadata.get(DEADLINE_EPOCH_MILLIS));
        long nowMillis = System.currentTimeMillis();
        if (parentEpoch > 0 && parentEpoch <= nowMillis) {
            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Parent invocation expired before encoding");
        }
        Duration requested = this.options.invocationDeadline();
        long budget = requested.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1).toNanos() : requested.toNanos();
        if (parentEpoch > 0) budget = Math.min(budget, Duration.ofMillis(Math.min(86400000L, parentEpoch - nowMillis)).toNanos());
        var parentControl = org.j7m.middleware.application.api.J7mContext.currentInvocation().control();
        if (parentControl != org.j7m.middleware.application.api.InvocationControl.unavailable()) {
            Duration parentRemaining = parentControl.remainingBudget();
            if (parentRemaining.isZero() || parentRemaining.isNegative() || parentControl.cancellationRequested()) {
                throw MiddlewareException.of(parentControl.cancellationRequested() ? MiddlewareErrorCode.CANCELLED
                        : MiddlewareErrorCode.DEADLINE_EXCEEDED, "Parent invocation no longer admits work");
            }
            budget = Math.min(budget, parentRemaining.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1).toNanos() : parentRemaining.toNanos());
        }
        Long annotatedMillis = null;
        for (Method declaration : declarations(this.api, method)) {
            var deadline = declaration.getAnnotation(org.j7m.middleware.annotations.Deadline.class);
            if (deadline != null) {
                if (deadline.millis() <= 0 || annotatedMillis != null && annotatedMillis.longValue() != deadline.millis()) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Conflicting or invalid method deadline");
                }
                annotatedMillis = deadline.millis();
            }
        }
        if (annotatedMillis != null) budget = Math.min(budget, Duration.ofMillis(Math.min(86400000L, annotatedMillis)).toNanos());
        long callDeadline = enteredNanos + budget;
        if (this.localDeadlineNanos != null && this.localDeadlineNanos - enteredNanos < budget) callDeadline = this.localDeadlineNanos;
        if (budget <= 0 || callDeadline - enteredNanos <= 0) {
            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Invocation budget expired before encoding");
        }
        AsyncMethodDescriptor async = java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())
                ? AsyncMethodDescriptor.fromMethod(method) : null;
        MethodDescriptor descriptor = MethodDescriptor.fromMethod(method);
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] safeArgs = args == null ? new Object[0] : args;
        List<EncodedValue> encodedArguments = new ArrayList<>(safeArgs.length);
        try {
            for (int index = 0; index < safeArgs.length; index++) {
                encodedArguments.add(this.context.encodeValue(safeArgs[index], parameterTypes[index]));
            }
        } catch (RuntimeException exception) {
            this.context.releaseInvocationCallbacks(encodedArguments);
            throw exception;
        }
        InvocationOptions invocationOptions = this.invocationOptions(metadata, contextSecurity, callDeadline);
        if (declarations(this.api, method).stream().anyMatch(declaration -> hasAnnotation(declaration, "org.j7m.middleware.annotations.OneWay"))) {
            if (method.getReturnType() != Void.TYPE) {
                throw MiddlewareException.of(
                        MiddlewareErrorCode.INVALID_ARGUMENT,
                        "one-way remote methods must return void");
            }
            final long queuedDeadline = callDeadline;
            try {
                this.context.executeOneWay(() -> {
                    try {
                        SecurityContexts.call(contextSecurity, () -> {
                            long left = queuedDeadline - System.nanoTime();
                            if (left <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "One-way queue budget expired");
                            this.provider.invoke(this.ref, descriptor, encodedArguments,
                                    new InvocationOptions(invocationOptions.invocationId(),
                                            Duration.ofNanos(Math.min(left, invocationOptions.deadline().toNanos())), invocationOptions.metadata()));
                            return null;
                        });
                    } catch (RuntimeException exception) {
                        this.context.releaseInvocationCallbacks(encodedArguments);
                        throw exception;
                    }
                });
            } catch (RuntimeException exception) {
                this.context.releaseInvocationCallbacks(encodedArguments);
                throw exception;
            }
            return null;
        }
        if (async != null) {
            java.util.concurrent.CompletableFuture<Object> reply = this.provider.invokeAsync(this.ref, descriptor,
                    List.copyOf(encodedArguments), invocationOptions, async);
            reply.whenComplete((value, failure) -> {
                if (failure != null) this.context.releaseInvocationCallbacks(encodedArguments);
            });
            return reply;
        }
        try {
            return this.provider.invoke(this.ref, descriptor, encodedArguments, invocationOptions, method.getReturnType());
        } catch (RuntimeException exception) {
            this.context.releaseInvocationCallbacks(encodedArguments);
            throw exception;
        }
    }

    private static List<Method> declarations(Class<?> contract, Method method) {
        List<Method> result = new ArrayList<>();
        for (Method declared : contract.getDeclaredMethods()) {
            if (declared.getName().equals(method.getName()) && java.util.Arrays.equals(declared.getParameterTypes(), method.getParameterTypes())) {
                result.add(declared);
            }
        }
        for (Class<?> parent : contract.getInterfaces()) result.addAll(declarations(parent, method));
        return result;
    }

    private static boolean hasAnnotation(final Method method, final String annotationName) {
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().getName().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private Object invokeObjectMethod(final Object proxy, final Method method, final Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return this.api.getName() + " proxy -> " + this.ref;
        }
        if ("hashCode".equals(name)) {
            return this.ref.hashCode();
        }
        if ("equals".equals(name)) {
            Object other = args == null || args.length == 0 ? null : args[0];
            return ProxyReferenceSupport.objectRef(other).map(this.ref::equals).orElse(Boolean.FALSE);
        }
        throw new UnsupportedOperationException(name);
    }

    private InvocationOptions invocationOptions(final Map<String, String> metadata, final SecurityContext securityContext, final long callDeadline) {
        long nowMillis = System.currentTimeMillis();
        long deadlineMillis = parseLong(metadata.get(DEADLINE_EPOCH_MILLIS));
        if (deadlineMillis == 0L) {
            deadlineMillis = nowMillis + Math.max(1L, this.options.invocationDeadline().compareTo(Duration.ofDays(1)) > 0
                    ? 86400000L : this.options.invocationDeadline().toMillis());
            metadata.put(DEADLINE_EPOCH_MILLIS, Long.toString(deadlineMillis));
        }
        if (deadlineMillis <= nowMillis) {
            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "invocation deadline has already expired");
        }
        int depth = parseInt(metadata.get(CALL_DEPTH)) + 1;
        int nestedCalls = parseInt(metadata.get(NESTED_CALL_COUNT)) + 1;
        if (depth > MAX_CALL_DEPTH || nestedCalls > MAX_NESTED_CALLS) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "nested invocation limit exceeded");
        }
        metadata.put(CALL_DEPTH, Integer.toString(depth));
        metadata.put(NESTED_CALL_COUNT, Integer.toString(nestedCalls));
        if (securityContext != null) {
            if (!securityContext.correlationId().isBlank()) {
                metadata.putIfAbsent(CORRELATION_ID, securityContext.correlationId());
            }
            if (!securityContext.invocationId().isBlank()) {
                metadata.putIfAbsent(CORRELATION_PARENT_INVOCATION_ID, securityContext.invocationId());
            }
        }
        metadata.putIfAbsent(CORRELATION_ID, java.util.UUID.randomUUID().toString());
        metadata.put(CORRELATION_INVOCATION_ID, java.util.UUID.randomUUID().toString());
        Duration remaining = Duration.ofMillis(Math.min(86400000L, Math.max(1L, deadlineMillis - nowMillis)));
        long nanos = callDeadline - System.nanoTime();
        if (nanos <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Invocation budget expired while encoding");
        if (nanos < remaining.toNanos()) remaining = Duration.ofNanos(nanos);
        metadata.put(DEADLINE_EPOCH_MILLIS, Long.toString(nowMillis + Math.max(1L, remaining.toMillis())));
        return new InvocationOptions(null, remaining, metadata);
    }

    private static long parseLong(final String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static int parseInt(final String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
