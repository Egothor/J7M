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

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URISyntaxException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.PreparedObjectRegistration;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.application.api.ActivationIdentity;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRepository;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.AuditPublisher;
import org.j7m.middleware.application.api.ConfigurationView;
import org.j7m.middleware.application.api.InvocationApplicationContext;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.application.api.J7mApplication;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.MetricsPublisher;
import org.j7m.middleware.application.api.ManagedApplicationProbe;
import org.j7m.middleware.application.api.RuntimeServiceIdentity;
import org.j7m.middleware.application.api.ServiceExporter;
import org.j7m.middleware.application.components.ManagedHostingOptions;
import org.j7m.middleware.application.components.ManagedProbeCoordinator;
import org.j7m.middleware.application.components.ManagedProbeMonitor;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.j7m.middleware.transport.api.InvocationBoundary;
import org.j7m.middleware.transport.api.InvocationCompletionScope;
import org.j7m.middleware.application.api.ManagedProbeKind;
import org.j7m.middleware.application.internal.BoundedBundleIO;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SignaturePolicy;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.ResourceUsageSnapshot;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.TrustDomain;

/**
 * Runtime-adjacent manager for classloader-based live application activation.
 *
 * <p>The manager is thread-safe. It owns extracted bundle directories,
 * application classloaders, lifecycle objects, and registrations it creates.
 * It delegates service export and registry binding to the existing Phase 1
 * runtime API and does not depend on concrete transport implementations.</p>
 */
public final class ApplicationManager {
    private static final int MAX_PAYLOAD_ACCOUNTING_DEPTH = 8;
    private static final int MAX_PAYLOAD_ACCOUNTING_OBJECTS = 1024;
    private final MiddlewareRuntime runtime;
    private final ApplicationRepository repository;
    private final ApplicationBundleVerifier verifier;
    private final ApplicationPlatformLimits limits;
    private final ManagedHostingOptions managedHostingOptions;
    private ManagedProbeMonitor probeMonitor;
    private java.util.concurrent.ThreadPoolExecutor invocationExecutor;
    private final ConcurrentMap<String, ManagedActivation> activations;
    private final Map<String, ApplicationActivation> historicalActivations;
    private final Map<String, ApplicationCoordinate> rollbackCandidates;
    private final Map<String, String> rollbackRetainedInstances;
    private final List<String> upgradeHistory;
    private final List<String> auditEvents;

