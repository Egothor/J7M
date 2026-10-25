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

package org.j7m.middleware.application.distributed;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.Observed;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.distributed.TraceCarrier;
import org.j7m.middleware.integrations.Observability;

/**
 * Optional D15 adapter for finite host-named application spans. It creates no additional RPC span.
 * Observers are borrowed host resources; bindings never close their exporter. Span completion follows
 * settled user work and inner transaction/durable finalizers, before transport encoding.
 */
public final class ManagedObservedPolicy implements ManagedInvocationPolicy {
    private final Map<String, Observability> observers;
    /**
     * Installs explicit bounded observer profiles.
     * @param observers at most64 names mapped to existing host-owned concrete D15 observers
     * @throws IllegalArgumentException for invalid names or capacity
     */
    public ManagedObservedPolicy(Map<String, Observability> observers) {
        this.observers = Map.copyOf(observers);
        if (this.observers.size() > 64 || this.observers.keySet().stream().anyMatch(name -> !name.matches("[A-Za-z0-9_.-]{1,128}"))) {
            throw new IllegalArgumentException("Invalid observation profiles");
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public Phase phase() { return Phase.OBSERVATION; }
    /**
     * {@inheritDoc}
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(Observed.class); }
    private Observability observer(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        var annotation = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, Observed.class);
        if (annotation == null) return null;
        var observer = observers.get(annotation.value());
        if (observer == null) throw new IllegalArgumentException("Unknown application observation profile");
        return observer;
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        observer(contract, implementation, businessInterface);
    }
    /**
     * {@inheritDoc}
     */
    @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        var observer = observer(contract, implementation, businessInterface);
        return new Binding() {
            private final AtomicBoolean detached = new AtomicBoolean();
            @Override public Object invoke(InvocationContext context, Object[] arguments, Proceed next) throws Exception {
                if (detached.get()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Observation binding closed");
                if (observer == null) return next.invoke();
                if (!next.supportsResultFinalizers()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Observed requires trusted result settlement");
                var span = observer.startDetached(Observability.Operation.APPLICATION_HANDLER,
                        TraceCarrier.fromMap(InvocationTraceContext.current()));
                boolean registered = false;
                try (var attachment = span.attach()) {
                    next.addResultFinalizer(result -> {
                        if (result.failure() != null) span.failed();
                        span.end(); return CompletableFuture.completedFuture(result);
                    });
                    registered = true;
                    return next.invoke();
                } catch (Exception | Error failure) {
                    // The registered host finalizer ends an entered operation after its actual user work.
                    span.failed(); if (!registered) span.end(); throw failure;
                }
            }
            @Override public void close() { detached.set(true); }
        };
    }
}
