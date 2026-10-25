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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedHostAdapter;
import org.j7m.middleware.application.api.ManagedLifecycle;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedProbe;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.j7m.middleware.application.components.LiveConfiguration;
import org.j7m.middleware.application.components.ManagedContainer;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.j7m.middleware.application.components.ManagedContextBindings;
import org.j7m.middleware.application.components.ManagedHostingOptions;
import org.j7m.middleware.application.components.ManagedInvocationPolicy;
import org.j7m.middleware.application.components.ManagedInvocationPolicy.Binding;
import org.j7m.middleware.application.components.ManagedInvocationPolicy.Proceed;

/**
 * Verified-bundle host bridge. All preparation validates before the container constructs values.
 */
final class ManagedPojoActivation implements AutoCloseable {
    record ProbeBinding(String id, ManagedHostAdapter<?> adapter, ManagedProbe descriptor) { }
    private final List<ProbeBinding> probeBindings = new ArrayList<>();
    private record IndexSnapshot(int formatVersion, List<ManagedComponentDefinition<?>> components, List<ManagedMethod> methods) implements ManagedComponentIndex { }
    private record BoundMethod(ManagedServiceContract contract, List<Binding> policies) { }
    private record Service(ManagedHostAdapter<?> adapter, Class<?> contract, Map<ManagedMethod, BoundMethod> methods,
            Map<String, String> metadata, boolean hosted) {
        Service(ManagedHostAdapter<?> adapter, Class<?> contract, Map<ManagedMethod, BoundMethod> methods, Map<String, String> metadata) { this(adapter, contract, methods, metadata, false); }
    }
    private record HostedPlan(String key, org.j7m.middleware.application.api.ManagedHostedAdapter<?> adapter,
            org.j7m.middleware.application.api.ManagedHostedFeature feature,
            org.j7m.middleware.application.components.ManagedHostedResources.Entry entry) { }
    private final List<HostedPlan> hostedPlans = new ArrayList<>();
    private final Map<String, Service> hostedServices = new LinkedHashMap<>();
    private final Map<String, ObjectRef> hostedTargets = new LinkedHashMap<>();
    private final List<org.j7m.middleware.application.components.ManagedHostedResources.Binding> hostedBindings = new ArrayList<>();
    private volatile List<org.j7m.middleware.application.components.ManagedHostedResources.Binding> hostedAdmission = List.of();
    private volatile boolean hostedStarted;
    private volatile boolean hostedClosed;
    private volatile boolean hostedCommitted;
    private final Map<String, Service> services = new LinkedHashMap<>();
    private final Map<String, ObjectRef> references = new LinkedHashMap<>();
    private final List<Binding> ownedPolicies = new ArrayList<>();
    private final LiveConfiguration configuration;
    private final org.j7m.middleware.codec.api.ValueCodec valueCodec;
    private ManagedContainer container;
    private boolean closed;
    private final ThreadLocal<org.j7m.middleware.transport.api.InvocationCompletionScope> invocationScope = new ThreadLocal<>();

