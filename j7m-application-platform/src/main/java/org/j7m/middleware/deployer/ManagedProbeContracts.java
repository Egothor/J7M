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

package org.j7m.middleware.deployer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.j7m.middleware.annotations.HealthCheck;
import org.j7m.middleware.annotations.ReadinessCheck;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedHostAdapter;
import org.j7m.middleware.application.api.ManagedProbe;
import org.j7m.middleware.application.api.ManagedProbeKind;

/**
 * Validates finite generated probe metadata even when javac processing was bypassed.
 */
final class ManagedProbeContracts {
    private ManagedProbeContracts() { }

    static List<ManagedProbe> validate(final ManagedComponentDefinition<?> definition) {
        Set<ManagedProbe> expected = new LinkedHashSet<>();
        collect(definition.type(), definition.type(), new HashSet<>(), expected);
        if (!(definition instanceof ManagedHostAdapter<?> adapter)) {
            if (!expected.isEmpty()) throw new IllegalArgumentException("managed probe adapter is missing");
            return List.of();
        }
        List<ManagedProbe> actual = List.copyOf(adapter.probes());
        if (actual.size() > 64 || !new HashSet<>(actual).equals(expected) || new HashSet<>(actual).size() != actual.size()) {
            throw new IllegalArgumentException("managed probe metadata mismatch");
        }
        if (!actual.isEmpty() && definition.scope() != Scope.Kind.ACTIVATION) {
            throw new IllegalArgumentException("managed probes require activation scope");
        }
        return actual;
    }

    private static void collect(final Class<?> root, final Class<?> type, final Set<Class<?>> visited, final Set<ManagedProbe> expected) {
        if (type == null || type == Object.class || !visited.add(type)) return;
        if (visited.size() > 256) throw new IllegalArgumentException("managed probe hierarchy capacity");
        for (Method method : type.getDeclaredMethods()) {
            boolean health = method.isAnnotationPresent(HealthCheck.class);
            boolean readiness = method.isAnnotationPresent(ReadinessCheck.class);
            if (!health && !readiness) continue;
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0 || method.getReturnType() != boolean.class) {
                throw new IllegalArgumentException("invalid managed probe signature");
            }
            try {
                Method effective = root.getMethod(method.getName());
                if (health) expected.add(new ManagedProbe(ManagedProbeKind.HEALTH, ManagedRuntimeContracts.identity(effective)));
                if (readiness) expected.add(new ManagedProbe(ManagedProbeKind.READINESS, ManagedRuntimeContracts.identity(effective)));
            } catch (NoSuchMethodException failure) { throw new IllegalArgumentException("managed probe method is missing"); }
        }
        for (Class<?> contract : type.getInterfaces()) collect(root, contract, visited, expected);
        collect(root, type.getSuperclass(), visited, expected);
    }
}
