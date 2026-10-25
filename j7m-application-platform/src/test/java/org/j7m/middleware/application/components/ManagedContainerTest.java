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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

class ManagedContainerTest {
    @FunctionalInterface interface Maker<T> { T make(ManagedResolver resolver) throws Exception; }
    static final class Value implements AutoCloseable {
        final String id; final List<String> closed; final boolean broken;
        Value(String id, List<String> closed) { this(id, closed, false); }
        Value(String id, List<String> closed, boolean broken) { this.id = id; this.closed = closed; this.broken = broken; }
        @Override public void close() { closed.add(id); if (broken) throw new IllegalStateException("secret disposer detail"); }
    }
    record Definition<T>(Class<T> type, String name, Scope.Kind scope, boolean owned,
            List<ManagedDependency> dependencies, List<ManagedQualifier> qualifiers, Maker<T> maker) implements ManagedComponentDefinition<T> {
        @Override public T create(ManagedResolver resolver) throws Exception { return maker.make(resolver); }
    }
    static <T> Definition<T> definition(Class<T> type, String name, Scope.Kind scope, Maker<T> maker, ManagedDependency... deps) {
        return new Definition<>(type, name, scope, true, List.of(deps), List.of(), maker);
    }
    static ManagedDependency edge(Class<?> type, String name, boolean provider) { return new ManagedDependency(type, name, List.of(), provider); }
    static ManagedComponentIndex index(ManagedComponentDefinition<?>... definitions) { return () -> List.of(definitions); }
    static ManagedContainer activate(ManagedComponentDefinition<?>... definitions) { return ManagedContainer.activate(List.of(index(definitions))); }
    static void code(String expected, Runnable action) { assertEquals(expected, assertThrows(ManagedContainer.Failure.class, action::run).code()); }

    @Test void retainedIdleCallCannotBeClosedOrShutDownAndRetentionTokensAreBoundedAndIdempotent() throws Exception {
        var closed = new ArrayList<String>();
        var value = definition(Value.class, "call", Scope.Kind.CALL, resolver -> new Value("call", closed));
        try (var container = ManagedContainer.activate(List.of(index(value)), 1); var call = container.openCall()) {
            call.get(Value.class, "call", List.of());
            var retention = call.retain();
            code("CALL_RETENTION_CAPACITY", () -> call.retain());
            code("CALL_BUSY", call::close);
            code("CALL_BUSY", () -> container.lifecycle(ManagedLifecycle.SHUTDOWN));
            assertTrue(closed.isEmpty());
            retention.close(); retention.close();
            call.close(); call.close();
            assertEquals(List.of("call"), closed);
            code("CALL_CLOSED", () -> call.retain());
        }
    }

