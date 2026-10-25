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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, explicitly trusted host setup for annotation preflight. Named policies describe
 * available configuration identities; capabilities declare which annotation behaviours the caller's
 * actual host supplies. Neither registration nor successful preflight installs an interceptor,
 * creates a component or proves runtime enforcement. Application annotations never grant capabilities.
 */
public final class AnnotationPolicyRegistry {
    /**
     * Independent policy namespaces; the same name may exist in different kinds.
     */
    public enum Kind {
        /**
         * Admission/rate-limit policy.
         */ RATE_LIMIT,
        /**
         * Host resource requirement.
         */ RESOURCE,
        /**
         * Host isolation profile.
         */ ISOLATION,
        /**
         * Retry configuration for a future registered adapter.
         */ RETRY,
        /**
         * Routing/selection configuration for a future registered adapter.
         */ ROUTING,
        /**
         * Observation configuration for a future registered adapter.
         */ OBSERVABILITY
    }
    private final Map<Kind, Set<String>> policies;
    private final Set<Class<? extends Annotation>> capabilities;
    /**
     * Creates bounded, defensive host setup; no annotation values are included in errors.
     * @param namedPolicies policy names by independent namespace, at most 128 names per kind
     * @param trustedHostCapabilities explicitly supplied runtime-retained annotation types, at most 256
     * @throws IllegalArgumentException for null, duplicate, malformed or excessive entries
     */
    public AnnotationPolicyRegistry(final Map<Kind, ? extends Collection<String>> namedPolicies,
            final Collection<? extends Class<? extends Annotation>> trustedHostCapabilities) {
        if (namedPolicies == null || trustedHostCapabilities == null || trustedHostCapabilities.size() > 256) {
            throw new IllegalArgumentException("Invalid annotation host setup");
        }
        final Map<Kind, Set<String>> copy = new EnumMap<>(Kind.class);
        for (Map.Entry<Kind, ? extends Collection<String>> entry : namedPolicies.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().size() > 128) {
                throw new IllegalArgumentException("Invalid policy namespace");
            }
            final Set<String> names = new HashSet<>();
            for (String name : entry.getValue()) {
                if (!identifier(name) || !names.add(name)) {
                    throw new IllegalArgumentException("Invalid or duplicate policy identifier");
                }
            }
            copy.put(entry.getKey(), Set.copyOf(names));
        }
        final Set<Class<? extends Annotation>> types = new HashSet<>();
        for (Class<? extends Annotation> type : trustedHostCapabilities) {
            final Retention retention = type == null ? null : type.getAnnotation(Retention.class);
            if (type == null || !type.isAnnotation() || retention == null
                    || retention.value() != RetentionPolicy.RUNTIME || !types.add(type)) {
                throw new IllegalArgumentException("Invalid or duplicate annotation capability");
            }
        }
        this.policies = Map.copyOf(copy);
        this.capabilities = Set.copyOf(types);
    }

    /**
     * Creates setup with no policies or claimed annotation behaviours.
     * @return immutable empty host setup
     */
    public static AnnotationPolicyRegistry empty() {
        return new AnnotationPolicyRegistry(Map.of(), List.of());
    }

    /**
     * Tests an exact namespace/name without unnamed fallback.
     * @param kind policy namespace
     * @param name bounded policy identifier
     * @return whether that exact policy exists
     * @throws IllegalArgumentException for a null kind or malformed identifier
     */
    public boolean contains(final Kind kind, final String name) {
        if (kind == null || !identifier(name)) {
            throw new IllegalArgumentException("Invalid policy reference");
        }
        return this.policies.getOrDefault(kind, Set.of()).contains(name);
    }

    /**
     * Tests an explicit host declaration, not actual runtime enforcement.
     * @param annotation annotation type
     * @return whether the host declared this annotation behaviour
     * @throws IllegalArgumentException for null
     */
    public boolean supports(final Class<? extends Annotation> annotation) {
        if (annotation == null) {
            throw new IllegalArgumentException("Missing annotation type");
        }
        return this.capabilities.contains(annotation);
    }

    static boolean identifier(final String name) {
        return name != null && name.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    }
}
