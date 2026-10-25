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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.junit.jupiter.api.Test;

class ManagedPolicyOrderingTest {
    private static class Policy implements ManagedInvocationPolicy {
        final AtomicReference<Phase> selected;
        final AtomicInteger validated = new AtomicInteger();
        Policy(final Phase phase) { selected = new AtomicReference<>(phase); }
        @Override public Phase phase() { return selected.get(); }
        @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(); }
        @Override public void validateComponent(final Class<?> component, final boolean exported) { validated.incrementAndGet(); }
        @Override public void validate(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> business) { }
        @Override public Binding bind(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> business) {
            throw new AssertionError("validation must not construct bindings");
        }
    }

    @Test void trustedHostPhasesAreSortedAndSamePhaseOrderIsStable() {
        var first = new Policy(ManagedInvocationPolicy.Phase.APPLICATION);
        var second = new Policy(ManagedInvocationPolicy.Phase.APPLICATION);
        var security = new Policy(ManagedInvocationPolicy.Phase.SECURITY);
        var replay = new Policy(ManagedInvocationPolicy.Phase.REPLAY);
        var transaction = new Policy(ManagedInvocationPolicy.Phase.TRANSACTION);
        var options = new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(first, replay, second, transaction, security));
        assertEquals(List.of(ManagedInvocationPolicy.Phase.SECURITY, ManagedInvocationPolicy.Phase.REPLAY,
                ManagedInvocationPolicy.Phase.TRANSACTION, ManagedInvocationPolicy.Phase.APPLICATION,
                ManagedInvocationPolicy.Phase.APPLICATION), options.policies().stream().map(ManagedInvocationPolicy::phase).toList());
        options.policies().get(3).validateComponent(Object.class, false);
        assertEquals(1, first.validated.get()); assertEquals(0, second.validated.get());
        assertThrows(UnsupportedOperationException.class, () -> options.policies().clear());
        assertEquals(ManagedInvocationPolicy.Phase.APPLICATION, new ManagedCallingPolicy().phase());
        try (var resource = new ManagedResourcePolicy(java.util.Map.of(), java.util.Map.of())) {
            assertEquals(ManagedInvocationPolicy.Phase.ADMISSION, resource.phase());
        }
    }

    @Test void legacyContinuationCannotClaimFinalizerSupportOrInstallAnInvocationAttachment() throws Exception {
        ManagedInvocationPolicy.Proceed legacy = () -> "legacy";
        assertEquals("legacy", legacy.invoke());
        assertFalse(legacy.supportsResultFinalizers());
        assertNull(legacy.physicalCompletion());
        assertThrows(IllegalStateException.class, () -> legacy.addResultFinalizer(java.util.concurrent.CompletableFuture::completedFuture));
        assertThrows(IllegalStateException.class, () -> legacy.putContext(String.class, "must not install"));
        assertThrows(IllegalStateException.class, () -> legacy.context(String.class));
        assertThrows(IllegalStateException.class, () -> legacy.findContext(String.class));
    }

    @Test void mutableOrMissingHostPhaseFailsBeforeComponentValidation() {
        var policy = new Policy(ManagedInvocationPolicy.Phase.REPLAY);
        var options = new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(policy));
        policy.selected.set(ManagedInvocationPolicy.Phase.SECURITY);
        assertThrows(IllegalArgumentException.class, () -> options.policies().getFirst().validateComponent(Object.class, false));
        assertEquals(0, policy.validated.get());
        policy.selected.set(null);
        assertThrows(NullPointerException.class, () -> new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(policy)));
    }
}
