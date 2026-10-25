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
import java.lang.annotation.*;
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

class ManagedMetadataTest {
    @Qualifier @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER, ElementType.TYPE})
    @interface Region { String value() default "east"; }
    enum Choice { FIRST }
    static class Factory {
        @Produces(ownership = Produces.Ownership.BORROWED, name = "external")
        public String external() { return "borrowed"; }
        @Produces
        public String internal() { return "owned"; }
    }
    @Test void qualifierCanonicalValuesKeepTypesArrayShapesAndDelimitersDistinct() {
        assertNotEquals(ManagedQualifier.value(1), ManagedQualifier.value(1L));
        assertNotEquals(ManagedQualifier.value(new int[]{1, 2}), ManagedQualifier.value(new Integer[]{1, 2}));
        assertNotEquals(ManagedQualifier.value(new String[]{"a", "b"}), ManagedQualifier.value(new String[]{"a:v:1:b"}));
        for (Object value : List.of("text", true, (byte) 1, (short) 2, 3, 4L, 'c', 1.5F, 2.5D, Choice.FIRST, String[].class)) assertEquals(ManagedQualifier.value(value), ManagedQualifier.value(value));
        assertNotEquals(ManagedQualifier.value(0.0D), ManagedQualifier.value(-0.0D));
        assertEquals(ManagedQualifier.value(Double.NaN), ManagedQualifier.value(Double.longBitsToDouble(0x7ff8000000000001L)));
        assertDoesNotThrow(() -> ManagedQualifier.value(new String[0]));
        assertDoesNotThrow(() -> ManagedQualifier.value(new int[64]));
        assertThrows(IllegalArgumentException.class, () -> ManagedQualifier.value(new int[65]));
        assertThrows(IllegalArgumentException.class, () -> ManagedQualifier.value(new Object()));
        assertThrows(IllegalArgumentException.class, () -> ManagedQualifier.value(null));
        assertThrows(IllegalArgumentException.class, () -> ManagedQualifier.value("x".repeat(8193)));
        assertThrows(IllegalArgumentException.class, () -> ManagedQualifier.value(Region.class.getAnnotation(Retention.class)));
    }
    @Test void metadataIsDefensivelyCopiedAndRejectsInvalidQualifierAndEdgeBounds() {
        var members = new HashMap<String, String>(); members.put("value", ManagedQualifier.value("east"));
        var qualifier = new ManagedQualifier(Region.class.getName(), members); members.clear(); assertEquals(1, qualifier.members().size());
        var qualifiers = new ArrayList<ManagedQualifier>(); qualifiers.add(qualifier);
        var attributes = new HashMap<String, String>(); attributes.put("config.key", "setting");
        var edge = new ManagedDependency(String.class, "name", qualifiers, true, attributes); qualifiers.clear(); attributes.clear();
        assertEquals(1, edge.qualifiers().size()); assertEquals(Map.of("config.key", "setting"), edge.attributes()); assertFalse(edge.toString().contains("setting"));
        assertThrows(UnsupportedOperationException.class, () -> edge.attributes().clear());
        assertEquals(Map.of(), new ManagedDependency(String.class, "", List.of(), false).attributes());
        assertThrows(IllegalArgumentException.class, () -> new ManagedQualifier("", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedDependency(String.class, "x".repeat(257), List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new ManagedDependency(String.class, "", List.of(qualifier, qualifier), false));
        assertThrows(IllegalArgumentException.class, () -> new ManagedDependency(int.class, "", List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new ManagedDependency(String.class, "", List.of(), false, Map.of("", "x")));
    }
    @Test void fullMethodIdentityPreservesOverloadsAndBinaryArrayNames() {
        var parameters = new ArrayList<>(List.of("[I"));
        var first = new ManagedMethod("app.Service", "run", parameters, "java.lang.String"); parameters.clear();
        var second = new ManagedMethod("app.Service", "run", List.of("[Ljava.lang.String;"), "java.lang.String");
        assertNotEquals(first, second); assertEquals(List.of("[I"), first.parameterTypes());
        assertThrows(IllegalArgumentException.class, () -> new ManagedMethod("app.Service", "", List.of(), "void"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedMethod("app.Service", "run", List.of(""), "void"));
    }
    @Test void annotationContractsAreRuntimeRetainedWithExplicitTargetsAndDefaults() throws Exception {
        assertEquals(RetentionPolicy.RUNTIME, Qualifier.class.getAnnotation(Retention.class).value());
        assertEquals(Set.of(ElementType.ANNOTATION_TYPE), Set.of(Qualifier.class.getAnnotation(Target.class).value()));
        assertEquals(Set.of(ElementType.TYPE, ElementType.METHOD), Set.of(Scope.class.getAnnotation(Target.class).value()));
        assertEquals(Scope.Kind.ACTIVATION, Scope.class.getMethod("value").getDefaultValue());
        assertEquals(Set.of(ElementType.METHOD), Set.of(Produces.class.getAnnotation(Target.class).value()));
        assertEquals(Produces.Ownership.OWNED, Produces.class.getMethod("ownership").getDefaultValue());
        assertEquals(Produces.Ownership.BORROWED, Factory.class.getMethod("external").getAnnotation(Produces.class).ownership());
        assertEquals("external", Factory.class.getMethod("external").getAnnotation(Produces.class).name());
        assertEquals(Produces.Ownership.OWNED, Factory.class.getMethod("internal").getAnnotation(Produces.class).ownership());
        assertEquals("", Produces.class.getMethod("name").getDefaultValue());
        assertFalse(Set.of(Named.class.getAnnotation(Target.class).value()).contains(ElementType.METHOD));
    }
}
