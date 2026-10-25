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

import java.util.Map;
import java.util.Set;
import java.util.Objects;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ManagedDependency;

/**
 * Immutable bounded capabilities registered by trusted host setup, never by application metadata.
 * A dependency selects exactly one registered primary attribute. Factories validate all metadata
 * before any component constructor runs. Returned application values remain borrowed; the container
 * owns each BoundValue and closes it once on failed activation or shutdown.
 */
public final class ManagedHostBindings {
    private final Map<String, Factory> factories;

    /**
     * A trusted, thread-safe host integration capability. Error details are redacted by the container.
     */
    public interface Factory {
        /**
         * Validates metadata and scope without opening connections or constructing application values.
         * @param dependency explicit generated edge
         * @param scope scope of the component capturing this edge
         * @throws IllegalArgumentException for unsupported metadata, types or unsafe scope captures
         */
        void validate(ManagedDependency dependency, Scope.Kind scope);
        /**
         * Creates one owned binding after complete graph validation and before component construction.
         * @param dependency validated generated edge
         * @return owned binding, never null
         * @throws Exception when preparation fails
         */
        BoundValue bind(ManagedDependency dependency) throws Exception;
    }

    /**
     * Prepared host binding. Reads must be nonblocking and must not establish new network connections.
     */
    public interface BoundValue extends AutoCloseable {
        /**
         * Reads the current prepared value; caller ownership remains borrowed.
         * @return value assignable to the declared edge type, possibly null only when the adapter allows it
         * @throws IllegalStateException if unavailable or closed
         */
        Object get();
        /**
         * Releases this binding's watches/resources, once; no application value is disposed here.
         * @throws Exception if cleanup fails
         */
        @Override void close() throws Exception;
    }

    /**
     * Registers at most32 explicit primary attributes such as context.kind or service.name.
     * @param factories nonnull immutable snapshot of trusted integration capabilities
     * @throws IllegalArgumentException for invalid or excessive attributes
     * @throws NullPointerException for null map, keys or factories
     */
    public ManagedHostBindings(final Map<String, ? extends Factory> factories) {
        Objects.requireNonNull(factories, "factories");
        if (factories.size() > 32) { throw new IllegalArgumentException("host binding capacity"); }
        this.factories = Map.copyOf(factories);
        for (String key : this.factories.keySet()) {
            if (!key.matches("[a-z][a-z0-9]{0,31}\\.[a-z][a-z0-9.]{0,63}")) {
                throw new IllegalArgumentException("invalid host binding attribute");
            }
        }
    }

    /**
     * Returns a registry granting no host integrations.
     * @return immutable empty registry
     */
    public static ManagedHostBindings empty() { return new ManagedHostBindings(Map.of()); }

    /**
     * Adds one explicit capability to a new registry, rejecting replacement of an existing capability.
     * @param primaryAttribute exact primary attribute
     * @param factory trusted host integration
     * @return new bounded immutable registry
     * @throws IllegalArgumentException for duplicate or invalid attributes
     */
    public ManagedHostBindings with(final String primaryAttribute, final Factory factory) {
        Map<String, Factory> copy = new java.util.LinkedHashMap<>(factories);
        if (copy.putIfAbsent(primaryAttribute, Objects.requireNonNull(factory)) != null) {
            throw new IllegalArgumentException("host binding already registered");
        }
        return new ManagedHostBindings(copy);
    }

    /**
     * Reports whether trusted host setup installed one primary integration capability.
     * This observation grants no authority and does not validate application metadata.
     * @param primaryAttribute exact integration attribute
     * @return whether a factory was explicitly registered
     */
    public boolean supports(final String primaryAttribute) { return factories.containsKey(primaryAttribute); }

    boolean matches(final ManagedDependency dependency) {
        return factories.keySet().stream().anyMatch(dependency.attributes()::containsKey);
    }

    Factory select(final ManagedDependency dependency) {
        Set<String> primary = new java.util.HashSet<>(factories.keySet());
        primary.addAll(java.util.Set.of("context.kind", "service.name", "resource.name"));
        if (primary.stream().filter(dependency.attributes()::containsKey).count() != 1) {
            throw new IllegalArgumentException("ambiguous host binding");
        }
        Factory selected = null;
        for (var entry : factories.entrySet()) {
            if (dependency.attributes().containsKey(entry.getKey())) {
                if (selected != null) { throw new IllegalArgumentException("ambiguous host binding"); }
                selected = entry.getValue();
            }
        }
        if (selected == null) { throw new IllegalArgumentException("unsupported host binding"); }
        return selected;
    }
}
