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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit immutable capabilities installed by trusted host setup, never by a bundle descriptor.
 * Factories are borrowed host-owned thread-safe objects; per-activation bound values are owned.
 * @param bindings finite immutable host integration registry
 * @param probeSettings bounded host-owned probe execution profile
 * @param invocationSettings finite tracked invocation execution profile
 * @param policies ordered trusted policy factories; at most64, each claiming at most64 annotation types
 * @param hostedResources finite trusted hosted feature backends, empty for ordinary hosting
 */
public record ManagedHostingOptions(ManagedHostBindings bindings, List<ManagedInvocationPolicy> policies, ManagedProbeSettings probeSettings, ManagedInvocationSettings invocationSettings, ManagedHostedResources hostedResources) {
    /**
     * Preserves ordinary hosting for an existing invocation execution profile.
     * @param bindings trusted dependency bindings
     * @param policies trusted invocation policies
     * @param probeSettings bounded probe profile
     * @param invocationSettings bounded invocation profile
     */
    public ManagedHostingOptions(final ManagedHostBindings bindings, final List<ManagedInvocationPolicy> policies,
            final ManagedProbeSettings probeSettings, final ManagedInvocationSettings invocationSettings) {
        this(bindings, policies, probeSettings, invocationSettings, ManagedHostedResources.empty());
    }
    /**
     * Preserves the default invocation capacity for an existing probe profile.
     * @param bindings trusted bindings
     * @param policies trusted policies
     * @param probeSettings bounded probe profile
     */
    public ManagedHostingOptions(final ManagedHostBindings bindings, final List<ManagedInvocationPolicy> policies,
            final ManagedProbeSettings probeSettings) {
        this(bindings, policies, probeSettings, ManagedInvocationSettings.defaults());
    }

    /**
     * Preserves the default development-qualified probe profile.
     * @param bindings trusted bindings
     * @param policies trusted policies
     */
    public ManagedHostingOptions(final ManagedHostBindings bindings, final List<ManagedInvocationPolicy> policies) {
        this(bindings, policies, ManagedProbeSettings.defaults(), ManagedInvocationSettings.defaults());
    }

    /**
     * Validates a bounded unambiguous capability inventory without binding application classes.
     * @param bindings explicit registry, never null
     * @param policies explicit immutable policy snapshot
     * @param probeSettings bounded host-owned sampling profile
     * @param invocationSettings finite tracked invocation profile
     * @throws IllegalArgumentException for excessive or duplicate annotation capabilities
     * @throws NullPointerException for null input or factory
     */
    public ManagedHostingOptions {
        java.util.Objects.requireNonNull(probeSettings, "probeSettings");
        java.util.Objects.requireNonNull(invocationSettings, "invocationSettings");
        java.util.Objects.requireNonNull(hostedResources, "hostedResources");
        java.util.Objects.requireNonNull(bindings, "bindings");
        policies = List.copyOf(policies);
        if (policies.size() > 64) { throw new IllegalArgumentException("too many host policies"); }
        Set<Class<? extends Annotation>> claimed = new HashSet<>();
        java.util.List<ManagedInvocationPolicy> snapshots = new java.util.ArrayList<>();
        for (ManagedInvocationPolicy policy : policies) {
            Set<Class<? extends Annotation>> types = Set.copyOf(policy.annotationTypes());
            if (types.size() > 64) { throw new IllegalArgumentException("too many policy capabilities"); }
            for (Class<? extends Annotation> type : types) {
                if (!type.isAnnotation() || !claimed.add(type)) { throw new IllegalArgumentException("duplicate policy capability"); }
            }
            snapshots.add(new PolicySnapshot(policy, types, java.util.Objects.requireNonNull(policy.phase())));
        }
        snapshots.sort(java.util.Comparator.comparing(ManagedInvocationPolicy::phase));
        policies = List.copyOf(snapshots);
    }

    /**
     * Tests whether this immutable host configuration installs the actual policy instance.
     * Only this class's private snapshots are unwrapped; application-controlled equals(), labels
     * and annotation claims never substitute for the installed object. No policy binding is run.
     * @param policy exact required host-owned policy, never null
     * @return true only for the same underlying installation instance
     * @throws NullPointerException for a missing required policy
     */
    public boolean installs(final ManagedInvocationPolicy policy) {
        ManagedInvocationPolicy required = java.util.Objects.requireNonNull(policy);
        while (required instanceof PolicySnapshot snapshot) required = snapshot.delegate();
        for (var installed : policies) {
            ManagedInvocationPolicy actual = installed;
            while (actual instanceof PolicySnapshot snapshot) actual = snapshot.delegate();
            if (actual == required) return true;
        }
        return false;
    }

    private record PolicySnapshot(ManagedInvocationPolicy delegate, Set<Class<? extends Annotation>> annotationTypes, Phase phase) implements ManagedInvocationPolicy {
        private void stable() {
            if (phase != delegate.phase()) throw new IllegalArgumentException("host policy phase changed");
            if (!annotationTypes.equals(Set.copyOf(delegate.annotationTypes()))) throw new IllegalArgumentException("host policy capabilities changed");
        }
        @Override public void validateComponent(Class<?> component, boolean exported) { stable(); delegate.validateComponent(component, exported); }
        @Override public void validateHostedExposure(final Class<?> component,
                final Set<org.j7m.middleware.application.api.ManagedServiceContract> contracts) {
            stable(); delegate.validateHostedExposure(component, contracts);
        }
        @Override public void validateHostedExposure(final Class<?> component,
                final Map<org.j7m.middleware.application.api.ManagedServiceContract, Class<?>> surfaces) {
            stable(); delegate.validateHostedExposure(component, surfaces);
        }
        @Override public void validateHosted(final org.j7m.middleware.application.api.ManagedServiceContract contract,
                final Class<?> component, final Set<org.j7m.middleware.application.api.ManagedMethod> callbacks) {
            stable(); delegate.validateHosted(contract, component, callbacks);
        }
        @Override public Binding bindHosted(final org.j7m.middleware.application.api.ManagedServiceContract contract,
                final Class<?> component, final Set<org.j7m.middleware.application.api.ManagedMethod> callbacks) throws Exception {
            stable(); return delegate.bindHosted(contract, component, callbacks);
        }
        @Override public void validateExposure(final Class<?> component,
                final Map<org.j7m.middleware.application.api.ManagedServiceContract, Class<?>> exports) {
            stable(); delegate.validateExposure(component, exports);
        }
        @Override public void validate(org.j7m.middleware.application.api.ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
            stable(); delegate.validate(contract, implementation, businessInterface);
        }
        @Override public Binding bind(org.j7m.middleware.application.api.ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) throws Exception {
            stable(); return delegate.bind(contract, implementation, businessInterface);
        }
    }

    /**
     * Returns a host profile with no optional policies or application-controlled bindings.
     * The manager adds only its own safe current-context capability at activation.
     * @return immutable default profile
     */
    public static ManagedHostingOptions defaults() { return new ManagedHostingOptions(new ManagedHostBindings(Map.of()), List.of()); }
}
