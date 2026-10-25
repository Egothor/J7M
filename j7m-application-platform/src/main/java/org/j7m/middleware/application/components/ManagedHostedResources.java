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
import java.util.concurrent.CompletionStage;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedHostedFeature;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * Immutable trusted host registry for finite generated hosted features.
 * Application descriptors cannot install factories or choose authority. Factories and their fixed
 * worker identities are borrowed from host setup; each bound lifecycle is activation-owned.
 * No component constructor may run until every declared feature has passed factory validation.
 */
public final class ManagedHostedResources {
    /**
     * Finite backend adapter installed by trusted host setup, never by an application provider.
     */
    public interface Factory {
        /**
         * Declares actual guard-policy instances required by this backend before any constructor.
         * The host checks identity through its immutable policy snapshots, not equals(), names or
         * application metadata. Return an immutable stable set of at most16 host-owned instances.
         * @return required actual policy installations, empty when no guard dependency exists
         */
        default java.util.Set<ManagedInvocationPolicy> requiredPolicies() { return java.util.Set.of(); }
        /**
         * Declares invocation-free backend kinds actually implemented by this factory.
         * Only these exact installed kinds may omit callbacks; application metadata grants nothing.
         * @return stable immutable backend kind set, at most16
         */
        default java.util.Set<String> backendKinds() { return java.util.Set.of(); }
        /**
         * Returns the annotation capabilities actually enforced by this backend.
         * @return finite immutable annotation set, empty by default
         */
        default java.util.Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes() { return java.util.Set.of(); }
        /**
         * Validates a complete descriptor without constructing components or starting effects.
         * @param feature finite generated declaration
         * @param definition exact generated component plan
         * @param bundle verified program identity and immutable semantic configuration
         * @throws IllegalArgumentException for unsupported types, policy or incompatible configuration
         */
        void validate(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition, Bundle bundle);

        /**
         * Creates an activation-owned lifecycle after successful global validation.
         * Admission remains closed through start; the host commits every prepared binding atomically. A pure replicated reducer must not use
         * invoker from consensus callbacks; ordinary hosted handlers must use it instead of raw calls.
         * @param feature validated descriptor
         * @param definition exact generated component plan
         * @param component activation-owned component, never owned by the returned binding
         * @param bundle verified program/configuration snapshot
         * @param invoker host-owned policy/context/physical-completion boundary
         * @return owned lifecycle, never an application factory registration
         * @throws Exception if preparation cannot be established
         */
        Binding bind(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition, Object component,
                Bundle bundle, Invoker invoker) throws Exception;
    }

    /**
     * One owned backend lifecycle. Operations must be bounded and close must be idempotent.
     */
    public interface Binding extends AutoCloseable {
        /**
         * Atomically closes new work admission without waiting or performing external I/O.
         * Called under the host's activation admission guard before asynchronous resource draining.
         * It must be thread-safe and idempotent, including after close. The default fails closed.
         * @throws UnsupportedOperationException unless this backend implements actual admission control
         */
        default void stopAdmission() { throw new UnsupportedOperationException("Hosted admission control is unavailable"); }
        /**
         * Prepares storage and endpoints with external effect admission closed.
         * @throws Exception if durable recovery or endpoint setup fails
         */
        void prepare() throws Exception;
        /**
         * Arms the prepared resource after application initialization/startup has completed.
         * No external application work is admitted here. Actual admission requires both the later
         * local commitAdmission() and the host's shared all-resources admission guard.
         * @throws Exception if the resource cannot safely arm
         */
        void start() throws Exception;
        /**
         * Commits local admission after every backend has armed successfully.
         * This must perform no external I/O or wait. A backend also honors the shared invoker
         * admission guard, which remains closed until the host commits the entire activation.
         * @throws UnsupportedOperationException unless atomic admission is implemented
         */
        default void commitAdmission() { throw new UnsupportedOperationException("Hosted admission commit is unavailable"); }
        /**
         * Closes new admission and waits within the budget for actual apply/query/snapshot work.
         * A false result retains ownership and the classloader; it never authorizes forced cleanup.
         * @param budget positive remaining drain budget
         * @return true only after actual resource work has exited
         * @throws Exception if safe draining cannot be established
         */
        boolean drain(Duration budget) throws Exception;
        /**
         * Releases a fully drained or never-published resource; does not close the component.
         * @throws Exception if durable/endpoint cleanup fails
         */
        @Override void close() throws Exception;
    }