    /**
     * Creates an application manager.
     *
     * @param runtime runtime used for service export and registry binding
     * @param repository application repository used to fetch bundles
     */
    public ApplicationManager(final MiddlewareRuntime runtime, final ApplicationRepository repository) {
        this(runtime, repository, ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates an application manager with explicit application-platform limits.
     *
     * @param runtime runtime used for service export and registry binding
     * @param repository application repository used to fetch bundles
     * @param limits bundle extraction and metadata limits
     */
    public ApplicationManager(
            final MiddlewareRuntime runtime,
            final ApplicationRepository repository,
            final ApplicationPlatformLimits limits) {
        this(runtime, repository, limits, ManagedHostingOptions.defaults());
    }

    /**
     * Creates a manager with explicit trusted managed-profile capabilities.
     * Existing activation requests remain legacy unless j7m.annotations.profile is managed-v1.
     * @param runtime runtime owning authenticated transports and exports
     * @param repository verified application repository
     * @param limits extraction and admission bounds, or null for defaults
     * @param managedHostingOptions host-owned finite integrations/policies; never bundle supplied
     * @throws IllegalArgumentException for missing runtime or repository
     * @throws NullPointerException for missing host options
     */
    public ApplicationManager(final MiddlewareRuntime runtime, final ApplicationRepository repository,
            final ApplicationPlatformLimits limits, final ManagedHostingOptions managedHostingOptions) {
        this(runtime, repository, limits, managedHostingOptions,
                org.j7m.middleware.application.repository.PublicKeyTrustStore.localDevelopment());
    }

    /**
     * Creates a host with explicit operator-owned bundle-signing trust.
     * Signature keys are independent of application metadata and repository verification records.
     * Older constructors retain local development trust for compatibility.
     * @param runtime authenticated runtime and export owner
     * @param repository application artifact repository
     * @param limits extraction and metadata bounds, or null for defaults
     * @param managedHostingOptions explicit host integration capabilities
     * @param signingTrust immutable operator-provided public signing keys; empty denies signed artifacts
     * @throws IllegalArgumentException for missing runtime or repository
     * @throws NullPointerException for missing options or trust store
     */
    public ApplicationManager(final MiddlewareRuntime runtime, final ApplicationRepository repository,
            final ApplicationPlatformLimits limits, final ManagedHostingOptions managedHostingOptions,
            final org.j7m.middleware.application.repository.PublicKeyTrustStore signingTrust) {
        this.managedHostingOptions = java.util.Objects.requireNonNull(managedHostingOptions);
        if (runtime == null || repository == null) {
            throw new IllegalArgumentException("runtime and repository are required");
        }
        this.runtime = runtime;
        this.repository = repository;
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        this.verifier = new ApplicationBundleVerifier(java.util.Objects.requireNonNull(signingTrust), this.limits);
        this.activations = new ConcurrentHashMap<>();
        this.historicalActivations = Collections.synchronizedMap(new LinkedHashMap<>());
        this.rollbackCandidates = Collections.synchronizedMap(new LinkedHashMap<>());
        this.rollbackRetainedInstances = Collections.synchronizedMap(new LinkedHashMap<>());
        this.upgradeHistory = java.util.Collections.synchronizedList(new ArrayList<>());
        this.auditEvents = java.util.Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * Creates an activation plan for an application coordinate.
     *
     * @param coordinate application coordinate to plan
     * @return activation plan
     */
    public ApplicationDeploymentPlan planActivation(final ApplicationCoordinate coordinate) {
        return this.planActivation(coordinate, false, Map.of());
    }

    /**
     * Creates an activation plan without mutating runtime state.
     *
     * @param coordinate application coordinate to plan
     * @param strictVerification whether strict verification is required
     * @param configuration safe planning configuration
     * @return activation plan
     */
    public ApplicationDeploymentPlan planActivation(
            final ApplicationCoordinate coordinate,
            final boolean strictVerification,
            final Map<String, String> configuration) {
        List<String> steps = new ArrayList<>();
        try {
            ApplicationBundle bundle = this.repository.fetch(coordinate);
            SignaturePolicy policy = ApplicationBundleVerifier.policy(
                    configuration == null ? null : configuration.get("verificationProfile"), strictVerification);
            if (this.repository instanceof LocalApplicationRepository localRepository
                    && !localRepository.verifiedForActivation(coordinate, policy)) {
                steps.add("ERROR: exact artifact digest does not have a successful verification record");
                return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.REJECTED, steps);
            }
            org.j7m.middleware.application.api.ApplicationVerificationResult verification = this.verifier.verify(bundle, policy);
            if (!verification.accepted()) {
                steps.add("ERROR: verification rejected");
                return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.REJECTED, steps);
            }
            ApplicationManifest manifest = this.verifier.manifest(bundle);
            ResourceLimits limits = ResourceLimits.from(manifest.resources());
            this.enforceActivationResourcePolicy(limits, configuration == null ? Map.of() : configuration);
            if (!manifest.javaVersion().isBlank()
                    && Integer.parseInt(System.getProperty("java.specification.version").replaceAll("[^0-9]", ""))
                    < Integer.parseInt(manifest.javaVersion().replaceAll("[^0-9]", ""))) {
                steps.add("ERROR: Java runtime is below application requirement");
                return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.REJECTED, steps);
            }
            if (!"CLASSPATH".equals(manifest.moduleMode()) && !"JPMS".equals(manifest.moduleMode())) {
                steps.add("ERROR: unsupported module mode");
                return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.REJECTED, steps);
            }
            steps.add("verify bundle digest " + bundle.sha256());
            steps.add("verify security and resource policy");
            steps.add("create isolated " + manifest.moduleMode() + " runtime");
            steps.add("instantiate " + manifest.bootstrapClass());
            steps.add("export " + manifest.services().size() + " declared services");
            steps.add("start application");
            this.emitAudit("application.plan", Map.of("coordinate", coordinate.toString(), "result", "success"));
            return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.PLANNED, steps);
        } catch (RuntimeException exception) {
            steps.add("ERROR: " + safeMessage(exception));
            this.emitAudit("application.plan", Map.of("coordinate", coordinate.toString(), "result", "rejected"));
            return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.REJECTED, steps);
        }
    }

    /**
     * Activates an application from a repository coordinate.
     *
     * @param coordinate application coordinate to activate
     * @param request activation request
     * @return activation snapshot
     */
    public ApplicationActivation activate(final ApplicationCoordinate coordinate, final ApplicationActivationRequest request) {
        ApplicationActivationRequest safeRequest = request == null ? new ApplicationActivationRequest("system", "dev", false, Map.of()) : request;
        String annotationProfile = safeRequest.configuration().getOrDefault("j7m.annotations.profile", "legacy");
        if (!Set.of("legacy", "managed-v1").contains(annotationProfile)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported annotation profile");
        }
        String requestedVerification = safeRequest.configuration().getOrDefault("verificationProfile", "");
        String normalizedVerification = requestedVerification.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if ("managed-v1".equals(annotationProfile) && !normalizedVerification.isEmpty()
                && !Set.of("strict-regulated", "regulated", "production-regulated", "strict-production", "production", "strict").contains(normalizedVerification)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "managed hosting requires a signed verification profile");
        }
        SignaturePolicy policy = ApplicationBundleVerifier.policy(requestedVerification, safeRequest.strictVerification());
        if ("managed-v1".equals(annotationProfile) && !policy.signatureRequired()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "managed hosting requires signed verification");
        }
        ApplicationBundle bundle = this.repository.fetch(coordinate);
        if (this.repository instanceof LocalApplicationRepository localRepository
                && !localRepository.verifiedForActivation(coordinate, policy)) {
            this.emitAudit("application.activate", Map.of(
                    "coordinate", coordinate.toString(),
                    "result", "denied",
                    "reason", "missing-or-stale-verification-record"));
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED,
                    "application activation requires a successful repository verification record");
        }
        org.j7m.middleware.application.api.ApplicationVerificationResult verification = this.verifier.verify(bundle, policy);
        if (!verification.accepted()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application bundle verification failed");
        }
        ApplicationManifest manifest = this.verifier.manifest(bundle);
        ResourceLimits limits = ResourceLimits.from(manifest.resources());
        this.enforceActivationResourcePolicy(limits, safeRequest.configuration());
        ApplicationIdentity applicationIdentity = manifest.identity();
        ApplicationInstanceId instanceId = new ApplicationInstanceId("app-" + UUID.randomUUID());
        ActivationIdentity activationId = new ActivationIdentity("activation-" + UUID.randomUUID());
        ApplicationInstanceIdentity instanceIdentity = new ApplicationInstanceIdentity(instanceId, applicationIdentity, this.runtime.identity(), manifest.moduleMode());
        ManagedActivation activation = new ManagedActivation(activationId, instanceIdentity);
        this.activations.put(instanceId.value(), activation);
        try {
            activation.manifest = manifest;
            activation.activationRequest = safeRequest;
            activation.managedProfile = "managed-v1".equals(annotationProfile);
            activation.resourceLimits = limits;
            activation.artifactDigest = bundle.sha256();
            activation.operatorPrincipal = safeRequest.principalId();
            activation.verificationProfile = policy.name();
            activation.verificationSummary = "accepted";
            activation.resourcePolicySummary = "accepted";
            activation.securityPolicySummary = "accepted";
            activation.correlationId = UUID.randomUUID().toString();
            this.transition(activation, ApplicationRunState.LOADING, "extract-bundle");
            Path extracted = this.extractBundle(bundle);
            activation.extractedDirectory = extracted;
            Principal principal = new Principal(safeRequest.principalId(), safeRequest.principalId(), java.util.Set.of("application-deployer"), java.util.Set.of());
            ApplicationContext context = this.context(manifest, instanceIdentity, principal, safeRequest.configuration(), activation);
            if ("JPMS".equals(manifest.moduleMode())) {
                this.startJpmsActivation(manifest, activation, context);
            } else {
                this.startClasspathActivation(manifest, activation, context);
            }
            this.startProbes(activation);
            List<PreparedObjectRegistration<?>> preparedServices = this.prepareServices(manifest, activation);
            this.commitPreparedServices(activation, preparedServices);
            synchronized (activation) {
                if (activation.managedPojo != null) activation.managedPojo.commitHostedAdmission();
                this.transition(activation, ApplicationRunState.RUNNING, "readiness-commit");
                activation.acceptingInvocations = true;
                if (activation.managedPojo != null) activation.managedPojo.openHostedAdmission();
            }
            this.emitAudit("application.activate", Map.of(
                    "coordinate", coordinate.toString(),
                    "instanceId", instanceId.value(),
                    "moduleMode", manifest.moduleMode(),
                    "result", "success"));
            return activation.snapshot();
        } catch (MiddlewareException exception) {
            this.emitAudit("application.activate", Map.of(
                    "coordinate", coordinate.toString(),
                    "instanceId", instanceId.value(),
                    "result", "failed"));
            this.markFailure(activation, exception.code().name(), safeMessage(exception));
            if (exception instanceof org.j7m.middleware.application.components.ManagedInjectionException diagnostic) activation.injectionDiagnostic = diagnostic.diagnostic();
            this.rollbackFailedActivation(activation);
            throw exception;
        } catch (ReflectiveOperationException | IOException | RuntimeException exception) {
            this.emitAudit("application.activate", Map.of(
                    "coordinate", coordinate.toString(),
                    "instanceId", instanceId.value(),
                    "result", "failed"));
            this.markFailure(activation, MiddlewareErrorCode.INTERNAL.name(), safeMessage(exception));
            this.rollbackFailedActivation(activation);
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "application activation failed",
                    Map.of(), null, this.runtime.identity().runtimeId(), false, true), exception);
        }
    }

    /**
     * Deactivates a running application instance.
     *
     * @param instanceId active instance id
     * @return deactivation result
     */
    public ApplicationDeploymentResult deactivate(final ApplicationInstanceId instanceId) {
        ManagedActivation activation = this.activations.get(instanceId.value());
        if (activation == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found");
        }
        if (activation.managedPojo != null && activation.managedPojo.hasHostedResources()) {
            synchronized (activation) {
                activation.acceptingInvocations = false;
                activation.managedPojo.stopHostedAdmission();
                this.closeProbes(activation);
                if (activation.state != ApplicationRunState.DRAINING) {
                    this.transition(activation, ApplicationRunState.DRAINING, "hosted-resource-drain");
                    try { activation.managedPojo.drain(); }
                    catch (RuntimeException failure) { this.recordLifecycleCleanupFailure(activation, failure, "drain"); }
                }
            }
            boolean drained;
            try { drained = activation.managedPojo.drainHosted(); }
            catch (RuntimeException failure) {
                this.recordLifecycleCleanupFailure(activation, failure, "hosted-resource-drain"); drained = false;
            }
            if (!drained) {
                return new ApplicationDeploymentResult(false, ApplicationRunState.DRAINING, "hosted resources are still completing", List.of(instanceId.value()));
            }
        }
        synchronized (activation) {
            if (activation.state == ApplicationRunState.STOPPED) {
                return new ApplicationDeploymentResult(true, ApplicationRunState.STOPPED, "application already stopped", List.of(instanceId.value()));
            }
            if (activation.state == ApplicationRunState.UNLOADED) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application instance is unloaded");
            }
            activation.acceptingInvocations = false;
            this.closeProbes(activation);
            ApplicationContext context = this.context(null, activation.identity, Principal.runtime(this.runtime.identity().runtimeId()), Map.of(), activation);
            int lifecycleFailures = 0;
            if (activation.state != ApplicationRunState.DRAINING) {
                this.transition(activation, ApplicationRunState.DRAINING, "drain");
                try {
                    if (activation.managedPojo != null) {
                        activation.managedPojo.drain();
                    } else if (activation.application != null) {
                        activation.application.drain(context);
                    } else if (activation.applicationObject != null) {
                        this.invokeJpmsLifecycle(activation.applicationObject, "drain", activation.layerContextProxy);
                    }
                } catch (RuntimeException exception) {
                    lifecycleFailures++;
                    this.recordLifecycleCleanupFailure(activation, exception, "drain");
                }
            }
            if (activation.managedPojo != null && activation.activeInvocations.get() != 0) {
                return new ApplicationDeploymentResult(false, ApplicationRunState.DRAINING,
                        "managed invocations are still completing", List.of(instanceId.value()));
            }
            this.transition(activation, ApplicationRunState.STOPPING, "stop");
            this.closeRegistrations(activation);
            activation.heartbeatStopped = true;
            try {
                if (activation.managedPojo != null) {
                    activation.managedPojo.close();
                } else if (activation.application != null) {
                    activation.application.stop(context);
                } else if (activation.applicationObject != null) {
                    this.invokeJpmsLifecycle(activation.applicationObject, "stop", activation.layerContextProxy);
                }
            } catch (RuntimeException exception) {
                lifecycleFailures++;
                this.recordLifecycleCleanupFailure(activation, exception, "stop");
            }
            this.transition(activation, ApplicationRunState.STOPPED, "stopped");
            this.emitAudit("application.deactivate", Map.of(
                    "instanceId", instanceId.value(),
                    "result", lifecycleFailures == 0 ? "success" : "success-with-cleanup-failures"));
            return new ApplicationDeploymentResult(true, ApplicationRunState.STOPPED, "application stopped", List.of(instanceId.value()));
        }
    }

    /**
     * Drains a running application instance without stopping or unloading it.
     *
     * <p>The transition is idempotent for an already draining instance. A
     * successful drain closes the managed invocation gate and invokes the
     * application drain hook while preserving services, registrations,
     * classloader or module-layer ownership, and cleanup diagnostics for the
     * later stop/unload path.</p>
     *
     * @param instanceId active instance id
     * @return drain result
     */
    public ApplicationDeploymentResult drain(final ApplicationInstanceId instanceId) {
        ManagedActivation activation = this.activations.get(instanceId.value());
        if (activation == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found");
        }
        synchronized (activation) {
            if (activation.state == ApplicationRunState.DRAINING) {
                return new ApplicationDeploymentResult(true, ApplicationRunState.DRAINING, "application already draining",
                        List.of(instanceId.value()));
            }
            if (activation.state != ApplicationRunState.RUNNING) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application instance must be running before drain");
            }
            activation.acceptingInvocations = false;
            this.closeProbes(activation);
            this.transition(activation, ApplicationRunState.DRAINING, "drain");
            ApplicationContext context = this.context(null, activation.identity, Principal.runtime(this.runtime.identity().runtimeId()), Map.of(), activation);
            int lifecycleFailures = 0;
            try {
                if (activation.managedPojo != null) {
                    activation.managedPojo.drain();
                } else if (activation.application != null) {
                    activation.application.drain(context);
                } else if (activation.applicationObject != null) {
                    this.invokeJpmsLifecycle(activation.applicationObject, "drain", activation.layerContextProxy);
                }
            } catch (RuntimeException exception) {
                lifecycleFailures++;
                this.recordLifecycleCleanupFailure(activation, exception, "drain");
            }
            this.emitAudit("application.drain", Map.of(
                    "instanceId", instanceId.value(),
                    "result", lifecycleFailures == 0 ? "success" : "success-with-cleanup-failures"));
            String details = lifecycleFailures == 0 ? "drainHook=completed" : "drainHook=cleanup-failure-recorded";
            return new ApplicationDeploymentResult(true, ApplicationRunState.DRAINING, "application draining",
                    List.of(instanceId.value(), details));
        }
    }

    /**
     * Unloads a stopped application instance and releases local references.
     *
     * @param instanceId stopped instance id
     * @return unload result
     */
    public ApplicationDeploymentResult unload(final ApplicationInstanceId instanceId) {
        ManagedActivation activation = this.activations.get(instanceId.value());
        if (activation == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found");
        }
        synchronized (activation) {
            if (activation.state == ApplicationRunState.UNLOADED) {
                return new ApplicationDeploymentResult(true, ApplicationRunState.UNLOADED, "application already unloaded", List.of(instanceId.value()));
            }
            if (activation.state != ApplicationRunState.STOPPED && activation.state != ApplicationRunState.FAILED) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application instance must be stopped or failed before unload");
            }
            this.transition(activation, ApplicationRunState.UNLOADING, "unload");
            try {
                if (activation.classLoader != null) {
                    activation.classLoader.close();
                }
            } catch (IOException exception) {
                activation.stopCleanupFailureCount++;
                activation.stopCleanupFailureSummary = "classloader-close-failed";
                this.emitAudit("application.cleanup", Map.of("result", "classloader-close-failed"));
            } finally {
                this.releaseManagerReferences(activation);
                this.transition(activation, ApplicationRunState.UNLOADED, "unloaded");
                this.rememberHistoricalActivation(instanceId.value(), activation.snapshot());
                this.activations.remove(instanceId.value());
            }
            this.emitAudit("application.unload", Map.of(
                    "instanceId", instanceId.value(),
                    "result", "success"));
            return new ApplicationDeploymentResult(true, ApplicationRunState.UNLOADED, "application unloaded", List.of(instanceId.value()));
        }
    }

    /**
     * Performs a stop-start upgrade to an exact target coordinate.
     *
     * @param instanceId active instance id
     * @param targetVersion target coordinate
     * @param strategy requested strategy, only {@code stop-start} is supported
     * @param request activation request for the target version
     * @return deployment result
     */
    public synchronized ApplicationDeploymentResult upgrade(
            final ApplicationInstanceId instanceId,
            final ApplicationCoordinate targetVersion,
            final String strategy,
            final ApplicationActivationRequest request) {
        return this.upgrade(instanceId, targetVersion, new org.j7m.middleware.deployer.api.UpgradeOptions(strategy), request);
    }

    /**
     * Upgrades an active application using a local-runtime strategy.
     *
     * @param instanceId active instance id
     * @param targetVersion target coordinate
     * @param options upgrade options
     * @param request activation request for the target version
     * @return deployment result
     */
    public synchronized ApplicationDeploymentResult upgrade(
            final ApplicationInstanceId instanceId,
            final ApplicationCoordinate targetVersion,
            final org.j7m.middleware.deployer.api.UpgradeOptions options,
            final ApplicationActivationRequest request) {
        org.j7m.middleware.deployer.api.UpgradeOptions safeOptions =
                options == null ? org.j7m.middleware.deployer.api.UpgradeOptions.stopStart() : options;
        ManagedActivation current = this.activations.get(instanceId.value());
        if (current == null || current.state != ApplicationRunState.RUNNING
                && !(current.managedProfile && current.state == ApplicationRunState.DRAINING)) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "running application instance not found");
        }
        String strategy = safeOptions.strategy();
        if (!Set.of("stop-start", "rolling", "blue-green", "canary-baseline").contains(strategy)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported upgrade strategy: " + strategy);
        }
        ApplicationCoordinate previous = current.identity.application().coordinate();
        ApplicationActivationRequest targetRequest = current.managedProfile ? lifecycleRequest(current.activationRequest, request) : request;
        this.repository.verify(targetVersion);
        synchronized (current) {
            if (current.managedProfile && current.activeInvocations.get() != 0) {
                return new ApplicationDeploymentResult(false, current.state, "managed upgrade awaits invocation completion", List.of());
            }
            this.transition(current, ApplicationRunState.UPGRADING, "upgrade-" + strategy);
        }
        if ("rolling".equals(strategy)) {
            return this.rollingUpgrade(current, targetVersion, safeOptions, targetRequest);
        }
        if ("blue-green".equals(strategy)) {
            return this.blueGreenUpgrade(current, targetVersion, safeOptions, targetRequest);
        }
        if ("canary-baseline".equals(strategy)) {
            return this.canaryBaselineUpgrade(current, targetVersion, safeOptions, targetRequest);
        }
        current.selectedActiveVersion = false;
        this.deactivate(instanceId);
        try {
            ApplicationActivation upgraded = this.activate(targetVersion, targetRequest);
            ManagedActivation upgradedManaged = this.activations.get(upgraded.instanceIdentity().instanceId().value());
            if (upgradedManaged != null) {
                upgradedManaged.upgradeStrategyState = "stop-start-success";
                upgradedManaged.selectedActiveVersion = true;
                upgradedManaged.previousActivationRequest = current.activationRequest;
            }
            this.rememberRollbackCandidate(upgraded.instanceIdentity().instanceId().value(), previous);
            this.addUpgradeHistory("stop-start upgrade " + previous + " -> " + targetVersion + " succeeded");
            this.emitAudit("application.upgrade", Map.of(
                    "previous", previous.toString(),
                    "target", targetVersion.toString(),
                    "strategy", "stop-start",
                    "result", "success"));
            return new ApplicationDeploymentResult(true, ApplicationRunState.RUNNING, "upgrade succeeded",
                    List.of("strategy=stop-start", "previous=" + previous, "current=" + targetVersion,
                            "newInstance=" + upgraded.instanceIdentity().instanceId().value()));
        } catch (RuntimeException exception) {
            try {
                ApplicationActivation restored = this.activate(previous, current.managedProfile ? current.activationRequest : request);
                this.addUpgradeHistory("upgrade " + previous + " -> " + targetVersion + " failed; rollback restored "
                        + restored.instanceIdentity().instanceId().value());
                this.emitAudit("application.upgrade", Map.of(
                        "previous", previous.toString(),
                        "target", targetVersion.toString(),
                        "strategy", "stop-start",
                        "result", "failed-restored"));
                return new ApplicationDeploymentResult(false, ApplicationRunState.RUNNING, "upgrade failed and previous version was restored",
                        List.of("previous=" + previous, "target=" + targetVersion));
            } catch (RuntimeException restoreFailure) {
                this.addUpgradeHistory("upgrade " + previous + " -> " + targetVersion + " failed; rollback restore failed");
                this.emitAudit("application.upgrade", Map.of(
                        "previous", previous.toString(),
                        "target", targetVersion.toString(),
                        "strategy", "stop-start",
                        "result", "failed-restore-failed"));
                throw exception;
            }
        }
    }

    private static ApplicationActivationRequest lifecycleRequest(final ApplicationActivationRequest previous,
            final ApplicationActivationRequest override) {
        if (previous == null) return override;
        Map<String, String> configuration = new LinkedHashMap<>(previous.configuration());
        if (override != null) configuration.putAll(override.configuration());
        boolean managed = "managed-v1".equals(configuration.get("j7m.annotations.profile"));
        return new ApplicationActivationRequest(override == null ? previous.principalId() : override.principalId(),
                override == null ? previous.environment() : override.environment(),
                managed || previous.strictVerification() || override != null && override.strictVerification(), configuration);
    }

    /**
     * Rolls back a running upgraded application to its previous coordinate.
     *
     * @param instanceId current upgraded instance id
     * @param request activation request for the previous version
     * @return deployment result
     */
    public synchronized ApplicationDeploymentResult rollback(final ApplicationInstanceId instanceId, final ApplicationActivationRequest request) {
        ManagedActivation current = this.activations.get(instanceId.value());
        if (current == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found");
        }
        ApplicationCoordinate previous = this.rollbackCandidates.get(instanceId.value());
        if (previous == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "rollback candidate is not available");
        }
        ApplicationCoordinate currentCoordinate = current.identity.application().coordinate();
        this.repository.verify(previous);
        ApplicationActivationRequest restoredRequest = current.previousActivationRequest != null
                ? lifecycleRequest(current.previousActivationRequest, request)
                : current.managedProfile ? lifecycleRequest(current.activationRequest, request) : request;
        synchronized (current) {
            if (current.managedProfile && current.activeInvocations.get() != 0) {
                return new ApplicationDeploymentResult(false, current.state, "managed rollback awaits invocation completion", List.of());
            }
            this.transition(current, ApplicationRunState.ROLLING_BACK, "rollback");
        }
        String retainedInstance = this.rollbackRetainedInstances.get(instanceId.value());
        if (retainedInstance != null) {
            ManagedActivation retained = this.activations.get(retainedInstance);
            if (retained != null) {
                retained.selectedActiveVersion = true;
                retained.upgradeStrategyState = "rollback-selected-retained";
            }
            this.deactivate(instanceId);
            this.rollbackCandidates.remove(instanceId.value());
            this.rollbackRetainedInstances.remove(instanceId.value());
            this.addUpgradeHistory("rollback " + currentCoordinate + " -> " + previous + " succeeded using retained instance");
            this.emitAudit("application.rollback", Map.of(
                    "from", currentCoordinate.toString(),
                    "to", previous.toString(),
                    "result", "success-retained"));
            return new ApplicationDeploymentResult(true, ApplicationRunState.RUNNING, "rollback succeeded",
                    List.of("restored=" + previous, "retainedInstance=" + retainedInstance));
        }
        this.deactivate(instanceId);
        try {
            ApplicationActivation restored = this.activate(previous, restoredRequest);
            this.rollbackCandidates.remove(instanceId.value());
            this.addUpgradeHistory("rollback " + currentCoordinate + " -> " + previous + " succeeded");
            this.emitAudit("application.rollback", Map.of(
                    "from", currentCoordinate.toString(),
                    "to", previous.toString(),
                    "result", "success"));
            return new ApplicationDeploymentResult(true, ApplicationRunState.RUNNING, "rollback succeeded",
                    List.of("restored=" + previous, "newInstance=" + restored.instanceIdentity().instanceId().value()));
        } catch (RuntimeException exception) {
            this.addUpgradeHistory("rollback " + currentCoordinate + " -> " + previous + " failed");
            this.emitAudit("application.rollback", Map.of(
                    "from", currentCoordinate.toString(),
                    "to", previous.toString(),
                    "result", "failed"));
            throw exception;
        }
    }

    /**
     * Lists active application snapshots matching the query.
     *
     * @param query application query
     * @return matching activation snapshots
     */
    public List<ApplicationActivation> list(final ApplicationQuery query) {
        ApplicationQuery safeQuery = query == null ? ApplicationQuery.all() : query;
        return this.activations.values().stream()
                .filter(activation -> safeQuery.applicationId() == null
                        || activation.identity.application().coordinate().id().equals(safeQuery.applicationId()))
                .filter(activation -> safeQuery.state() == null || activation.state == safeQuery.state())
                .map(ManagedActivation::snapshot)
                .toList();
    }

    /**
     * Returns the active snapshot for one exact application instance.
     *
     * @param instanceId application instance id
     * @return activation snapshot, or {@code null} when the instance is not
     *         currently active
     */
    public ApplicationActivation active(final ApplicationInstanceId instanceId) {
        if (instanceId == null) {
            return null;
        }
        ManagedActivation activation = this.activations.get(instanceId.value());
        return activation == null ? null : activation.snapshot();
    }

    /**
     * Lists historical activation snapshots released from the active manager set.
     *
     * @return immutable historical snapshot list
     */
    public List<ApplicationActivation> historicalApplications() {
        synchronized (this.historicalActivations) {
            return List.copyOf(this.historicalActivations.values());
        }
    }

    /**
     * Returns safe local application-platform status.
     *
     * @return immutable status metadata
     */
    public Map<String, String> applicationStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("installedApplications", Integer.toString(this.repository.status().artifactCount()));
        status.put("repositoryAuditChainValid", Boolean.toString(this.repository.status().metadata()
                .getOrDefault("auditChainValid", "false").equals("true")));
        status.put("activeApplicationInstances", Long.toString(this.activations.values().stream()
                .filter(activation -> activation.state == ApplicationRunState.RUNNING).count()));
        synchronized (this.historicalActivations) {
            status.put("historicalApplicationInstances", Integer.toString(this.historicalActivations.size()));
        }
        status.put("upgradeHistoryCount", Integer.toString(this.upgradeHistory().size()));
        status.put("applicationAuditCounters", Integer.toString(this.auditEvents().size()));
        status.put("repositorySignatureStatus", this.repository.status().metadata().getOrDefault("signatureVerification", "unknown"));
        status.put("repositorySbomStatus", this.repository.status().metadata().getOrDefault("sbomVerification", "unknown"));
        status.put("repositoryProvenanceStatus", this.repository.status().metadata().getOrDefault("provenanceVerification", "unknown"));
        status.put("selectedActiveVersion", this.activations.values().stream()
                .filter(activation -> activation.state == ApplicationRunState.RUNNING && activation.selectedActiveVersion)
                .map(activation -> activation.identity.application().coordinate().toString())
                .findFirst().orElse(""));
        status.put("coexistingVersions", Integer.toString((int) this.activations.values().stream()
                .filter(activation -> activation.state == ApplicationRunState.RUNNING).count()));
        return Map.copyOf(status);
    }

    /**
     * Loads an exported service interface class for an active application.
     *
     * @param instanceId application instance id
     * @param interfaceName service interface name
     * @return interface class from the activation's classloader or module layer
     */
    public Class<?> serviceInterface(final ApplicationInstanceId instanceId, final String interfaceName) {
        ManagedActivation activation = this.activations.get(instanceId.value());
        if (activation == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found");
        }
        try {
            if (activation.moduleLayer != null) {
                ServiceBinding binding = activation.jpmsServices.get(interfaceName);
                if (binding == null) {
                    throw new ClassNotFoundException(interfaceName);
                }
                return Class.forName(interfaceName, true, activation.moduleLayer.findLoader(binding.interfaceModule()));
            }
            return Class.forName(interfaceName, true, activation.classLoader);
        } catch (ClassNotFoundException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.NOT_FOUND, "service interface not found",
                    Map.of(), null, this.runtime.identity().runtimeId(), false, false), exception);
        }
    }

    /**
     * Returns upgrade and rollback history.
     *
     * @return immutable history snapshot
     */
    public List<String> upgradeHistory() {
        synchronized (this.upgradeHistory) {
            return List.copyOf(this.upgradeHistory);
        }
    }

    /**
     * Returns bounded safe lifecycle audit events emitted by this manager.
     *
     * @return immutable audit event snapshot
     */
    public List<String> auditEvents() {
        synchronized (this.auditEvents) {
            return List.copyOf(this.auditEvents);
        }
    }

    private void emitAudit(final String action, final Map<String, String> details) {
        synchronized (this.auditEvents) {
            if (this.auditEvents.size() == this.limits.maxAuditEventsPerPage()) {
                this.auditEvents.remove(0);
            }
            this.auditEvents.add(action + " " + new java.util.TreeMap<>(details));
        }
    }

    private void addUpgradeHistory(final String entry) {
        synchronized (this.upgradeHistory) {
            if (this.upgradeHistory.size() == this.limits.maxAuditEventsPerPage()) {
                this.upgradeHistory.remove(0);
            }
            this.upgradeHistory.add(entry);
        }
    }

    private void rememberHistoricalActivation(final String instanceId, final ApplicationActivation activation) {
        synchronized (this.historicalActivations) {
            evictEldestIfFull(this.historicalActivations, this.limits.maxAuditEventsPerPage());
            this.historicalActivations.put(instanceId, activation);
        }
    }

    /**
     * Returns host-only probes bound to the exact activation generation.
     * Legacy profiles return Optional.empty; a managed activation without declared probes returns
     * a present empty list. Callbacks reject new work after drain, execute outside host monitors and
     * retain actual in-flight lifetime until application code exits, including after caller timeout.
     * @param instanceId exact activation instance
     * @return immutable finite callback inventory, never caller-authorized remote methods
     * @throws MiddlewareException when the instance is unknown
     */
    public Optional<List<ManagedApplicationProbe>> probes(final ApplicationInstanceId instanceId) {
        ManagedActivation activation = this.activations.get(instanceId.value());
        if (activation == null) throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found");
        synchronized (activation) {
            if (!activation.managedProfile) return Optional.empty();
            ManagedPojoActivation owner = activation.managedPojo;
            if (owner == null) return Optional.of(List.of());
            return Optional.of(owner.probes().stream().map(probe -> new ManagedApplicationProbe(probe.id(), probe.descriptor().kind(),
                    () -> this.checkProbe(activation, owner, probe))).toList());
        }
    }

    /**
     * Returns a cached bounded operational observation without executing application code.
     * Legacy activations and managed activations without declared probes return empty.
     * A present UNKNOWN observation never proves deployment readiness.
     * @param instanceId exact owned activation generation
     * @return fresh cached observation, or empty when no probes are registered
     * @throws MiddlewareException if the activation does not exist
     */
    public Optional<ManagedProbeCoordinator.Snapshot> probeStatus(
            final ApplicationInstanceId instanceId) {
        ManagedActivation activation = this.activations.get(instanceId.value());
        if (activation == null) { throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found"); }
        synchronized (activation) {
            return activation.probeRegistration == null ? Optional.empty() : Optional.of(activation.probeRegistration.snapshot());
        }
    }

    private boolean hasReadiness(final ManagedActivation activation) {
        return activation.managedPojo != null && activation.managedPojo.probes().stream().anyMatch(
                probe -> probe.descriptor().kind() == ManagedProbeKind.READINESS);
    }

    private synchronized ManagedProbeMonitor monitor() {
        if (this.probeMonitor == null) { this.probeMonitor = managedHostingOptions.probeSettings().newMonitor(); }
        return this.probeMonitor;
    }

    private void startProbes(final ManagedActivation activation) {
        synchronized (activation) {
            if (activation.managedPojo == null || activation.managedPojo.probes().isEmpty()) { return; }
            Object token = new Object();
            activation.probeToken = token;
            boolean readiness = this.hasReadiness(activation);
            var checks = this.probes(activation.identity.instanceId()).orElseThrow().stream().map(
                    probe -> new ManagedProbeCoordinator.Check(probe.id(), probe.check())).toList();
            activation.probeRegistration = this.monitor().register(checks, readiness, ready -> {
                synchronized (activation) {
                    if (activation.probeToken != token || activation.state != ApplicationRunState.RUNNING || !activation.acceptingInvocations) { return; }
                    if (readiness) {
                        for (ObjectRegistration<?> registration : activation.registrations) {
                            registration.setReadiness(ready, managedHostingOptions.probeSettings().freshness());
                        }
                    }
                }
            });
        }
    }

    private void closeProbes(final ManagedActivation activation) {
        synchronized (activation) {
            activation.probeToken = null;
            if (this.hasReadiness(activation)) {
                for (ObjectRegistration<?> owned : activation.registrations) {
                    owned.setReadiness(false, managedHostingOptions.probeSettings().freshness());
                }
            }
            var registration = activation.probeRegistration;
            activation.probeRegistration = null;
            if (registration != null) { registration.close(); }
        }
    }

    private boolean checkProbe(final ManagedActivation activation, final ManagedPojoActivation owner,
            final ManagedPojoActivation.ProbeBinding probe) {
        synchronized (activation) {
            if (activation.managedPojo != owner || activation.state != ApplicationRunState.RUNNING || !activation.acceptingInvocations) {
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "application probe is unavailable");
            }
            activation.activeInvocations.incrementAndGet();
        }
        Principal principal = Principal.anonymous();
        InvocationContext context = new InvocationContext(new InvocationSecurityContext(principal, principal.roles(),
                new TrustDomain(activation.identity.application().trustDomain())),
                new InvocationApplicationContext(activation.identity.application(), activation.identity), "host-probe");
        try {
            return SecurityContexts.call(
                    new SecurityContext(principal, "",
                            new TrustDomain(activation.identity.application().trustDomain()), null, "host-probe", "", "", false,
                            org.j7m.middleware.security.api.TransportSecurityLevel.UNKNOWN, Set.of(), Map.of(), java.time.Instant.now()), () -> {
                        try (var ignored = J7mContext.open(context)) { return owner.check(probe); }
                    });
        } catch (Throwable failure) {
            rethrowFatalLifecycleFailure(failure);
            if (containsInterruptedException(failure)) Thread.currentThread().interrupt();
            throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "application probe failed");
        } finally { activation.activeInvocations.decrementAndGet(); }
    }

    private void rememberRollbackCandidate(final String instanceId, final ApplicationCoordinate coordinate) {
        synchronized (this.rollbackCandidates) {
            evictEldestIfFull(this.rollbackCandidates, this.limits.maxAuditEventsPerPage());
            this.rollbackCandidates.put(instanceId, coordinate);
        }
    }

    private void rememberRollbackRetainedInstance(final String instanceId, final String retainedInstanceId) {
        synchronized (this.rollbackRetainedInstances) {
            evictEldestIfFull(this.rollbackRetainedInstances, this.limits.maxAuditEventsPerPage());
            this.rollbackRetainedInstances.put(instanceId, retainedInstanceId);
        }
    }

    private static void evictEldestIfFull(final Map<String, ?> map, final int limit) {
        if (map.size() < limit || map.isEmpty()) {
            return;
        }
        String eldest = map.keySet().iterator().next();
        map.remove(eldest);
    }

    private void startClasspathActivation(
            final ApplicationManifest manifest,
            final ManagedActivation activation,
            final ApplicationContext context) throws IOException, ReflectiveOperationException {
        activation.classLoader = this.createClassLoader(activation.extractedDirectory);
        this.transition(activation, ApplicationRunState.LOADED, "classpath-loaded");
        this.prevalidateServices(manifest, activation);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(activation.classLoader);
            if (activation.managedProfile) {
                this.startManagedActivation(manifest, activation, context, activation.classLoader);
                return;
            }
            Class<?> bootstrapClass = Class.forName(manifest.bootstrapClass(), true, activation.classLoader);
            this.requireClasspathOwnedClass(activation, bootstrapClass);
            Object bootstrap = bootstrapClass.getDeclaredConstructor().newInstance();
            if (!(bootstrap instanceof J7mApplication application)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bootstrap class does not implement J7mApplication");
            }
            activation.application = application;
            this.transition(activation, ApplicationRunState.STARTING, "initialize");
            application.initialize(context);
            application.start(context);
            activation.lifecycleStarted = true;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private void startJpmsActivation(
            final ApplicationManifest manifest,
            final ManagedActivation activation,
            final ApplicationContext context) throws ReflectiveOperationException {
        this.validateJpmsModuleDeclarations(manifest, activation.extractedDirectory);
        if (activation.managedProfile && (!ApplicationContext.class.getModule().isNamed()
                || manifest.modules().stream().anyMatch(module -> "org.j7m.api".equals(module.name())))) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "managed JPMS requires a shared host API module");
        }
        List<Path> moduleEntries = activation.managedProfile
                ? manifest.modules().stream().map(module -> activation.extractedDirectory.resolve(module.path())).toList()
                : this.modulePathEntries(manifest, activation.extractedDirectory);
        ModuleFinder finder = ModuleFinder.of(moduleEntries.toArray(Path[]::new));
        Set<String> roots = new HashSet<>();
        for (ApplicationManifest.ModuleDeclaration module : manifest.modules()) {
            roots.add(module.name());
        }
        if (!activation.managedProfile) { roots.add("org.j7m.api"); }
        Configuration configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), roots);
        ClassLoader parentLoader = ApplicationManager.class.getClassLoader();
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, parentLoader);
        activation.moduleLayer = layer;
        activation.moduleNames = manifest.modules().stream().map(ApplicationManifest.ModuleDeclaration::name).toList();
        activation.jpmsServices = this.buildJpmsServiceTable(manifest, activation.extractedDirectory);
        this.transition(activation, ApplicationRunState.LOADED, "jpms-loaded");
        this.prevalidateServices(manifest, activation);
        ClassLoader bootstrapLoader = layer.findLoader(manifest.bootstrapModule());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(bootstrapLoader);
            if (activation.managedProfile) {
                this.startManagedActivation(manifest, activation, context, bootstrapLoader);
                return;
            }
            Class<?> bootstrapClass = Class.forName(manifest.bootstrapClass(), true, bootstrapLoader);
            this.requireDeclaredJpmsClassOrigin(manifest, bootstrapClass);
            Object bootstrap = bootstrapClass.getDeclaredConstructor().newInstance();
            activation.applicationObject = bootstrap;
            activation.layerContextProxy = this.layerApplicationContextProxy(layer, manifest.bootstrapModule(), context);
            this.transition(activation, ApplicationRunState.STARTING, "initialize");
            this.invokeJpmsLifecycle(bootstrap, "initialize", activation.layerContextProxy);
            this.invokeJpmsLifecycle(bootstrap, "start", activation.layerContextProxy);
            activation.lifecycleStarted = true;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private void startManagedActivation(final ApplicationManifest manifest, final ManagedActivation activation,
            final ApplicationContext context, final ClassLoader loader) {
        try {
            activation.managedPojo = new ManagedPojoActivation(manifest, context, loader, activation.moduleLayer,
                    type -> {
                        if (activation.moduleLayer == null) this.requireClasspathOwnedClass(activation, type);
                        else this.requireDeclaredJpmsClassOrigin(manifest, type);
                    }, managedHostingOptions, activation.artifactDigest, boundary -> openInvocation(activation, null, boundary));
            this.transition(activation, ApplicationRunState.STARTING, "managed-lifecycle");
            activation.managedPojo.start();
            activation.lifecycleStarted = true;
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "managed hosting failed"); }
    }

    private void prevalidateServices(final ApplicationManifest manifest, final ManagedActivation activation) throws ClassNotFoundException {
        for (ApplicationManifest.ServiceDeclaration service : manifest.services()) {
            Class<?> interfaceClass = activation.moduleLayer == null
                    ? Class.forName(service.interfaceName(), false, activation.classLoader)
                    : this.loadJpmsServiceInterface(activation, service);
            if (!interfaceClass.isInterface()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "application service interface is not an interface");
            }
            if (activation.moduleLayer == null) {
                this.requireClasspathOwnedClass(activation, interfaceClass);
            } else {
                this.requireDeclaredJpmsClassOrigin(manifest, interfaceClass);
            }
            Class<?> implementationClass = activation.moduleLayer == null
                    ? Class.forName(service.implementationClass(), false, activation.classLoader)
                    : this.loadJpmsServiceImplementation(activation, service);
            if (activation.moduleLayer == null) {
                this.requireClasspathOwnedClass(activation, implementationClass);
            } else {
                this.requireDeclaredJpmsClassOrigin(manifest, implementationClass);
            }
            if (!interfaceClass.isAssignableFrom(implementationClass)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "application service implementation does not implement interface");
            }
        }
    }

    private List<PreparedObjectRegistration<?>> prepareServices(final ApplicationManifest manifest, final ManagedActivation activation) throws ReflectiveOperationException {
        List<PreparedObjectRegistration<?>> prepared = new ArrayList<>(manifest.services().size());
        try {
            for (ApplicationManifest.ServiceDeclaration service : manifest.services()) {
                prepared.add(this.prepareService(manifest, activation, service));
            }
            return List.copyOf(prepared);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            this.closePreparedServices(activation, prepared);
            throw exception;
        }
    }

    private PreparedObjectRegistration<?> prepareService(
            final ApplicationManifest manifest,
            final ManagedActivation activation,
            final ApplicationManifest.ServiceDeclaration service) throws ReflectiveOperationException {
        Class<?> interfaceClass = activation.moduleLayer == null
                ? Class.forName(service.interfaceName(), true, activation.classLoader)
                : this.loadJpmsServiceInterface(activation, service);
        if (!interfaceClass.isInterface()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "application service interface is not an interface");
        }
        if (activation.moduleLayer == null) {
            this.requireClasspathOwnedClass(activation, interfaceClass);
        } else {
            this.requireDeclaredJpmsClassOrigin(manifest, interfaceClass);
        }
        Class<?> implementationClass = activation.moduleLayer == null
                ? Class.forName(service.implementationClass(), true, activation.classLoader)
                : this.loadJpmsServiceImplementation(activation, service);
        if (activation.moduleLayer == null) {
            this.requireClasspathOwnedClass(activation, implementationClass);
        } else {
            this.requireDeclaredJpmsClassOrigin(manifest, implementationClass);
        }
        Object target = activation.managedPojo == null ? implementationClass.getDeclaredConstructor().newInstance()
                : activation.managedPojo.target(service.serviceName());
        Object exported = Proxy.newProxyInstance(interfaceClass.getClassLoader(), new Class<?>[] {interfaceClass},
                this.contextualHandler(target, activation, service));
        @SuppressWarnings({"unchecked", "rawtypes"})
        PreparedObjectRegistration<?> prepared = this.runtime.prepareExport(service.serviceName(), exported, (Class) interfaceClass,
                new ExportOptions(!service.globallyExported(), service.transport(), this.runtime.identity().registryContext(),
                        List.of(service.interfaceName()), this.serviceMetadata(manifest, service, activation), Duration.ofSeconds(30L), Duration.ofSeconds(10L)));
        if (activation.managedPojo != null) {
            try {
                if (this.hasReadiness(activation)) { prepared.setReadiness(false, managedHostingOptions.probeSettings().freshness()); }
                activation.managedPojo.attachPrepared(service.serviceName(), prepared.ref());
                prepared.setInvocationCompletionFactory(boundary -> this.openInvocation(activation, service, boundary));
                prepared.setValueCodec(activation.managedPojo.valueCodec());
            }
            catch (RuntimeException failure) { prepared.close(); throw failure; }
        }
        activation.services.add(new RuntimeServiceIdentity(service.serviceName(), service.interfaceName(), service.implementationClass()));
        activation.partialServicesExportedCount = Math.max(activation.partialServicesExportedCount, activation.services.size());
        return prepared;
    }

    private void commitPreparedServices(
            final ManagedActivation activation,
            final List<PreparedObjectRegistration<?>> preparedServices) {
        if (preparedServices.isEmpty()) return;
        try {
            List<ObjectRegistration<?>> committed = this.runtime.commitPreparedExports(preparedServices);
            activation.registrations.addAll(committed);
        } catch (RuntimeException exception) {
            for (PreparedObjectRegistration<?> prepared : preparedServices) {
                try {
                    prepared.close();
                } catch (RuntimeException closeFailure) {
                    if (containsInterruptedException(closeFailure)) {
                        Thread.currentThread().interrupt();
                    }
                    activation.registrationCleanupFailureCount++;
                    activation.registrationCleanupFailureSummary = "registration-close-failed-partial";
                }
            }
            activation.partialServicesCleanedCount += preparedServices.size();
            throw exception;
        }
    }

    private void closePreparedServices(
            final ManagedActivation activation,
            final List<PreparedObjectRegistration<?>> preparedServices) {
        int closed = 0;
        int failures = 0;
        for (PreparedObjectRegistration<?> prepared : preparedServices) {
            try {
                prepared.close();
                closed++;
            } catch (RuntimeException exception) {
                failures++;
                if (containsInterruptedException(exception)) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        activation.partialServicesCleanedCount += closed;
        if (failures > 0) {
            activation.registrationCleanupFailureCount += failures;
            activation.registrationCleanupFailureSummary = "prepared-export-close-failed";
            this.emitAudit("application.cleanup", Map.of(
                    "result", "prepared-export-close-failed",
                    "failures", Integer.toString(failures)));
        }
    }

    private synchronized java.util.concurrent.Executor invocationExecutor() {
        if (this.invocationExecutor == null) {
            this.invocationExecutor = managedHostingOptions.invocationSettings().newExecutor();
        }
        return this.invocationExecutor;
    }

    private InvocationCompletionScope openInvocation(final ManagedActivation activation,
            final ApplicationManifest.ServiceDeclaration service, final InvocationBoundary boundary) {
        if (boundary.inboundBytes() < 0 && activation.resourceLimits != null && activation.resourceLimits.maxInboundPayloadBytes > 0) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Trusted input size is unavailable");
        }
        this.admitApplicationInvocation(activation, service, null, true, boundary.inboundBytes());
        org.j7m.middleware.application.components.ManagedContainer.CallScope call = null;
        try {
            call = activation.managedPojo.openInvocationCall();
            final SecurityContext security = boundary.security();
            final Principal principal = security == null ? Principal.anonymous() : security.principal();
            final TrustDomain trust = security == null ? new TrustDomain(activation.identity.application().trustDomain()) : security.trustDomain();
            final InvocationContext context = new InvocationContext(new InvocationSecurityContext(principal, principal.roles(), trust),
                    new InvocationApplicationContext(activation.identity.application(), activation.identity), boundary.options().invocationId());
            final var settings = managedHostingOptions.invocationSettings();
            return activation.managedPojo.ownedScope(new ManagedInvocationLifetime(call, context, security, boundary.options().deadline(), invocationExecutor(),
                    settings.maximumTasksPerInvocation(), settings.maximumPendingPerInvocation(), () -> {
                        activation.completedInvocations.incrementAndGet();
                        activation.activeInvocations.decrementAndGet();
                    }));
        } catch (RuntimeException failure) {
            if (call != null) { call.close(); }
            activation.activeInvocations.decrementAndGet();
            throw failure;
        }
    }

    private InvocationHandler contextualHandler(final Object target, final ManagedActivation activation, final ApplicationManifest.ServiceDeclaration service) {
        return (Object proxy, Method method, Object[] args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (activation.managedPojo != null) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "J7M managed service proxy";
                        default -> throw MiddlewareException.of(MiddlewareErrorCode.METHOD_NOT_FOUND, "unsupported object method");
                    };
                }
                return this.invokeApplicationMethod(target, method, args);
            }
            if (activation.managedPojo != null && ManagedInvocationLifetime.active()
                    && activation.identity.equals(J7mContext.currentInvocation().application().instanceIdentity())) {
                // The trusted boundary measured the encoded frame before decoding. Re-reading DTO
                // private fields here would require JPMS opens and would lose the wire-size bound.
                this.admitApplicationInvocation(activation, service, null, false, 0);
                try { return activation.managedPojo.invoke(service.serviceName(), method, args); }
                catch (MiddlewareException failure) { throw failure; }
                catch (Exception failure) {
                    if (containsInterruptedException(failure)) Thread.currentThread().interrupt();
                    throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "managed application invocation failed");
                }
            }
            this.admitApplicationInvocation(activation, service, args);
            SecurityContext securityContext = SecurityContexts.current();
            Principal principal = securityContext == null ? Principal.anonymous() : securityContext.principal();
            TrustDomain trustDomain = securityContext == null ? new TrustDomain(activation.identity.application().trustDomain()) : securityContext.trustDomain();
            InvocationContext context = new InvocationContext(
                    new InvocationSecurityContext(principal, principal.roles(), trustDomain),
                    new InvocationApplicationContext(activation.identity.application(), activation.identity),
                    UUID.randomUUID().toString());
            try (J7mContext.Scope ignored = J7mContext.open(context)) {
                Object result;
                if (activation.managedPojo != null) {
                    try { result = activation.managedPojo.invoke(service.serviceName(), method, args); }
                    catch (MiddlewareException failure) { throw failure; }
                    catch (Exception failure) {
                        if (containsInterruptedException(failure)) Thread.currentThread().interrupt();
                        throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "managed application invocation failed");
                    }
                } else {
                    Method implementationMethod = target.getClass().getMethod(method.getName(), method.getParameterTypes());
                    result = this.invokeApplicationMethod(target, implementationMethod, args);
                }
                activation.completedInvocations.incrementAndGet();
                return result;
            } finally {
                activation.activeInvocations.decrementAndGet();
            }
        };
    }

    private void admitApplicationInvocation(final ManagedActivation activation,
            final ApplicationManifest.ServiceDeclaration service, final Object[] args) {
        this.admitApplicationInvocation(activation, service, args, true);
    }

    private void admitApplicationInvocation(final ManagedActivation activation,
            final ApplicationManifest.ServiceDeclaration service, final Object[] args, final boolean account) {
        this.admitApplicationInvocation(activation, service, args, account, -1);
    }

    private void admitApplicationInvocation(final ManagedActivation activation,
            final ApplicationManifest.ServiceDeclaration service, final Object[] args, final boolean account, final int frameBytes) {
        synchronized (activation) {
            if (!activation.acceptingInvocations || activation.state != ApplicationRunState.RUNNING
                    || service == null && activation.managedPojo != null && !activation.managedPojo.hostedAdmissionOpen()) {
                activation.lastResourceRejectionReason = "application-not-running";
                this.emitAudit("application.invoke", Map.of(
                        "application", activation.identity.application().coordinate().toString(),
                        "service", service == null ? "hosted" : service.serviceName(),
                        "result", "denied",
                        "reason", "application-not-running"));
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "application instance is not running");
            }
            ResourceLimits limits = activation.resourceLimits == null ? ResourceLimits.unlimited() : activation.resourceLimits;
            int payloadBytes = frameBytes >= 0 ? frameBytes : estimatePayloadBytes(args, limits.maxInboundPayloadBytes);
            if (account) activation.lastInboundPayloadBytes.set(payloadBytes);
            if (limits.maxInboundPayloadBytes > 0 && payloadBytes > limits.maxInboundPayloadBytes) {
                activation.rejectedPayloadInvocations.incrementAndGet();
                activation.lastResourceRejectionReason = "max-inbound-payload";
                this.emitAudit("application.invoke", Map.of(
                        "application", activation.identity.application().coordinate().toString(),
                        "service", service == null ? "hosted" : service.serviceName(),
                        "result", "denied",
                        "reason", "max-inbound-payload"));
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "application inbound payload limit exceeded");
            }
            if (!account) { return; }
            int active = activation.activeInvocations.incrementAndGet();
            if (limits.maxConcurrentInvocations > 0 && active > limits.maxConcurrentInvocations) {
                activation.activeInvocations.decrementAndGet();
                activation.rejectedConcurrentInvocations.incrementAndGet();
                activation.lastResourceRejectionReason = "max-concurrent-invocations";
                this.emitAudit("application.invoke", Map.of(
                        "application", activation.identity.application().coordinate().toString(),
                        "service", service == null ? "hosted" : service.serviceName(),
                        "result", "denied",
                        "reason", "max-concurrent-invocations"));
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "application concurrency limit exceeded");
            }
        }
    }

    private Object invokeApplicationMethod(final Object target, final Method method, final Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof MiddlewareException middlewareException) {
                throw middlewareException;
            }
            rethrowFatalLifecycleFailure(cause);
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.REMOTE_EXCEPTION,
                    "application method failed", Map.of(), null, this.runtime.identity().runtimeId(), false, false), cause);
        } catch (IllegalAccessException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "application method is not accessible", Map.of(), null, this.runtime.identity().runtimeId(), false, true), exception);
        }
    }

    private Object layerApplicationContextProxy(final ModuleLayer layer, final String bootstrapModule, final ApplicationContext context) throws ClassNotFoundException {
        Class<?> contextType = Class.forName("org.j7m.middleware.application.api.ApplicationContext", true, layer.findLoader(bootstrapModule));
        return Proxy.newProxyInstance(contextType.getClassLoader(), new Class<?>[] {contextType},
                (Object proxy, Method method, Object[] args) -> this.layerContextValue(method, context));
    }

    private Object layerContextValue(final Method method, final ApplicationContext context) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return "J7M JPMS application context";
        }
        if ("applicationIdentity".equals(name)) {
            return this.layerApplicationIdentity(method.getReturnType().getClassLoader(), context.applicationIdentity());
        }
        if ("instanceIdentity".equals(name)) {
            return this.layerApplicationInstanceIdentity(method.getReturnType().getClassLoader(), context.instanceIdentity());
        }
        if ("activationPrincipal".equals(name)) {
            return this.layerPrincipal(method.getReturnType().getClassLoader(), context.activationPrincipal());
        }
        if ("runtimeIdentity".equals(name)) {
            return this.layerRuntimeIdentity(method.getReturnType().getClassLoader(), context.runtimeIdentity());
        }
        if ("configuration".equals(name)) {
            return this.layerConfigurationView(method.getReturnType(), context.configuration());
        }
        if ("services".equals(name)) {
            return this.layerServiceExporter(method.getReturnType(), context.services());
        }
        if ("audit".equals(name)) {
            return this.layerAuditPublisher(method.getReturnType(), context.audit());
        }
        if ("metrics".equals(name)) {
            return this.layerMetricsPublisher(method.getReturnType(), context.metrics());
        }
        if ("resources".equals(name)) {
            return this.layerResourceUsageSnapshot(method.getReturnType().getClassLoader(), context.resources());
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class) {
            return Integer.valueOf(0);
        }
        if (returnType == long.class) {
            return Long.valueOf(0L);
        }
        if (returnType == short.class) {
            return Short.valueOf((short) 0);
        }
        if (returnType == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        throw MiddlewareException.of(MiddlewareErrorCode.METHOD_NOT_FOUND, "unsupported application context method");
    }

    private void invokeJpmsLifecycle(final Object application, final String methodName, final Object layerContextProxy) {
        Method selected = null;
        Class<?> contextType = this.lifecycleContextType(layerContextProxy);
        for (Method method : application.getClass().getMethods()) {
            if (!this.isExactJpmsLifecycleCandidate(method, methodName, contextType, application)) {
                continue;
            }
            if (selected != null) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application lifecycle method is ambiguous");
            }
            selected = method;
        }
        if (selected == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.METHOD_NOT_FOUND, "application lifecycle method not found");
        }
        try {
            selected.invoke(application, layerContextProxy);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            rethrowFatalLifecycleFailure(cause);
            if (containsInterruptedException(cause)) {
                Thread.currentThread().interrupt();
            }
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "application lifecycle hook failed",
                    Map.of(), null, this.runtime.identity().runtimeId(), false, true), exception);
        } catch (ReflectiveOperationException exception) {
            if (containsInterruptedException(exception)) {
                Thread.currentThread().interrupt();
            }
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "application lifecycle hook failed",
                    Map.of(), null, this.runtime.identity().runtimeId(), false, true), exception);
        }
    }

    private static void rethrowFatalLifecycleFailure(final Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            if (current instanceof VirtualMachineError virtualMachineError) {
                throw virtualMachineError;
            }
            if (current instanceof Error error && isThreadDeath(error)) {
                throw error;
            }
            current = current.getCause();
            depth++;
        }
    }

    private static boolean isThreadDeath(final Error error) {
        Class<?> type = error.getClass();
        while (type != null) {
            if ("java.lang.ThreadDeath".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private Class<?> lifecycleContextType(final Object layerContextProxy) {
        if (layerContextProxy == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application lifecycle context is unavailable");
        }
        Class<?>[] interfaces = layerContextProxy.getClass().getInterfaces();
        Class<?> selected = null;
        for (Class<?> candidate : interfaces) {
            if (ApplicationContext.class.getName().equals(candidate.getName())
                    && candidate.isInterface()
                    && this.sameCodeSource(ApplicationContext.class, candidate)) {
                if (selected != null && selected != candidate) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application lifecycle context is ambiguous");
                }
                selected = candidate;
            }
        }
        if (selected == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application lifecycle context is invalid");
        }
        return selected;
    }

    private boolean isExactJpmsLifecycleCandidate(
            final Method method,
            final String methodName,
            final Class<?> contextType,
            final Object application) {
        int modifiers = method.getModifiers();
        if (!method.getName().equals(methodName)
                || !Modifier.isPublic(modifiers)
                || method.isSynthetic()
                || method.isBridge()
                || method.getReturnType() != void.class
                || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != contextType) {
            return false;
        }
        return method.canAccess(application);
    }

    private boolean sameCodeSource(final Class<?> left, final Class<?> right) {
        java.security.CodeSource leftSource = left.getProtectionDomain().getCodeSource();
        java.security.CodeSource rightSource = right.getProtectionDomain().getCodeSource();
        if (leftSource == null || rightSource == null) {
            return leftSource == rightSource;
        }
        return leftSource.getLocation().equals(rightSource.getLocation());
    }

    private Object layerApplicationIdentity(final ClassLoader loader, final ApplicationIdentity value) {
        try {
            Class<?> type = Class.forName("org.j7m.middleware.application.api.ApplicationIdentity", true, loader);
            return type.getConstructor(
                    Class.forName("org.j7m.middleware.application.api.ApplicationCoordinate", true, loader),
                    String.class,
                    String.class).newInstance(
                            this.layerApplicationCoordinate(loader, value.coordinate()),
                            value.displayName(),
                            value.trustDomain());
        } catch (ReflectiveOperationException exception) {
            throw this.bridgeFailure(exception);
        }
    }

    private Object layerApplicationInstanceIdentity(final ClassLoader loader, final ApplicationInstanceIdentity value) {
        try {
            Class<?> type = Class.forName("org.j7m.middleware.application.api.ApplicationInstanceIdentity", true, loader);
            Class<?> idType = Class.forName("org.j7m.middleware.application.api.ApplicationInstanceId", true, loader);
            return type.getConstructor(
                    idType,
                    Class.forName("org.j7m.middleware.application.api.ApplicationIdentity", true, loader),
                    Class.forName("org.j7m.middleware.api.RuntimeIdentity", true, loader),
                    String.class).newInstance(
                            idType.getConstructor(String.class).newInstance(value.instanceId().value()),
                            this.layerApplicationIdentity(loader, value.application()),
                            this.layerRuntimeIdentity(loader, value.runtime()),
                            value.classLoaderMode());
        } catch (ReflectiveOperationException exception) {
            throw this.bridgeFailure(exception);
        }
    }

    private Object layerApplicationCoordinate(final ClassLoader loader, final ApplicationCoordinate value) {
        try {
            Class<?> idType = Class.forName("org.j7m.middleware.application.api.ApplicationId", true, loader);
            Object id = idType.getConstructor(String.class, String.class).newInstance(value.id().group(), value.id().name());
            Class<?> versionType = Class.forName("org.j7m.middleware.application.api.ApplicationVersion", true, loader);
            Object version = versionType.getConstructor(String.class).newInstance(value.version().value());
            Class<?> coordinateType = Class.forName("org.j7m.middleware.application.api.ApplicationCoordinate", true, loader);
            return coordinateType.getConstructor(idType, versionType).newInstance(id, version);
        } catch (ReflectiveOperationException exception) {
            throw this.bridgeFailure(exception);
        }
    }

    private Object layerRuntimeIdentity(final ClassLoader loader, final RuntimeIdentity value) {
        try {
            Class<?> type = Class.forName("org.j7m.middleware.api.RuntimeIdentity", true, loader);
            return type.getConstructor(String.class, String.class, Map.class)
                    .newInstance(value.runtimeId(), value.registryContext(), value.metadata());
        } catch (ReflectiveOperationException exception) {
            throw this.bridgeFailure(exception);
        }
    }

    private Object layerPrincipal(final ClassLoader loader, final Principal value) {
        try {
            Class<?> type = Class.forName("org.j7m.middleware.security.api.Principal", true, loader);
            return type.getConstructor(String.class, String.class, Set.class, Set.class)
                    .newInstance(value.id(), value.displayName(), value.roles(), value.tags());
        } catch (ReflectiveOperationException exception) {
            throw this.bridgeFailure(exception);
        }
    }

    private Object layerResourceUsageSnapshot(final ClassLoader loader, final ResourceUsageSnapshot value) {
        try {
            Class<?> type = Class.forName("org.j7m.middleware.security.api.ResourceUsageSnapshot", true, loader);
            return type.getConstructor(int.class, int.class, int.class, int.class, int.class)
                    .newInstance(value.activeInvocations(), value.exportedObjects(), value.callbackExports(),
                            value.inboundBytes(), value.outboundBytes());
        } catch (ReflectiveOperationException exception) {
            throw this.bridgeFailure(exception);
        }
    }

    private Object layerConfigurationView(final Class<?> type, final ConfigurationView view) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object proxy, Method method, Object[] args) -> {
            if ("get".equals(method.getName())) {
                return view.get((String) args[0]);
            }
            if ("asMap".equals(method.getName())) {
                return view.asMap();
            }
            if ("toString".equals(method.getName())) {
                return "J7M JPMS configuration view";
            }
            throw MiddlewareException.of(MiddlewareErrorCode.METHOD_NOT_FOUND, "unsupported configuration view method");
        });
    }

    private Object layerServiceExporter(final Class<?> type, final ServiceExporter exporter) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object proxy, Method method, Object[] args) -> {
            if ("export".equals(method.getName())) {
                throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED,
                        "JPMS application services must be declared in the manifest");
            }
            if ("toString".equals(method.getName())) {
                return "J7M JPMS service exporter";
            }
            return method.invoke(exporter, args);
        });
    }

    private Object layerAuditPublisher(final Class<?> type, final AuditPublisher publisher) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object proxy, Method method, Object[] args) -> {
            if ("publish".equals(method.getName())) {
                @SuppressWarnings("unchecked")
                Map<String, String> details = (Map<String, String>) args[1];
                publisher.publish((String) args[0], details);
                return null;
            }
            if ("toString".equals(method.getName())) {
                return "J7M JPMS audit publisher";
            }
            throw MiddlewareException.of(MiddlewareErrorCode.METHOD_NOT_FOUND, "unsupported audit publisher method");
        });
    }

    private Object layerMetricsPublisher(final Class<?> type, final MetricsPublisher publisher) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object proxy, Method method, Object[] args) -> {
            if ("record".equals(method.getName())) {
                @SuppressWarnings("unchecked")
                Map<String, String> tags = (Map<String, String>) args[2];
                publisher.record((String) args[0], ((Long) args[1]).longValue(), tags);
                return null;
            }
            if ("toString".equals(method.getName())) {
                return "J7M JPMS metrics publisher";
            }
            throw MiddlewareException.of(MiddlewareErrorCode.METHOD_NOT_FOUND, "unsupported metrics publisher method");
        });
    }

    private MiddlewareException bridgeFailure(final ReflectiveOperationException exception) {
        return new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to bridge JPMS application context",
                Map.of(), null, this.runtime.identity().runtimeId(), false, true), exception);
    }

    private Class<?> loadJpmsServiceInterface(
            final ManagedActivation activation,
            final ApplicationManifest.ServiceDeclaration service) throws ClassNotFoundException {
        ServiceBinding binding = activation.jpmsServices.get(service.interfaceName());
        if (binding == null || !binding.implementationClass().equals(service.implementationClass())) {
            throw new ClassNotFoundException(service.interfaceName());
        }
        return Class.forName(service.interfaceName(), false, activation.moduleLayer.findLoader(binding.interfaceModule()));
    }

    private Class<?> loadJpmsServiceImplementation(
            final ManagedActivation activation,
            final ApplicationManifest.ServiceDeclaration service) throws ClassNotFoundException {
        ServiceBinding binding = activation.jpmsServices.get(service.interfaceName());
        if (binding == null || !binding.implementationClass().equals(service.implementationClass())) {
            throw new ClassNotFoundException(service.implementationClass());
        }
        return Class.forName(service.implementationClass(), false, activation.moduleLayer.findLoader(binding.implementationModule()));
    }

    private Map<String, ServiceBinding> buildJpmsServiceTable(
            final ApplicationManifest manifest,
            final Path extractedDirectory) {
        Map<String, ServiceBinding> bindings = new LinkedHashMap<>();
        for (ApplicationManifest.ServiceDeclaration service : manifest.services()) {
            String interfaceModule = this.declaredModuleContainingClass(manifest, extractedDirectory, service.interfaceName());
            String implementationModule = this.declaredModuleContainingClass(manifest, extractedDirectory, service.implementationClass());
            if (bindings.put(service.interfaceName(), new ServiceBinding(
                    service.interfaceName(), service.implementationClass(), interfaceModule, implementationModule)) != null) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate JPMS service interface declaration");
            }
        }
        return Map.copyOf(bindings);
    }

    private String declaredModuleContainingClass(
            final ApplicationManifest manifest,
            final Path extractedDirectory,
            final String className) {
        String entryName = className.replace('.', '/') + ".class";
        String foundModule = "";
        for (ApplicationManifest.ModuleDeclaration declaration : manifest.modules()) {
            Path modulePath = extractedDirectory.resolve(declaration.path()).normalize();
            try (JarFile jar = new JarFile(modulePath.toFile(), true)) {
                if (jar.getEntry(entryName) != null) {
                    if (!foundModule.isBlank()) {
                        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "JPMS service class has duplicate module owners");
                    }
                    foundModule = declaration.name();
                }
            } catch (IOException exception) {
                throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "declared JPMS module cannot be inspected", Map.of(), null, this.runtime.identity().runtimeId(),
                        false, false), exception);
            }
        }
        if (foundModule.isBlank()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "JPMS service class is not declared by application manifest modules");
        }
        return foundModule;
    }

    private List<Path> modulePathEntries(final ApplicationManifest manifest, final Path extractedDirectory) {
        List<Path> paths = new ArrayList<>();
        for (ApplicationManifest.ModuleDeclaration module : manifest.modules()) {
            Path modulePath = extractedDirectory.resolve(module.path()).normalize();
            if (!modulePath.startsWith(extractedDirectory) || !Files.exists(modulePath)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "declared module JAR is missing: " + module.path());
            }
            paths.add(modulePath);
        }
        paths.add(this.moduleLocation(ApplicationContext.class));
        paths.add(this.moduleLocation(org.j7m.middleware.api.MiddlewareRuntime.class));
        paths.add(this.moduleLocation(org.j7m.middleware.security.api.Principal.class));
        paths.add(this.moduleLocation(org.j7m.middleware.annotations.RemoteService.class));
        return paths;
    }

    private void validateJpmsModuleDeclarations(final ApplicationManifest manifest, final Path extractedDirectory) {
        Set<String> declared = new HashSet<>();
        for (ApplicationManifest.ModuleDeclaration module : manifest.modules()) {
            if (!declared.add(module.name())) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate application module declaration");
            }
            Path modulePath = extractedDirectory.resolve(module.path()).normalize();
            if (!modulePath.startsWith(extractedDirectory) || !Files.isRegularFile(modulePath)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "declared module JAR is missing: " + module.path());
            }
            Optional<ModuleReference> reference = ModuleFinder.of(modulePath).find(module.name());
            if (reference.isEmpty()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "declared module JAR descriptor does not match manifest");
            }
            ModuleDescriptor descriptor = reference.get().descriptor();
            if (!descriptor.name().equals(module.name())) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "declared module JAR descriptor does not match manifest");
            }
        }
        if (!declared.contains(manifest.bootstrapModule())) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bootstrap module is not declared in the application manifest");
        }
    }

    private void requireDeclaredJpmsClassOrigin(final ApplicationManifest manifest, final Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray()) {
            return;
        }
        String moduleName = type.getModule() == null ? "" : type.getModule().getName();
        for (ApplicationManifest.ModuleDeclaration declaration : manifest.modules()) {
            if (declaration.name().equals(moduleName)) {
                return;
            }
        }
        throw org.j7m.middleware.application.components.ManagedInjectionException.preflight(type,
                org.j7m.middleware.application.components.ManagedInjectionException.Reason.CLASS_ORIGIN);
    }

    private Path moduleLocation(final Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to locate module dependency",
                    Map.of(), null, this.runtime.identity().runtimeId(), false, true), exception);
        }
    }

    private Map<String, String> serviceMetadata(final ApplicationManifest manifest, final ApplicationManifest.ServiceDeclaration service,
            final ManagedActivation activation) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : service.metadata().entrySet()) {
            if (Set.of("domain", "classification", "example", "phase", "sbom", "provenance", "tier", "region", "zone", "owner",
                    "mode", "template", "version")
                    .contains(entry.getKey())) {
                metadata.put(entry.getKey(), entry.getValue());
            }
        }
        metadata.put("application", manifest.identity().coordinate().toString());
        metadata.put("applicationInstance", manifest.identity().coordinate().id().name());
        metadata.put("moduleMode", manifest.moduleMode());
        if (activation.managedPojo != null) metadata.putAll(activation.managedPojo.metadata(service.serviceName()));
        return Map.copyOf(metadata);
    }

    private ApplicationContext context(
            final ApplicationManifest manifest,
            final ApplicationInstanceIdentity instanceIdentity,
            final Principal principal,
            final Map<String, String> configuration,
            final ManagedActivation activation) {
        ApplicationIdentity identity = manifest == null ? instanceIdentity.application() : manifest.identity();
        RuntimeIdentity runtimeIdentity = this.runtime.identity();
        return new ApplicationContext() {
            @Override
            public ApplicationIdentity applicationIdentity() {
                return identity;
            }

            @Override
            public ApplicationInstanceIdentity instanceIdentity() {
                return instanceIdentity;
            }

            @Override
            public Principal activationPrincipal() {
                return principal;
            }

            @Override
            public RuntimeIdentity runtimeIdentity() {
                return runtimeIdentity;
            }

            @Override
            public ConfigurationView configuration() {
                return ConfigurationView.of(configuration);
            }

            @Override
            public ServiceExporter services() {
                return new ServiceExporter() {
                    @Override
                    public <T> ObjectRegistration<T> export(
                            final String serviceName,
                            final T instance,
                            final Class<T> api,
                            final ExportOptions options) {
                        if (activation == null || activation.state != ApplicationRunState.RUNNING) {
                            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED,
                                    "application services cannot be exported before readiness");
                        }
                        ObjectRegistration<T> registration = runtime.export(serviceName, instance, api, options);
                        activation.registrations.add(registration);
                        return registration;
                    }
                };
            }

            @Override
            public AuditPublisher audit() {
                return AuditPublisher.noop();
            }

            @Override
            public MetricsPublisher metrics() {
                return MetricsPublisher.noop();
            }

            @Override
            public ResourceUsageSnapshot resources() {
                if (activation == null) {
                    return new ResourceUsageSnapshot(0, runtime.status().instances().size(), 0, 0, 0);
                }
                return new ResourceUsageSnapshot(activation.activeInvocations.get(), runtime.status().instances().size(), 0,
                        activation.lastInboundPayloadBytes.get(), 0);
            }
        };
    }

    private void enforceActivationResourcePolicy(final ResourceLimits limits, final Map<String, String> configuration) {
        double maxCpu = parseDouble(configuration.get("resource.maxCpu"), Double.MAX_VALUE);
        long maxMemory = parseBytes(configuration.get("resource.maxMemory"), Long.MAX_VALUE);
        if (limits.cpu > maxCpu || limits.memoryBytes > maxMemory) {
            this.emitAudit("application.activate", Map.of(
                    "result", "denied",
                    "reason", "resource-policy"));
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "application resource policy denied activation");
        }
    }

    private static int estimatePayloadBytes(final Object[] args, final int configuredLimit) {
        if (args == null || args.length == 0) {
            return 0;
        }
        PayloadAccounting accounting = new PayloadAccounting(configuredLimit);
        accounting.accountArray(args, 0);
        return accounting.result();
    }

    private static double parseDouble(final String value, final double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseBytes(final String value, final long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        long multiplier = 1L;
        String number = normalized;
        if (normalized.endsWith("MIB")) {
            multiplier = 1024L * 1024L;
            number = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("KIB")) {
            multiplier = 1024L;
            number = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("GIB")) {
            multiplier = 1024L * 1024L * 1024L;
            number = normalized.substring(0, normalized.length() - 3);
        }
        try {
            return Long.parseLong(number.trim()) * multiplier;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Path extractBundle(final ApplicationBundle bundle) throws IOException {
        Path directory = Files.createTempDirectory("j7m-app-");
        long aggregate = 0L;
        try {
            BoundedBundleIO.validateBundleFile(bundle.path(), this.limits);
            try (ZipFile zip = new ZipFile(bundle.path().toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
                List<ZipEntry> entries = BoundedBundleIO.validatedEntries(zip, this.limits);
                for (ZipEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                    String name = BoundedBundleIO.normalizeEntryName(entry.getName(), false, this.limits);
                Path target = directory.resolve(name);
                    Path normalized = target.normalize();
                    if (!normalized.startsWith(directory)) {
                        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "unsafe bundle path");
                    }
                Files.createDirectories(target.getParent());
                try (java.io.InputStream input = zip.getInputStream(entry)) {
                        try (java.io.OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                            long copied = BoundedBundleIO.copy(input, output, this.limits.maxEntryExpandedBytes(),
                                    this.limits.ioBufferBytes(), null);
                            aggregate = BoundedBundleIO.checkedAdd(aggregate, copied, "expanded bundle size limit exceeded");
                            if (aggregate > this.limits.maxAggregateExpandedBytes()) {
                                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "expanded bundle size limit exceeded");
                            }
                        }
                    }
                }
            }
            return directory;
        } catch (IOException | RuntimeException exception) {
            this.deleteTreeBestEffort(directory);
            throw exception;
        }
    }

    private URLClassLoader createClassLoader(final Path extracted) throws IOException {
        Path lib = extracted.resolve("lib");
        List<URL> urls = new ArrayList<>();
        if (Files.isDirectory(lib)) {
            try (java.util.stream.Stream<Path> stream = Files.list(lib)) {
                java.util.Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (path.toString().endsWith(".jar")) {
                        if (urls.size() == this.limits.maxEntryCount()) {
                            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED,
                                    "application classpath entry limit exceeded");
                        }
                        urls.add(path.toUri().toURL());
                    }
                }
            }
        }
        return new ApplicationClassLoader(urls.toArray(URL[]::new), ApplicationManager.class.getClassLoader());
    }

    private void requireClasspathOwnedClass(final ManagedActivation activation, final Class<?> type) {
        if (activation.moduleLayer != null || type == null || type.isPrimitive() || type.isArray()) {
            return;
        }
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application class origin is unavailable");
        }
        try {
            Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            Path libraryDirectory = activation.extractedDirectory.resolve("lib").toAbsolutePath().normalize();
            if (!location.startsWith(libraryDirectory) || !Files.isRegularFile(location)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application class origin is outside bundle");
            }
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application class origin is invalid");
        }
    }

    private void rollbackFailedActivation(final ManagedActivation activation) {
        activation.acceptingInvocations = false;
        if (activation.managedPojo != null && activation.managedPojo.hasHostedResources()) {
            activation.managedPojo.stopHostedAdmission();
            boolean drained;
            try { drained = activation.managedPojo.drainHosted(); }
            catch (RuntimeException failure) {
                this.recordLifecycleCleanupFailure(activation, failure, "hosted-failed-start-drain"); drained = false;
            }
            if (!drained || activation.activeInvocations.get() != 0) {
                this.closeProbes(activation);
                this.transition(activation, ApplicationRunState.DRAINING, "failed-start-work-still-completing");
                return;
            }
        }
        this.closeProbes(activation);
        this.transition(activation, ApplicationRunState.FAILED, "failed");
        this.stopStartedActivation(activation);
        this.closeRegistrations(activation);
        activation.heartbeatStopped = true;
        this.closeClassLoader(activation);
        this.releaseManagerReferences(activation);
    }

    private void stopStartedActivation(final ManagedActivation activation) {
        if (activation.managedPojo != null) {
            try { activation.managedPojo.close(); activation.lifecycleStoppedDuringRollback = true; }
            catch (RuntimeException failure) { this.recordLifecycleCleanupFailure(activation, failure, "managed-stop"); }
            return;
        }
        if (!activation.lifecycleStarted) {
            return;
        }
        try {
            if (activation.application != null) {
                activation.application.stop(this.context(null, activation.identity, Principal.runtime(this.runtime.identity().runtimeId()), Map.of(), activation));
            } else if (activation.applicationObject != null && activation.layerContextProxy != null) {
                this.invokeJpmsLifecycle(activation.applicationObject, "stop", activation.layerContextProxy);
            }
            activation.lifecycleStoppedDuringRollback = true;
        } catch (RuntimeException exception) {
            if (containsInterruptedException(exception)) {
                Thread.currentThread().interrupt();
            }
            activation.stopCleanupFailureCount++;
            activation.stopCleanupFailureSummary = "application-stop-failed";
            this.emitAudit("application.rollback", Map.of(
                    "result", "cleanup-stop-failed",
                    "application", activation.identity.application().coordinate().toString()));
        }
    }

    private void recordLifecycleCleanupFailure(
            final ManagedActivation activation,
            final RuntimeException exception,
            final String stage) {
        if (containsInterruptedException(exception)) {
            Thread.currentThread().interrupt();
        }
        activation.stopCleanupFailureCount++;
        activation.stopCleanupFailureSummary = "application-" + stage + "-failed";
        this.emitAudit("application.cleanup", Map.of(
                "result", "lifecycle-hook-failed",
                "stage", stage));
    }

    private ApplicationDeploymentResult rollingUpgrade(
            final ManagedActivation current,
            final ApplicationCoordinate targetVersion,
            final org.j7m.middleware.deployer.api.UpgradeOptions options,
            final ApplicationActivationRequest request) {
        ApplicationCoordinate previous = current.identity.application().coordinate();
        try {
            ApplicationActivation upgraded = this.activate(targetVersion, request);
            ManagedActivation target = this.activations.get(upgraded.instanceIdentity().instanceId().value());
            if (target != null) {
                target.upgradeStrategyState = "rolling-step-1-complete";
                target.rollingStepState = "replaced=" + current.identity.instanceId().value();
                target.selectedActiveVersion = true;
                target.previousActivationRequest = current.activationRequest;
            }
            current.selectedActiveVersion = false;
            this.deactivate(current.identity.instanceId());
            this.rememberRollbackCandidate(upgraded.instanceIdentity().instanceId().value(), previous);
            this.addUpgradeHistory("rolling upgrade " + previous + " -> " + targetVersion + " succeeded batchSize=" + options.rollingBatchSize());
            this.emitAudit("application.upgrade", Map.of("strategy", "rolling", "previous", previous.toString(),
                    "target", targetVersion.toString(), "result", "success"));
            return new ApplicationDeploymentResult(true, ApplicationRunState.RUNNING, "rolling upgrade succeeded",
                    List.of("strategy=rolling", "previous=" + previous, "current=" + targetVersion));
        } catch (RuntimeException exception) {
            current.state = ApplicationRunState.RUNNING;
            current.acceptingInvocations = true;
            current.upgradeStrategyState = "rolling-rollback-baseline-active";
            this.addUpgradeHistory("rolling upgrade " + previous + " -> " + targetVersion + " failed; baseline retained");
            return new ApplicationDeploymentResult(false, ApplicationRunState.RUNNING, "rolling upgrade failed and baseline remained active",
                    List.of("strategy=rolling", "previous=" + previous, "target=" + targetVersion));
        }
    }

    private ApplicationDeploymentResult blueGreenUpgrade(
            final ManagedActivation current,
            final ApplicationCoordinate targetVersion,
            final org.j7m.middleware.deployer.api.UpgradeOptions options,
            final ApplicationActivationRequest request) {
        ApplicationCoordinate previous = current.identity.application().coordinate();
        try {
            ApplicationActivation green = this.activate(targetVersion, request);
            ManagedActivation greenActivation = this.activations.get(green.instanceIdentity().instanceId().value());
            current.state = ApplicationRunState.RUNNING;
            current.acceptingInvocations = true;
            current.upgradeColor = "blue";
            current.selectedActiveVersion = false;
            current.upgradeStrategyState = "blue-retained";
            if (greenActivation != null) {
                greenActivation.upgradeColor = "green";
                greenActivation.selectedActiveVersion = true;
                greenActivation.previousActivationRequest = current.activationRequest;
                greenActivation.coexistingVersions = previous + "," + targetVersion;
                greenActivation.upgradeStrategyState = "green-selected";
            }
            this.rememberRollbackCandidate(green.instanceIdentity().instanceId().value(), previous);
            this.rememberRollbackRetainedInstance(green.instanceIdentity().instanceId().value(), current.identity.instanceId().value());
            this.addUpgradeHistory("blue-green upgrade " + previous + " -> " + targetVersion + " switched to green");
            this.emitAudit("application.upgrade", Map.of("strategy", "blue-green", "previous", previous.toString(),
                    "target", targetVersion.toString(), "result", "success"));
            return new ApplicationDeploymentResult(true, ApplicationRunState.RUNNING, "blue-green upgrade switched to green",
                    List.of("strategy=blue-green", "blue=" + previous, "green=" + targetVersion,
                            "retainPrevious=" + options.retainPrevious()));
        } catch (RuntimeException exception) {
            current.state = ApplicationRunState.RUNNING;
            current.acceptingInvocations = true;
            current.selectedActiveVersion = true;
            current.upgradeStrategyState = "blue-green-readiness-failed";
            this.addUpgradeHistory("blue-green upgrade " + previous + " -> " + targetVersion + " failed; blue retained");
            return new ApplicationDeploymentResult(false, ApplicationRunState.RUNNING, "blue-green upgrade failed and blue remained active",
                    List.of("strategy=blue-green", "previous=" + previous, "target=" + targetVersion));
        }
    }

    private ApplicationDeploymentResult canaryBaselineUpgrade(
            final ManagedActivation current,
            final ApplicationCoordinate targetVersion,
            final org.j7m.middleware.deployer.api.UpgradeOptions options,
            final ApplicationActivationRequest request) {
        ApplicationCoordinate previous = current.identity.application().coordinate();
        current.state = ApplicationRunState.RUNNING;
        current.acceptingInvocations = true;
        current.selectedActiveVersion = true;
        current.upgradeStrategyState = "canary-unsupported-no-probe-boundary";
        this.addUpgradeHistory("canary upgrade " + previous + " -> " + targetVersion + " unsupported; baseline retained");
        this.emitAudit("application.upgrade", Map.of("strategy", "canary-baseline", "previous", previous.toString(),
                "target", targetVersion.toString(), "result", "unsupported"));
        return new ApplicationDeploymentResult(false, ApplicationRunState.RUNNING,
                "canary baseline upgrade is unsupported without a configured probe boundary",
                List.of("strategy=canary-baseline", "previous=" + previous, "target=" + targetVersion,
                        "reason=unsupported-no-probe-boundary"));
    }

    private void transition(final ManagedActivation activation, final ApplicationRunState state, final String stage) {
        activation.state = state;
        activation.stage = stage;
        activation.transitionTimestamps.put(state.name(), Instant.now().toString());
        if (state == ApplicationRunState.FAILED || state == ApplicationRunState.REJECTED) {
            activation.lastFailedState = state.name();
        } else {
            activation.lastSuccessfulState = state.name();
        }
        this.emitAudit("application.transition", Map.of(
                "instanceId", activation.identity.instanceId().value(),
                "state", state.name(),
                "stage", stage));
    }

    private void markFailure(final ManagedActivation activation, final String code, final String message) {
        activation.failureCode = code;
        activation.failureMessage = message;
        activation.failureDetails = "<redacted>";
        activation.rollbackAttempted = "false";
        activation.rollbackResult = "not-required";
    }

    private void closeRegistrations(final ManagedActivation activation) {
        int count = activation.registrations.size();
        int failures = 0;
        for (ObjectRegistration<?> registration : activation.registrations) {
            try {
                registration.close();
            } catch (RuntimeException exception) {
                failures++;
                if (containsInterruptedException(exception)) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        activation.partialServicesExportedCount = Math.max(activation.partialServicesExportedCount, count);
        activation.partialServicesCleanedCount += count;
        activation.registryRecordsCleanedCount += count;
        if (failures > 0) {
            activation.registrationCleanupFailureCount += failures;
            activation.registrationCleanupFailureSummary = failures >= count
                    ? "registration-close-failed-all"
                    : "registration-close-failed-partial";
            this.emitAudit("application.cleanup", Map.of(
                    "result", "registration-close-failed",
                    "failures", Integer.toString(Math.min(failures, count))));
        }
        activation.registrations.clear();
    }

    private static boolean containsInterruptedException(final Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private void releaseManagerReferences(final ManagedActivation activation) {
        if (this.deleteTree(activation.extractedDirectory, activation)) {
            activation.extractedDirectory = null;
        }
        activation.classLoader = null;
        activation.moduleLayer = null;
        activation.application = null;
        activation.applicationObject = null;
        activation.managedPojo = null;
        activation.activationRequest = null;
        activation.previousActivationRequest = null;
        activation.layerContextProxy = null;
        activation.managerReferencesReleased = true;
    }

    private void closeClassLoader(final ManagedActivation activation) {
        if (activation.classLoader == null) {
            return;
        }
        try {
            activation.classLoader.close();
        } catch (IOException exception) {
            this.emitAudit("application.cleanup", Map.of("result", "classloader-close-failed"));
        }
    }

    private boolean deleteTree(final Path path, final ManagedActivation activation) {
        if (path == null || !Files.exists(path)) {
            activation.cleanupDeleteStatus = "complete";
            return true;
        }
        BoundedCleanupCounter counter = new BoundedCleanupCounter(this.limits.maxEntryCount());
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    counter.increment();
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    counter.increment();
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            activation.cleanupDeleteExaminedCount = counter.count();
            activation.cleanupDeleteStatus = "complete";
            activation.cleanupDeleteFailureSummary = "";
            return true;
        } catch (IOException | RuntimeException exception) {
            activation.cleanupDeleteExaminedCount = counter.count();
            activation.cleanupDeleteStatus = exception instanceof MiddlewareException ? "bounded-retry-required" : "retry-required";
            activation.cleanupDeleteFailureSummary = "delete-failed";
            this.emitAudit("application.cleanup", Map.of("result", "delete-failed"));
            return false;
        }
    }

    private void deleteTreeBestEffort(final Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException exception) {
            this.emitAudit("application.cleanup", Map.of("result", "delete-failed"));
        }
    }

    private static String safeMessage(final Throwable throwable) {
        if (throwable == null) {
            return "operation failed";
        }
        if (throwable instanceof MiddlewareException middlewareException) {
            return "middleware operation failed: " + middlewareException.code().name();
        }
        if (throwable instanceof IOException) {
            return "application I/O operation failed";
        }
        if (throwable instanceof ReflectiveOperationException) {
            return "operation failed";
        }
        if (throwable instanceof RuntimeException) {
            return "application operation failed";
        }
        return "operation failed";
    }

    private static final class PayloadAccounting {
        private final Set<Object> seen;
        private final long limitPlusOne;
        private long bytes;
        private int objects;

        private PayloadAccounting(final int configuredLimit) {
            this.seen = Collections.newSetFromMap(new IdentityHashMap<>());
            this.limitPlusOne = configuredLimit <= 0 ? Integer.MAX_VALUE : (long) configuredLimit + 1L;
        }

        private void accountArray(final Object[] values, final int depth) {
            if (!this.accountContainer(values, depth)) {
                return;
            }
            for (Object value : values) {
                this.account(value, depth + 1);
                if (this.bytes >= this.limitPlusOne) {
                    return;
                }
            }
        }

        private void account(final Object value, final int depth) {
            if (value == null || this.bytes >= this.limitPlusOne) {
                return;
            }
            if (depth > MAX_PAYLOAD_ACCOUNTING_DEPTH) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application inbound payload structure exceeds configured depth");
            }
            Class<?> type = value.getClass();
            this.objects++;
            if (this.objects > MAX_PAYLOAD_ACCOUNTING_OBJECTS) {
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED,
                        "application inbound payload structure exceeds configured count");
            }
            if (value instanceof String text) {
                this.addUtf8Bytes(text);
            } else if (value instanceof byte[] bytesValue) {
                this.add(bytesValue.length);
            } else if (value instanceof boolean[] booleans) {
                this.add(booleans.length);
            } else if (value instanceof char[] characters) {
                this.add((long) characters.length * Character.BYTES);
            } else if (value instanceof short[] shorts) {
                this.add((long) shorts.length * Short.BYTES);
            } else if (value instanceof int[] integers) {
                this.add((long) integers.length * Integer.BYTES);
            } else if (value instanceof long[] longs) {
                this.add((long) longs.length * Long.BYTES);
            } else if (value instanceof float[] floats) {
                this.add((long) floats.length * Float.BYTES);
            } else if (value instanceof double[] doubles) {
                this.add((long) doubles.length * Double.BYTES);
            } else if (value instanceof Object[] array) {
                this.accountArray(array, depth);
            } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                    || value instanceof Byte || value instanceof Float || value instanceof Double) {
                this.add(8L);
            } else if (value instanceof Boolean) {
                this.add(1L);
            } else if (value instanceof Character) {
                this.add(Character.BYTES);
            } else if (type.isRecord()) {
                this.accountRecord(value, type, depth);
            } else if (value instanceof Enum<?>) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application inbound payload contains unsupported enum value");
            } else if (value instanceof List<?> list && isJdkCollection(value)) {
                this.accountIterable(list, depth);
            } else if (value instanceof Set<?> set && isJdkCollection(value)) {
                this.accountIterable(set, depth);
            } else if (value instanceof Map<?, ?> map && isJdkCollection(value)) {
                this.accountMap(map, depth);
            } else {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application inbound payload contains unsupported structural type");
            }
        }

        private void accountIterable(final Iterable<?> values, final int depth) {
            if (!this.accountContainer(values, depth)) {
                return;
            }
            for (Object value : values) {
                this.account(value, depth + 1);
                if (this.bytes >= this.limitPlusOne) {
                    return;
                }
            }
        }

        private void accountMap(final Map<?, ?> values, final int depth) {
            if (!this.accountContainer(values, depth)) {
                return;
            }
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                this.account(entry.getKey(), depth + 1);
                this.account(entry.getValue(), depth + 1);
                if (this.bytes >= this.limitPlusOne) {
                    return;
                }
            }
        }

        private boolean accountContainer(final Object value, final int depth) {
            if (depth > MAX_PAYLOAD_ACCOUNTING_DEPTH) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application inbound payload structure exceeds configured depth");
            }
            if (!this.seen.add(value)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application inbound payload contains repeated structural identity");
            }
            return true;
        }

        private void addUtf8Bytes(final String value) {
            long total = 0L;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character <= 0x7f) {
                    total++;
                } else if (character <= 0x7ff) {
                    total += 2L;
                } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    total += 4L;
                    index++;
                } else {
                    total += 3L;
                }
                if (total >= this.limitPlusOne) {
                    break;
                }
            }
            this.add(total);
        }

        private void add(final long amount) {
            if (amount < 0L || this.bytes > Long.MAX_VALUE - amount) {
                this.bytes = this.limitPlusOne;
                return;
            }
            this.bytes += amount;
            if (this.bytes > this.limitPlusOne) {
                this.bytes = this.limitPlusOne;
            }
        }

        private int result() {
            return this.bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) this.bytes;
        }

        private static boolean isJdkCollection(final Object value) {
            String name = value.getClass().getName();
            return name.startsWith("java.util.");
        }

        private void accountRecord(final Object value, final Class<?> type, final int depth) {
            if (!this.accountContainer(value, depth)) {
                return;
            }
            RecordComponent[] components = type.getRecordComponents();
            this.add(16L);
            for (RecordComponent component : components) {
                Class<?> componentType = component.getType();
                if (isFixedWidthRecordComponent(componentType)) {
                    this.add(fixedRecordComponentBytes(componentType));
                } else {
                    this.account(recordComponentValue(value, type, component), depth + 1);
                }
            }
        }

        private static boolean isFixedWidthRecordComponent(final Class<?> type) {
            return type == boolean.class || type == Boolean.class
                    || type == byte.class || type == Byte.class
                    || type == short.class || type == Short.class
                    || type == char.class || type == Character.class
                    || type == int.class || type == Integer.class
                    || type == float.class || type == Float.class
                    || type == long.class || type == Long.class
                    || type == double.class || type == Double.class
                    || type == Instant.class;
        }

        private static long fixedRecordComponentBytes(final Class<?> type) {
            if (type == boolean.class || type == Boolean.class) {
                return 1L;
            }
            if (type == byte.class || type == Byte.class) {
                return Byte.BYTES;
            }
            if (type == short.class || type == Short.class) {
                return Short.BYTES;
            }
            if (type == char.class || type == Character.class) {
                return Character.BYTES;
            }
            if (type == int.class || type == Integer.class || type == float.class || type == Float.class) {
                return Integer.BYTES;
            }
            if (type == long.class || type == Long.class || type == double.class || type == Double.class) {
                return Long.BYTES;
            }
            if (type == Instant.class) {
                return Long.BYTES + Integer.BYTES;
            }
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                    "application inbound payload contains unsupported record component");
        }

        private static Object recordComponentValue(
                final Object value,
                final Class<?> recordType,
                final RecordComponent component) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(recordType, MethodHandles.lookup());
                VarHandle handle = lookup.findVarHandle(recordType, component.getName(), component.getType());
                return handle.get(value);
            } catch (NoSuchFieldException | IllegalAccessException | RuntimeException exception) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "application inbound payload contains unsupported record component");
            }
        }
    }

    private static final class ApplicationClassLoader extends URLClassLoader {
        private ApplicationClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (this.getClassLoadingLock(name)) {
                Class<?> loaded = this.findLoadedClass(name);
                if (loaded == null) {
                    if (isParentFirst(name)) {
                        loaded = super.loadClass(name, false);
                    } else {
                        try {
                            loaded = this.findClass(name);
                        } catch (ClassNotFoundException exception) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) {
                    this.resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean isParentFirst(final String name) {
            return name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("jdk.")
                    || name.startsWith("sun.")
                    || name.startsWith("org.j7m.middleware.api.")
                    || name.startsWith("org.j7m.middleware.application.api.")
                    || name.startsWith("org.j7m.middleware.security.api.")
                    || name.startsWith("org.j7m.middleware.annotations.")
                    || name.startsWith("org.j7m.middleware.deployer.api.");
        }
    }

    private static final class BoundedCleanupCounter {
        private final int limit;
        private int count;

        private BoundedCleanupCounter(final int limit) {
            this.limit = limit;
        }

        private void increment() {
            this.count++;
            if (this.count > this.limit) {
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "cleanup entry limit exceeded");
            }
        }

        private int count() {
            return this.count;
        }
    }

    private record ServiceBinding(
            String interfaceName,
            String implementationClass,
            String interfaceModule,
            String implementationModule) {
    }

    private static final class ManagedActivation {
        private final ActivationIdentity activationId;
        private final ApplicationInstanceIdentity identity;
        private final List<ObjectRegistration<?>> registrations;
        private final List<RuntimeServiceIdentity> services;
        private volatile ApplicationRunState state;
        private volatile URLClassLoader classLoader;
        private volatile ModuleLayer moduleLayer;
        private volatile List<String> moduleNames;
        private volatile Map<String, ServiceBinding> jpmsServices;
        private volatile Path extractedDirectory;
        private volatile ApplicationActivationRequest activationRequest;
        private volatile ApplicationActivationRequest previousActivationRequest;
        private volatile boolean managedProfile;
        private volatile ManagedPojoActivation managedPojo;
        private Object probeToken;
        private ManagedProbeMonitor.Registration probeRegistration;
        private volatile J7mApplication application;
        private volatile Object applicationObject;
        private volatile Object layerContextProxy;
        private volatile ApplicationManifest manifest;
        private volatile ResourceLimits resourceLimits;
        private volatile boolean acceptingInvocations;
        private volatile boolean lifecycleStarted;
        private volatile boolean lifecycleStoppedDuringRollback;
        private volatile boolean heartbeatStopped;
        private volatile boolean managerReferencesReleased;
        private volatile boolean selectedActiveVersion;
        private volatile String artifactDigest;
        private volatile String operatorPrincipal;
        private volatile String verificationProfile;
        private volatile String verificationSummary;
        private volatile String resourcePolicySummary;
        private volatile String securityPolicySummary;
        private volatile String correlationId;
        private volatile String stage;
        private volatile String failureCode;
        private volatile String failureMessage;
        private volatile String failureDetails;
        private volatile org.j7m.middleware.application.api.InjectionDiagnostic injectionDiagnostic;
        private volatile String lastSuccessfulState;
        private volatile String lastFailedState;
        private volatile String rollbackAttempted;
        private volatile String rollbackResult;
        private volatile int registrationCleanupFailureCount;
        private volatile String registrationCleanupFailureSummary;
        private volatile int stopCleanupFailureCount;
        private volatile String stopCleanupFailureSummary;
        private volatile String upgradeStrategyState;
        private volatile String upgradeColor;
        private volatile String coexistingVersions;
        private volatile String rollingStepState;
        private volatile int canaryInvocations;
        private volatile int canarySuccesses;
        private volatile int partialServicesExportedCount;
        private volatile int partialServicesCleanedCount;
        private volatile int registryRecordsCleanedCount;
        private volatile int cleanupDeleteExaminedCount;
        private volatile String cleanupDeleteStatus;
        private volatile String cleanupDeleteFailureSummary;
        private final Map<String, String> transitionTimestamps;
        private final AtomicInteger activeInvocations;
        private final AtomicInteger completedInvocations;
        private final AtomicInteger rejectedConcurrentInvocations;
        private final AtomicInteger rejectedPayloadInvocations;
        private final AtomicInteger lastInboundPayloadBytes;
        private volatile String lastResourceRejectionReason;

        private ManagedActivation(final ActivationIdentity activationId, final ApplicationInstanceIdentity identity) {
            this.activationId = activationId;
            this.identity = identity;
            this.registrations = new ArrayList<>();
            this.services = new ArrayList<>();
            this.moduleNames = List.of();
            this.jpmsServices = Map.of();
            this.state = ApplicationRunState.LOADING;
            this.resourceLimits = ResourceLimits.unlimited();
            this.acceptingInvocations = false;
            this.heartbeatStopped = false;
            this.managerReferencesReleased = false;
            this.selectedActiveVersion = true;
            this.artifactDigest = "";
            this.operatorPrincipal = "";
            this.verificationProfile = "";
            this.verificationSummary = "";
            this.resourcePolicySummary = "";
            this.securityPolicySummary = "";
            this.correlationId = "";
            this.stage = "";
            this.failureCode = "";
            this.failureMessage = "";
            this.failureDetails = "";
            this.lastSuccessfulState = "";
            this.lastFailedState = "";
            this.rollbackAttempted = "";
            this.rollbackResult = "";
            this.registrationCleanupFailureSummary = "";
            this.stopCleanupFailureSummary = "";
            this.upgradeStrategyState = "";
            this.upgradeColor = "";
            this.coexistingVersions = "";
            this.rollingStepState = "";
            this.cleanupDeleteStatus = "not-started";
            this.cleanupDeleteFailureSummary = "";
            this.transitionTimestamps = new ConcurrentHashMap<>();
            this.activeInvocations = new AtomicInteger();
            this.completedInvocations = new AtomicInteger();
            this.rejectedConcurrentInvocations = new AtomicInteger();
            this.rejectedPayloadInvocations = new AtomicInteger();
            this.lastInboundPayloadBytes = new AtomicInteger();
            this.lastResourceRejectionReason = "";
        }

        private ApplicationActivation snapshot() {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("classLoader", this.classLoader == null ? "" : "isolated-application-loader");
            metadata.put("moduleMode", this.identity.classLoaderMode());
            metadata.put("moduleLayer", this.moduleLayer == null ? "" : "isolated-application-layer");
            metadata.put("modules", String.join(",", this.moduleNames));
            metadata.put("extractedDirectory", this.extractedDirectory == null ? "" : "<redacted>");
            metadata.put("serviceCount", Integer.toString(this.services.size()));
            metadata.put("lastActivationResult", this.state.name());
            metadata.put("activationId", this.activationId.value());
            metadata.put("applicationCoordinate", this.identity.application().coordinate().toString());
            metadata.put("artifactDigest", this.artifactDigest);
            metadata.put("stage", this.stage);
            metadata.put("safeErrorCode", this.failureCode);
            metadata.put("safeMessage", this.failureMessage);
            metadata.put("redactedDetails", this.failureDetails);
            if (this.injectionDiagnostic != null) {
                metadata.put("injection.reasonCode", this.injectionDiagnostic.reasonCode());
                metadata.put("injection.component", this.injectionDiagnostic.component());
                metadata.put("injection.graphPath", this.injectionDiagnostic.metadata().getOrDefault("graphPath", ""));
            }
            metadata.put("correlationId", this.correlationId);
            metadata.put("principal", this.operatorPrincipal);
            metadata.put("runtimeId", this.identity.runtime().runtimeId());
            metadata.put("verificationProfile", this.verificationProfile);
            metadata.put("verificationSummary", this.verificationSummary);
            metadata.put("resourcePolicySummary", this.resourcePolicySummary);
            metadata.put("securityPolicySummary", this.securityPolicySummary);
            metadata.put("partialServicesExportedCount", Integer.toString(this.partialServicesExportedCount));
            metadata.put("partialServicesCleanedCount", Integer.toString(this.partialServicesCleanedCount));
            metadata.put("registryRecordsCleanedCount", Integer.toString(this.registryRecordsCleanedCount));
            metadata.put("registrationCleanupFailureCount", Integer.toString(this.registrationCleanupFailureCount));
            metadata.put("registrationCleanupFailureSummary", this.registrationCleanupFailureSummary);
            metadata.put("stopCleanupFailureCount", Integer.toString(this.stopCleanupFailureCount));
            metadata.put("stopCleanupFailureSummary", this.stopCleanupFailureSummary);
            metadata.put("rollbackAttempted", this.rollbackAttempted);
            metadata.put("rollbackResult", this.rollbackResult);
            metadata.put("lastSuccessfulState", this.lastSuccessfulState);
            metadata.put("lastFailedState", this.lastFailedState);
            metadata.put("transitionTimestamps", new java.util.TreeMap<>(this.transitionTimestamps).toString());
            metadata.put("selectedActiveVersion", Boolean.toString(this.selectedActiveVersion));
            metadata.put("coexistingVersions", this.coexistingVersions);
            metadata.put("upgradeStrategyState", this.upgradeStrategyState);
            metadata.put("blueGreenColor", this.upgradeColor);
            metadata.put("rollingStepState", this.rollingStepState);
            metadata.put("canaryInvocations", Integer.toString(this.canaryInvocations));
            metadata.put("canarySuccesses", Integer.toString(this.canarySuccesses));
            metadata.put("heartbeatStopped", Boolean.toString(this.heartbeatStopped));
            metadata.put("lifecycleStoppedDuringRollback", Boolean.toString(this.lifecycleStoppedDuringRollback));
            metadata.put("managerReferencesReleased", Boolean.toString(this.managerReferencesReleased));
            metadata.put("cleanupDeleteStatus", this.cleanupDeleteStatus);
            metadata.put("cleanupDeleteExaminedCount", Integer.toString(this.cleanupDeleteExaminedCount));
            metadata.put("cleanupDeleteFailureSummary", this.cleanupDeleteFailureSummary);
            ResourceLimits limits = this.resourceLimits == null ? ResourceLimits.unlimited() : this.resourceLimits;
            metadata.put("configuredCpu", Double.toString(limits.cpu));
            metadata.put("configuredMemory", Long.toString(limits.memoryBytes));
            metadata.put("configuredMaxConcurrentInvocations", Integer.toString(limits.maxConcurrentInvocations));
            metadata.put("configuredMaxInboundPayload", Integer.toString(limits.maxInboundPayloadBytes));
            metadata.put("activeInvocations", Integer.toString(this.activeInvocations.get()));
            metadata.put("completedInvocations", Integer.toString(this.completedInvocations.get()));
            metadata.put("rejectedConcurrentInvocations", Integer.toString(this.rejectedConcurrentInvocations.get()));
            metadata.put("rejectedPayloadInvocations", Integer.toString(this.rejectedPayloadInvocations.get()));
            metadata.put("lastResourceRejectionReason", this.lastResourceRejectionReason);
            return new ApplicationActivation(this.activationId, this.identity, this.state, List.copyOf(this.services), metadata);
        }
    }

    private record ResourceLimits(double cpu, long memoryBytes, int maxConcurrentInvocations, int maxInboundPayloadBytes) {
        private static ResourceLimits from(final ApplicationManifest.ResourceDeclaration resources) {
            if (resources == null) {
                return unlimited();
            }
            return new ResourceLimits(
                    parseDouble(resources.cpu(), 0.0D),
                    parseBytes(resources.memory(), 0L),
                    resources.maxConcurrentInvocations(),
                    (int) Math.min(Integer.MAX_VALUE, parseBytes(resources.maxInboundPayload(), 0L)));
        }

        private static ResourceLimits unlimited() {
            return new ResourceLimits(0.0D, 0L, 0, 0);
        }
    }
}
