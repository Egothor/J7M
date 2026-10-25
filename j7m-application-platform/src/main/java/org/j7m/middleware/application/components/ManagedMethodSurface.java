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

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.j7m.middleware.application.api.ManagedServiceContract;

/**
 * Resolves one trusted public contract's effective declaration lineage without loading wire names.
 */
final class ManagedMethodSurface {
    private record HostedSurface(Class<?> component, Set<ManagedServiceContract> contracts) { }
    private static final ThreadLocal<HostedSurface> HOSTED = new ThreadLocal<>();
    private ManagedMethodSurface() { }

    static boolean isHosted(final ManagedServiceContract contract, final Class<?> component) {
        HostedSurface hosted = HOSTED.get();
        return hosted != null && hosted.component() == component && hosted.contracts().contains(contract);
    }

    static <T> T hosted(final Class<?> component, final Set<ManagedServiceContract> contracts,
            final java.util.concurrent.Callable<T> work) throws Exception {
        if (component.isInterface() || contracts.isEmpty() || contracts.size() > 4096) {
            throw new IllegalArgumentException("Invalid hosted method surface");
        }
        for (var contract : contracts) {
            if (Arrays.stream(component.getMethods()).noneMatch(method -> matches(method, contract)
                    && Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))) {
                throw new IllegalArgumentException("Hosted callback is not public");
            }
        }
        HostedSurface previous = HOSTED.get(); HOSTED.set(new HostedSurface(component, Set.copyOf(contracts)));
        try { return work.call(); }
        finally { if (previous == null) HOSTED.remove(); else HOSTED.set(previous); }
    }

    static Method method(final ManagedServiceContract contract, final Class<?> businessInterface) {
        Objects.requireNonNull(contract);
        boolean concreteHosted = isHosted(contract, businessInterface);
        if (businessInterface == null || !businessInterface.isInterface() && !concreteHosted) {
            throw new IllegalArgumentException("Invalid managed method interface");
        }
        final Method exposed = Arrays.stream(businessInterface.getMethods()).filter(method -> matches(method, contract))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Managed method is not exposed"));
        if (Modifier.isStatic(exposed.getModifiers()) || !Modifier.isPublic(exposed.getModifiers())) {
            throw new IllegalArgumentException("Managed method is not an instance entry");
        }
        return exposed;
    }

    static List<AnnotatedElement> declarations(final ManagedServiceContract contract,
            final Class<?> implementation, final Class<?> businessInterface) {
        if (implementation == null || businessInterface == null || !businessInterface.isAssignableFrom(implementation)) {
            throw new IllegalArgumentException("Invalid managed method implementation");
        }
        final Method exposed = method(contract, businessInterface);
        final List<AnnotatedElement> declarations = new ArrayList<>();
        for (Class<?> type : types(implementation)) {
            if (!type.isInterface() || Arrays.stream(type.getMethods()).anyMatch(method -> sameParameters(method, exposed))) {
                declarations.add(type);
                for (Method method : type.getDeclaredMethods()) {
                    // An erased bridge carries the implementation's annotations; a same-named
                    // private helper or static method is not an implementation override.
                    if (!Modifier.isPrivate(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                            && sameParameters(method, exposed)) {
                        declarations.add(method);
                    }
                }
            }
        }
        return List.copyOf(declarations);
    }

    static void validateExposure(final Class<?> component,
            final java.util.Map<ManagedServiceContract, Class<?>> exports,
            final Set<Class<? extends java.lang.annotation.Annotation>> claimed) {
        Objects.requireNonNull(component); Objects.requireNonNull(exports); Objects.requireNonNull(claimed);
        if (exports.size() > 4096) throw new IllegalArgumentException("Managed export policy capacity");
        final Set<AnnotatedElement> covered = new LinkedHashSet<>();
        for (var entry : exports.entrySet()) {
            for (AnnotatedElement declaration : declarations(entry.getKey(), component, entry.getValue())) {
                covered.add(declaration);
                if (declaration instanceof Method method) covered.addAll(List.of(method.getParameters()));
            }
        }
        for (AnnotatedElement declaration : List.copyOf(covered)) {
            if (declaration instanceof Method bridge && bridge.isBridge()) {
                List<Method> bodies = Arrays.stream(bridge.getDeclaringClass().getDeclaredMethods())
                        .filter(method -> bridgeBody(bridge, method)).toList();
                if (bodies.size() == 1) {
                    Method body = bodies.getFirst();
                    if (annotationsCopied(body, bridge, claimed)) {
                        covered.add(body);
                        for (int index = 0; index < body.getParameterCount(); index++) {
                            if (annotationsCopied(body.getParameters()[index], bridge.getParameters()[index], claimed))
                                covered.add(body.getParameters()[index]);
                        }
                    }
                }
            }
        }
        for (Class<?> type : types(component)) {
            // Non-entry type invariants, such as a host heap ceiling, are validated separately.
            if (!exports.isEmpty()) requireCovered(type, covered, claimed);
            for (Method method : type.getDeclaredMethods()) {
                requireCovered(method, covered, claimed);
                for (var parameter : method.getParameters()) requireCovered(parameter, covered, claimed);
            }
            for (var field : type.getDeclaredFields()) requireCovered(field, covered, claimed);
            for (var constructor : type.getDeclaredConstructors()) {
                requireCovered(constructor, covered, claimed);
                for (var parameter : constructor.getParameters()) requireCovered(parameter, covered, claimed);
            }
        }
    }

    private static boolean bridgeBody(final Method bridge, final Method body) {
        if (body.isBridge() || body.isSynthetic() || Modifier.isPrivate(body.getModifiers()) || Modifier.isStatic(body.getModifiers())
                || !bridge.getName().equals(body.getName()) || bridge.getParameterCount() != body.getParameterCount()
                || !bridge.getReturnType().isAssignableFrom(body.getReturnType())) return false;
        for (int index = 0; index < bridge.getParameterCount(); index++)
            if (!bridge.getParameterTypes()[index].isAssignableFrom(body.getParameterTypes()[index])) return false;
        return true;
    }

    private static boolean annotationsCopied(final AnnotatedElement source, final AnnotatedElement target,
            final Set<Class<? extends java.lang.annotation.Annotation>> claimed) {
        return Arrays.stream(source.getDeclaredAnnotations()).filter(annotation -> claimed.contains(annotation.annotationType()))
                .allMatch(annotation -> annotation.equals(target.getDeclaredAnnotation(annotation.annotationType())));
    }

    private static void requireCovered(final AnnotatedElement site, final Set<AnnotatedElement> covered,
            final Set<Class<? extends java.lang.annotation.Annotation>> claimed) {
        if (!covered.contains(site) && Arrays.stream(site.getDeclaredAnnotations())
                .anyMatch(annotation -> claimed.contains(annotation.annotationType()))) {
            throw new IllegalArgumentException("Managed policy site has no supported actual export boundary");
        }
    }

    static List<Class<?>> types(final Class<?> implementation) {
        Objects.requireNonNull(implementation);
        final Set<Class<?>> types = new LinkedHashSet<>();
        hierarchy(implementation, types);
        return List.copyOf(types);
    }

    private static boolean matches(final Method method, final ManagedServiceContract contract) {
        return method.getDeclaringClass().getName().equals(contract.method().declaringType())
                && method.getName().equals(contract.method().name())
                && method.getReturnType().getName().equals(contract.method().returnType())
                && Arrays.stream(method.getParameterTypes()).map(Class::getName).toList().equals(contract.method().parameterTypes());
    }

    private static boolean sameParameters(final Method left, final Method right) {
        return left.getName().equals(right.getName()) && Arrays.equals(left.getParameterTypes(), right.getParameterTypes());
    }

    private static void hierarchy(final Class<?> type, final Set<Class<?>> result) {
        if (type == null || type == Object.class || !result.add(type)) { return; }
        if (result.size() > 64) { throw new IllegalArgumentException("Managed method hierarchy is excessive"); }
        hierarchy(type.getSuperclass(), result);
        for (Class<?> contract : type.getInterfaces()) { hierarchy(contract, result); }
    }
}