    @Test void constructorThenFieldInjectionPublishesOnlyCompleteProduct() {
        var closed = new ArrayList<String>(); var dependency = definition(Value.class, "dependency", Scope.Kind.ACTIVATION, r -> new Value("dependency", closed));
        var fields = new ArrayList<String>();
        var product = new ManagedComponentDefinition<Value>() {
            @Override public Class<Value> type() { return Value.class; }
            @Override public String name() { return "product"; }
            @Override public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
            @Override public List<ManagedQualifier> qualifiers() { return List.of(); }
            @Override public List<ManagedDependency> dependencies() { return List.of(edge(Value.class, "dependency", false)); }
            @Override public Value create(ManagedResolver r) { fields.add("constructor:" + r.resolve(Value.class, "dependency", List.of()).id); return new Value("product", closed); }
            @Override public void inject(Value instance, ManagedResolver r) { fields.add("field:" + r.resolve(Value.class, "dependency", List.of()).id); }
        };
        assertTrue(product.owned());
        try (var container = activate(product, dependency)) {
            assertEquals(List.of("constructor:dependency", "field:dependency"), fields);
            assertSame(container.get(Value.class, "product", List.of()), container.get(Value.class, "product", List.of()));
            assertEquals(2, container.status().created());
        }
        assertEquals(List.of("product", "dependency"), closed);
    }
    @Test void missingAmbiguousLazyCyclesAndScopeCaptureFailBeforeAnyConstructor() {
        var effects = new AtomicInteger();
        var bad = definition(Object.class, "root", Scope.Kind.ACTIVATION, r -> { effects.incrementAndGet(); return new Object(); }, edge(String.class, "missing", false));
        code("MISSING_DEPENDENCY", () -> activate(bad));
        var a = definition(String.class, "", Scope.Kind.PROTOTYPE, r -> "a");
        var b = definition(StringBuilder.class, "", Scope.Kind.PROTOTYPE, r -> new StringBuilder());
        var ambiguous = definition(Object.class, "root", Scope.Kind.ACTIVATION, r -> { effects.incrementAndGet(); return new Object(); }, edge(CharSequence.class, "", false));
        code("AMBIGUOUS_DEPENDENCY", () -> activate(a, b, ambiguous));
        var cycle = definition(Object.class, "cycle", Scope.Kind.ACTIVATION, r -> { effects.incrementAndGet(); return new Object(); }, edge(Object.class, "cycle", true));
        code("DEPENDENCY_CYCLE", () -> activate(cycle));
        var call = definition(String.class, "call", Scope.Kind.CALL, r -> "request");
        var proto = definition(Object.class, "proto", Scope.Kind.PROTOTYPE, r -> new Object(), edge(String.class, "call", false));
        var leak = definition(Object.class, "activation", Scope.Kind.ACTIVATION, r -> { effects.incrementAndGet(); return new Object(); }, edge(Object.class, "proto", false));
        code("SCOPE_CAPTURE", () -> activate(leak, proto, call)); assertEquals(0, effects.get());
    }
    @Test void strictNamesAndFullQualifierValuesNeverFallBack() {
        var red = new ManagedQualifier("app.Color", Map.of("value", ManagedQualifier.value("red")));
        var blue = new ManagedQualifier("app.Color", Map.of("value", ManagedQualifier.value("blue")));
        var first = new Definition<>(String.class, "", Scope.Kind.ACTIVATION, false, List.of(), List.of(red), r -> "red");
        var second = new Definition<>(String.class, "", Scope.Kind.ACTIVATION, false, List.of(), List.of(blue), r -> "blue");
        try (var container = activate(first, second)) {
            assertEquals("red", container.get(CharSequence.class, "", List.of(red)));
            assertEquals("blue", container.get(String.class, "", List.of(blue)));
            code("MISSING_DEPENDENCY", () -> container.get(String.class, "unknown", List.of(red)));
            code("MISSING_DEPENDENCY", () -> container.get(String.class, "", List.of()));
        }
    }
    record Holder(ManagedProvider<Value> provider) { }
    @Test void providerFromActivationResolvesCurrentCallAndNeverRevivesClosedScope() throws Exception {
        var closed = new ArrayList<String>(); var ids = new AtomicInteger();
        var call = definition(Value.class, "call", Scope.Kind.CALL, r -> new Value("call-" + ids.incrementAndGet(), closed));
        var holder = definition(Holder.class, "", Scope.Kind.ACTIVATION, r -> new Holder(r.provider(Value.class, "call", List.of())), edge(Value.class, "call", true));
        var container = activate(holder, call); Holder instance = container.get(Holder.class, "", List.of());
        code("NO_ACTIVE_CALL", instance.provider::get); assertFalse(container.status().closed());
        Value first;
        try (var scope = container.openCall()) { first = scope.call(instance.provider::get); assertSame(first, scope.call(instance.provider::get)); }
        try (var scope = container.openCall()) { assertNotSame(first, scope.call(instance.provider::get)); }
        assertEquals(List.of("call-1", "call-2"), closed); container.close(); code("CONTAINER_CLOSED", instance.provider::get);
    }
    @Test void concurrentCallsAreSeparateAndBusyCloseDoesNotLoseEventualCleanup() throws Exception {
        var closed = Collections.synchronizedList(new ArrayList<String>()); var ids = new AtomicInteger();
        try (var container = activate(definition(Value.class, "", Scope.Kind.CALL, r -> new Value("call-" + ids.incrementAndGet(), closed))); var pool = Executors.newFixedThreadPool(2)) {
            var left = container.openCall(); var right = container.openCall(); var barrier = new CyclicBarrier(2); var release = new CountDownLatch(1);
            Callable<Value> one = () -> left.call(() -> { Value value = left.get(Value.class, "", List.of()); barrier.await(5, TimeUnit.SECONDS); release.await(5, TimeUnit.SECONDS); return value; });
            Future<Value> a = pool.submit(one); Future<Value> b = pool.submit(() -> right.call(() -> { Value value = right.get(Value.class, "", List.of()); barrier.await(5, TimeUnit.SECONDS); return value; }));
            Value other = b.get(5, TimeUnit.SECONDS); code("CALL_BUSY", left::close); code("CALL_BUSY", container::close);
            release.countDown(); assertNotSame(other, a.get(5, TimeUnit.SECONDS)); left.close(); right.close(); assertEquals(2, closed.size()); assertEquals(0, container.status().activeCalls());
        }
    }
    @Test void prototypeOwnershipAndBorrowedOwnedAliasesCloseExactlyOnce() {
        var closed = new ArrayList<String>(); var shared = new Value("shared", closed); var borrowed = new Value("borrowed", closed);
        var own1 = definition(Value.class, "one", Scope.Kind.ACTIVATION, r -> shared);
        var own2 = definition(Value.class, "two", Scope.Kind.ACTIVATION, r -> shared);
        var alias = new Definition<>(Value.class, "alias", Scope.Kind.ACTIVATION, false, List.of(), List.of(), r -> shared);
        var external = new Definition<>(Value.class, "borrowed", Scope.Kind.ACTIVATION, false, List.of(), List.of(), r -> borrowed);
        var prototype = definition(Value.class, "prototype", Scope.Kind.PROTOTYPE, r -> new Value("prototype", closed));
        var container = activate(own1, own2, alias, external, prototype);
        try (var call = container.openCall()) { assertNotSame(call.get(Value.class, "prototype", List.of()), call.get(Value.class, "prototype", List.of())); }
        assertEquals(List.of("prototype", "prototype"), closed); container.close(); container.close();
        assertEquals(List.of("prototype", "prototype", "shared"), closed);
    }
    @Test void failedFactoryAndFailedDisposerCleanRemainingOwnedValuesOnceWithoutSecretMessages() {
        var closed = new ArrayList<String>();
        var receiver = definition(Value.class, "receiver", Scope.Kind.ACTIVATION, r -> new Value("receiver", closed, true));
        var dependency = definition(Value.class, "dependency", Scope.Kind.ACTIVATION, r -> new Value("dependency", closed));
        var failed = definition(Object.class, "failed", Scope.Kind.ACTIVATION, r -> { r.resolve(Value.class, "receiver", List.of()); r.resolve(Value.class, "dependency", List.of()); throw new Exception("secret constructor detail"); }, edge(Value.class, "receiver", false), edge(Value.class, "dependency", false));
        var error = assertThrows(ManagedContainer.Failure.class, () -> activate(failed, receiver, dependency));
        assertEquals("CONSTRUCTION_FAILED", error.code()); assertEquals(List.of("dependency", "receiver"), closed);
        assertFalse(error.toString().contains("secret")); assertEquals(1, error.getSuppressed().length);
    }
    @Test void explicitCloseFailureStillClosesAllOtherProductsAndCannotRepeat() {
        var closed = new ArrayList<String>(); var a = definition(Value.class, "a", Scope.Kind.ACTIVATION, r -> new Value("a", closed));
        var b = definition(Value.class, "b", Scope.Kind.ACTIVATION, r -> new Value("b", closed, true));
        var container = activate(a, b); code("DISPOSAL_FAILED", container::close); assertEquals(List.of("b", "a"), closed);
        container.close(); assertEquals(2, closed.size()); assertTrue(container.status().closed()); assertEquals(1, container.status().failures());
    }
    @Test void metadataVersionCapacityAndUndeclaredEdgesFailClosed() {
        var created = new AtomicInteger(); var def = definition(Object.class, "", Scope.Kind.ACTIVATION, r -> { created.incrementAndGet(); return new Object(); });
        ManagedComponentIndex future = new ManagedComponentIndex() { @Override public int formatVersion() { return 2; } @Override public List<ManagedComponentDefinition<?>> components() { return List.of(def); } };
        code("UNSUPPORTED_INDEX_FORMAT", () -> ManagedContainer.activate(List.of(future))); assertEquals(0, created.get());
        code("DUPLICATE_BINDING", () -> activate(def, def)); code("INVALID_CAPACITY", () -> ManagedContainer.activate(List.of(index()), 0));
        var undeclared = definition(Object.class, "bad", Scope.Kind.ACTIVATION, r -> r.resolve(String.class, "", List.of()));
        code("UNDECLARED_DEPENDENCY", () -> activate(undeclared, definition(String.class, "", Scope.Kind.ACTIVATION, r -> "value")));
        var metadata = definition(Object.class, "metadata", Scope.Kind.ACTIVATION, r -> new Object(), new ManagedDependency(String.class, "", List.of(), false, Map.of("routing.refresh", "ON_FAILURE")));
        code("UNSUPPORTED_DEPENDENCY_METADATA", () -> activate(metadata));
        var closed = new ArrayList<String>(); var owned = definition(Value.class, "one", Scope.Kind.ACTIVATION, r -> new Value("one", closed));
        var extra = definition(Value.class, "extra", Scope.Kind.PROTOTYPE, r -> new Value("extra", closed));
        var container = ManagedContainer.activate(List.of(index(owned, extra)), 1);
        code("INSTANCE_CAPACITY", () -> container.get(Value.class, "extra", List.of())); assertEquals(List.of("extra", "one"), closed); container.close();
    }
}
