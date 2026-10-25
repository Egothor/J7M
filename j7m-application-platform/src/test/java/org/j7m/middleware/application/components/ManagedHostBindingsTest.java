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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedDependency;
import org.j7m.middleware.application.api.ManagedProvider;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.application.api.ManagedResolver;
import org.j7m.middleware.security.api.Principal;
import org.junit.jupiter.api.Test;

/**
 * Host capability admission and ownership are tested separately from application hosting.
 */
class ManagedHostBindingsTest {
    private record Target(Object value) { }

    @Test
    void allMetadataIsValidatedBeforeBindingOrConstructingAndNoCapabilityIsImplicit() {
        var binds = new AtomicInteger();
        var creates = new AtomicInteger();
        var closes = new AtomicInteger();
        ManagedDependency edge = edge(String.class, false, "service.name", "first");
        var registry = new ManagedHostBindings(Map.of("service.name", factory(binds, closes, false)));
        assertThrows(ManagedContainer.Failure.class, () -> ManagedContainer.activate(List.of(index(edge, creates, Scope.Kind.ACTIVATION)), 10, null, ManagedHostBindings.empty()));
        assertEquals(0, creates.get());
        try (var container = ManagedContainer.activate(List.of(index(edge, creates, Scope.Kind.ACTIVATION)), 10, null, registry)) {
            assertEquals("prepared", container.get(Target.class, "", List.of()).value());
            assertEquals(1, creates.get());
            assertEquals(1, binds.get());
        }
        assertEquals(1, closes.get());
    }

    @Test
    void preparationFailureClosesAlreadyPreparedBindingsOnceAndRedactsHostErrors() {
        var binds = new AtomicInteger();
        var closes = new AtomicInteger();
        var creates = new AtomicInteger();
        ManagedDependency first = edge(String.class, false, "service.name", "first");
        ManagedDependency second = edge(String.class, false, "resource.name", "second");
        var registry = new ManagedHostBindings(Map.of("service.name", factory(binds, closes, false),
                "resource.name", factory(binds, closes, true)));
        var definition = new ManagedComponentDefinition<Target>() {
            public Class<Target> type() { return Target.class; }
            public String name() { return ""; }
            public List<ManagedQualifier> qualifiers() { return List.of(); }
            public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
            public List<ManagedDependency> dependencies() { return List.of(first, second); }
            public Target create(ManagedResolver resolver) { creates.incrementAndGet(); return new Target(null); }
        };
        var failure = assertThrows(ManagedContainer.Failure.class,
                () -> ManagedContainer.activate(List.of(() -> List.of(definition)), 10, null, registry));
        assertEquals("HOST_BINDING_FAILED", failure.code());
        assertFalse(failure.toString().contains("synthetic"));
        assertNull(failure.getCause());
        assertEquals(0, creates.get());
        assertEquals(1, closes.get());
    }

