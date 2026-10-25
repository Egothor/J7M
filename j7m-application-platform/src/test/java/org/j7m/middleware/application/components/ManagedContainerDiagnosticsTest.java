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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

/**
 * Actual graph validation and construction failures carry bounded redacted structured evidence.
 */
class ManagedContainerDiagnosticsTest {
    static class Root { } static class Leaf { } static class OtherLeaf extends Leaf { }
    private static ManagedDependency edge(Class<?> type) { return new ManagedDependency(type, "", List.of(), false, Map.of()); }
    private static <T> ManagedComponentDefinition<T> definition(Class<T> type, Scope.Kind scope, List<ManagedDependency> dependencies, java.util.concurrent.Callable<T> factory) {
        return new ManagedComponentDefinition<>() {
            public Class<T> type() { return type; }
            public String name() { return ""; }
            public List<ManagedQualifier> qualifiers() { return List.of(); }
            public Scope.Kind scope() { return scope; }
            public List<ManagedDependency> dependencies() { return dependencies; }
            public T create(ManagedResolver resolver) throws Exception { return factory.call(); }
        };
    }
    private static ManagedContainer activate(ManagedComponentDefinition<?>... definitions) {
        return ManagedContainer.activate(List.of(new ManagedComponentIndex() {
            public List<ManagedComponentDefinition<?>> components() { return List.of(definitions); }
        }));
    }
    private static void diagnostic(ManagedContainer.Failure error, String code, Class<?>... path) {
        assertEquals(code, error.code()); assertEquals(code, error.diagnostic().reasonCode());
        assertEquals("FAILED", error.diagnostic().result()); assertEquals("resolve", error.diagnostic().action());
        assertEquals(path[0].getName(), error.diagnostic().component());
        assertEquals(String.join(" -> ", Arrays.stream(path).map(Class::getName).toList()), error.diagnostic().metadata().get("graphPath"));
        assertThrows(UnsupportedOperationException.class, () -> error.diagnostic().metadata().put("secret", "value"));
    }
    @Test void missingAndAmbiguousEdgesReportOwnerAndRequestedTypeBeforeConstruction() {
        var constructors = new AtomicInteger();
        var root = definition(Root.class, Scope.Kind.ACTIVATION, List.of(edge(Leaf.class)), () -> { constructors.incrementAndGet(); return new Root(); });
        diagnostic(assertThrows(ManagedContainer.Failure.class, () -> activate(root)), "MISSING_DEPENDENCY", Root.class, Leaf.class);
        diagnostic(assertThrows(ManagedContainer.Failure.class, () -> activate(root,
                definition(Leaf.class, Scope.Kind.ACTIVATION, List.of(), Leaf::new), definition(OtherLeaf.class, Scope.Kind.ACTIVATION, List.of(), OtherLeaf::new))),
                "AMBIGUOUS_DEPENDENCY", Root.class, Leaf.class);
        assertEquals(0, constructors.get());
    }
    @Test void cycleAndScopeCaptureRetainOrderedPaths() {
        var root = definition(Root.class, Scope.Kind.ACTIVATION, List.of(edge(Leaf.class)), Root::new);
        diagnostic(assertThrows(ManagedContainer.Failure.class, () -> activate(root,
                definition(Leaf.class, Scope.Kind.ACTIVATION, List.of(edge(Root.class)), Leaf::new))), "DEPENDENCY_CYCLE", Root.class, Leaf.class, Root.class);
        diagnostic(assertThrows(ManagedContainer.Failure.class, () -> activate(root,
                definition(Leaf.class, Scope.Kind.CALL, List.of(), Leaf::new))), "SCOPE_CAPTURE", Root.class, Leaf.class);
    }
    @Test void constructionFailureContainsClassPathWithoutApplicationMessageOrCause() {
        var error = assertThrows(ManagedContainer.Failure.class, () -> activate(definition(Root.class, Scope.Kind.ACTIVATION, List.of(),
                () -> { throw new Exception("password=should-never-appear"); })));
        diagnostic(error, "CONSTRUCTION_FAILED", Root.class); assertNull(error.getCause());
        assertFalse(error.diagnostic().toString().contains("password")); assertFalse(error.toString().contains("password"));
    }
    @Test void nestedConstructionPreservesTheActualPathWithoutCallingApplicationExceptionHooks() {
        class PrivateFailure extends Exception {
            @Override public String getMessage() { throw new AssertionError("Application message hook must not run"); }
            @Override public String toString() { throw new AssertionError("Application rendering hook must not run"); }
        }
        var root = new ManagedComponentDefinition<Root>() {
            public Class<Root> type() { return Root.class; }
            public String name() { return ""; }
            public List<ManagedQualifier> qualifiers() { return List.of(); }
            public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
            public List<ManagedDependency> dependencies() { return List.of(edge(Leaf.class)); }
            public Root create(ManagedResolver resolver) { resolver.resolve(Leaf.class, "", List.of()); return new Root(); }
        };
        var failure = assertThrows(ManagedContainer.Failure.class, () -> activate(root,
                definition(Leaf.class, Scope.Kind.ACTIVATION, List.of(), () -> { throw new PrivateFailure(); })));
        diagnostic(failure, "CONSTRUCTION_FAILED", Root.class, Leaf.class);
        var boundary = ManagedInjectionException.from(failure);
        assertEquals(failure.diagnostic(), boundary.diagnostic()); assertNull(boundary.getCause());
        assertEquals("CONSTRUCTION_FAILED", boundary.remoteError().details().get("reasonCode"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void longCyclicGraphsTruncateAtExactBoundsAndNeverRetainBindingNames() {
        Class<?> array = Root.class;
        for (int i = 0; i < 200; i++) array = array.arrayType();
        final Class<?> type = array;
        var definitions = new ArrayList<ManagedComponentDefinition<?>>();
        for (int i = 0; i < 40; i++) {
            String name = "private-binding-credential-" + i, next = "private-binding-credential-" + ((i + 1) % 40);
            definitions.add(new ManagedComponentDefinition<Object>() {
                public Class<Object> type() { return (Class) type; }
                public String name() { return name; }
                public List<ManagedQualifier> qualifiers() { return List.of(); }
                public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
                public List<ManagedDependency> dependencies() { return List.of(new ManagedDependency(type, next, List.of(), false, Map.of())); }
                public Object create(ManagedResolver resolver) { throw new AssertionError("Graph must fail before construction"); }
            });
        }
        var failure = assertThrows(ManagedContainer.Failure.class, () -> activate(definitions.toArray(ManagedComponentDefinition<?>[]::new)));
        assertEquals("DEPENDENCY_CYCLE", failure.code()); assertEquals(256, failure.diagnostic().component().length());
        String path = failure.diagnostic().metadata().get("graphPath"); assertEquals(4096, path.length());
        assertTrue(path.split(" -> ").length <= 32); assertFalse(failure.diagnostic().toString().contains("private-binding"));
        var preflight = ManagedInjectionException.preflight(Root.class, ManagedInjectionException.Reason.JPMS_ACCESS);
        assertEquals(Root.class.getName(), preflight.diagnostic().component()); assertNull(preflight.getCause());
    }
}