    ManagedPojoActivation(final ApplicationManifest manifest, final ApplicationContext application, final ClassLoader loader,
            final ModuleLayer layer, final Consumer<Class<?>> origin, final ManagedHostingOptions options,
            final String artifactDigest,
            final org.j7m.middleware.transport.api.InvocationCompletionFactory completionFactory) throws Exception {
        configuration = new LiveConfiguration(1, application.configuration().asMap());
        try {
            valueCodec = ManagedSchemaAllowlist.codec(loader, layer, origin);
            List<ManagedComponentIndex> indexes = loadIndexes(loader, layer, origin);
            List<ManagedComponentDefinition<?>> definitions = new ArrayList<>();
            int exportedMethods = 0;
            Set<Class<? extends Annotation>> capabilities = new HashSet<>();
            if (options.bindings().supports("service.name")) { capabilities.add(org.j7m.middleware.annotations.ServiceRef.class); }
            for (var policy : options.policies()) { capabilities.addAll(policy.annotationTypes()); }
            capabilities.addAll(options.hostedResources().annotationTypes());
            for (ManagedComponentIndex index : indexes) {
                if ((index.formatVersion() != 1 && index.formatVersion() != 2) || index.components().size() > 1024) { throw invalid("invalid managed index"); }
                definitions.addAll(List.copyOf(index.components()));
                boolean featureIndex = index.components().stream().anyMatch(value -> value instanceof org.j7m.middleware.application.api.ManagedHostedAdapter<?>);
                if ((index.formatVersion() == 2) != featureIndex) throw invalid("hosted index feature version mismatch");
                if (definitions.size() > 1024) { throw invalid("managed definition capacity"); }
            }
            for (ManagedComponentDefinition<?> definition : definitions) {
                if (definition instanceof org.j7m.middleware.application.api.ManagedHostedAdapter<?> adapter) {
                    origin.accept(adapter.type());
                    for (var feature : List.copyOf(adapter.hostedFeatures())) {
                        if (hostedPlans.size() >= 64) throw invalid("hosted feature capacity");
                        String key = feature.kind() + ":" + feature.id();
                        if (hostedPlans.stream().anyMatch(existing -> existing.key.equals(key))) throw invalid("duplicate hosted feature");
                        var entry = options.hostedResources().require(feature.kind());
                        var requiredPolicies = options.hostedResources().requiredPolicies(feature.kind());
                        if (requiredPolicies.size() > 16) throw invalid("hosted required policy capacity");
                        int requiredCount = 0;
                        for (var required : requiredPolicies) {
                            if (++requiredCount > 16 || !options.installs(required)) throw invalid("hosted guard policy installation is missing");
                        }
                        var bundle = new org.j7m.middleware.application.components.ManagedHostedResources.Bundle(artifactDigest, application.configuration().asMap());
                        entry.factory().validate(feature, definition, bundle);
                        hostedPlans.add(new HostedPlan(key, adapter, feature, entry));
                        boolean backendOnly = options.hostedResources().backendOnly(feature.kind());
                        if ((!backendOnly || !feature.methods().isEmpty()) && definition.scope() != Scope.Kind.ACTIVATION) {
                            throw invalid("hosted callbacks require activation scope");
                        }
                        if (!backendOnly) {
                            Map<ManagedMethod, BoundMethod> methods = new LinkedHashMap<>();
                            for (var method : feature.methods()) {
                                exportedMethods++;
                                var contract = new ManagedServiceContract("hosted." + feature.kind() + "." + feature.id(), feature.version(), method);
                                for (var policy : options.policies()) policy.validateHosted(contract, definition.type(), Set.copyOf(feature.methods()));
                                methods.put(method, new BoundMethod(contract, List.of()));
                            }
                            if (methods.isEmpty()) throw invalid("hosted callback allowlist is empty");
                            hostedServices.put(key, new Service(adapter, definition.type(), Map.copyOf(methods), Map.of(), true));
                            String logical = "application/" + application.applicationIdentity().coordinate() + "/" + application.instanceIdentity().instanceId().value() + "/" + key;
                            hostedTargets.put(key, new ObjectRef(logical, logical, key, application.runtimeIdentity().runtimeId(),
                                    application.applicationIdentity().trustDomain(), List.of(), "hosted", "", 0, 1,
                                    Map.of("j7m.hosted.internal", "true")));
                        }
                    }
                }
            }
            for (ManagedComponentDefinition<?> definition : definitions) {
                ManagedRuntimeContracts.validateType(definition.type(), capabilities);
                var featureCoverage = new LinkedHashMap<org.j7m.middleware.application.api.ManagedHostedFeature, Set<Class<? extends Annotation>>>();
                for (HostedPlan plan : hostedPlans) if (plan.adapter.type() == definition.type()) {
                    featureCoverage.put(plan.feature, Set.copyOf(plan.entry.factory().annotationTypes()));
                }
                var hostedOnlyCapabilities = new java.util.HashSet<>(options.hostedResources().annotationTypes());
                // A type such as ShardKey can be enforced by a pure partition factory or
                // an invocation policy. The owning policy validates its actual exposure;
                // a globally installed factory must not require an unrelated RPC to be a reducer.
                for (var policy : options.policies()) hostedOnlyCapabilities.removeAll(policy.annotationTypes());
                ManagedRuntimeContracts.validateHostedCoverage(definition.type(), featureCoverage, Set.copyOf(hostedOnlyCapabilities));
                for (ManagedProbe probe : ManagedProbeContracts.validate(definition)) {
                    if (probeBindings.size() >= 64) throw invalid("managed probe capacity");
                    probeBindings.add(new ProbeBinding(probeIdentity(definition, probe), (ManagedHostAdapter<?>) definition, probe));
                }
                boolean exported = manifest.services().stream().anyMatch(service -> service.implementationClass().equals(definition.type().getName()))
                        || hostedServices.values().stream().anyMatch(service -> service.adapter.type() == definition.type());
                for (var policy : options.policies()) policy.validateComponent(definition.type(), exported);
            }
            Class<?> bootstrap = Class.forName(manifest.bootstrapClass(), false, loader);
            origin.accept(bootstrap);
            var identity = bootstrap.getAnnotation(org.j7m.middleware.annotations.J7mApplication.class);
            if (identity == null || !identity.group().equals(manifest.identity().coordinate().id().group())
                    || !identity.name().equals(manifest.identity().coordinate().id().name())
                    || !identity.version().equals(manifest.identity().coordinate().version().value())) {
                throw invalid("managed application identity mismatch");
            }
            requireActivation(definitions, bootstrap);
            for (ApplicationManifest.ServiceDeclaration declaration : manifest.services()) {
                if (declaration.metadata().keySet().stream().anyMatch(key -> key.startsWith("j7m.readiness."))) {
                    throw invalid("readiness metadata is host-owned");
                }
                Class<?> contract = Class.forName(declaration.interfaceName(), false, loader);
                Class<?> implementation = Class.forName(declaration.implementationClass(), false, loader);
                origin.accept(contract); origin.accept(implementation);
                if (!contract.isInterface() || !java.lang.reflect.Modifier.isPublic(contract.getModifiers()) || !contract.isAssignableFrom(implementation)) { throw invalid("invalid managed service interface"); }
                if (!contract.getModule().isExported(contract.getPackageName(), org.j7m.middleware.core.DefaultMiddlewareRuntime.class.getModule())) {
                    throw org.j7m.middleware.application.components.ManagedInjectionException.preflight(contract,
                            org.j7m.middleware.application.components.ManagedInjectionException.Reason.JPMS_ACCESS);
                }
                ManagedHostAdapter<?> adapter = requireActivation(definitions, implementation);
                J7mService annotation = implementation.getAnnotation(J7mService.class);
                if (annotation == null || !annotation.exported() || !annotation.name().equals(declaration.serviceName())
                        || annotation.contractVersion() < 1) { throw invalid("managed service declaration mismatch"); }
                String id = annotation.contractId().isEmpty() ? contract.getName() : annotation.contractId();
                Map<ManagedMethod, BoundMethod> methods = new LinkedHashMap<>();
                for (Method method : contract.getMethods()) {
                    if (method.getDeclaringClass() == Object.class || java.lang.reflect.Modifier.isStatic(method.getModifiers())) { continue; }
                    ManagedMethod signature = ManagedRuntimeContracts.identity(method);
                    boolean allowed = ManagedRuntimeContracts.allowed(implementation, method);
                    if (adapter.remoteMethods().contains(signature) != allowed) { throw invalid("managed method descriptor mismatch"); }
                    if (!allowed) { continue; }
                    ManagedServiceContract complete = new ManagedServiceContract(id, annotation.contractVersion(), signature);
                    for (var policy : options.policies()) { policy.validate(complete, implementation, contract); }
                    if (++exportedMethods > 4096) throw invalid("managed method capacity");
                    methods.put(signature, new BoundMethod(complete, List.of()));
                }
                if (methods.isEmpty()) { throw invalid("managed service has no explicit remote methods"); }
                Map<String, String> metadata = Map.of("j7m.contract.id", id, "j7m.contract.version", Integer.toString(annotation.contractVersion()),
                        "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(adapter.qualifiers()));
                for (var entry : metadata.entrySet()) {
                    if (declaration.metadata().containsKey(entry.getKey()) && !entry.getValue().equals(declaration.metadata().get(entry.getKey()))) {
                        throw invalid("conflicting managed service metadata");
                    }
                }
                if (services.putIfAbsent(declaration.serviceName(), new Service(adapter, contract, Map.copyOf(methods), metadata)) != null) {
                    throw invalid("duplicate managed service");
                }
            }
            for (ManagedComponentDefinition<?> definition : definitions) {
                Map<ManagedServiceContract, Class<?>> exports = new LinkedHashMap<>();
                for (Service service : services.values()) {
                    if (service.adapter.type().equals(definition.type())) {
                        for (BoundMethod method : service.methods.values()) {
                            Class<?> previous = exports.putIfAbsent(method.contract, service.contract);
                            if (previous != null && previous != service.contract) throw invalid("ambiguous managed exposure");
                        }
                    }
                }
                var hostedContracts = new HashSet<ManagedServiceContract>();
                for (Service service : hostedServices.values()) if (service.adapter.type() == definition.type()) {
                    for (BoundMethod method : service.methods.values()) hostedContracts.add(method.contract);
                }
                for (var contract : hostedContracts) exports.put(contract, definition.type());
                Map<ManagedServiceContract, Class<?>> snapshot = Map.copyOf(exports);
                for (var policy : options.policies()) {
                    if (hostedContracts.isEmpty()) policy.validateExposure(definition.type(), snapshot);
                    else policy.validateHostedExposure(definition.type(), snapshot);
                }
            }
            if ((long) exportedMethods * options.policies().size() > 16_384) throw invalid("managed policy binding capacity");
            Set<Binding> distinctPolicies = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            // Every export and policy is validated before any binding or component constructor runs.
            for (var entry : List.copyOf(services.entrySet())) {
                Service service = entry.getValue();
                Map<ManagedMethod, BoundMethod> bound = new LinkedHashMap<>();
                for (var method : service.methods.entrySet()) {
                    List<Binding> policies = new ArrayList<>();
                    for (var policy : options.policies()) {
                        Binding binding = java.util.Objects.requireNonNull(policy.bind(method.getValue().contract,
                                service.adapter.type(), service.contract));
                        if (!distinctPolicies.add(binding)) throw invalid("managed policy binding alias");
                        ownedPolicies.add(binding); policies.add(binding);
                    }
                    bound.put(method.getKey(), new BoundMethod(method.getValue().contract, List.copyOf(policies)));
                }
                services.put(entry.getKey(), new Service(service.adapter, service.contract, Map.copyOf(bound), service.metadata));
            }
            for (var entry : List.copyOf(hostedServices.entrySet())) {
                Service service = entry.getValue(); var bound = new LinkedHashMap<ManagedMethod, BoundMethod>();
                for (var method : service.methods.entrySet()) {
                    var policies = new ArrayList<Binding>();
                    for (var policy : options.policies()) {
                        Binding binding = java.util.Objects.requireNonNull(policy.bindHosted(method.getValue().contract, service.adapter.type(), Set.copyOf(service.methods.keySet())));
                        if (!distinctPolicies.add(binding)) throw invalid("managed policy binding alias");
                        ownedPolicies.add(binding); policies.add(binding);
                    }
                    bound.put(method.getKey(), new BoundMethod(method.getValue().contract, List.copyOf(policies)));
                }
                hostedServices.put(entry.getKey(), new Service(service.adapter, service.contract, Map.copyOf(bound), service.metadata, true));
            }
            List<ManagedComponentIndex> containerIndexes = indexes.stream().map(index -> (ManagedComponentIndex)
                    new IndexSnapshot(1, index.components(), index.methods())).toList();
            container = ManagedContainer.activate(containerIndexes, 4096, configuration,
                    options.bindings().with("context.kind", ManagedContextBindings.factory(application)));
            for (HostedPlan plan : hostedPlans) {
                org.j7m.middleware.application.components.ManagedHostedResources.Invoker invoker;
                Service service = hostedServices.get(plan.key);
                if (service == null) invoker = new org.j7m.middleware.application.components.ManagedHostedResources.Invoker() {
                    @Override public boolean admissionOpen() { return hostedAdmissionOpen(); }
                    @Override public java.util.concurrent.CompletionStage<Object> invokeDecoded(ManagedMethod method,
                            java.util.concurrent.Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions invocationOptions) {
                        throw invalid("pure reducer has no hosted invocation boundary");
                    }
                };
                else {
                    var callbacks = new LinkedHashMap<ManagedMethod, org.j7m.middleware.application.components.ManagedHostedInvoker.Callback>();
                    for (var method : service.methods.entrySet()) callbacks.put(method.getKey(), arguments -> around(service, method.getValue(), 0, arguments, hostedTargets.get(plan.key)));
                    invoker = new org.j7m.middleware.application.components.ManagedHostedInvoker(callbacks, completionFactory, plan.entry, hostedTargets.get(plan.key), this::hostedAdmissionOpen);
                }
                var bundle = new org.j7m.middleware.application.components.ManagedHostedResources.Bundle(artifactDigest, application.configuration().asMap());
                var binding = java.util.Objects.requireNonNull(plan.entry.factory().bind(plan.feature, plan.adapter, plan.feature.methods().isEmpty() ? null : target(plan.adapter), bundle, invoker));
                hostedBindings.add(binding); binding.prepare();
            }
            hostedAdmission = List.copyOf(hostedBindings);
        } catch (Exception | java.util.ServiceConfigurationError failure) {
            close();
            if (failure instanceof ManagedContainer.Failure graph) throw org.j7m.middleware.application.components.ManagedInjectionException.from(graph);
            if (failure instanceof org.j7m.middleware.application.components.ManagedInjectionException diagnostic) throw diagnostic;
            if (failure instanceof java.util.ServiceConfigurationError) throw org.j7m.middleware.application.components.ManagedInjectionException.preflight(null,
                    org.j7m.middleware.application.components.ManagedInjectionException.Reason.INDEX_PROVIDER_ACCESS);
            throw invalid("managed activation preparation failed");
        }
    }

    private static ManagedHostAdapter<?> requireActivation(final List<ManagedComponentDefinition<?>> definitions, final Class<?> type) {
        List<ManagedComponentDefinition<?>> matching = definitions.stream().filter(definition -> definition.type() == type).toList();
        if (matching.size() != 1 || matching.getFirst().scope() != Scope.Kind.ACTIVATION
                || !(matching.getFirst() instanceof ManagedHostAdapter<?> adapter)) {
            throw invalid("managed bootstrap and services require one activation adapter");
        }
        ManagedRuntimeContracts.validateScope(type, Scope.Kind.ACTIVATION);
        return adapter;
    }

    org.j7m.middleware.codec.api.ValueCodec valueCodec() { return valueCodec; }

    private static List<ManagedComponentIndex> loadIndexes(final ClassLoader loader, final ModuleLayer layer, final Consumer<Class<?>> origin) {
        ServiceLoader<ManagedComponentIndex> providers = layer == null ? ServiceLoader.load(ManagedComponentIndex.class, loader)
                : ServiceLoader.load(layer, ManagedComponentIndex.class);
        List<ManagedComponentIndex> result = new ArrayList<>();
        var iterator = providers.stream().iterator();
        while (iterator.hasNext()) {
            var provider = iterator.next();
            if (result.size() >= 256 || !provider.type().getName().endsWith("__J7mComponentIndex")) { throw invalid("invalid managed index provider"); }
            origin.accept(provider.type());
            ManagedComponentIndex index = provider.get();
            int version = index.formatVersion();
            if (version != 1 && version != 2) throw invalid("unsupported managed index format");
            if (index.components().size() > 1024 || index.methods().size() > 4096) throw invalid("managed index capacity");
            List<ManagedComponentDefinition<?>> components = List.copyOf(index.components());
            List<ManagedMethod> methods = List.copyOf(index.methods());
            if (components.size() > 1024 || methods.size() > 4096) throw invalid("managed index capacity");
            result.add(new IndexSnapshot(version, components, methods));
        }
        if (result.isEmpty()) { throw invalid("managed index is missing"); }
        return List.copyOf(result);
    }

    private static String probeIdentity(final ManagedComponentDefinition<?> definition, final ManagedProbe probe) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var fields = new java.io.DataOutputStream(bytes)) {
                fields.writeUTF(definition.type().getName()); fields.writeUTF(definition.name());
                fields.writeUTF(ManagedQualifier.fingerprint(definition.qualifiers()));
                fields.writeUTF(probe.kind().name()); fields.writeUTF(probe.method().declaringType());
                fields.writeUTF(probe.method().name()); fields.writeUTF(probe.method().returnType());
            }
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw invalid("managed probe identity failed"); }
    }

    List<ProbeBinding> probes() { return List.copyOf(probeBindings); }

    boolean check(final ProbeBinding probe) throws Exception {
        try (var call = container.openCall()) {
            return call.call(() -> directProbe(probe.adapter, target(probe.adapter), probe.descriptor));
        }
    }

    private Object target(final ManagedHostAdapter<?> adapter) {
        return container.get(adapter.type(), adapter.name(), adapter.qualifiers());
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean directProbe(final ManagedHostAdapter<T> adapter, final Object value, final ManagedProbe descriptor) throws Exception {
        return adapter.probe((T) value, descriptor);
    }

    void start() {
        container.lifecycle(ManagedLifecycle.INITIALIZE);
        container.lifecycle(ManagedLifecycle.STARTUP);
        hostedStarted = !hostedBindings.isEmpty();
        try { for (var binding : hostedBindings) binding.start(); }
        catch (Exception failure) { throw invalid("hosted resource startup failed"); }
    }

    boolean hasHostedResources() { return !hostedClosed && !hostedPlans.isEmpty(); }

    boolean hostedAdmissionOpen() { return hostedCommitted && !hostedClosed; }

    void commitHostedAdmission() { for (var binding : hostedAdmission) binding.commitAdmission(); }

    void openHostedAdmission() { hostedCommitted = true; }

    void stopHostedAdmission() { hostedCommitted = false; for (var binding : hostedAdmission) binding.stopAdmission(); }

    synchronized boolean drainHosted() {
        if (hostedClosed || hostedBindings.isEmpty()) return true;
        try {
            boolean drained = true;
            for (var binding : hostedBindings) drained &= binding.drain(java.time.Duration.ofMillis(1));
            if (!drained) return false;
            for (var binding : hostedBindings.reversed()) binding.close();
            hostedBindings.clear(); hostedAdmission = List.of(); hostedClosed = true; return true;
        } catch (Exception failure) { throw invalid("hosted resource drain failed"); }
    }

    void drain() { container.lifecycle(ManagedLifecycle.DRAIN); }

    Object target(final String name) {
        Service service = service(name);
        return container.get(service.adapter.type(), service.adapter.name(), service.adapter.qualifiers());
    }

    Map<String, String> metadata(final String name) { return service(name).metadata; }

    void attachPrepared(final String name, final ObjectRef reference) {
        Service service = service(name);
        if (!reference.serviceName().equals(name) || !reference.interfaces().contains(service.contract.getName())
                || references.putIfAbsent(name, reference) != null) { throw invalid("invalid managed export target"); }
    }

    private org.j7m.middleware.transport.api.InvocationCompletionScope requireInvocationScope() {
        var owned = invocationScope.get();
        if (owned == null) { throw invalid("host invocation scope is unavailable"); }
        return owned;
    }

    org.j7m.middleware.transport.api.InvocationCompletionScope ownedScope(
            final org.j7m.middleware.transport.api.InvocationCompletionScope delegate) {
        return new org.j7m.middleware.transport.api.InvocationCompletionScope() {
            private <T> T attached(final java.util.concurrent.Callable<T> action) throws Exception {
                var previous = invocationScope.get(); invocationScope.set(delegate);
                try { return action.call(); }
                finally { if (previous == null) invocationScope.remove(); else invocationScope.set(previous); }
            }
            @Override public <T> T call(final java.util.concurrent.Callable<T> action) throws Exception {
                return delegate.call(() -> attached(action));
            }
            @Override public <T> java.util.concurrent.CompletionStage<T> continueInContext(final java.util.concurrent.Callable<T> action) {
                return delegate.continueInContext(() -> attached(action));
            }
            @Override public java.util.concurrent.CompletionStage<Object> logicalResult() { return delegate.logicalResult(); }
            @Override public java.util.concurrent.CompletionStage<Void> physicalCompletion() { return delegate.physicalCompletion(); }
            @Override public boolean supportsResultFinalizers() { return delegate.supportsResultFinalizers(); }
            @Override public void addResultFinalizer(final org.j7m.middleware.transport.api.InvocationResultFinalizer finalizer) { delegate.addResultFinalizer(finalizer); }
            @Override public <T> void putContext(final Class<T> type, final T value) { delegate.putContext(type, value); }
            @Override public <T> T context(final Class<T> type) { return delegate.context(type); }
            @Override public <T> java.util.Optional<T> findContext(final Class<T> type) { return delegate.findContext(type); }
            @Override public void markApplicationEntry() { delegate.markApplicationEntry(); }
            @Override public void requestCancellation() { delegate.requestCancellation(); }
            @Override public void abortBeforeTarget() { delegate.abortBeforeTarget(); }
            @Override public void close() { delegate.close(); }
        };
    }

    ManagedContainer.CallScope openInvocationCall() { return container.openCall(); }

    Object invoke(final String name, final Method method, final Object[] arguments) throws Exception {
        Service service = service(name);
        BoundMethod binding = service.methods.get(ManagedRuntimeContracts.identity(method));
        if (binding == null) { throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "method is not allowlisted"); }
        Object[] values = arguments == null ? new Object[0] : arguments;
        if (ManagedInvocationLifetime.active()) {
            ObjectRef reference = references.get(name);
            if (reference == null) throw invalid("managed export target is unavailable");
            return around(service, binding, 0, values, reference);
        }
        try (var call = container.openCall()) {
            ObjectRef reference = references.get(name);
            if (reference == null) throw invalid("managed export target is unavailable");
            return call.call(() -> around(service, binding, 0, values, reference));
        }
    }

    private Object around(final Service service, final BoundMethod binding, final int index, final Object[] arguments, final ObjectRef reference) throws Exception {
        if (index == binding.policies.size()) {
            var control = J7mContext.currentInvocation().control();
            if (ManagedInvocationLifetime.active() && control.cancellationRequested()) {
                throw MiddlewareException.of(control.remainingBudget().isZero() ? MiddlewareErrorCode.DEADLINE_EXCEEDED
                        : MiddlewareErrorCode.CANCELLED, "Managed invocation budget ended before target entry");
            }
            var owned = invocationScope.get();
            if (owned != null) { owned.markApplicationEntry(); }
            Object result = service.hosted ? directHosted((org.j7m.middleware.application.api.ManagedHostedAdapter<?>) service.adapter, target(service), binding.contract.method(), arguments)
                    : direct(service.adapter, target(service), binding.contract.method(), arguments);
            if ((binding.contract.method().returnType().equals("java.util.concurrent.CompletionStage")
                    || binding.contract.method().returnType().equals("java.util.concurrent.CompletableFuture"))
                    && !(result instanceof java.util.concurrent.CompletionStage<?>)) {
                throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "Managed async method returned no result stage");
            }
            return result;
        }
        Binding current = binding.policies().get(index);
        Proceed continuation = new Proceed() {
            @Override public Object invoke() throws Exception { return around(service, binding, index + 1, arguments, reference); }
            @Override public ObjectRef target() { return reference; }
            @Override public java.util.concurrent.CompletionStage<Void> physicalCompletion() {
                return ManagedInvocationLifetime.currentPhysicalCompletion();
            }
            @Override public boolean supportsResultFinalizers() {
                var owned = invocationScope.get(); return owned != null && owned.supportsResultFinalizers();
            }
            @Override public void addResultFinalizer(final org.j7m.middleware.transport.api.InvocationResultFinalizer finalizer) {
                requireInvocationScope().addResultFinalizer(finalizer);
            }
            @Override public <T> void putContext(final Class<T> type, final T value) {
                requireInvocationScope().putContext(type, value);
            }
            @Override public <T> T context(final Class<T> type) { return ManagedInvocationLifetime.currentContext(type); }
            @Override public <T> java.util.Optional<T> findContext(final Class<T> type) { return requireInvocationScope().findContext(type); }
        };
        return current.invoke(J7mContext.currentInvocation(), arguments, continuation);
    }

    private Object target(final Service service) { return container.get(service.adapter.type(), service.adapter.name(), service.adapter.qualifiers()); }

    @SuppressWarnings("unchecked")
    private static <T> Object direct(final ManagedHostAdapter<T> adapter, final Object target, final ManagedMethod method, final Object[] arguments) throws Exception {
        return adapter.invoke((T) target, method, arguments);
    }

    @SuppressWarnings("unchecked")
    private static <T> Object directHosted(final org.j7m.middleware.application.api.ManagedHostedAdapter<T> adapter,
            final Object target, final ManagedMethod method, final Object[] arguments) throws Exception {
        return adapter.invokeHosted((T) target, method, arguments);
    }

    private Service service(final String name) {
        Service service = services.get(name);
        if (service == null) { throw invalid("managed service is not declared"); }
        return service;
    }

    @Override public void close() {
        if (closed) { return; }
        RuntimeException aggregate = null;
        if (!hostedClosed && !hostedBindings.isEmpty()) {
            if (hostedStarted) throw invalid("hosted resources require actual drain before cleanup");
            for (var binding : hostedBindings.reversed()) {
                try { binding.close(); } catch (Exception failure) { aggregate = invalid("hosted resource cleanup failed"); }
            }
            hostedBindings.clear(); hostedClosed = true;
        }
        if (container != null) {
            try { container.close(); }
            catch (RuntimeException failure) {
                if (!container.status().closed()) throw failure;
                aggregate = invalid("managed component cleanup failed");
            }
        }
        closed = true;
        for (var policy : ownedPolicies.reversed()) {
            try { policy.close(); } catch (Exception failure) { aggregate = invalid("managed policy cleanup failed"); }
        }
        ownedPolicies.clear(); services.clear(); references.clear(); probeBindings.clear(); configuration.close();
        if (aggregate != null) { throw aggregate; }
    }

    private static MiddlewareException invalid(final String reason) { return MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, reason); }
}
