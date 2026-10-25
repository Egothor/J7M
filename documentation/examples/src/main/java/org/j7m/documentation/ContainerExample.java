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

package org.j7m.documentation;

import java.util.List;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedDependency;
import org.j7m.middleware.application.api.ManagedLifecycle;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.application.api.ManagedResolver;
import org.j7m.middleware.application.components.ManagedContainer;

/**
 * Compares real generated injection/lifecycle adapters with explicit component definitions.
 */
public final class ContainerExample {
    private ContainerExample() { }

    /**
     * Runs the annotated and explicit variants and checks their observable results.
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        try (var container = ManagedContainer.activate(List.of(
                new GreetingFormatter__J7mComponentIndex(), new GreetingComponent__J7mComponentIndex()))) {
            var greeting = container.get(GreetingComponent.class, "", List.of());
            expectStopped(greeting);
            container.lifecycle(ManagedLifecycle.STARTUP);
            require("Hello, Ada".equals(greeting.greet("Ada")), "Generated injection did not resolve formatter");
            require(greeting == container.get(GreetingComponent.class, "", List.of()), "Activation scope changed identity");
            container.lifecycle(ManagedLifecycle.DRAIN);
            container.lifecycle(ManagedLifecycle.SHUTDOWN);
            expectStopped(greeting);
        }

        var formatter = definition(PlainFormatter.class, List.of(), resolver -> new PlainFormatter());
        var dependency = new ManagedDependency(PlainFormatter.class, "", List.of(), false);
        var greeting = definition(PlainGreeting.class, List.of(dependency), resolver -> {
            var product = new PlainGreeting(resolver.resolve(PlainFormatter.class, "", List.of()));
            product.start();
            return product;
        });
        PlainGreeting product;
        try (var container = ManagedContainer.activate(List.of(() -> List.of(formatter, greeting)))) {
            product = container.get(PlainGreeting.class, "", List.of());
            require("Hello, Ada".equals(product.greet("Ada")), "Explicit graph produced a different result");
        }
        require(product.stopped, "Owned AutoCloseable product was not disposed");
        try {
            ManagedContainer.activate(List.of(() -> List.of(greeting))).close();
            throw new AssertionError("Missing dependency was accepted");
        } catch (ManagedContainer.Failure failure) {
            require("MISSING_DEPENDENCY".equals(failure.code()), "Unexpected graph failure");
        }
        System.out.println("ContainerExample OK: generated and explicit graphs, lifecycle, missing dependency");
    }

    private static void expectStopped(GreetingComponent component) {
        try { component.greet("Ada"); throw new AssertionError("Stopped component admitted a call"); }
        catch (IllegalStateException expected) { require(expected.getMessage().equals("Component is not started"), "Wrong failure"); }
    }

    private interface Factory<T> { T create(ManagedResolver resolver); }
    private static <T> ManagedComponentDefinition<T> definition(Class<T> type, List<ManagedDependency> dependencies, Factory<T> factory) {
        return new ManagedComponentDefinition<>() {
            @Override public Class<T> type() { return type; }
            @Override public String name() { return ""; }
            @Override public List<ManagedQualifier> qualifiers() { return List.of(); }
            @Override public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
            @Override public List<ManagedDependency> dependencies() { return dependencies; }
            @Override public T create(ManagedResolver resolver) { return factory.create(resolver); }
        };
    }
    private static final class PlainFormatter {
        String format(String name) { return "Hello, " + name; }
    }
    private static final class PlainGreeting implements AutoCloseable {
        private final PlainFormatter formatter;
        private boolean stopped = true;
        PlainGreeting(PlainFormatter formatter) { this.formatter = formatter; }
        void start() { stopped = false; }
        String greet(String name) {
            if (stopped) throw new IllegalStateException("Component is not started");
            return formatter.format(name);
        }
        @Override public void close() { stopped = true; }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