    /**
     * Fixed-authority invocation handle retained only by a trusted bound factory.
     * It enters an exact generated callback on the calling bounded worker thread. The stage is
     * returned after root method return and completes only after policies and physical cleanup.
     * It must not be called on a scheduler, consensus apply thread or from application code.
     */
    @FunctionalInterface
    public interface Invoker {
        /**
         * Observes the host's shared activation admission guard, without granting authority.
         * Standalone trusted host handles default to ready; managed hosting supplies the actual
         * activation guard. This handle is never installed or supplied by application metadata.
         * @return whether the owning host has committed admission for all hosted resources
         */
        default boolean admissionOpen() { return true; }
        /**
         * Invokes a declared callback using the host-bound worker principal and logical target.
         * @param method exact callback identity in this feature
         * @param arguments ordered application data
         * @param options correlation and budget, clamped by the trusted host maximum
         * @return host-owned completion after actual work and finalization
         * @throws IllegalArgumentException for an undeclared callback or invalid options
         */
        default CompletionStage<Object> invoke(ManagedMethod method, Object[] arguments, InvocationOptions options) {
            Object[] copy = Objects.requireNonNull(arguments).clone();
            return invokeDecoded(method, () -> copy, options);
        }
        /**
         * Decodes application values inside the same owned CALL before invoking its callback.
         * The decoder is a trusted host codec action, not an application factory registration.
         * @param method exact generated callback identity
         * @param decoder bounded argument decoder executing inside actual invocation context
         * @param options correlation and finite remaining budget
         * @return host-owned completion after policies and physical cleanup
         * @throws IllegalArgumentException for an undeclared callback or invalid options
         */
        CompletionStage<Object> invokeDecoded(ManagedMethod method, java.util.concurrent.Callable<Object[]> decoder,
                InvocationOptions options);
        /**
         * Supplies the actual bounded encoded input size from a trusted source adapter.
         * The count is local host state, never an assertion taken from invocation metadata. The
         * owning application applies its payload limit before running this decoder or handler.
         * @param method exact callback identity
         * @param decoder bounded decoder within the owned CALL
         * @param options finite original budget
         * @param inboundBytes trusted nonnegative encoded input size
         * @return physical completion stage
         * @throws UnsupportedOperationException if this handle cannot enforce measured admission
         */
        default CompletionStage<Object> invokeDecoded(ManagedMethod method, java.util.concurrent.Callable<Object[]> decoder,
                InvocationOptions options, int inboundBytes) {
            throw new UnsupportedOperationException("Measured hosted admission is unsupported");
        }
    }

    /**
     * Verified immutable program identity; no field is an application authorization assertion.
     * @param artifactDigest host-verified lowercase SHA-256 of the complete deployed artifact
     * @param semanticConfiguration immutable deterministic configuration snapshot
     */
    public record Bundle(String artifactDigest, Map<String, String> semanticConfiguration) {
        /**
         * Validates the verified identity and bounds/copies configuration without logging values.
         * @param artifactDigest exact verified artifact hash
         * @param semanticConfiguration at most256 entries and65536 total characters
         * @throws IllegalArgumentException for malformed digest or excessive configuration
         * @throws NullPointerException for null configuration members
         */
        public Bundle {
            if (artifactDigest == null || !artifactDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid verified program digest");
            }
            semanticConfiguration = Map.copyOf(semanticConfiguration);
            long characters = semanticConfiguration.entrySet().stream()
                    .mapToLong(entry -> (long) entry.getKey().length() + entry.getValue().length()).sum();
            if (semanticConfiguration.size() > 256 || characters > 65536) {
                throw new IllegalArgumentException("Hosted configuration capacity");
            }
        }
        /**
         * Returns a diagnostic omitting configuration values.
         * @return fixed safe label
         */
        @Override public String toString() { return "ManagedHostedBundle[verified]"; }
    }

    /**
     * Borrowed trusted factory and immutable worker authority, never copied from application metadata.
     * @param factory actual backend implementation
     * @param worker fixed local worker identity, without a remote authentication claim
     * @param maximumBudget finite positive invocation cap, at most one day
     */
    public record Entry(Factory factory, SecurityContext worker, Duration maximumBudget) {
        /**
         * Rejects remote/system impersonation claims and invalid finite budgets.
         * @param factory borrowed backend implementation
         * @param worker fixed local worker authority
         * @param maximumBudget positive budget up to one day
         * @throws NullPointerException for null input
         * @throws IllegalArgumentException for remote authentication claims or invalid budget
         */
        public Entry {
            Objects.requireNonNull(factory); Objects.requireNonNull(worker); Objects.requireNonNull(maximumBudget);
            if (worker.authenticated() || !worker.remoteEndpoint().isEmpty()
                    || !worker.credential().type().equals("none") || maximumBudget.isZero()
                    || maximumBudget.isNegative() || maximumBudget.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Invalid hosted worker profile");
            }
        }
        /**
         * Returns a diagnostic omitting principal and credential data.
         * @return fixed safe label
         */
        @Override public String toString() { return "ManagedHostedEntry[trusted-host]"; }
    }

    private final Map<String, Entry> entries;
    private final Map<String, java.util.Set<Class<? extends java.lang.annotation.Annotation>>> capabilities;