    @Test
    void contextProvidersReadCurrentDispatchRatherThanTheConstructingPrincipal() throws Exception {
        ApplicationContext application = (ApplicationContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ApplicationContext.class}, (proxy, method, arguments) -> null);
        ManagedDependency dependency = edge(Principal.class, true, "context.kind", "principal");
        var registry = new ManagedHostBindings(Map.of("context.kind", ManagedContextBindings.factory(application)));
        var creates = new AtomicInteger();
        try (var container = ManagedContainer.activate(List.of(index(dependency, creates, Scope.Kind.ACTIVATION)), 10, null, registry)) {
            @SuppressWarnings("unchecked")
            ManagedProvider<Principal> provider = (ManagedProvider<Principal>) container.get(Target.class, "", List.of()).value();
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> current(provider, "first"));
                var second = executor.submit(() -> current(provider, "second"));
                assertEquals("first", first.get());
                assertEquals("second", second.get());
            }
            assertEquals(Principal.anonymous(), provider.get());
        }
        assertThrows(ManagedContainer.Failure.class, () -> ManagedContainer.activate(List.of(index(
                edge(Principal.class, false, "context.kind", "principal"), creates, Scope.Kind.ACTIVATION)), 10, null, registry));
        assertEquals(1, creates.get());
    }

    @Test
    void ambiguousPrimaryAttributesAndUnboundedRegistriesAreRejected() {
        var counter = new AtomicInteger();
        var registry = new ManagedHostBindings(Map.of("service.name", factory(counter, counter, false),
                "resource.name", factory(counter, counter, false)));
        var ambiguous = new ManagedDependency(String.class, "", List.of(), false, Map.of("service.name", "x", "resource.name", "y"));
        assertThrows(IllegalArgumentException.class, () -> registry.select(ambiguous));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostBindings(Map.of("invalid", factory(counter, counter, false))));
        var empty = ManagedHostBindings.empty();
        var composed = empty.with("service.name", factory(counter, counter, false));
        assertThrows(IllegalArgumentException.class, () -> empty.select(edge(String.class, false, "service.name", "x")));
        assertNotNull(composed.select(edge(String.class, false, "service.name", "x")));
        assertTrue(composed.supports("service.name"));
        assertFalse(empty.supports("service.name"));
        assertThrows(IllegalArgumentException.class, () -> composed.with("service.name", factory(counter, counter, false)));
    }

    @Test
    void shutdownMayReadPreparedContextProvidersButCannotResurrectThemAfterCleanup() {
        ApplicationContext application = (ApplicationContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ApplicationContext.class}, (proxy, method, arguments) -> null);
        var edge = edge(ApplicationContext.class, true, "context.kind", "application");
        var observed = new java.util.concurrent.atomic.AtomicReference<ApplicationContext>();
        org.j7m.middleware.application.api.ManagedHostAdapter<Target> adapter = new org.j7m.middleware.application.api.ManagedHostAdapter<>() {
            public Class<Target> type() { return Target.class; }
            public String name() { return ""; }
            public List<ManagedQualifier> qualifiers() { return List.of(); }
            public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
            public List<ManagedDependency> dependencies() { return List.of(edge); }
            public Target create(ManagedResolver resolver) { return new Target(resolver.provider(edge)); }
            public void lifecycle(Target target, org.j7m.middleware.application.api.ManagedLifecycle phase) {
                if (phase == org.j7m.middleware.application.api.ManagedLifecycle.SHUTDOWN) {
                    @SuppressWarnings("unchecked") ManagedProvider<ApplicationContext> provider = (ManagedProvider<ApplicationContext>) target.value();
                    observed.set(provider.get());
                }
            }
        };
        var registry = new ManagedHostBindings(Map.of("context.kind", ManagedContextBindings.factory(application)));
        var container = ManagedContainer.activate(List.of(() -> List.of(adapter)), 10, null, registry);
        @SuppressWarnings("unchecked") ManagedProvider<ApplicationContext> retained = (ManagedProvider<ApplicationContext>) container.get(Target.class, "", List.of()).value();
        container.lifecycle(org.j7m.middleware.application.api.ManagedLifecycle.STARTUP);
        container.close();
        assertSame(application, observed.get());
        assertThrows(ManagedContainer.Failure.class, retained::get);
    }

    private static String current(ManagedProvider<Principal> provider, String id) {
        Principal principal = new Principal(id, id, java.util.Set.of(), java.util.Set.of());
        try (var ignored = J7mContext.open(new InvocationContext(new InvocationSecurityContext(principal, java.util.Set.of(), null), null, id))) {
            return provider.get().id();
        }
    }

    private static ManagedDependency edge(Class<?> type, boolean provider, String primary, String value) {
        return new ManagedDependency(type, "", List.of(), provider, Map.of(primary, value));
    }

    private static ManagedComponentIndex index(ManagedDependency dependency, AtomicInteger creates, Scope.Kind scope) {
        return () -> List.of(new ManagedComponentDefinition<Target>() {
            public Class<Target> type() { return Target.class; }
            public String name() { return ""; }
            public List<ManagedQualifier> qualifiers() { return List.of(); }
            public Scope.Kind scope() { return scope; }
            public List<ManagedDependency> dependencies() { return List.of(dependency); }
            public Target create(ManagedResolver resolver) {
                creates.incrementAndGet();
                return new Target(dependency.provider() ? resolver.provider(dependency) : resolver.resolve(dependency));
            }
        });
    }

    private static ManagedHostBindings.Factory factory(AtomicInteger binds, AtomicInteger closes, boolean fail) {
        return new ManagedHostBindings.Factory() {
            public void validate(ManagedDependency dependency, Scope.Kind scope) { }
            public ManagedHostBindings.BoundValue bind(ManagedDependency dependency) throws Exception {
                binds.incrementAndGet();
                if (fail) throw new java.io.IOException("synthetic-host-detail");
                return new ManagedHostBindings.BoundValue() {
                    public Object get() { return "prepared"; }
                    public void close() { closes.incrementAndGet(); }
                };
            }
        };
    }
}
