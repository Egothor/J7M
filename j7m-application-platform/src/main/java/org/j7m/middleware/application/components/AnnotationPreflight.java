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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.j7m.middleware.annotations.Backpressure;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.IsolationProfile;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.Named;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.annotations.RateLimited;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RequiresTrustDomain;
import org.j7m.middleware.annotations.ResourceRequirement;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;

/**
 * Explicit version-one annotation contract preflight. It reads metadata only from supplied
 * classes, their interfaces and superclasses, without construction, classpath scanning or opening
 * JPMS packages. It is not wired into ApplicationManager; successful validation neither deploys
 * an application nor proves that declared host capabilities are enforced. Instances are thread-safe.
 */
public final class AnnotationPreflight {
    private static final String PREFIX = "org.j7m.middleware.annotations.";
    private static final Set<String> KNOWN = Set.of(
            "LeaseGuard", "LeaderTask", "PartitionHandler", "QueuePublisher", "ReconciliationHandler", "DurableJob", "Schedule", "EventSubscription", "JdbcTransaction", "ResourceTransaction", "Deduplicated", "IdempotencyKey", "CircuitBreaker", "Bulkhead", "Observed",
            "Async", "Audit", "Backpressure", "BackpressureAware", "Benchmark", "Callback", "Cancellable",
            "Checkpoint", "Compensation", "Confidential", "ConfigValue", "CurrentApplication", "CurrentInvocation",
            "CurrentPrincipal", "DataLocality", "DeadLetterHandler", "Deadline", "Drain", "DurableQueueConsumer",
            "HealthCheck", "HighAvailability", "Idempotent", "InboxHandler", "Initialize", "Inject", "IsolationProfile",
            "SchemaType", "WireField", "Qualifier", "Scope", "Produces", "ConfigProperties",
            "J7mApplication", "J7mComponent", "J7mResource", "J7mService", "LocalOnly", "LongRunning", "Named",
            "NoRemoteExport", "NonIdempotent", "OneWay", "OrderedBy", "OutboxPublisher", "PreferLocal", "Priority",
            "Progress", "RateLimited", "ReadinessCheck", "RemoteMethod", "RemoteService", "RequiresPermission",
            "RequiresRole", "RequiresTrustDomain", "ResourceRequirement", "Restore", "Retry", "SagaDefinition",
            "SagaStep", "Sensitive", "ShardKey", "Shutdown", "Startup", "SwitchLevel");
    private final AnnotationPolicyRegistry registry;

    /**
     * Versioned validation mode; selecting managed mode is an explicit caller action.
     */
    public enum Profile {
        /**
         * Migration warnings only; legacy injector semantics remain unchanged.
         */ LEGACY,
        /**
         * Strict contract errors, including missing trusted host capability declarations.
         */ MANAGED_V1
    }

    /**
     * Diagnostic severity; only ERROR prevents requireValid from returning normally.
     */
    public enum Severity {
        /**
         * Migration information, without rejecting a legacy caller.
         */ WARNING,
        /**
         * Contract rejection in the managed profile.
         */ ERROR
    }

    /**
     * Safe deterministic diagnostic; arbitrary annotation arguments and exception messages are omitted.
     * @param severity rejection or migration warning
     * @param code stable machine-readable reason
     * @param component inspected implementation binary name
     * @param fullSignature exact inspected location, including parameter position where relevant
     * @param annotationType annotation binary name for capability diagnostics, or empty when unrelated
     * @param policyKind policy namespace, or empty when unrelated
     * @param policyId reserved safe identifier; currently empty so untrusted policy values cannot leak
     */
    public record Diagnostic(Severity severity, String code, String component, String fullSignature,
            String annotationType, String policyKind, String policyId) { }

