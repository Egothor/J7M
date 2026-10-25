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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AnnotationPolicyRegistryTest {
    @Retention(RetentionPolicy.CLASS) @interface NonRuntime { }

    @Test void emptySetupNeverClaimsRuntimeCapabilitiesOrPolicies() {
        var empty = AnnotationPolicyRegistry.empty();
        assertFalse(empty.contains(AnnotationPolicyRegistry.Kind.RETRY, "default"));
        assertFalse(empty.supports(Inject.class));
        assertThrows(IllegalArgumentException.class, () -> empty.contains(null, "default"));
        assertThrows(IllegalArgumentException.class, () -> empty.supports(null));
    }

    @Test void registrationIsDefensiveAndPolicyNamespacesRemainIndependent() {
        var names = new ArrayList<>(List.of("bounded"));
        var capabilities = new ArrayList<>(List.of(Inject.class));
        var source = new HashMap<AnnotationPolicyRegistry.Kind, List<String>>();
        source.put(AnnotationPolicyRegistry.Kind.RETRY, names);
        var registry = new AnnotationPolicyRegistry(source, capabilities);
        names.clear(); capabilities.clear(); source.clear();
        assertTrue(registry.contains(AnnotationPolicyRegistry.Kind.RETRY, "bounded"));
        assertFalse(registry.contains(AnnotationPolicyRegistry.Kind.ROUTING, "bounded"));
        assertTrue(registry.supports(Inject.class));
        assertFalse(registry.supports(Named.class));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"bad id", "../policy", "secret=synthetic"})
    void malformedPolicyIdsFailWithoutEchoingTheirValues(String name) {
        var values = new ArrayList<String>(); values.add(name);
        var error = assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(
                Map.of(AnnotationPolicyRegistry.Kind.RETRY, values), List.of()));
        assertEquals("Invalid or duplicate policy identifier", error.getMessage());
    }

    @Test void policyCountAndIdentifierLengthBoundariesAreExplicit() {
        var names = IntStream.range(0, 128).mapToObj(i -> "policy" + i).toList();
        var registry = new AnnotationPolicyRegistry(Map.of(AnnotationPolicyRegistry.Kind.RETRY, names), List.of());
        assertTrue(registry.contains(AnnotationPolicyRegistry.Kind.RETRY, "policy127"));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(
                Map.of(AnnotationPolicyRegistry.Kind.RETRY, IntStream.range(0, 129).mapToObj(i -> "p" + i).toList()), List.of()));
        assertTrue(new AnnotationPolicyRegistry(Map.of(AnnotationPolicyRegistry.Kind.RETRY, List.of("x".repeat(128))), List.of())
                .contains(AnnotationPolicyRegistry.Kind.RETRY, "x".repeat(128)));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(
                Map.of(AnnotationPolicyRegistry.Kind.RETRY, List.of("x".repeat(129))), List.of()));
    }

    @Test void duplicateAndInvalidCapabilitiesAndPolicyBindingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(
                Map.of(AnnotationPolicyRegistry.Kind.RETRY, List.of("same", "same")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(Map.of(), List.of(Inject.class, Inject.class)));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(Map.of(), List.of(NonRuntime.class)));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationPolicyRegistry(Map.of(), java.util.Collections.nCopies(257, Inject.class)));
    }
}
