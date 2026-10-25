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

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.ServiceRef;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.ConfigProperties;
import org.j7m.middleware.annotations.J7mComponent;
import org.j7m.middleware.annotations.Named;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.Produces;
import org.j7m.middleware.annotations.Qualifier;
import org.j7m.middleware.annotations.Scope;

/**
 * Package-private direct-code generator; never loads or instantiates application classes.
 */
final class ComponentGenerator {
    private final ProcessingEnvironment environment;

    ComponentGenerator(final ProcessingEnvironment environment) { this.environment = environment; }

    String generate(final TypeElement type) throws IOException {
        require(type.getKind() == ElementKind.CLASS && type.getModifiers().contains(Modifier.PUBLIC)
                && !type.getModifiers().contains(Modifier.ABSTRACT) && type.getTypeParameters().isEmpty()
                && !type.getNestingKind().isNested(), "J7M_COMPONENT_REQUIRES_PUBLIC_CONCRETE_TOP_LEVEL_CLASS");
        var service = type.getAnnotation(org.j7m.middleware.annotations.J7mService.class);
        if (service != null) {
            require(service.contractVersion() > 0 && service.contractId().length() <= 512
                    && (service.contractId().isEmpty() || !service.contractId().isBlank())
                    && service.contractId().chars().noneMatch(Character::isISOControl)
                    && !service.name().isBlank() && service.name().length() <= 256
                    && service.name().chars().noneMatch(Character::isISOControl), "J7M_INVALID_SERVICE_CONTRACT");
        }
        validateHierarchy(type, new LinkedHashSet<>(), scope(type));
        for (Element member : members(type)) {
            validateElement(member);
            if (member instanceof ExecutableElement executable) {
                for (VariableElement parameter : executable.getParameters()) { validateElement(parameter); }
            }
        }
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR).map(e -> (ExecutableElement) e).toList();
        List<ExecutableElement> injected = constructors.stream().filter(e -> e.getAnnotation(Inject.class) != null).toList();
        require(injected.size() <= 1, "J7M_MULTIPLE_INJECTION_CONSTRUCTORS");
        ExecutableElement constructor = !injected.isEmpty() ? injected.getFirst()
                : constructors.size() == 1 ? constructors.getFirst()
                : constructors.stream().filter(e -> e.getParameters().isEmpty()).findFirst().orElse(null);
        require(constructor != null && constructor.getModifiers().contains(Modifier.PUBLIC), "J7M_INACCESSIBLE_CONSTRUCTOR");
        List<Element> dependencySites = new ArrayList<>(constructor.getParameters());
        List<String> dependencies = new ArrayList<>();
        String arguments = arguments(constructor, dependencies);
        StringBuilder injection = new StringBuilder();
        for (Element member : members(type)) {
            if ((member.getAnnotation(Inject.class) != null || member.getAnnotation(ConfigValue.class) != null || member.getAnnotation(ServiceRef.class) != null || member.getAnnotation(J7mResource.class) != null || current(member) != null) && member.getKind() == ElementKind.FIELD) {
                require(member.getModifiers().contains(Modifier.PUBLIC) && !member.getModifiers().contains(Modifier.FINAL)
                        && !member.getModifiers().contains(Modifier.STATIC), "J7M_INACCESSIBLE_INJECTION_FIELD");
                String dependency = dependency(member.asType(), member);
                dependencies.add(dependency); dependencySites.add(member);
                injection.append("((").append(((TypeElement) member.getEnclosingElement()).getQualifiedName()).append(")value).").append(member.getSimpleName()).append(provider(member.asType()) ? "=resolver.provider(" : "=resolver.resolve(").append(dependency).append(");");
            }
        }
        String className = type.getQualifiedName().toString();
        String name = componentName(type);
        String qualifiers = qualifiers(type);
        List<String> definitions = new ArrayList<>();
        definitions.add(definition(className, name, qualifiers, scope(type), dependencies, dependencySites,
                "new " + className + "(" + arguments + ")", injection.toString(), true));
        for (Element member : members(type)) {
            if (member.getAnnotation(Produces.class) != null) {
                require(member instanceof ExecutableElement && member.getModifiers().contains(Modifier.PUBLIC), "J7M_INACCESSIBLE_FACTORY");
                ExecutableElement factory = (ExecutableElement) member;
                require(factory.getTypeParameters().isEmpty() && factory.getReturnType().getKind() == TypeKind.DECLARED,
                        "J7M_UNSUPPORTED_FACTORY_TYPE");
                String resultType = concrete(factory.getReturnType());
                List<String> factoryDependencies = new ArrayList<>();
                String receiver = className;
                if (!member.getModifiers().contains(Modifier.STATIC)) {
                    String dependency = "new org.j7m.middleware.application.api.ManagedDependency(" + className + ".class,"
                            + J7mProcessor.quote(name) + "," + qualifiers + ",false)";
                    factoryDependencies.add(dependency);
                    receiver = "((" + className + ")resolver.resolve(" + dependency + "))";
                }
                String factoryArguments = arguments(factory, factoryDependencies);
                Produces produces = member.getAnnotation(Produces.class);
                definitions.add(definition(resultType, produces.name(), qualifiers(member), scope(member), factoryDependencies, new ArrayList<Element>(factory.getParameters()),
                        receiver + "." + member.getSimpleName() + "(" + factoryArguments + ")", "",
                        produces.ownership() == Produces.Ownership.OWNED));
            }
        }
        String pkg = environment.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String simple = type.getSimpleName() + "__J7mComponentIndex";
        String qualified = pkg.isEmpty() ? simple : pkg + "." + simple;
        Set<String> methods = new LinkedHashSet<>();
        collectMethods(type, methods, new LinkedHashSet<>());
        boolean hostedIndex = definitions.stream().anyMatch(value -> value.startsWith("new org.j7m.middleware.application.api.ManagedHostedAdapter")
                || value.startsWith("new org.j7m.middleware.application.api.ManagedPartitionDefinition")
                || value.startsWith("new org.j7m.middleware.application.api.ManagedJobDefinition"));
        String source = J7mProcessor.GENERATED_LICENSE + (pkg.isEmpty() ? "" : "package " + pkg + ";\n")
                + "/**\n * Supplies finite generated component metadata using direct public access.\n * Loading this index does not activate its components or publish services.\n */\npublic final class " + simple
                + " implements org.j7m.middleware.application.api.ManagedComponentIndex {\n"
                + "/**\n * Creates the metadata provider without constructing application components.\n */ public " + simple + "(){}\n"
                + (hostedIndex ? "/**\n * Returns the hosted component metadata format consumed by this index.\n * @return format version 2\n */\npublic int formatVersion(){return 2;}\n" : "")
                + "/**\n * Returns the finite component definitions and their declared dependencies.\n * @return immutable component definition list\n */ public java.util.List<org.j7m.middleware.application.api.ManagedComponentDefinition<?>> components(){return java.util.List.of("
                + String.join(",", definitions) + ");}\n"
                + "/**\n * Returns full binary erased method identities, including inherited declarations.\n * @return immutable managed method metadata list\n */ public java.util.List<org.j7m.middleware.application.api.ManagedMethod> methods(){return java.util.List.of("
                + String.join(",", methods) + ");}\n}\n";
        try (Writer writer = environment.getFiler().createSourceFile(qualified, type).openWriter()) { writer.write(source); }
        return qualified;
    }

    private void validateHierarchy(final TypeElement type, final Set<String> visited, final String selectedScope) {
        if (!visited.add(type.getQualifiedName().toString()) || type.getQualifiedName().contentEquals("java.lang.Object")) { return; }
        validateElement(type);
        Scope declaredScope = type.getAnnotation(Scope.class);
        require(declaredScope == null || declaredScope.value().name().equals(selectedScope), "J7M_CONFLICTING_INHERITED_SCOPE");
        for (TypeMirror parent : environment.getTypeUtils().directSupertypes(type.asType())) {
            Element declaration = environment.getTypeUtils().asElement(parent);
            if (declaration instanceof TypeElement parentType) { validateHierarchy(parentType, visited, selectedScope); }
        }
    }

    private void validateElement(final Element element) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            TypeElement type = (TypeElement) annotation.getAnnotationType().asElement();
            String name = type.getQualifiedName().toString();
            require(!name.startsWith("jakarta.inject."), "J7M_UNSUPPORTED_JAKARTA_INJECTION");
            if (name.startsWith("org.j7m.middleware.annotations.")) {
                Set<ElementKind> sites = switch (name.substring("org.j7m.middleware.annotations.".length())) {
                    case "J7mComponent", "J7mApplication", "J7mService", "PartitionHandler", "SagaDefinition" -> Set.of(ElementKind.CLASS);
                    case "RemoteService", "LocalOnly", "NoRemoteExport" -> Set.of(ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.METHOD, ElementKind.PARAMETER);
                    case "DurableJob", "Schedule", "Checkpoint", "Restore", "Progress", "SagaStep", "Compensation", "ReconciliationHandler", "InboxHandler", "OutboxPublisher", "DurableQueueConsumer", "DeadLetterHandler", "QueuePublisher", "EventSubscription", "JdbcTransaction", "ResourceTransaction", "Deduplicated", "OneWay", "Deadline", "Idempotent", "NonIdempotent", "Retry", "RemoteMethod", "Initialize", "Startup", "Drain", "Shutdown", "HealthCheck", "ReadinessCheck" -> Set.of(ElementKind.METHOD);
                    case "LeaseGuard", "LeaderTask", "OrderedBy", "DataLocality" -> Set.of(ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.METHOD);
                    case "ShardKey" -> Set.of(ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.METHOD, ElementKind.PARAMETER);
                    case "IdempotencyKey" -> Set.of(ElementKind.PARAMETER);
                    case "CurrentApplication", "CurrentPrincipal", "CurrentInvocation" -> Set.of(ElementKind.FIELD, ElementKind.PARAMETER);
                    case "Inject" -> Set.of(ElementKind.CONSTRUCTOR, ElementKind.FIELD, ElementKind.PARAMETER);
                    case "Named", "J7mResource" -> Set.of(ElementKind.CLASS, ElementKind.FIELD, ElementKind.PARAMETER);
                    case "Scope" -> Set.of(ElementKind.CLASS, ElementKind.METHOD);
                    case "Produces" -> Set.of(ElementKind.METHOD);
                    case "ConfigValue" -> Set.of(ElementKind.FIELD, ElementKind.PARAMETER);
                    case "ServiceRef" -> Set.of(ElementKind.FIELD, ElementKind.PARAMETER);
                    case "CircuitBreaker", "Bulkhead", "Observed", "RateLimited", "Backpressure", "Async", "Cancellable", "LongRunning", "RequiresRole", "RequiresPermission", "RequiresTrustDomain", "Confidential", "Audit", "Priority", "ResourceRequirement", "IsolationProfile" -> Set.of(ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.METHOD);
                    case "Sensitive" -> Set.of(ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.METHOD, ElementKind.FIELD, ElementKind.PARAMETER);
                    default -> Set.of();
                };
                require(sites.contains(element.getKind()), "J7M_UNSUPPORTED_MANAGED_ANNOTATION_SITE");
                if (name.endsWith(".Scope") && element.getKind() == ElementKind.METHOD) {
                    require(element.getAnnotation(Produces.class) != null, "J7M_SCOPE_REQUIRES_FACTORY");
                }
            } else if (!name.startsWith("java.lang.annotation.")) {
                require(!unsupportedMeta(type, new LinkedHashSet<>(), 0), "J7M_UNSUPPORTED_COMPOSED_MANAGED_ANNOTATION");
            }
        }
    }

    private boolean unsupportedMeta(final TypeElement annotation, final Set<String> path, final int depth) {
        require(depth <= 8 && path.add(annotation.getQualifiedName().toString()), "J7M_MANAGED_METADATA_GRAPH_LIMIT");
        try {
            for (AnnotationMirror meta : annotation.getAnnotationMirrors()) {
                TypeElement type = (TypeElement) meta.getAnnotationType().asElement();
                String name = type.getQualifiedName().toString();
                if (name.equals(Qualifier.class.getName()) || name.startsWith("java.lang.annotation.")) { continue; }
                if (name.startsWith("org.j7m.middleware.annotations.") || unsupportedMeta(type, path, depth + 1)) { return true; }
            }
            return false;
        } finally { path.remove(annotation.getQualifiedName().toString()); }
    }

    private String definition(final String type, final String name, final String qualifiers, final String scope,
            final List<String> dependencies, final List<Element> dependencySites, final String creator, final String injector, final boolean owned) {
        TypeElement component = environment.getElementUtils().getTypeElement(type);
        var hosted = new HostedAdapterGenerator(environment);
        boolean isHosted = hosted.hosted(component) || !hosted.backends(component, dependencySites).isEmpty();
        if (isHosted && hosted.adapter(component).equals("ManagedJobDefinition")) require(scope.equals("ACTIVATION"), "J7M_JOB_REQUIRES_ACTIVATION_SCOPE");
        return "new org.j7m.middleware.application.api." + (isHosted ? hosted.adapter(component) : "ManagedHostAdapter") + "<" + type + ">(){"
                + "public Class<" + type + "> type(){return " + type + ".class;}"
                + "public String name(){return " + J7mProcessor.quote(name) + ";}"
                + "public java.util.List<org.j7m.middleware.application.api.ManagedQualifier> qualifiers(){return " + qualifiers + ";}"
                + "public org.j7m.middleware.annotations.Scope.Kind scope(){return org.j7m.middleware.annotations.Scope.Kind." + scope + ";}"
                + "public java.util.List<org.j7m.middleware.application.api.ManagedDependency> dependencies(){return java.util.List.of(" + String.join(",", dependencies) + ");}"
                + "public " + type + " create(org.j7m.middleware.application.api.ManagedResolver resolver)throws Exception{return " + creator + ";}"
                + "public void inject(" + type + " value,org.j7m.middleware.application.api.ManagedResolver resolver)throws Exception{" + injector + "}"
                + "public boolean owned(){return " + owned + ";}"
                + new HostAdapterGenerator(environment).generate(component, scope) + (isHosted ? hosted.generate(component, dependencySites) : "") + "}";
    }

    private String arguments(final ExecutableElement executable, final List<String> dependencies) {
        List<String> arguments = new ArrayList<>();
        for (VariableElement parameter : executable.getParameters()) {
            String dependency = dependency(parameter.asType(), parameter);
            dependencies.add(dependency);
            arguments.add("resolver." + (provider(parameter.asType()) ? "provider" : "resolve") + "(" + dependency + ")");
        }
        return String.join(",", arguments);
    }

    private String dependency(final TypeMirror original, final Element element) {
        boolean provider = provider(original);
        TypeMirror type = original;
        if (provider) {
            DeclaredType declared = (DeclaredType) original;
            require(declared.getTypeArguments().size() == 1, "J7M_RAW_PROVIDER");
            type = declared.getTypeArguments().getFirst();
        }
        boolean optional = provider && environment.getTypeUtils().erasure(type).toString().equals("java.util.Optional");
        if (optional) {
            DeclaredType declared = (DeclaredType) type;
            require(declared.getTypeArguments().size() == 1, "J7M_RAW_OPTIONAL_SERVICE");
            type = declared.getTypeArguments().getFirst();
            require(element.getAnnotation(ServiceRef.class) != null || dynamic(injectOptions(element)), "J7M_OPTIONAL_REQUIRES_SERVICE_PROVIDER");
        }
        String context = current(element);
        ConfigValue config = element.getAnnotation(ConfigValue.class);
        if (context != null) {
            require(element.getAnnotation(ServiceRef.class) == null && element.getAnnotation(J7mResource.class) == null && !dynamic(injectOptions(element)), "J7M_CONFLICTING_CONTEXT_BINDING");
            require(config == null, "J7M_CONFLICTING_CONTEXT_BINDING");
            Element owner = element.getEnclosingElement();
            if (owner instanceof ExecutableElement executable && owner.getKind() == ElementKind.CONSTRUCTOR) { owner = owner.getEnclosingElement(); }
            require("application".equals(context) || provider || "CALL".equals(scope(owner)), "J7M_CURRENT_REQUIRES_PROVIDER_OR_CALL_SCOPE");
            return "new org.j7m.middleware.application.api.ManagedDependency(" + concrete(type) + ".class,"
                    + J7mProcessor.quote(injectionName(element)) + "," + qualifiers(element) + "," + provider
                    + ",java.util.Map.of(\"context.kind\"," + J7mProcessor.quote(context) + "))";
        }
        Element declaration = environment.getTypeUtils().asElement(type);
        ConfigProperties properties = declaration == null ? null : declaration.getAnnotation(ConfigProperties.class);
        require(config == null || properties == null, "J7M_CONFLICTING_CONFIG_BINDING");
        String distributed = distributed(type, element, provider, optional, config != null || properties != null);
        if (distributed != null) { return distributed; }
        String attributes = "java.util.Map.of()";
        if (config != null) {
            require(!type.getKind().isPrimitive() || config.required() || !config.defaultValue().isEmpty(),
                    "J7M_OPTIONAL_PRIMITIVE_CONFIG_REQUIRES_DEFAULT");
            require(!config.value().isBlank() && config.value().length() <= 512, "J7M_INVALID_CONFIG_KEY");
            require(!config.refresh() || provider, "J7M_REFRESH_REQUIRES_PROVIDER");
            attributes = "java.util.Map.of(\"config.key\"," + J7mProcessor.quote(config.value())
                    + ",\"config.default\"," + J7mProcessor.quote(config.defaultValue())
                    + ",\"config.required\",\"" + config.required() + "\",\"config.refresh\",\"" + config.refresh() + "\")";
        } else if (properties != null) {
            require(!properties.refresh() || provider, "J7M_REFRESH_REQUIRES_PROVIDER");
            attributes = "java.util.Map.of(\"config.prefix\"," + J7mProcessor.quote(properties.prefix())
                    + ",\"config.refresh\",\"" + properties.refresh() + "\")";
        }
        String concrete = type.getKind().isPrimitive() && config != null
                ? environment.getTypeUtils().boxedClass((javax.lang.model.type.PrimitiveType) type).getQualifiedName().toString()
                : concrete(type);
        return "new org.j7m.middleware.application.api.ManagedDependency(" + concrete + ".class,"
                + J7mProcessor.quote(injectionName(element)) + "," + qualifiers(element) + "," + provider + "," + attributes + ")";
    }

    private String distributed(final TypeMirror type, final Element element, final boolean provider, final boolean optional, final boolean configuration) {
        ServiceRef service = element.getAnnotation(ServiceRef.class);
        J7mResource resource = element.getAnnotation(J7mResource.class);
        Inject inject = injectOptions(element);
        boolean dynamic = dynamic(inject);
        require(service == null || resource == null, "J7M_CONFLICTING_DISTRIBUTED_BINDING");
        if (service == null && resource == null && !dynamic) { return null; }
        require(!configuration, "J7M_CONFLICTING_CONFIG_BINDING");
        BindingRefresh refresh = service != null ? service.refresh() : resource != null ? resource.refresh() : inject.refresh();
        String policy = service != null ? service.policy() : resource != null ? resource.policy() : inject.policy();
        require(!dynamic || service == null && resource == null || inject.refresh() == refresh && inject.policy().equals(policy), "J7M_CONFLICTING_BINDING_OPTIONS");
        require(policy.matches("[A-Za-z0-9_.-]{1,128}"), "J7M_INVALID_BINDING_POLICY");
        String typeName = concrete(type), name = resource != null ? resource.value() : service != null ? service.name() : injectionName(element);
        require(!name.isBlank() && name.length() <= 256 && name.chars().noneMatch(Character::isISOControl), "J7M_INVALID_BINDING_NAME");
        String injectionName = injectionName(element);
        require(injectionName.isEmpty() || injectionName.equals(name), "J7M_CONFLICTING_BINDING_NAME");
        String attributes;
        if (resource != null) {
            attributes = "java.util.Map.of(\"resource.name\"," + J7mProcessor.quote(name);
        } else {
            require(environment.getTypeUtils().asElement(type).getKind() == ElementKind.INTERFACE, "J7M_SERVICE_BINDING_REQUIRES_INTERFACE");
            String contract = service != null && !service.contractId().isEmpty() ? service.contractId()
                    : environment.getElementUtils().getBinaryName((TypeElement) environment.getTypeUtils().asElement(type)).toString();
            int version = service == null ? 1 : service.contractVersion();
            require(!contract.isBlank() && contract.length() <= 512 && contract.chars().noneMatch(Character::isISOControl) && version > 0, "J7M_INVALID_SERVICE_CONTRACT");
            attributes = "java.util.Map.of(\"service.name\"," + J7mProcessor.quote(name) + ",\"service.contract.id\"," + J7mProcessor.quote(contract)
                    + ",\"service.contract.version\"," + J7mProcessor.quote(Integer.toString(version));
        }
        if (optional) attributes += ",\"binding.optional\",\"true\"";
        attributes += ",\"binding.refresh\"," + J7mProcessor.quote(refresh.name()) + ",\"binding.policy\"," + J7mProcessor.quote(policy) + ")";
        return "new org.j7m.middleware.application.api.ManagedDependency(" + typeName + ".class," + J7mProcessor.quote(injectionName)
                + "," + qualifiers(element) + "," + provider + "," + attributes + ")";
    }

    private static boolean dynamic(final Inject inject) {
        return inject != null && (inject.refresh() != BindingRefresh.NEVER || !inject.policy().equals("default"));
    }

    private static Inject injectOptions(final Element element) {
        Inject direct = element.getAnnotation(Inject.class);
        Element owner = element.getEnclosingElement();
        Inject constructor = owner != null && owner.getKind() == ElementKind.CONSTRUCTOR ? owner.getAnnotation(Inject.class) : null;
        require(!dynamic(direct) || !dynamic(constructor) || direct.refresh() == constructor.refresh() && direct.policy().equals(constructor.policy()), "J7M_CONFLICTING_BINDING_OPTIONS");
        return dynamic(direct) ? direct : dynamic(constructor) ? constructor : direct;
    }

    private static String current(final Element element) {
        List<String> contexts = new ArrayList<>();
        if (element.getAnnotation(org.j7m.middleware.annotations.CurrentPrincipal.class) != null) { contexts.add("principal"); }
        if (element.getAnnotation(org.j7m.middleware.annotations.CurrentInvocation.class) != null) { contexts.add("invocation"); }
        if (element.getAnnotation(org.j7m.middleware.annotations.CurrentApplication.class) != null) { contexts.add("application"); }
        require(contexts.size() <= 1, "J7M_CONFLICTING_CONTEXT_BINDING");
        return contexts.isEmpty() ? null : contexts.getFirst();
    }

    private boolean provider(final TypeMirror type) {
        return environment.getTypeUtils().erasure(type).toString().equals("org.j7m.middleware.application.api.ManagedProvider");
    }

    private String concrete(final TypeMirror type) {
        require(type.getKind() == TypeKind.DECLARED && ((DeclaredType) type).getTypeArguments().isEmpty(), "J7M_UNSUPPORTED_GENERIC_OR_PRIMITIVE_DEPENDENCY");
        TypeElement declaration = (TypeElement) ((DeclaredType) type).asElement();
        require(declaration.getModifiers().contains(Modifier.PUBLIC) && declaration.getTypeParameters().isEmpty(), "J7M_INACCESSIBLE_DEPENDENCY_TYPE");
        return type.toString();
    }

    private String componentName(final TypeElement element) {
        String explicit = element.getAnnotation(J7mComponent.class) == null ? "" : element.getAnnotation(J7mComponent.class).name();
        String named = injectionName(element);
        require(explicit.isEmpty() || named.isEmpty() || explicit.equals(named), "J7M_CONFLICTING_QUALIFIERS");
        return explicit.isEmpty() ? named : explicit;
    }

    private static String injectionName(final Element element) {
        Named named = element.getAnnotation(Named.class);
        J7mResource resource = element.getAnnotation(J7mResource.class);
        require(named == null || resource == null || named.value().equals(resource.value()), "J7M_CONFLICTING_QUALIFIERS");
        return named != null ? named.value() : resource != null ? resource.value() : "";
    }

    private static String scope(final Element element) {
        Scope scope = element.getAnnotation(Scope.class);
        return scope == null ? "ACTIVATION" : scope.value().name();
    }

    private String qualifiers(final Element element) {
        List<String> qualifiers = new ArrayList<>();
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            TypeElement annotationType = (TypeElement) annotation.getAnnotationType().asElement();
            if (annotationType.getAnnotation(Qualifier.class) != null) {
                List<String> entries = new ArrayList<>();
                environment.getElementUtils().getElementValuesWithDefaults(annotation).entrySet().stream()
                        .sorted(Comparator.comparing(entry -> entry.getKey().getSimpleName().toString()))
                        .forEach(entry -> entries.add("java.util.Map.entry(" + J7mProcessor.quote(entry.getKey().getSimpleName().toString())
                                + ",org.j7m.middleware.application.api.ManagedQualifier.value(" + annotationValue(entry.getValue(), entry.getKey().getReturnType()) + "))"));
                qualifiers.add("new org.j7m.middleware.application.api.ManagedQualifier("
                        + J7mProcessor.quote(environment.getElementUtils().getBinaryName(annotationType).toString())
                        + ",java.util.Map.ofEntries(" + String.join(",", entries) + "))");
            }
        }
        return "java.util.List.of(" + String.join(",", qualifiers) + ")";
    }

    private String annotationValue(final AnnotationValue annotation, final TypeMirror declaredType) {
        Object value = annotation.getValue();
        if (value instanceof String text) { return J7mProcessor.quote(text); }
        if (value instanceof Character character) { return "Character.valueOf((char)" + (int) character + ")"; }
        if (value instanceof TypeMirror type) { return type + ".class"; }
        if (value instanceof VariableElement constant) { return constant.getEnclosingElement() + "." + constant.getSimpleName(); }
        if (value instanceof List<?> values) {
            TypeMirror component = ((javax.lang.model.type.ArrayType) declaredType).getComponentType();
            List<String> members = values.stream().map(item -> annotationValue((AnnotationValue) item, component)).toList();
            return "new " + component + "[]{" + String.join(",", members) + "}";
        }
        require(value instanceof Boolean || value instanceof Number, "J7M_UNSUPPORTED_QUALIFIER_MEMBER");
        if (value instanceof Byte) { return "Byte.valueOf((byte)" + value + ")"; }
        if (value instanceof Short) { return "Short.valueOf((short)" + value + ")"; }
        if (value instanceof Long) { return value + "L"; }
        if (value instanceof Float) { return "Float.valueOf(" + J7mProcessor.quote(value.toString()) + ")"; }
        if (value instanceof Double) { return "Double.valueOf(" + J7mProcessor.quote(value.toString()) + ")"; }
        return value.toString();
    }

    private List<Element> members(final TypeElement type) {
        List<Element> result = new ArrayList<>(type.getEnclosedElements());
        for (TypeMirror parent : environment.getTypeUtils().directSupertypes(type.asType())) {
            Element declaration = environment.getTypeUtils().asElement(parent);
            if (declaration instanceof TypeElement parentType && !parentType.getQualifiedName().contentEquals("java.lang.Object")) {
                for (Element member : members(parentType)) {
                    if (!result.contains(member)) { result.add(member); }
                }
            }
        }
        return result;
    }

    private void collectMethods(final TypeElement type, final Set<String> methods, final Set<String> visited) {
        if (!visited.add(type.getQualifiedName().toString()) || type.getQualifiedName().contentEquals("java.lang.Object")) { return; }
        for (Element member : type.getEnclosedElements()) {
            if (member instanceof ExecutableElement method && member.getKind() == ElementKind.METHOD
                    && member.getModifiers().contains(Modifier.PUBLIC) && !member.getModifiers().contains(Modifier.STATIC)) {
                List<String> parameters = method.getParameters().stream().map(parameter -> J7mProcessor.quote(binary(parameter.asType()))).toList();
                methods.add("new org.j7m.middleware.application.api.ManagedMethod(" + J7mProcessor.quote(environment.getElementUtils().getBinaryName(type).toString())
                        + "," + J7mProcessor.quote(method.getSimpleName().toString()) + ",java.util.List.of(" + String.join(",", parameters) + "),"
                        + J7mProcessor.quote(binary(method.getReturnType())) + ")");
            }
        }
        for (TypeMirror parent : environment.getTypeUtils().directSupertypes(type.asType())) {
            Element declaration = environment.getTypeUtils().asElement(parent);
            if (declaration instanceof TypeElement parentType) { collectMethods(parentType, methods, visited); }
        }
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

    private static void require(final boolean condition, final String code) {
        if (!condition) { throw new IllegalArgumentException(code); }
    }
}
