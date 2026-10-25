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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.Qualifier;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.application.api.ManagedMethod;

/**
 * Deployment-time validation independent of whether javac ran the installed processor.
 */
final class ManagedRuntimeContracts {
    private static final String PREFIX = "org.j7m.middleware.annotations.";
    private static final Set<String> SUPPORTED = Set.of("J7mComponent", "J7mApplication", "J7mService", "Inject", "Named",
            "J7mResource", "Scope", "Produces", "ConfigValue", "ConfigProperties", "CurrentApplication", "CurrentPrincipal",
            "CurrentInvocation", "Initialize", "Startup", "Drain", "Shutdown", "RemoteMethod", "RemoteService", "LocalOnly", "NoRemoteExport", "HealthCheck", "ReadinessCheck");

    private ManagedRuntimeContracts() { }

    static ManagedMethod identity(final Method method) {
        return new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
    }

    static void validateHostedCoverage(final Class<?> component,
            final java.util.Map<org.j7m.middleware.application.api.ManagedHostedFeature, Set<Class<? extends Annotation>>> features,
            final Set<Class<? extends Annotation>> hostedCapabilities) {
        for (Class<?> type : hierarchy(component)) {
            if (type.getName().startsWith("java.")) continue;
            for (Annotation annotation : type.getDeclaredAnnotations()) {
                if (!hostedCapabilities.contains(annotation.annotationType())) continue;
                Annotation selected = component.getDeclaredAnnotation(annotation.annotationType());
                if (selected == null || !selected.equals(annotation) || features.values().stream()
                        .noneMatch(capabilities -> capabilities.contains(annotation.annotationType()))) {
                    throw new IllegalArgumentException("Hosted type metadata has no exact feature");
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                for (Annotation annotation : method.getDeclaredAnnotations()) {
                    if (hostedCapabilities.contains(annotation.annotationType())) requireHostedMethod(component, method, -1, annotation, features);
                }
                for (int index = 0; index < method.getParameterCount(); index++) {
                    for (Annotation annotation : method.getParameters()[index].getDeclaredAnnotations()) {
                        if (hostedCapabilities.contains(annotation.annotationType())) requireHostedMethod(component, method, index, annotation, features);
                    }
                }
            }
            for (var field : type.getDeclaredFields()) rejectHostedSite(field, hostedCapabilities);
            for (var constructor : type.getDeclaredConstructors()) {
                rejectHostedSite(constructor, hostedCapabilities);
                for (var parameter : constructor.getParameters()) rejectHostedSite(parameter, hostedCapabilities);
            }
        }
    }

    private static void rejectHostedSite(final AnnotatedElement site, final Set<Class<? extends Annotation>> capabilities) {
        for (Annotation annotation : site.getDeclaredAnnotations()) {
            if (capabilities.contains(annotation.annotationType())) throw new IllegalArgumentException("Unsupported hosted annotation site");
        }
    }

    private static void requireHostedMethod(final Class<?> component, final Method declaration, final int parameter,
            final Annotation annotation,
            final java.util.Map<org.j7m.middleware.application.api.ManagedHostedFeature, Set<Class<? extends Annotation>>> features) {
        for (var entry : features.entrySet()) {
            if (!entry.getValue().contains(annotation.annotationType())) continue;
            for (Method method : component.getMethods()) {
                if (!entry.getKey().methods().contains(identity(method)) || !method.getName().equals(declaration.getName())
                        || !java.util.Arrays.equals(method.getParameterTypes(), declaration.getParameterTypes())
                        || !declaration.getReturnType().isAssignableFrom(method.getReturnType())
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                Annotation selected = parameter < 0 ? method.getDeclaredAnnotation(annotation.annotationType())
                        : method.getParameters()[parameter].getDeclaredAnnotation(annotation.annotationType());
                if (annotation.equals(selected)) return;
            }
        }
        throw new IllegalArgumentException("Hosted method metadata has no exact callback");
    }

    static void validateType(final Class<?> type, final Set<Class<? extends Annotation>> capabilities) {
        for (Class<?> ancestor : hierarchy(type)) {
            if (ancestor.getName().startsWith("java.")) { continue; }
            validateElement(ancestor, capabilities);
            for (Method method : ancestor.getDeclaredMethods()) {
                validateElement(method, capabilities);
                if (method.isAnnotationPresent(RemoteMethod.class) && (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.getTypeParameters().length != 0)) {
                    throw new IllegalArgumentException("invalid managed remote signature");
                }
                int lifecycle = 0;
                for (Class<? extends Annotation> hook : List.of(org.j7m.middleware.annotations.Initialize.class,
                        org.j7m.middleware.annotations.Startup.class, org.j7m.middleware.annotations.Drain.class, org.j7m.middleware.annotations.Shutdown.class)) {
                    if (method.isAnnotationPresent(hook)) lifecycle++;
                }
                if (lifecycle > 1 || lifecycle == 1 && (method.getParameterCount() != 0 || method.getReturnType() != void.class
                        || !java.lang.reflect.Modifier.isPublic(method.getModifiers()) || java.lang.reflect.Modifier.isStatic(method.getModifiers()))) {
                    throw new IllegalArgumentException("invalid managed lifecycle signature");
                }
                for (var parameter : method.getParameters()) { validateElement(parameter, capabilities); }
            }
            for (Constructor<?> constructor : ancestor.getDeclaredConstructors()) {
                validateElement(constructor, capabilities);
                for (var parameter : constructor.getParameters()) { validateElement(parameter, capabilities); }
            }
            for (var field : ancestor.getDeclaredFields()) {
                validateElement(field, capabilities);
                boolean injected = field.isAnnotationPresent(org.j7m.middleware.annotations.ServiceRef.class)
                        || field.isAnnotationPresent(org.j7m.middleware.annotations.J7mResource.class)
                        || field.isAnnotationPresent(org.j7m.middleware.annotations.Inject.class)
                        || field.isAnnotationPresent(org.j7m.middleware.annotations.ConfigValue.class)
                        || field.isAnnotationPresent(org.j7m.middleware.annotations.CurrentApplication.class)
                        || field.isAnnotationPresent(org.j7m.middleware.annotations.CurrentPrincipal.class)
                        || field.isAnnotationPresent(org.j7m.middleware.annotations.CurrentInvocation.class);
                if (injected && (!java.lang.reflect.Modifier.isPublic(field.getModifiers())
                        || java.lang.reflect.Modifier.isFinal(field.getModifiers()) || java.lang.reflect.Modifier.isStatic(field.getModifiers()))) {
                    throw new IllegalArgumentException("invalid managed injection field");
                }
            }
        }
    }

    private static void validateElement(final AnnotatedElement element, final Set<Class<? extends Annotation>> capabilities) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            Class<? extends Annotation> type = annotation.annotationType();
            if (type.getName().startsWith("jakarta.inject.")) { throw new IllegalArgumentException("unsupported Jakarta injection"); }
            if (type.getName().startsWith(PREFIX)) {
                if (!SUPPORTED.contains(type.getName().substring(PREFIX.length())) && !capabilities.contains(type)) {
                    throw new IllegalArgumentException("unsupported managed annotation");
                }
            } else if (!capabilities.contains(type) && unsupportedMeta(type, new HashSet<>(), 0)) {
                throw new IllegalArgumentException("unsupported composed managed annotation");
            }
        }
    }