    /**
     * Full erased method identity; overloads, declaring owners and contract versions remain distinct.
     * @param contractId explicit stable contract identifier
     * @param contractVersion positive contract version
     * @param declaringType declaring binary type name
     * @param methodName Java method name
     * @param parameterTypes ordered erased binary parameter names
     * @param returnType erased binary return name
     */
    public record MethodIdentity(String contractId, int contractVersion, String declaringType,
            String methodName, List<String> parameterTypes, String returnType) {
        /**
         * Copies the ordered parameter types.
         */
        public MethodIdentity {
            parameterTypes = List.copyOf(parameterTypes);
        }
    }

    /**
     * Immutable validation result, not runtime acceptance or deployment authorization.
     * @param profile explicit validation mode
     * @param contractId inspected contract identity
     * @param contractVersion inspected version
     * @param methods deterministic full method identities, excluding bridge/synthetic/Object methods
     * @param diagnostics deterministic safe diagnostics
     */
    public record Report(Profile profile, String contractId, int contractVersion,
            List<MethodIdentity> methods, List<Diagnostic> diagnostics) {
        /**
         * Defensively copies validation evidence.
         */
        public Report {
            methods = List.copyOf(methods);
            diagnostics = List.copyOf(diagnostics);
        }
        /**
         * Tests only this preflight's diagnostics, not host enforcement.
         * @return whether no ERROR diagnostic is present
         */
        public boolean valid() {
            return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
        }
        /**
         * Fails before caller-controlled construction/export when managed validation rejected input.
         * @throws MiddlewareException INVALID_ARGUMENT with a fixed redacted summary if invalid
         */
        public void requireValid() {
            if (!valid()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Annotation preflight rejected; inspect safe diagnostics");
            }
        }
    }

