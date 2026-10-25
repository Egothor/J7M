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

package org.j7m.middleware.processor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.j7m.middleware.annotations.Drain;
import org.j7m.middleware.annotations.Initialize;
import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;

/**
 * Emits direct public host calls; does not execute application code during compilation.
 */
final class HostAdapterGenerator {
    private final ProcessingEnvironment environment;

    HostAdapterGenerator(final ProcessingEnvironment environment) { this.environment = environment; }

    String generate(final TypeElement type) { return generate(type, "ACTIVATION"); }

    String generate(final TypeElement type, final String scope) {
        List<ExecutableElement> declarations = methods(type, new LinkedHashSet<>());
        Map<String, List<ExecutableElement>> bySignature = new LinkedHashMap<>();
        for (ExecutableElement method : declarations) {
            bySignature.computeIfAbsent(key(method), ignored -> new ArrayList<>()).add(method);
        }
        List<String> remote = new ArrayList<>();
        List<String> dispatch = new ArrayList<>();
        List<String> probes = new ArrayList<>();
        List<String> probeDispatch = new ArrayList<>();
        Map<String, List<String>> lifecycle = new LinkedHashMap<>();
        for (String phase : List.of("INITIALIZE", "STARTUP", "DRAIN", "SHUTDOWN")) { lifecycle.put(phase, new ArrayList<>()); }
        TypeElement closeable = environment.getElementUtils().getTypeElement("java.lang.AutoCloseable");
        boolean autoClose = environment.getTypeUtils().isAssignable(type.asType(), closeable.asType());
        for (List<ExecutableElement> lineage : bySignature.values()) {
            boolean explicit = lineage.stream().anyMatch(method -> method.getAnnotation(RemoteMethod.class) != null);
            boolean local = typeAnnotation(type, LocalOnly.class, key(lineage.getFirst()), new LinkedHashSet<>()) || lineage.stream().anyMatch(method -> method.getAnnotation(LocalOnly.class) != null);
            require(!(explicit && local), "J7M_CONFLICTING_REMOTE_LOCAL_METHOD");
            if (explicit) {
                for (ExecutableElement method : lineage) {
                    require(method.getModifiers().contains(Modifier.PUBLIC) && !method.getModifiers().contains(Modifier.STATIC)
                            && method.getTypeParameters().isEmpty(), "J7M_INACCESSIBLE_REMOTE_METHOD");
                    validateByValue(method.getReturnType(), method.getAnnotation(NoRemoteExport.class) != null);
                    for (var parameter : method.getParameters()) {
                        validateByValue(parameter.asType(), parameter.getAnnotation(NoRemoteExport.class) != null);
                    }
                    String identity = identity(method);
                    remote.add(identity);
                    dispatch.add("if(method.equals(" + identity + ")){" + invocation(method) + "}");
                }
            }
            boolean async = typeAnnotation(type, org.j7m.middleware.annotations.Async.class, key(lineage.getFirst()), new LinkedHashSet<>())
                    || lineage.stream().anyMatch(method -> method.getAnnotation(org.j7m.middleware.annotations.Async.class) != null);
            boolean oneWay = lineage.stream().anyMatch(method -> method.getAnnotation(org.j7m.middleware.annotations.OneWay.class) != null);
            boolean safe = lineage.stream().anyMatch(method -> method.getAnnotation(org.j7m.middleware.annotations.Idempotent.class) != null);
            boolean unsafe = lineage.stream().anyMatch(method -> method.getAnnotation(org.j7m.middleware.annotations.NonIdempotent.class) != null);
            require(!(async && oneWay) && !(safe && unsafe), "J7M_CONFLICTING_CALL_POLICY");
            Set<Long> deadlines = new LinkedHashSet<>();
            for (ExecutableElement declaration : lineage) {
                var deadline = declaration.getAnnotation(org.j7m.middleware.annotations.Deadline.class);
                if (deadline != null) { deadlines.add(deadline.millis()); }
                require(deadlines.size() <= 1, "J7M_CONFLICTING_DEADLINE");
                require(deadline == null || deadline.millis() >= 1 && deadline.millis() <= 86400000L, "J7M_INVALID_DEADLINE");
                var retry = declaration.getAnnotation(org.j7m.middleware.annotations.Retry.class);
                require(retry == null || retry.maxAttempts() >= 0 && retry.maxAttempts() <= 16
                        && retry.policy().matches("[A-Za-z0-9_.-]{1,128}"), "J7M_INVALID_RETRY_POLICY");
                require(retry == null || !oneWay, "J7M_ONEWAY_RETRY_CONFLICT");
                require(retry == null || retry.maxAttempts() == 0 || safe && !unsafe && !oneWay, "J7M_UNSAFE_RETRY");
                if (oneWay) require(declaration.getReturnType().getKind() == TypeKind.VOID, "J7M_INVALID_ONEWAY_SIGNATURE");
                String resultName = environment.getTypeUtils().erasure(declaration.getReturnType()).toString();
                if (explicit && (resultName.equals("java.util.concurrent.CompletionStage")
                        || resultName.equals("java.util.concurrent.CompletableFuture")
                        || resultName.equals("java.util.concurrent.Future"))) {
                    require(async, "J7M_ASYNC_MARKER_REQUIRED");
                }
                if (async) {
                    TypeMirror result = declaration.getReturnType();
                    require(result instanceof javax.lang.model.type.DeclaredType, "J7M_INVALID_ASYNC_SIGNATURE");
                    var declared = (javax.lang.model.type.DeclaredType) result;
                    String erased = environment.getTypeUtils().erasure(result).toString();
                    require((erased.equals("java.util.concurrent.CompletionStage") || erased.equals("java.util.concurrent.CompletableFuture"))
                            && declared.getTypeArguments().size() == 1
                            && concreteAsyncPayload(declared.getTypeArguments().getFirst()),
                            "J7M_INVALID_ASYNC_SIGNATURE");
                }
            }
            Set<String> probeKinds = new LinkedHashSet<>();
            for (ExecutableElement declaration : lineage) {
                if (declaration.getAnnotation(org.j7m.middleware.annotations.HealthCheck.class) != null) probeKinds.add("HEALTH");
                if (declaration.getAnnotation(org.j7m.middleware.annotations.ReadinessCheck.class) != null) probeKinds.add("READINESS");
            }
            if (!probeKinds.isEmpty()) {
                require("ACTIVATION".equals(scope), "J7M_PROBE_REQUIRES_ACTIVATION_SCOPE");
                for (ExecutableElement declaration : lineage) {
                    if (declaration.getAnnotation(org.j7m.middleware.annotations.HealthCheck.class) != null
                            || declaration.getAnnotation(org.j7m.middleware.annotations.ReadinessCheck.class) != null) {
                        require(declaration.getParameters().isEmpty() && declaration.getReturnType().getKind() == TypeKind.BOOLEAN
                                && declaration.getModifiers().contains(Modifier.PUBLIC) && !declaration.getModifiers().contains(Modifier.STATIC),
                                "J7M_INVALID_MANAGED_PROBE_SIGNATURE");
                    }
                }
                ExecutableElement effective = lineage.stream().filter(method -> method.getModifiers().contains(Modifier.PUBLIC)
                        && !method.getModifiers().contains(Modifier.STATIC)).findFirst().orElseThrow();
                for (String kind : probeKinds) {
                    String descriptor = "new org.j7m.middleware.application.api.ManagedProbe(org.j7m.middleware.application.api.ManagedProbeKind."
                            + kind + "," + identity(effective) + ")";
                    probes.add(descriptor);
                    probeDispatch.add("if(descriptor.equals(" + descriptor + "))return value." + effective.getSimpleName() + "();");
                }
            }
            List<ExecutableElement> hooks = lineage.stream().filter(method -> phase(method) != null).toList();
            if (!hooks.isEmpty()) {
                Set<String> phases = new LinkedHashSet<>();
                for (ExecutableElement hook : hooks) { phases.add(phase(hook)); }
                require(phases.size() == 1, "J7M_CONFLICTING_LIFECYCLE_OVERRIDE");
                ExecutableElement hook = hooks.getFirst();
                require(hook.getModifiers().contains(Modifier.PUBLIC) && !hook.getModifiers().contains(Modifier.STATIC)
                        && hook.getParameters().isEmpty() && hook.getReturnType().getKind() == TypeKind.VOID,
                        "J7M_INVALID_LIFECYCLE_SIGNATURE");
                require(!explicit, "J7M_LIFECYCLE_REMOTE_CONFLICT");
                if (!(autoClose && "SHUTDOWN".equals(phase(hook)) && hook.getSimpleName().contentEquals("close"))) {
                    lifecycle.get(phase(hook)).add("value." + hook.getSimpleName() + "();");
                }
            }
        }
        StringBuilder hooks = new StringBuilder("public void lifecycle(" + type.getQualifiedName()
                + " value,org.j7m.middleware.application.api.ManagedLifecycle phase)throws Exception{switch(phase){");
        for (var entry : lifecycle.entrySet()) {
            entry.getValue().sort(Comparator.naturalOrder());
            hooks.append("case ").append(entry.getKey()).append("->{").append(String.join("", entry.getValue())).append('}');
        }
        hooks.append("}} ");
        return hooks + "public java.util.List<org.j7m.middleware.application.api.ManagedProbe> probes(){return java.util.List.of("
                + String.join(",", probes) + ");}"
                + "public boolean probe(" + type.getQualifiedName() + " value,org.j7m.middleware.application.api.ManagedProbe descriptor)throws Exception{"
                + String.join("", probeDispatch) + "throw new IllegalArgumentException(\"probe is not declared\");}"
                + "public java.util.List<org.j7m.middleware.application.api.ManagedMethod> remoteMethods(){return java.util.List.of("
                + String.join(",", new LinkedHashSet<>(remote)) + ");}"
                + "public Object invoke(" + type.getQualifiedName() + " value,org.j7m.middleware.application.api.ManagedMethod method,Object[] arguments)throws Exception{"
                + String.join("", dispatch) + "throw new IllegalArgumentException(\"method is not allowlisted\");}";
    }

