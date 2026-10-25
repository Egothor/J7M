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

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.transport.api.*;
import org.j7m.middleware.security.api.*;

/**
 * Actual production lifetime and policy boundary reused by local and process qualification.
 */
final class TransactionInvocation {
    @FunctionalInterface interface Work { Object run(TransactionInvocation frame) throws Exception; }
    final ManagedInvocationLifetime lifetime;
    private TransactionInvocation(ManagedInvocationLifetime lifetime) { this.lifetime = lifetime; }
    static ManagedServiceContract contract(Class<?> business) {
        Method method = business.getMethods()[0];
        return new ManagedServiceContract("qualified-transactions", 1, new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName()));
    }
    ManagedInvocationPolicy.Proceed next(Callable<Object> work, boolean root) {
        return new ManagedInvocationPolicy.Proceed() {
            @Override public boolean supportsResultFinalizers() { return true; }
            @Override public void addResultFinalizer(InvocationResultFinalizer callback) { lifetime.addResultFinalizer(callback); }
            @Override public <T> void putContext(Class<T> type, T value) { lifetime.putContext(type, value); }
            @Override public <T> T context(Class<T> type) { return lifetime.context(type); }
            @Override public <T> Optional<T> findContext(Class<T> type) { return lifetime.findContext(type); }
            @Override public Object invoke() throws Exception { if (root) lifetime.markApplicationEntry(); return work.call(); }
        };
    }
    static Object invoke(ManagedInvocationPolicy.Binding binding, Work work) throws Exception {
        try (var container = ManagedContainer.activate(List.of())) { return invoke(container, binding, work); }
    }
    static Object invoke(ManagedContainer container, ManagedInvocationPolicy.Binding binding, Work work) throws Exception {
        return invoke(container, binding, work, new Object[]{"input"});
    }
    static Object invoke(ManagedContainer container, ManagedInvocationPolicy.Binding binding, Work work, Object[] arguments) throws Exception {
        return invoke(container, binding, work, arguments, InvocationContext.empty());
    }
    static Object invokeAs(InvocationContext identity, ManagedInvocationPolicy.Binding binding, Work work) throws Exception {
        try (var container = ManagedContainer.activate(List.of())) { return invoke(container, binding, work, new Object[]{"input"}, identity); }
    }
    static InvocationContext caller() { return new InvocationContext(new InvocationSecurityContext(new Principal("alice", "alice", Set.of(), Set.of()), Set.of(), new TrustDomain("transactions")), null, "test"); }
    static Object invokeWithin(Duration budget, ManagedInvocationPolicy.Binding binding, Work work) throws Exception {
        try (var container = ManagedContainer.activate(List.of())) { return invoke(container, binding, work, new Object[]{"input"}, InvocationContext.empty(), budget); }
    }
    private static Object invoke(ManagedContainer container, ManagedInvocationPolicy.Binding binding, Work work, Object[] arguments, InvocationContext identity) throws Exception {
        return invoke(container, binding, work, arguments, identity, Duration.ofSeconds(10));
    }
    private static Object invoke(ManagedContainer container, ManagedInvocationPolicy.Binding binding, Work work, Object[] arguments, InvocationContext identity, Duration budget) throws Exception {
        try (var actor = Executors.newSingleThreadExecutor()) {
            var lifetime = new ManagedInvocationLifetime(container.openCall(), identity, null, budget, actor, 16, 16, () -> { });
            var frame = new TransactionInvocation(lifetime);
            try {
                try { lifetime.call(() -> binding.invoke(J7mContext.currentInvocation(), arguments, frame.next(() -> work.run(frame), true))); }
                catch (Exception failure) { assertNotNull(failure); }
                return lifetime.logicalResult().toCompletableFuture().get(12, TimeUnit.SECONDS);
            } finally { lifetime.close(); lifetime.physicalCompletion().toCompletableFuture().get(12, TimeUnit.SECONDS); }
        }
    }
    static ManagedInvocationPolicy.Binding compose(ManagedInvocationPolicy.Binding outer, ManagedInvocationPolicy.Binding inner) {
        return new ManagedInvocationPolicy.Binding() {
            @Override public Object invoke(InvocationContext context, Object[] arguments, ManagedInvocationPolicy.Proceed next) throws Exception {
                return outer.invoke(context, arguments, new ManagedInvocationPolicy.Proceed() {
                    @Override public Object invoke() throws Exception { return inner.invoke(context, arguments, next); }
                    @Override public boolean supportsResultFinalizers() { return next.supportsResultFinalizers(); }
                    @Override public void addResultFinalizer(InvocationResultFinalizer finalizer) { next.addResultFinalizer(finalizer); }
                    @Override public <T> void putContext(Class<T> type, T value) { next.putContext(type, value); }
                    @Override public <T> T context(Class<T> type) { return next.context(type); }
                    @Override public <T> Optional<T> findContext(Class<T> type) { return next.findContext(type); }
                });
            }
            @Override public void close() { /* The fixture explicitly owns the two borrowed bindings. */ }
        };
    }

}
