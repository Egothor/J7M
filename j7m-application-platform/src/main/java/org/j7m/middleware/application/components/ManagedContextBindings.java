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

import java.util.Objects;
import java.util.Set;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.ManagedDependency;
import org.j7m.middleware.security.api.Principal;

/**
 * Trusted host factory for safe current context views; application metadata cannot register it.
 */
public final class ManagedContextBindings {
    private ManagedContextBindings() { }

    /**
     * Grants safe context bindings for one application activation.
     * Principal/invocation providers read the dispatch thread's current immutable snapshot each time;
     * direct captures are permitted only in a CALL scope. The activation ApplicationContext is borrowed.
     * @param application safe activation context, never null
     * @return thread-safe host capability for primary attribute context.kind
     */
    public static ManagedHostBindings.Factory factory(final ApplicationContext application) {
        Objects.requireNonNull(application, "application");
        return new ManagedHostBindings.Factory() {
            @Override
            public void validate(final ManagedDependency dependency, final Scope.Kind scope) {
                if (!dependency.attributes().keySet().equals(Set.of("context.kind")) || !dependency.name().isEmpty()
                        || !dependency.qualifiers().isEmpty()) { throw new IllegalArgumentException("invalid context binding"); }
                String kind = dependency.attributes().get("context.kind");
                Class<?> expected = switch (kind) {
                    case "principal" -> Principal.class;
                    case "invocation" -> InvocationContext.class;
                    case "application" -> ApplicationContext.class;
                    default -> throw new IllegalArgumentException("unsupported context binding");
                };
                if (dependency.type() != expected || (!"application".equals(kind) && !dependency.provider() && scope != Scope.Kind.CALL)) {
                    throw new IllegalArgumentException("unsafe context capture");
                }
            }

            @Override
            public ManagedHostBindings.BoundValue bind(final ManagedDependency dependency) {
                return new ManagedHostBindings.BoundValue() {
                    private volatile boolean closed;
                    @Override public Object get() {
                        if (closed) { throw new IllegalStateException("context binding closed"); }
                        return switch (dependency.attributes().get("context.kind")) {
                            case "principal" -> J7mContext.currentInvocation().security().principal();
                            case "invocation" -> J7mContext.currentInvocation();
                            case "application" -> application;
                            default -> throw new IllegalStateException("unsupported context binding");
                        };
                    }
                    @Override public void close() { closed = true; }
                };
            }
        };
    }
}