    private String invocation(final ExecutableElement method) {
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < method.getParameters().size(); index++) {
            TypeMirror type = environment.getTypeUtils().erasure(method.getParameters().get(index).asType());
            arguments.add("(" + type + ")arguments[" + index + "]");
        }
        String call = "value." + method.getSimpleName() + "(" + String.join(",", arguments) + ")";
        return "if(arguments.length!=" + arguments.size() + ")throw new IllegalArgumentException(\"invalid argument count\");"
                + (method.getReturnType().getKind() == TypeKind.VOID ? call + ";return null;" : "return " + call + ";");
    }

    private void validateByValue(final TypeMirror type, final boolean noExport) {
        TypeMirror erased = environment.getTypeUtils().erasure(type);
        Element declaration = environment.getTypeUtils().asElement(erased);
        boolean typeNoExport = declaration != null && declaration.getAnnotation(NoRemoteExport.class) != null;
        if (!noExport && !typeNoExport) { return; }
        require(erased.getKind().isPrimitive() || erased.getKind() == TypeKind.VOID
                || Set.of("java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                    "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character", "byte[]",
                    "org.j7m.middleware.api.ObjectRef").contains(erased.toString())
                || declaration != null && declaration.getAnnotation(org.j7m.middleware.annotations.SchemaType.class) != null,
                "J7M_UNSUPPORTED_NO_REMOTE_EXPORT_REFERENCE");
    }

    private List<ExecutableElement> methods(final TypeElement type, final Set<String> visited) {
        if (!visited.add(type.getQualifiedName().toString()) || type.getQualifiedName().contentEquals("java.lang.Object")) { return List.of(); }
        List<ExecutableElement> result = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD) { result.add((ExecutableElement) member); }
        }
        for (TypeMirror parent : environment.getTypeUtils().directSupertypes(type.asType())) {
            if (environment.getTypeUtils().asElement(parent) instanceof TypeElement declaration) { result.addAll(methods(declaration, visited)); }
        }
        return result;
    }

    private boolean typeAnnotation(final TypeElement type, final Class<? extends java.lang.annotation.Annotation> annotation,
            final String signature, final Set<String> visited) {
        if (!visited.add(type.getQualifiedName().toString())) { return false; }
        if (type.getKind() == ElementKind.INTERFACE && environment.getElementUtils().getAllMembers(type).stream()
                .noneMatch(member -> member instanceof ExecutableElement method && member.getKind() == ElementKind.METHOD && key(method).equals(signature))) { return false; }
        if (type.getAnnotation(annotation) != null) { return true; }
        for (TypeMirror parent : environment.getTypeUtils().directSupertypes(type.asType())) {
            if (environment.getTypeUtils().asElement(parent) instanceof TypeElement declaration
                    && typeAnnotation(declaration, annotation, signature, visited)) { return true; }
        }
        return false;
    }

    private String key(final ExecutableElement method) {
        return method.getSimpleName() + "(" + String.join(",", method.getParameters().stream()
                .map(parameter -> environment.getTypeUtils().erasure(parameter.asType()).toString()).toList()) + ")";
    }

    private String identity(final ExecutableElement method) {
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        return "new org.j7m.middleware.application.api.ManagedMethod(" + J7mProcessor.quote(environment.getElementUtils().getBinaryName(owner).toString())
                + "," + J7mProcessor.quote(method.getSimpleName().toString()) + ",java.util.List.of("
                + String.join(",", method.getParameters().stream().map(parameter -> J7mProcessor.quote(binary(parameter.asType()))).toList())
                + ")," + J7mProcessor.quote(binary(method.getReturnType())) + ")";
    }

    private String binary(final TypeMirror type) {
        TypeMirror erased = environment.getTypeUtils().erasure(type);
        if (erased.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((javax.lang.model.type.ArrayType) erased).getComponentType();
            if (component.getKind() == TypeKind.ARRAY) { return "[" + binary(component); }
            if (component.getKind().isPrimitive()) {
                return "[" + Map.of(TypeKind.BOOLEAN, "Z", TypeKind.BYTE, "B", TypeKind.SHORT, "S", TypeKind.INT, "I",
                        TypeKind.LONG, "J", TypeKind.CHAR, "C", TypeKind.FLOAT, "F", TypeKind.DOUBLE, "D").get(component.getKind());
            }
            return "[L" + binary(component) + ";";
        }
        Element declaration = environment.getTypeUtils().asElement(erased);
        return declaration instanceof TypeElement declared ? environment.getElementUtils().getBinaryName(declared).toString() : erased.toString();
    }

    private boolean concreteAsyncPayload(final TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return concreteAsyncPayload(((javax.lang.model.type.ArrayType) type).getComponentType());
        }
        if (type.getKind().isPrimitive()) { return true; }
        if (!(type instanceof javax.lang.model.type.DeclaredType declared) || !declared.getTypeArguments().isEmpty()) { return false; }
        var future = environment.getElementUtils().getTypeElement("java.util.concurrent.Future");
        var stage = environment.getElementUtils().getTypeElement("java.util.concurrent.CompletionStage");
        return !environment.getTypeUtils().isAssignable(environment.getTypeUtils().erasure(type), environment.getTypeUtils().erasure(future.asType()))
                && !environment.getTypeUtils().isAssignable(environment.getTypeUtils().erasure(type), environment.getTypeUtils().erasure(stage.asType()));
    }

    private static String phase(final ExecutableElement method) {
        List<String> phases = new ArrayList<>();
        if (method.getAnnotation(Initialize.class) != null) { phases.add("INITIALIZE"); }
        if (method.getAnnotation(Startup.class) != null) { phases.add("STARTUP"); }
        if (method.getAnnotation(Drain.class) != null) { phases.add("DRAIN"); }
        if (method.getAnnotation(Shutdown.class) != null) { phases.add("SHUTDOWN"); }
        require(phases.size() <= 1, "J7M_CONFLICTING_LIFECYCLE_PHASES");
        return phases.isEmpty() ? null : phases.getFirst();
    }

    private static void require(final boolean condition, final String code) {
        if (!condition) { throw new IllegalArgumentException(code); }
    }
}