    private static boolean unsupportedMeta(final Class<? extends Annotation> annotation, final Set<Class<?>> path, final int depth) {
        if (depth > 8 || !path.add(annotation)) { throw new IllegalArgumentException("managed metadata graph limit"); }
        try {
            for (Annotation meta : annotation.getDeclaredAnnotations()) {
                Class<? extends Annotation> type = meta.annotationType();
                if (type == Qualifier.class || type.getName().startsWith("java.lang.annotation.")) { continue; }
                if (type.getName().startsWith(PREFIX) || unsupportedMeta(type, path, depth + 1)) { return true; }
            }
            return false;
        } finally { path.remove(annotation); }
    }

    static void validateScope(final Class<?> type, final org.j7m.middleware.annotations.Scope.Kind selected) {
        for (Class<?> ancestor : hierarchy(type)) {
            var scope = ancestor.getAnnotation(org.j7m.middleware.annotations.Scope.class);
            if (scope != null && scope.value() != selected) { throw new IllegalArgumentException("conflicting managed scope"); }
        }
    }

    static boolean allowed(final Class<?> implementation, final Method requested) {
        boolean explicit = false;
        boolean local = false;
        boolean async = false;
        List<Method> lineage = new java.util.ArrayList<>();
        for (Class<?> type : hierarchy(implementation)) {
            if (type.isInterface() && java.util.Arrays.stream(type.getMethods()).noneMatch(method -> method.getName().equals(requested.getName())
                    && java.util.Arrays.equals(method.getParameterTypes(), requested.getParameterTypes()))) continue;
            local |= type.isAnnotationPresent(LocalOnly.class);
            async |= type.isAnnotationPresent(org.j7m.middleware.annotations.Async.class);
            if (type.isAnnotationPresent(NoRemoteExport.class)) { throw new IllegalArgumentException("automatic export forbidden"); }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(requested.getName())
                        && java.util.Arrays.equals(method.getParameterTypes(), requested.getParameterTypes())) {
                    explicit |= method.isAnnotationPresent(RemoteMethod.class);
                    async |= method.isAnnotationPresent(org.j7m.middleware.annotations.Async.class);
                    local |= method.isAnnotationPresent(LocalOnly.class);
                    lineage.add(method);
                }
            }
        }
        if (explicit && local) { throw new IllegalArgumentException("conflicting remote and local declarations"); }
        if (explicit && (java.util.concurrent.CompletionStage.class.isAssignableFrom(requested.getReturnType())
                || java.util.concurrent.Future.class.isAssignableFrom(requested.getReturnType())) && !async) {
            throw new IllegalArgumentException("async result requires an explicit managed calling contract");
        }
        if (explicit && async) { org.j7m.middleware.transport.api.AsyncMethodDescriptor.fromMethod(requested); }
        if (explicit) for (Method method : lineage) {
            validateValue(method.getReturnType(), method.isAnnotationPresent(NoRemoteExport.class));
            for (var parameter : method.getParameters()) { validateValue(parameter.getType(), parameter.isAnnotationPresent(NoRemoteExport.class)); }
        }
        return explicit && !local;
    }

    private static void validateValue(final Class<?> type, final boolean forced) {
        if (!forced && !type.isAnnotationPresent(NoRemoteExport.class)) { return; }
        if (type.isPrimitive() || Set.of(String.class, Boolean.class, Byte.class, Short.class, Integer.class,
                Long.class, Float.class, Double.class, Character.class, byte[].class,
                org.j7m.middleware.api.ObjectRef.class).contains(type)
                || type.isAnnotationPresent(org.j7m.middleware.annotations.SchemaType.class)) { return; }
        throw new IllegalArgumentException("unsupported automatic reference suppression");
    }

    private static List<Class<?>> hierarchy(final Class<?> root) {
        Set<Class<?>> result = new LinkedHashSet<>();
        collect(root, result);
        return List.copyOf(result);
    }

    private static void collect(final Class<?> type, final Set<Class<?>> result) {
        if (type == null || type == Object.class || !result.add(type)) { return; }
        if (result.size() > 256) { throw new IllegalArgumentException("managed hierarchy limit"); }
        for (Class<?> contract : type.getInterfaces()) { collect(contract, result); }
        collect(type.getSuperclass(), result);
    }
}