    /**
     * Creates a validator with explicitly trusted host setup; no runtime behaviour is installed.
     * @param registry immutable host policy and capability declarations
     * @throws NullPointerException if registry is null
     */
    public AnnotationPreflight(final AnnotationPolicyRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /**
     * Validates an explicit contract surface without constructing components or invoking hooks.
     * Conflicting explicit scalar values reject across all relevant declaration levels; this
     * baseline does not approve a narrowing/override without a future verified adapter contract.
     * Only an interface contributing the inspected method participates in its type defaults.
     * Roles/permissions are cumulative requirements, never an override that relaxes another declaration.
     * Different trust domains, remote/local or idempotency declarations conflict. Custom composed
     * annotations and Jakarta Inject are reported as unsupported, not silently treated as implemented.
     * Cyclic metadata or metadata deeper than eight edges is rejected conservatively, even if no
     * J7M marker has yet been found; reflection is not an untrusted-code sandbox.
     * @param profile explicit legacy or strict managed mode
     * @param contractId stable identifier, at most128 characters
     * @param contractVersion positive contract version
     * @param implementation concrete inspected type
     * @param contracts explicit implemented interface roots, at most32
     * @return immutable deterministic report without arbitrary annotation values
     * @throws IllegalArgumentException for malformed input, nonimplemented contracts or excessive surface
     */
    public Report inspect(final Profile profile, final String contractId, final int contractVersion,
            final Class<?> implementation, final List<Class<?>> contracts) {
        if (profile == null || !AnnotationPolicyRegistry.identifier(contractId) || contractVersion < 1
                || implementation == null || implementation.isInterface() || Modifier.isAbstract(implementation.getModifiers())
                || contracts == null || contracts.size() > 32) {
            throw new IllegalArgumentException("Invalid annotation preflight input");
        }
        final Set<Class<?>> roots = new HashSet<>();
        roots.add(implementation);
        for (Class<?> contract : contracts) {
            if (contract == null || !contract.isInterface() || !contract.isAssignableFrom(implementation)) {
                throw new IllegalArgumentException("Invalid implemented contract");
            }
            roots.add(contract);
        }
        final Set<Class<?>> types = new HashSet<>();
        for (Class<?> root : roots) {
            hierarchy(root, types);
        }
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final Map<String, List<Method>> groups = new TreeMap<>();
        final Map<Class<?>, List<Annotation>> defaults = new TreeMap<>(Comparator.comparing(Class::getName));
        final Map<String, MethodIdentity> identities = new TreeMap<>();
        for (Class<?> type : types.stream().sorted(Comparator.comparing(Class::getName)).toList()) {
            checkElement(type, type.getName(), implementation, profile, diagnostics);
            defaults.put(type, List.of(type.getDeclaredAnnotations()));
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                checkElement(constructor, constructor.toGenericString(), implementation, profile, diagnostics);
                checkParameters(constructor.getParameters(), constructor.toGenericString(), implementation, profile, diagnostics);
            }
            if (Arrays.stream(type.getDeclaredConstructors()).filter(constructor -> constructor.isAnnotationPresent(Inject.class)).count() > 1) {
                add(diagnostics, profile, "MULTIPLE_INJECTION_CONSTRUCTORS", implementation, type.getName(), "", "");
            }
            for (Field field : type.getDeclaredFields()) {
                final String location = type.getName() + "#" + field.getName();
                checkElement(field, location, implementation, profile, diagnostics);
                if (field.isAnnotationPresent(Inject.class) && (Modifier.isFinal(field.getModifiers()) || Modifier.isStatic(field.getModifiers()))) {
                    add(diagnostics, profile, "INVALID_INJECTION_FIELD", implementation, location, "", "");
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                final String location = signature(method);
                checkElement(method, location, implementation, profile, diagnostics);
                checkParameters(method.getParameters(), location, implementation, profile, diagnostics);
                final MethodIdentity identity = new MethodIdentity(contractId, contractVersion, type.getName(), method.getName(),
                        Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
                identities.put(location, identity);
                if (identities.size() > 1024) {
                    throw new IllegalArgumentException("Annotation method surface limit");
                }
                groups.computeIfAbsent(method.getName() + Arrays.toString(method.getParameterTypes()), ignored -> new ArrayList<>()).add(method);
            }
        }
        for (List<Method> group : groups.values()) {
            final List<Annotation> annotations = group.stream().flatMap(method -> Arrays.stream(method.getDeclaredAnnotations())).toList();
            final Method representative = group.getFirst();
            final List<Annotation> relevantDefaults = defaults.entrySet().stream()
                    .filter(entry -> !entry.getKey().isInterface() || contributes(entry.getKey(), representative))
                    .flatMap(entry -> entry.getValue().stream()).toList();
            checkGroup(annotations, relevantDefaults, representative, implementation, profile, diagnostics);
        }
        final List<Diagnostic> sorted = diagnostics.stream().distinct().sorted(Comparator.comparing(Diagnostic::component)
                .thenComparing(Diagnostic::fullSignature).thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::annotationType).thenComparing(Diagnostic::policyKind).thenComparing(Diagnostic::policyId)).toList();
        return new Report(profile, contractId, contractVersion, List.copyOf(identities.values()), sorted);
    }

    private static void hierarchy(final Class<?> type, final Set<Class<?>> types) {
        if (type == null || type == Object.class || !types.add(type)) {
            return;
        }
        if (types.size() > 64) {
            throw new IllegalArgumentException("Annotation hierarchy limit");
        }
        hierarchy(type.getSuperclass(), types);
        for (Class<?> parent : type.getInterfaces()) {
            hierarchy(parent, types);
        }
    }

    private void checkParameters(final Parameter[] parameters, final String location, final Class<?> implementation,
            final Profile profile, final List<Diagnostic> diagnostics) {
        for (int index = 0; index < parameters.length; index++) {
            checkElement(parameters[index], location + "[" + index + "]", implementation, profile, diagnostics);
        }
    }

    private void checkElement(final AnnotatedElement element, final String location, final Class<?> implementation,
            final Profile profile, final List<Diagnostic> diagnostics) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            final Class<? extends Annotation> type = annotation.annotationType();
            if (type.getName().startsWith(PREFIX)) {
                if (!KNOWN.contains(type.getName().substring(PREFIX.length()))) {
                    capabilityDiagnostic(diagnostics, profile, "UNKNOWN_ANNOTATION", implementation, location, type);
                } else if (!registry.supports(type)) {
                    capabilityDiagnostic(diagnostics, profile, "UNSUPPORTED_BEHAVIOR", implementation, location, type);
                }
            } else if (type.getName().startsWith("jakarta.inject.")) {
                capabilityDiagnostic(diagnostics, profile, "UNSUPPORTED_JAKARTA_INTEROP", implementation, location, type);
            } else {
                final MetaState state = j7mMeta(type, new HashSet<>(), 0);
                if (state != MetaState.ABSENT) {
                    capabilityDiagnostic(diagnostics, profile, state == MetaState.PRESENT
                            ? "UNSUPPORTED_COMPOSED_ANNOTATION" : "UNSUPPORTED_METADATA_GRAPH", implementation, location, type);
                }
            }
            if (profile == Profile.LEGACY && (annotation instanceof Named named && !named.value().isEmpty()
                    || annotation instanceof J7mResource resource && !resource.value().isEmpty())) {
                capabilityDiagnostic(diagnostics, Profile.LEGACY, "LEGACY_QUALIFIER_FALLBACK_POSSIBLE", implementation, location, type);
            }
            if (profile == Profile.LEGACY && Set.of("CurrentInvocation", "CurrentPrincipal", "CurrentApplication")
                    .contains(type.getSimpleName()) && type.getName().startsWith(PREFIX)) {
                capabilityDiagnostic(diagnostics, Profile.LEGACY, "LEGACY_CONTEXT_CAPTURE", implementation, location, type);
            }
            if (annotation instanceof RateLimited rate) {
                checkPolicy(AnnotationPolicyRegistry.Kind.RATE_LIMIT, rate.value().isEmpty() ? "default" : rate.value(), location, implementation, profile, diagnostics);
            } else if (annotation instanceof ResourceRequirement resource) {
                checkPolicy(AnnotationPolicyRegistry.Kind.RESOURCE, resource.value(), location, implementation, profile, diagnostics);
            } else if (annotation instanceof IsolationProfile isolation) {
                checkPolicy(AnnotationPolicyRegistry.Kind.ISOLATION, isolation.value(), location, implementation, profile, diagnostics);
            }
            if (annotation instanceof Deadline deadline && deadline.millis() <= 0
                    || annotation instanceof Retry retry && retry.maxAttempts() < 0
                    || annotation instanceof Backpressure backpressure && (backpressure.maxConcurrent() == 0 || backpressure.maxConcurrent() < -1)) {
                add(diagnostics, profile, "INVALID_POLICY_BOUND", implementation, location, "", "");
            }
        }
        final J7mService service = element.getDeclaredAnnotation(J7mService.class);
        if (service != null && service.exported() && element.isAnnotationPresent(NoRemoteExport.class)) {
            add(diagnostics, profile, "CONFLICTING_EXPORT_CONTRACT", implementation, location, "", "");
        }
        final Named named = element.getDeclaredAnnotation(Named.class);
        final J7mResource resource = element.getDeclaredAnnotation(J7mResource.class);
        if (named != null && resource != null && !resource.value().isEmpty() && !named.value().equals(resource.value())) {
            add(diagnostics, profile, "CONFLICTING_QUALIFIERS", implementation, location, "", "");
        }
    }

    private void checkPolicy(final AnnotationPolicyRegistry.Kind kind, final String name, final String location,
            final Class<?> implementation, final Profile profile, final List<Diagnostic> diagnostics) {
        if (!AnnotationPolicyRegistry.identifier(name)) {
            add(diagnostics, profile, "INVALID_POLICY_REFERENCE", implementation, location, kind.name(), "");
        } else if (!registry.contains(kind, name)) {
            add(diagnostics, profile, "UNKNOWN_POLICY", implementation, location, kind.name(), "");
        }
    }

    private static void checkGroup(final List<Annotation> methods, final List<Annotation> defaults, final Method method,
            final Class<?> implementation, final Profile profile, final List<Diagnostic> diagnostics) {
        final String location = signature(method);
        if (has(methods, RemoteMethod.class) && (has(methods, LocalOnly.class) || has(defaults, LocalOnly.class))
                || has(methods, Idempotent.class) && has(methods, NonIdempotent.class)) {
            add(diagnostics, profile, "CONFLICTING_METHOD_CONTRACT", implementation, location, "", "");
        }
        if (has(methods, OneWay.class) && method.getReturnType() != void.class) {
            add(diagnostics, profile, "ONEWAY_REQUIRES_VOID", implementation, location, "", "");
        }
        for (Class<? extends Annotation> scalar : List.of(Deadline.class, Retry.class, Backpressure.class,
                RateLimited.class, ResourceRequirement.class, IsolationProfile.class)) {
            final List<Annotation> effective = new ArrayList<>(defaults.stream().filter(scalar::isInstance).toList());
            effective.addAll(methods.stream().filter(scalar::isInstance).toList());
            if (effective.stream().distinct().count() > 1) {
                add(diagnostics, profile, "CONFLICTING_POLICY_DECLARATIONS", implementation, location, "", "");
            }
        }
        final List<Annotation> security = new ArrayList<>(defaults);
        security.addAll(methods);
        if (security.stream().filter(RequiresTrustDomain.class::isInstance).distinct().count() > 1) {
            add(diagnostics, profile, "CONFLICTING_TRUST_DOMAINS", implementation, location, "", "");
        }
    }

    private static boolean contributes(final Class<?> type, final Method method) {
        try {
            type.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static void capabilityDiagnostic(final List<Diagnostic> diagnostics, final Profile profile,
            final String code, final Class<?> implementation, final String location,
            final Class<? extends Annotation> annotation) {
        if (diagnostics.size() >= 4096) {
            throw new IllegalArgumentException("Annotation diagnostic limit");
        }
        diagnostics.add(new Diagnostic(profile == Profile.MANAGED_V1 ? Severity.ERROR : Severity.WARNING,
                code, implementation.getName(), location, annotation.getName(), "", ""));
    }

    private static boolean has(final List<Annotation> annotations, final Class<? extends Annotation> type) {
        return annotations.stream().anyMatch(type::isInstance);
    }

    private enum MetaState { ABSENT, PRESENT, LIMIT }

    private static MetaState j7mMeta(final Class<? extends Annotation> type, final Set<Class<?>> visited, final int depth) {
        if (depth > 8 || !visited.add(type)) {
            return MetaState.LIMIT;
        }
        MetaState result = MetaState.ABSENT;
        for (Annotation meta : type.getDeclaredAnnotations()) {
            if (meta.annotationType().getName().startsWith(PREFIX)) {
                result = MetaState.PRESENT;
                break;
            }
            if (!meta.annotationType().getName().startsWith("java.lang.annotation.")) {
                final MetaState nested = j7mMeta(meta.annotationType(), visited, depth + 1);
                if (nested == MetaState.PRESENT) {
                    result = nested;
                    break;
                }
                if (nested == MetaState.LIMIT) {
                    result = nested;
                }
            }
        }
        visited.remove(type);
        return result;
    }

    private static String signature(final Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + "("
                + String.join(",", Arrays.stream(method.getParameterTypes()).map(Class::getName).toList())
                + "):" + method.getReturnType().getName();
    }

    private static void add(final List<Diagnostic> diagnostics, final Profile profile, final String code,
            final Class<?> implementation, final String location, final String kind, final String policy) {
        if (diagnostics.size() >= 4096) {
            throw new IllegalArgumentException("Annotation diagnostic limit");
        }
        diagnostics.add(new Diagnostic(profile == Profile.MANAGED_V1 ? Severity.ERROR : Severity.WARNING,
                code, implementation.getName(), location, "", kind, policy));
    }
}