    private final Map<String, java.util.Set<String>> backendKinds;
    private final Map<String, java.util.List<ManagedInvocationPolicy>> requiredPolicies;

    /**
     * Copies a finite unambiguous host registry.
     * @param entries adapter kind to trusted factory/profile, at most64
     * @throws IllegalArgumentException for malformed kinds or capacity
     * @throws NullPointerException for null keys or values
     */
    public ManagedHostedResources(final Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
        if (this.entries.size() > 64 || this.entries.keySet().stream()
                .anyMatch(kind -> !kind.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) {
            throw new IllegalArgumentException("Invalid hosted resource registry");
        }
        var inventory = new java.util.HashMap<String, java.util.Set<Class<? extends java.lang.annotation.Annotation>>>();
        var claimed = new java.util.HashMap<Class<? extends java.lang.annotation.Annotation>, Factory>();
        var backends = new java.util.HashMap<String, java.util.Set<String>>();
        var guards = new java.util.HashMap<String, java.util.List<ManagedInvocationPolicy>>();
        for (var entry : this.entries.entrySet()) {
            var types = java.util.Set.copyOf(entry.getValue().factory().annotationTypes());
            if (types.size() > 64) throw new IllegalArgumentException("Hosted capability capacity");
            for (var type : types) {
                Factory previous = claimed.putIfAbsent(type, entry.getValue().factory());
                if (!type.isAnnotation() || previous != null && previous != entry.getValue().factory()) {
                    throw new IllegalArgumentException("Duplicate hosted capability");
                }
            }
            var kinds = java.util.Set.copyOf(entry.getValue().factory().backendKinds());
            if (kinds.size() > 16 || kinds.stream().anyMatch(kind -> !kind.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) {
                throw new IllegalArgumentException("Invalid hosted backend kinds");
            }
            var policies = java.util.List.copyOf(entry.getValue().factory().requiredPolicies());
            if (policies.size() > 16) throw new IllegalArgumentException("Hosted required policy capacity");
            backends.put(entry.getKey(), kinds); guards.put(entry.getKey(), policies);
            inventory.put(entry.getKey(), types);
        }
        capabilities = Map.copyOf(inventory);
        backendKinds = Map.copyOf(backends); requiredPolicies = Map.copyOf(guards);
    }

    /**
     * Selects a previously installed capability; no application fallback or discovery occurs.
     * @param kind exact generated adapter kind
     * @return borrowed trusted entry
     * @throws IllegalArgumentException if the kind is unsupported
     */
    public Entry require(final String kind) {
        Entry entry = entries.get(kind);
        if (entry == null) throw new IllegalArgumentException("Unsupported hosted resource kind");
        if (!capabilities.get(kind).equals(java.util.Set.copyOf(entry.factory().annotationTypes()))) {
            throw new IllegalArgumentException("Hosted capabilities changed");
        }
        if (!backendKinds.get(kind).equals(java.util.Set.copyOf(entry.factory().backendKinds()))) {
            throw new IllegalArgumentException("Hosted backend kinds changed");
        }
        var current = java.util.List.copyOf(entry.factory().requiredPolicies());
        var expected = requiredPolicies.get(kind);
        if (current.size() != expected.size() || current.stream().anyMatch(policy -> expected.stream().noneMatch(value -> value == policy))) {
            throw new IllegalArgumentException("Hosted required policies changed");
        }
        return entry;
    }

    /**
     * Tests the exact installed invocation-free capability, without a descriptor fallback.
     * @param kind selected installed kind
     * @return whether this factory owns that backend kind
     */
    public boolean backendOnly(final String kind) { require(kind); return backendKinds.get(kind).contains(kind); }

    /**
     * Returns immutable actual policy identities captured from trusted setup.
     * @param kind selected installed kind
     * @return required actual guard objects
     */
    public java.util.List<ManagedInvocationPolicy> requiredPolicies(final String kind) { require(kind); return requiredPolicies.get(kind); }

    /**
     * Returns whether a trusted capability is installed without invoking its factory.
     * @param kind exact adapter kind
     * @return whether it has an explicitly installed factory
     */
    public boolean supports(final String kind) { return entries.containsKey(kind); }

    /**
     * Returns the finite capabilities of explicitly installed backend factories.
     * @return immutable annotation capability union
     * @throws IllegalArgumentException for excessive or duplicate capability declarations
     */
    public java.util.Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes() {
        var result = new java.util.HashSet<Class<? extends java.lang.annotation.Annotation>>();
        for (String kind : entries.keySet()) { require(kind); result.addAll(capabilities.get(kind)); }
        return java.util.Set.copyOf(result);
    }

    /**
     * Returns an empty opt-in registry preserving ordinary managed hosting behavior.
     * @return immutable empty registry
     */
    public static ManagedHostedResources empty() { return new ManagedHostedResources(Map.of()); }
}
