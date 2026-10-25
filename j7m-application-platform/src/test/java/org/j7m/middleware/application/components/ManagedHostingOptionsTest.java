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

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.junit.jupiter.api.Test;

/**
 * Host capabilities and wire identities must remain immutable and unambiguous.
 */
class ManagedHostingOptionsTest {
    @Test
    void capabilitySnapshotsCannotExpandAfterValidationAndDuplicateClaimsAreRejected() {
        Set<Class<? extends Annotation>> mutable = new HashSet<>(Set.of(org.j7m.middleware.annotations.RequiresRole.class));
        ManagedInvocationPolicy factory = factory(mutable);
        var options = new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(factory));
        mutable.add(org.j7m.middleware.annotations.Audit.class);
        assertEquals(Set.of(org.j7m.middleware.annotations.RequiresRole.class), options.policies().getFirst().annotationTypes());
        assertThrows(IllegalArgumentException.class, () -> options.policies().getFirst().validateComponent(ManagedHostingOptionsTest.class, false));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(factory, factory)));
        assertTrue(ManagedHostingOptions.defaults().policies().isEmpty());
    }

    @Test
    void wireContractEnvelopeKeepsVersionDistinctFromIndexFormatAndRejectsMalformedIdentifiers() {
        var method = new ManagedMethod("example.Business", "call", List.of("[I"), "java.lang.String");
        assertEquals(2, new ManagedServiceContract("business", 2, method).version());
        assertThrows(IllegalArgumentException.class, () -> new ManagedServiceContract("business", 0, method));
        assertThrows(IllegalArgumentException.class, () -> new ManagedServiceContract("unsafe\nidentity", 1, method));
        assertThrows(NullPointerException.class, () -> new ManagedServiceContract("business", 1, null));
    }

    @org.j7m.middleware.annotations.RequiresRole("operator") static class RestrictedHelper { }

    @Test
    void policiesDoNotSilentlyClaimLocalHelperCallsWithoutAComponentAdapter() {
        ManagedInvocationPolicy factory = factory(Set.of(org.j7m.middleware.annotations.RequiresRole.class));
        assertThrows(IllegalArgumentException.class, () -> factory.validateComponent(RestrictedHelper.class, false));
        assertDoesNotThrow(() -> factory.validateComponent(RestrictedHelper.class, true));
    }

    public static class HostedWorker { public String run() { return "unsafe"; } }
    @Test void immutableSnapshotMustPreserveAnActualHostedGuardInsteadOfFallingBackToOrdinaryBinding() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var guard = new ManagedInvocationPolicy() {
            @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(); }
            @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> business) { }
            @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> business) {
                return new Binding() {
                    @Override public Object invoke(org.j7m.middleware.application.api.InvocationContext context, Object[] args, Proceed next) throws Exception { return next.invoke(); }
                    @Override public void close() { }
                };
            }
            @Override public Binding bindHosted(ManagedServiceContract contract, Class<?> component, Set<ManagedMethod> callbacks) {
                return new Binding() {
                    @Override public Object invoke(org.j7m.middleware.application.api.InvocationContext context, Object[] args, Proceed next) { calls.incrementAndGet(); throw new IllegalStateException("required hosted guard"); }
                    @Override public void close() { }
                };
            }
        };
        var method = new ManagedMethod(HostedWorker.class.getName(), "run", List.of(), "java.lang.String");
        var contract = new ManagedServiceContract("hosted.job.worker", 1, method);
        try (var direct = guard.bindHosted(contract, HostedWorker.class, Set.of(method))) {
            assertThrows(IllegalStateException.class, () -> direct.invoke(org.j7m.middleware.application.api.InvocationContext.empty(), new Object[0], () -> "unsafe"));
        }
        assertEquals(1, calls.get());
        var options = new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(guard));
        var body = new java.util.concurrent.atomic.AtomicInteger();
        try (var actual = options.policies().getFirst().bindHosted(contract, HostedWorker.class, Set.of(method))) {
            assertThrows(IllegalStateException.class, () -> actual.invoke(org.j7m.middleware.application.api.InvocationContext.empty(), new Object[0], () -> { body.incrementAndGet(); return "unsafe"; }));
        }
        assertEquals(0, body.get()); assertEquals(2, calls.get());
    }

    private static class EqualPolicy implements ManagedInvocationPolicy {
        @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(); }
        @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> business) { }
        @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> business) { throw new AssertionError("Identity lookup must not bind"); }
        @Override public boolean equals(Object other) { return other instanceof EqualPolicy; }
        @Override public int hashCode() { return 1; }
    }
    @Test void actualInstallationIdentitySurvivesPrivateSnapshotsButCannotBeSpoofedByEqualsOrNames() {
        var actual = new EqualPolicy(); var different = new EqualPolicy();
        assertEquals(actual, different);
        var options = new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(actual));
        assertTrue(options.installs(actual)); assertTrue(options.installs(options.policies().getFirst()));
        assertFalse(options.installs(different)); assertFalse(ManagedHostingOptions.defaults().installs(actual));
        assertThrows(NullPointerException.class, () -> options.installs(null));
        var copied = new ManagedHostingOptions(options.bindings(), options.policies());
        assertTrue(copied.installs(actual)); assertFalse(copied.installs(different));
    }

    private static ManagedInvocationPolicy factory(Set<Class<? extends Annotation>> capabilities) {
        return new ManagedInvocationPolicy() {
            public Set<Class<? extends Annotation>> annotationTypes() { return capabilities; }
            public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) { }
            public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
                throw new AssertionError("option validation must not prepare application bindings");
            }
        };
    }
}
