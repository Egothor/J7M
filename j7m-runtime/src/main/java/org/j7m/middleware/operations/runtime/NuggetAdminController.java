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

package org.j7m.middleware.operations.runtime;

import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.j7m.middleware.api.InstanceStatus;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.api.RegistrationState;
import org.j7m.middleware.api.RegistryStatus;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.api.TransportStatus;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRepositoryStatus;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.RuntimeServiceIdentity;
import org.j7m.middleware.deployer.api.ActivationOptions;
import org.j7m.middleware.deployer.api.ApplicationDeployer;
import org.j7m.middleware.deployer.api.DeactivationOptions;
import org.j7m.middleware.deployer.api.UnloadOptions;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetAdminCapabilities;
import org.j7m.middleware.operations.api.NuggetAdminCommandResult;
import org.j7m.middleware.operations.api.NuggetAdminContract;
import org.j7m.middleware.operations.api.NuggetAdminOperationKind;
import org.j7m.middleware.operations.api.NuggetAdminOutcome;
import org.j7m.middleware.operations.api.NuggetAdminRequestContext;
import org.j7m.middleware.operations.api.NuggetAdminResultHeader;
import org.j7m.middleware.operations.api.NuggetAdminState;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationFailureReason;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetCapability;
import org.j7m.middleware.operations.api.NuggetConfigurationSummary;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIdentity;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetPinSourceType;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.operations.api.NuggetStatus;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Redactor;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityAuditSink;
import org.j7m.middleware.security.api.SecurityMode;

/**
 * Headless per-JVM nugget administration admission service.
 *
 * <p>The controller owns nugget identity, PIN verifier lifecycle, admission
 * throttling, safe status, local/configured discovery, and authentication audit.
 * It does not bind a network endpoint, parse CLI commands, perform remote
 * authentication, or grant authorization. Successful PIN admission and discovery
 * records remain separate from authorization and from {@code ObjectRef} routing
 * semantics.</p>
 */
public final class NuggetAdminController implements NuggetAdminService {
    private static final Pattern SAFE_DISK_QUERY_IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern SAFE_METRIC_ID = Pattern.compile("[a-z][a-z0-9.]{0,95}");
    private static final Pattern SAFE_LABEL_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{0,96}");
    private static final Set<String> SAFE_METRIC_LABELS = Set.of("component", "result", "state", "visibility", "role", "source");
    private static final Set<String> SAFE_METRIC_UNITS = Set.of("count", "bytes", "milliseconds", "nanoseconds", "ratio", "percent");
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "secret", "password", "passwd", "pwd", "token", "key", "credential", "auth", "bearer", "session",
            "cookie", "certificate", "cert", "private", "database", "cloud");
    private static final char[] DEVELOPMENT_PIN_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final int SUPPORTED_DISCOVERY_PROTOCOL_VERSION = 1;
    private static final int MAX_EXPIRED_DISCOVERY_OBSERVATIONS = 2_048;
    private static final Duration MAX_DISCOVERY_CLOCK_SKEW = Duration.ofSeconds(30L);
    private static final int MAX_ADMISSIONS = 1_024;
    private static final int MAX_ADMISSION_CLEANUP_SCAN = 64;
    private static final Duration ADMISSION_TTL = Duration.ofMinutes(15L);
    private static final int CONTROL_ACTION_WAITING = 0;
    private static final int CONTROL_ACTION_RUNNING = 1;
    private static final int CONTROL_ACTION_COMPLETED = 2;
    private static final int CONTROL_ACTION_TIMED_OUT = 3;
    private static final int CONTROL_ACTION_CANCELLED = 4;
    private static final int CONTROL_ACTION_FAILED = 5;

    private final RuntimeIdentity runtimeIdentity;
    private final NuggetAdminConfiguration configuration;
    private final String nuggetId;
    private final Instant startedAt;
    private final AtomicReference<NuggetAdminState> state;
    private final ReentrantReadWriteLock verifierLock;
    private final Object admissionsLock;
    private volatile NuggetAuthenticationThrottle throttle;
    private volatile ConcurrentHashMap<String, AdmissionSession> admissions;
    private volatile ExecutorService providerExecutor;
    private volatile ExecutorService lifecycleExecutor;
    private volatile ExecutorService controlExecutor;
    private volatile ScheduledExecutorService controlMonitorExecutor;
    private volatile ScheduledExecutorService discoveryExecutor;
    private volatile NuggetPinVerifier verifier;
    private volatile NuggetPinSourceType activeSourceType;
    private volatile boolean lowerSafetySource;
    private volatile Path activeVerifierPath;
    private volatile Instant lastDiscoveryPublicationAt;
    private volatile Instant lastDiscoveryScanAt;
    private volatile int lastDiscoveryActiveCount;
    private volatile ScheduledFuture<?> discoveryHeartbeatTask;
    private volatile ConcurrentHashMap<String, Instant> expiredDiscoveryRecords;
    private volatile ConcurrentHashMap<String, PendingConfirmation> confirmations;
    private final Object confirmationsLock;
    private final Set<Future<?>> controlActionFutures;
    private final ConcurrentHashMap<Thread, ControlActionTask> activeControlActions;
    private final Object controlTransitionLock;
    private final AtomicReference<NuggetState> controlState;
    private final AtomicBoolean shutdownSubmitted;
    private final AtomicBoolean terminationSubmitted;
    private final AtomicReference<ControlOperationStatus> shutdownControlStatus;
    private final AtomicReference<ControlOperationStatus> terminationControlStatus;
    private final Runnable runtimeCloseAction;
    private long controlTransitionVersion;

    private NuggetAdminController(
            final RuntimeIdentity runtimeIdentity,
            final NuggetAdminConfiguration configuration,
            final Runnable runtimeCloseAction) {
        if (runtimeIdentity == null) {
            throw new IllegalArgumentException("runtimeIdentity must not be null");
        }
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        this.runtimeIdentity = runtimeIdentity;
        this.configuration = configuration;
        this.nuggetId = "nugget-" + UUID.randomUUID();
        this.startedAt = configuration.clock().instant();
        this.state = new AtomicReference<>(NuggetAdminState.STOPPED);
        this.verifierLock = new ReentrantReadWriteLock();
        this.admissionsLock = new Object();
        this.activeSourceType = NuggetPinSourceType.NONE;
        this.lastDiscoveryPublicationAt = Instant.EPOCH;
        this.lastDiscoveryScanAt = Instant.EPOCH;
        this.confirmationsLock = new Object();
        this.controlActionFutures = ConcurrentHashMap.newKeySet();
        this.activeControlActions = new ConcurrentHashMap<>();
        this.controlTransitionLock = new Object();
        this.controlState = new AtomicReference<>(NuggetState.STARTING);
        this.shutdownSubmitted = new AtomicBoolean();
        this.terminationSubmitted = new AtomicBoolean();
        this.shutdownControlStatus = new AtomicReference<>();
        this.terminationControlStatus = new AtomicReference<>();
        this.runtimeCloseAction = runtimeCloseAction == null ? configuration.runtimeCloseAction() : runtimeCloseAction;
    }

    /**
     * Creates and starts a controller.
     *
     * @param runtimeIdentity hosted runtime identity
     * @param configuration nugget admin configuration
     * @return started controller
     */
    public static NuggetAdminController start(final RuntimeIdentity runtimeIdentity, final NuggetAdminConfiguration configuration) {
        return start(runtimeIdentity, configuration, null);
    }

    /**
     * Creates and starts a controller with an assembly-supplied runtime close
     * action.
     *
     * @param runtimeIdentity hosted runtime identity
     * @param configuration nugget admin configuration
     * @param runtimeCloseAction authoritative runtime close action
     * @return started controller
     */
    public static NuggetAdminController start(
            final RuntimeIdentity runtimeIdentity,
            final NuggetAdminConfiguration configuration,
            final Runnable runtimeCloseAction) {
        NuggetAdminController controller = new NuggetAdminController(runtimeIdentity, configuration, runtimeCloseAction);
        controller.start();
        return controller;
    }

    /**
     * Starts the headless administration lifecycle.
     */
    public synchronized void start() {
        NuggetAdminState current = this.state.get();
        if (current == NuggetAdminState.ENABLED || current == NuggetAdminState.DISABLED) {
            return;
        }
        if (current == NuggetAdminState.STOPPING) {
            throw safeConfigurationException("nugget admin is stopping");
        }
        this.state.set(NuggetAdminState.STARTING);
        if (!this.configuration.enabled()) {
            this.activeSourceType = NuggetPinSourceType.NONE;
            this.state.set(NuggetAdminState.DISABLED);
            setControlState(NuggetState.STOPPED);
            audit("nugget.admin.disabled", "allow", "", "", Map.of("adminState", "DISABLED"));
            return;
        }
        try {
            StartMaterial material = resolveStartMaterial();
            this.verifierLock.writeLock().lock();
            try {
                this.verifier = material.verifier();
            } finally {
                this.verifierLock.writeLock().unlock();
            }
            this.activeSourceType = material.sourceType();
            this.lowerSafetySource = material.lowerSafety();
            this.confirmations = new ConcurrentHashMap<>();
            this.state.set(NuggetAdminState.ENABLED);
            setControlState(NuggetState.RUNNING);
            audit("nugget.admin.enabled", "allow", "", "", Map.of("adminState", "ENABLED", "source", this.activeSourceType.name()));
            if (this.configuration.unsafeDeveloperMode()) {
                audit("nugget.admin.unsafeDeveloperMode.enabled", "allow", "unsafe-developer-mode", "",
                        Map.of("adminState", "ENABLED"));
            }
            audit("nugget.pin.verifier.initialized", "allow", "", "",
                    Map.of("format", NuggetPinVerifier.FORMAT_VERSION, "source", this.activeSourceType.name()));
            startLocalDiscoveryPublication();
        } catch (RuntimeException e) {
            rollbackFailedStart();
            this.activeSourceType = NuggetPinSourceType.NONE;
            this.state.set(NuggetAdminState.FAILED);
            setControlState(NuggetState.DEGRADED);
            audit("nugget.pin.configuration.rejected", "deny", "invalid-admission-configuration", "", Map.of());
            throw safeConfigurationException("nugget admin PIN configuration rejected");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetStatus status() {
        NuggetAdminState adminState = this.state.get();
        NuggetIdentity identity = new NuggetIdentity(
                this.nuggetId,
                nuggetName(),
                this.runtimeIdentity,
                this.startedAt,
                safeProcessId(),
                safeHostSummary(),
                j7mVersion(),
                System.getProperty("java.version", ""),
                nuggetState(adminState),
                capabilityMap(adminState),
                Map.of());
        NuggetConfigurationSummary summary = new NuggetConfigurationSummary(
                adminState,
                this.configuration.enabled(),
                this.configuration.unsafeDeveloperMode(),
                this.activeSourceType,
                this.lowerSafetySource,
                this.verifier == null ? "" : this.verifier.formatVersion(),
                Map.of());
        return new NuggetStatus(identity, summary, this.configuration.clock().instant(), Map.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetAdminCommandResult<NuggetAdminCapabilities> adminCapabilities(
            final NuggetAdminRequestContext context) {
        final NuggetAdminRequestContext safeContext = context == null
                ? new NuggetAdminRequestContext(
                        NuggetAdminContract.PROTOCOL_VERSION,
                        "runtime-capabilities",
                        "runtime-capabilities",
                        null,
                        NuggetAdminOperationKind.CAPABILITIES,
                        this.configuration.clock().instant(),
                        NuggetAdminContract.DEFAULT_CLIENT_BUDGET,
                        Instant.EPOCH,
                        Map.of())
                : context;
        final Map<String, String> capabilityNames = capabilityMap(this.state.get());
        final ArrayList<NuggetCapability> capabilityValues = new ArrayList<>(NuggetCapability.values().length);
        for (NuggetCapability capability : NuggetCapability.values()) {
            if (capabilityNames.containsKey(capability.name())) {
                capabilityValues.add(capability);
            }
        }
        final List<NuggetAdminOperationKind> operationKinds = List.of(
                NuggetAdminOperationKind.CAPABILITIES,
                NuggetAdminOperationKind.DISCOVERY,
                NuggetAdminOperationKind.AUTHENTICATION,
                NuggetAdminOperationKind.INTROSPECTION,
                NuggetAdminOperationKind.APPLICATION_LIFECYCLE,
                NuggetAdminOperationKind.NUGGET_CONTROL);
        final Instant now = this.configuration.clock().instant();
        final NuggetAdminCapabilities capabilities = new NuggetAdminCapabilities(
                true,
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                capabilityValues,
                operationKinds,
                NuggetAdminContract.MAX_PAGE_LIMIT,
                NuggetAdminContract.MAX_METADATA_ENTRIES,
                now,
                Map.of("provider", "j7m-runtime"));
        final NuggetAdminResultHeader header = new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                safeContext.requestId(),
                safeContext.correlationId(),
                "",
                NuggetAdminOperationKind.CAPABILITIES,
                NuggetAdminOutcome.COMPLETED,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                capabilityValues.size(),
                capabilityValues.size(),
                false,
                List.of(),
                null,
                safeContext.requestedAt(),
                now,
                Duration.ZERO,
                Map.of("provider", "j7m-runtime"));
        audit("nugget.capabilities.read", "allow", "", safeContext.correlationId(),
                Map.of("count", Integer.toString(capabilityValues.size())));
        return new NuggetAdminCommandResult<>(header, capabilities, NuggetAdminCapabilities.PAYLOAD_TYPE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetAuthenticationResult authenticate(final NuggetAuthenticationRequest request, final char[] pin) {
        NuggetAuthenticationRequest safeRequest = request == null
                ? NuggetAuthenticationRequest.of("", NuggetAuthenticationSource.UNKNOWN, "", false, this.configuration.clock().instant())
                : request;
        NuggetAdminState current = this.state.get();
        if (current != NuggetAdminState.ENABLED) {
            audit("nugget.auth.service.unavailable", "deny", "service-unavailable", safeRequest.correlationId(), sourceMetadata(safeRequest));
            return failure(safeRequest, NuggetAuthenticationOutcome.UNAVAILABLE, NuggetAuthenticationFailureReason.SERVICE_UNAVAILABLE,
                    MiddlewareErrorCode.UNAVAILABLE, "nugget admission service unavailable", Duration.ZERO, Map.of());
        }
        String throttleKey = throttleKey(safeRequest);
        NuggetAuthenticationThrottle activeThrottle = throttle();
        if (activeThrottle == null) {
            audit("nugget.auth.service.unavailable", "deny", "service-unavailable", safeRequest.correlationId(), sourceMetadata(safeRequest));
            return failure(safeRequest, NuggetAuthenticationOutcome.UNAVAILABLE, NuggetAuthenticationFailureReason.SERVICE_UNAVAILABLE,
                    MiddlewareErrorCode.UNAVAILABLE, "nugget admission service unavailable", Duration.ZERO, Map.of());
        }
        long now = this.configuration.monotonicTimeSource().getAsLong();
        NuggetAuthenticationThrottle.Attempt throttleAttempt = activeThrottle.beforeAttempt(throttleKey, now);
        Duration retryAfter = throttleAttempt.retryAfter();
        if (!retryAfter.isZero()) {
            audit("nugget.auth.throttled", "deny", "throttled", safeRequest.correlationId(),
                    sourceMetadata(safeRequest, Map.of("retryAfterMillis", Long.toString(retryAfter.toMillis()))));
            return failure(safeRequest, NuggetAuthenticationOutcome.THROTTLED, NuggetAuthenticationFailureReason.THROTTLED,
                    MiddlewareErrorCode.OVERLOADED, "nugget admission request throttled", retryAfter, Map.of("retryAfterMillis", Long.toString(retryAfter.toMillis())));
        }
        if (!validPinInput(pin)) {
            Duration newDelay = activeThrottle.recordFailure(throttleKey, now);
            audit("nugget.auth.rejected", "deny", "invalid-request", safeRequest.correlationId(),
                    sourceMetadata(safeRequest, Map.of("retryAfterMillis", Long.toString(newDelay.toMillis()))));
            return failure(safeRequest, NuggetAuthenticationOutcome.INVALID_REQUEST, NuggetAuthenticationFailureReason.INVALID_REQUEST,
                    MiddlewareErrorCode.INVALID_ARGUMENT, "nugget admission request rejected", newDelay, Map.of());
        }
        boolean accepted;
        NuggetPinVerifier verifiedWith;
        this.verifierLock.readLock().lock();
        try {
            verifiedWith = this.verifier;
            if (verifiedWith == null || this.state.get() != NuggetAdminState.ENABLED) {
                audit("nugget.auth.service.unavailable", "deny", "service-unavailable", safeRequest.correlationId(), sourceMetadata(safeRequest));
                return failure(safeRequest, NuggetAuthenticationOutcome.UNAVAILABLE, NuggetAuthenticationFailureReason.SERVICE_UNAVAILABLE,
                        MiddlewareErrorCode.UNAVAILABLE, "nugget admission service unavailable", Duration.ZERO, Map.of());
            }
            this.configuration.lifecycleHooks().beforePinVerification();
            accepted = verifiedWith.verify(pin);
        } catch (RuntimeException e) {
            audit("nugget.auth.rejected", "deny", "admission-unavailable", safeRequest.correlationId(), sourceMetadata(safeRequest));
            return failure(safeRequest, NuggetAuthenticationOutcome.REJECTED, NuggetAuthenticationFailureReason.ADMISSION_UNAVAILABLE,
                    MiddlewareErrorCode.UNAUTHENTICATED, "nugget admission request rejected", Duration.ZERO, Map.of());
        } finally {
            this.verifierLock.readLock().unlock();
        }
        if (accepted) {
            activeThrottle.reset(throttleAttempt, now);
            upgradeVerifierIfRequired(verifiedWith, pin, safeRequest);
            String admissionId = UUID.randomUUID().toString();
            rememberAdmission(admissionId);
            audit("nugget.auth.accepted", "allow", "", safeRequest.correlationId(),
                    sourceMetadata(safeRequest, Map.of("admission", "pin-admitted")));
            return new NuggetAuthenticationResult(
                    NuggetAuthenticationOutcome.ACCEPTED,
                    NuggetAuthenticationFailureReason.NONE,
                    this.nuggetId,
                    safeRequest.correlationId(),
                    admissionId,
                    Duration.ZERO,
                    this.configuration.clock().instant(),
                    null,
                    Map.of("authorization", "not-granted"));
        }
        Duration newDelay = activeThrottle.recordFailure(throttleKey, now);
        audit("nugget.auth.rejected", "deny", "rejected", safeRequest.correlationId(),
                sourceMetadata(safeRequest, Map.of("retryAfterMillis", Long.toString(newDelay.toMillis()))));
        return failure(safeRequest, NuggetAuthenticationOutcome.REJECTED, NuggetAuthenticationFailureReason.REJECTED,
                MiddlewareErrorCode.UNAUTHENTICATED, "nugget admission request rejected", newDelay, Map.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(final NuggetIntrospection.Request request) {
        NuggetIntrospection.Request safeRequest = normalizeRequest(request);
        return authorized(safeRequest, NuggetIntrospection.Category.OVERVIEW, "nugget/overview",
                () -> {
                    NuggetStatus currentStatus = this.status();
                    NuggetIntrospection.RuntimeSummary runtime = runtimeSummary(safeRuntimeStatus(), false, safeRequest);
                    NuggetIntrospection.HealthSummary health = healthSummary();
                    NuggetIntrospection.ReadinessSummary readiness = readinessSummary();
                    boolean partial = runtime == null || health.partial() || readiness.partial();
                    return success(NuggetIntrospection.Category.OVERVIEW,
                            new NuggetIntrospection.Overview(currentStatus, runtime, health, readiness, partial, List.of()),
                            partial, Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(final NuggetIntrospection.Request request) {
        return authorized(request, NuggetIntrospection.Category.ENVIRONMENT, "nugget/environment",
                () -> success(NuggetIntrospection.Category.ENVIRONMENT, environmentSummary(), false, Map.of()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> disk(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.DiskQuery query) {
        NuggetIntrospection.DiskQuery safeQuery = query == null ? new NuggetIntrospection.DiskQuery("", NuggetIntrospection.Query.all()) : query;
        return authorized(request, NuggetIntrospection.Category.DISK, "nugget/disk",
                () -> {
                    if (!validDiskQuery(safeQuery)) {
                        return failureResult(NuggetIntrospection.Category.DISK, true, true,
                                MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported nugget disk query",
                                request == null ? "" : request.correlationId(), "invalid-query");
                    }
                    DiskCollection disk = diskSummaries(safeQuery);
                    return success(NuggetIntrospection.Category.DISK, disk.result(), disk.partial(), Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(final NuggetIntrospection.Request request) {
        return authorized(request, NuggetIntrospection.Category.HEALTH, "nugget/health",
                () -> {
                    NuggetIntrospection.HealthSummary summary = healthSummary();
                    return success(NuggetIntrospection.Category.HEALTH, summary, summary.partial(), Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(final NuggetIntrospection.Request request) {
        return authorized(request, NuggetIntrospection.Category.READINESS, "nugget/readiness",
                () -> {
                    NuggetIntrospection.ReadinessSummary summary = readinessSummary();
                    return success(NuggetIntrospection.Category.READINESS, summary, summary.partial(), Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(final NuggetIntrospection.Request request) {
        NuggetIntrospection.Request safeRequest = normalizeRequest(request);
        return authorized(safeRequest, NuggetIntrospection.Category.RUNTIME, "nugget/runtime",
                () -> {
                    RuntimeStatus status = safeRuntimeStatus();
                    boolean partial = status == null;
                    return success(NuggetIntrospection.Category.RUNTIME, runtimeSummary(status, partial, safeRequest), partial, Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.TransportSummary>> transports(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Query query) {
        NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query;
        return authorized(request, NuggetIntrospection.Category.TRANSPORTS, "nugget/transports",
                () -> {
                    if (!validTransportQuery(safeQuery)) {
                        return failureResult(NuggetIntrospection.Category.TRANSPORTS, true, true,
                                MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported nugget transport query",
                                request == null ? "" : request.correlationId(), "invalid-query");
                    }
                    return success(NuggetIntrospection.Category.TRANSPORTS, transportSummaries(safeQuery), false, Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.RegistrySummary> registry(final NuggetIntrospection.Request request) {
        NuggetIntrospection.Request safeRequest = normalizeRequest(request);
        return authorized(safeRequest, NuggetIntrospection.Category.REGISTRY, "nugget/registry",
                () -> success(NuggetIntrospection.Category.REGISTRY, registrySummary(safeRequest), safeRuntimeStatus() == null, Map.of()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.RepositorySummary> repository(final NuggetIntrospection.Request request) {
        return authorized(request, NuggetIntrospection.Category.REPOSITORY, "nugget/repository",
                () -> {
                    ApplicationRepositoryStatus status = safeRepositoryStatus();
                    boolean partial = status == null;
                    return success(NuggetIntrospection.Category.REPOSITORY, repositorySummary(status), partial, Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Query query) {
        NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query;
        NuggetIntrospection.Request safeRequest = normalizeRequest(request);
        return authorized(safeRequest, NuggetIntrospection.Category.APPLICATIONS, "nugget/applications",
                () -> {
                    if (!validApplicationQuery(safeQuery)) {
                        return failureResult(NuggetIntrospection.Category.APPLICATIONS, true, true,
                                MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported nugget application query",
                                safeRequest.correlationId(), "invalid-query");
                    }
                    return success(NuggetIntrospection.Category.APPLICATIONS, applicationSummaries(safeRequest, safeQuery), false, Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
            final NuggetIntrospection.Request request,
            final String applicationInstanceId) {
        NuggetIntrospection.Request safeRequest = normalizeRequest(request);
        return authorized(safeRequest, NuggetIntrospection.Category.APPLICATIONS, "nugget/applications",
                () -> applicationDetail(safeRequest, applicationInstanceId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Query query) {
        NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query;
        return authorized(request, NuggetIntrospection.Category.OBJECTS, "nugget/objects",
                () -> {
                    if (!validObjectQuery(safeQuery)) {
                        return failureResult(NuggetIntrospection.Category.OBJECTS, true, true,
                                MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported nugget object query",
                                request == null ? "" : request.correlationId(), "invalid-query");
                    }
                    return success(NuggetIntrospection.Category.OBJECTS, objectSummaries(request, safeQuery), false, Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
            final NuggetIntrospection.Request request,
            final String objectId) {
        return authorized(request, NuggetIntrospection.Category.OBJECTS, "nugget/objects/detail",
                () -> objectDetail(request, objectId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(final NuggetIntrospection.Request request) {
        NuggetIntrospection.Request safeRequest = normalizeRequest(request);
        return authorized(safeRequest, NuggetIntrospection.Category.METRICS, "nugget/metrics",
                () -> {
                    NuggetIntrospection.MetricsSummary summary = metricsSummary(safeRequest);
                    return success(NuggetIntrospection.Category.METRICS, summary, summary.partial(), Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> recentErrors(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.RecentErrorQuery query) {
        NuggetIntrospection.RecentErrorQuery safeQuery = query == null
                ? new NuggetIntrospection.RecentErrorQuery("", NuggetIntrospection.Query.all()) : query;
        return authorized(request, NuggetIntrospection.Category.ERRORS, "nugget/errors",
                () -> {
                    if (!validRecentErrorQuery(safeQuery.page())) {
                        return failureResult(NuggetIntrospection.Category.ERRORS, true, true,
                                MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported nugget recent-error query",
                                request == null ? "" : request.correlationId(), "invalid-query");
                    }
                    NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry> errors =
                            recentErrorSummaries(safeQuery);
                    return success(NuggetIntrospection.Category.ERRORS, errors, errors.partial(), Map.of());
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetApplicationLifecycle.Result applicationLifecycle(final NuggetApplicationLifecycle.Request request) {
        Instant started = this.configuration.clock().instant();
        NuggetApplicationLifecycle.Request safeRequest = request;
        if (safeRequest == null) {
            return lifecycleFailure(null, NuggetApplicationLifecycle.Action.START, false, false,
                    MiddlewareErrorCode.INVALID_ARGUMENT, "nugget lifecycle request is invalid",
                    "invalid-request", started, started, null, null, null, null, null);
        }
        if (!admitted(safeLifecycleAdmissionRequest(safeRequest))) {
            NuggetApplicationLifecycle.Result denied = lifecycleFailure(safeRequest, safeRequest.action(), false, false,
                    MiddlewareErrorCode.UNAUTHENTICATED, "nugget lifecycle operation requires PIN admission",
                    "admission-required", started, this.configuration.clock().instant(), null, null, null, null, null);
            auditLifecycle(safeRequest, denied, null, "deny", "admission-required");
            return denied;
        }
        if (lifecycleDeadlineExpired(safeRequest, started)) {
            NuggetApplicationLifecycle.Result expired = lifecycleFailure(safeRequest, safeRequest.action(), true, false,
                    MiddlewareErrorCode.DEADLINE_EXCEEDED, "nugget lifecycle operation deadline expired",
                    "deadline-exceeded", started, this.configuration.clock().instant(), null, null, null, null, null);
            auditLifecycle(safeRequest, expired, null, "deny", "deadline-exceeded");
            return expired;
        }
        NuggetState currentControlState = this.controlState.get();
        if (currentControlState == NuggetState.DRAINING || currentControlState == NuggetState.STOPPING) {
            String reason = currentControlState == NuggetState.STOPPING ? "nugget-shutting-down" : "nugget-draining";
            String message = currentControlState == NuggetState.STOPPING
                    ? "nugget is stopping and rejects new lifecycle mutations"
                    : "nugget is draining and rejects new lifecycle mutations";
            NuggetApplicationLifecycle.Result draining = lifecycleFailure(safeRequest, safeRequest.action(), true, false,
                    MiddlewareErrorCode.PERMISSION_DENIED, message, reason, started, this.configuration.clock().instant(),
                    null, null, null, null, null);
            auditLifecycle(safeRequest, draining, null, "deny", reason);
            return draining;
        }
        ApplicationDeployer deployer = this.configuration.applicationDeployer();
        if (deployer == null) {
            NuggetApplicationLifecycle.Result unavailable = lifecycleFailure(safeRequest, safeRequest.action(), true, true,
                    MiddlewareErrorCode.UNAVAILABLE, "application lifecycle provider unavailable",
                    "provider-unavailable", started, this.configuration.clock().instant(), null, null, null, null, null);
            auditLifecycle(safeRequest, unavailable, null, "deny", "provider-unavailable");
            return unavailable;
        }
        if (this.configuration.readOnly()) {
            NuggetApplicationLifecycle.Result readOnly = lifecycleFailure(safeRequest, safeRequest.action(), true, true,
                    MiddlewareErrorCode.PERMISSION_DENIED, "nugget administration is read-only",
                    "read-only", started, this.configuration.clock().instant(), null, null, null, null, null);
            auditLifecycle(safeRequest, readOnly, null, "deny", "read-only");
            return readOnly;
        }
        if (safeRequest.action() == NuggetApplicationLifecycle.Action.START && !this.configuration.startApplicationEnabled()) {
            NuggetApplicationLifecycle.Result disabled = lifecycleFailure(safeRequest, safeRequest.action(), true, true,
                    MiddlewareErrorCode.PERMISSION_DENIED, "application start is disabled",
                    "start-disabled", started, this.configuration.clock().instant(), null, null, null, null, null);
            auditLifecycle(safeRequest, disabled, null, "deny", "start-disabled");
            return disabled;
        }
        SecurityContext context = safeRequest.securityContext() == null
                ? SecurityContext.fromInvocationOptions(null, this.runtimeIdentity.runtimeId()) : safeRequest.securityContext();
        PermissionRequest permission = lifecyclePermission(safeRequest, context);
        AuthorizationDecision decision = this.configuration.security().policy().evaluate(context, permission);
        if (!decision.allowed()) {
            NuggetApplicationLifecycle.Result denied = lifecycleFailure(safeRequest, safeRequest.action(), true, false,
                    MiddlewareErrorCode.PERMISSION_DENIED, "nugget lifecycle operation denied",
                    "authorization-denied", started, this.configuration.clock().instant(), null, null, null, null, null);
            auditLifecycle(safeRequest, denied, decision, "deny", "authorization-denied");
            return denied;
        }
        try {
            NuggetApplicationLifecycle.Result result = executeLifecycleBounded(deployer, safeRequest, decision, started);
            auditLifecycle(safeRequest, result, decision, result.error() == null ? "allow" : "deny",
                    result.error() == null ? "" : result.error().details().getOrDefault("reason", "failed"));
            return result;
        } catch (MiddlewareException exception) {
            NuggetApplicationLifecycle.Result failed = lifecycleFailure(safeRequest, safeRequest.action(), true, true,
                    exception.code(), "nugget lifecycle operation failed", "lifecycle-failed", started,
                    this.configuration.clock().instant(), previousState(deployer, safeRequest.instanceId()),
                    null, null, null, null);
            auditLifecycle(safeRequest, failed, decision, "deny", "lifecycle-failed");
            return failed;
        } catch (RuntimeException exception) {
            NuggetApplicationLifecycle.Result failed = lifecycleFailure(safeRequest, safeRequest.action(), true, true,
                    MiddlewareErrorCode.INTERNAL, "nugget lifecycle operation failed", "lifecycle-failed", started,
                    this.configuration.clock().instant(), previousState(deployer, safeRequest.instanceId()),
                    null, null, null, null);
            auditLifecycle(safeRequest, failed, decision, "deny", "lifecycle-failed");
            return failed;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetControl.Result nuggetControl(final NuggetControl.Request request) {
        Instant started = this.configuration.clock().instant();
        NuggetControl.Request safeRequest = request;
        if (safeRequest == null) {
            return controlFailure(null, NuggetControl.Action.DRAIN_NUGGET, NuggetControl.Mode.GRACEFUL,
                    false, false, MiddlewareErrorCode.INVALID_ARGUMENT, "nugget control request is invalid",
                    "invalid-request", started, started, NuggetState.UNKNOWN, NuggetState.UNKNOWN, NuggetState.UNKNOWN,
                    List.of(), null);
        }
        NuggetState previous = nuggetState(this.state.get());
        if (controlDeadlineExpired(safeRequest, started)) {
            NuggetControl.Result expired = controlFailure(safeRequest, safeRequest.action(), safeRequest.mode(),
                    false, false, MiddlewareErrorCode.DEADLINE_EXCEEDED, "nugget control operation deadline expired",
                    "deadline-exceeded", started, this.configuration.clock().instant(), previous, previous, previous,
                    List.of(), null);
            auditControl(safeRequest, expired, null, "deny", "deadline-exceeded");
            return expired;
        }
        if (!exactSelfTarget(safeRequest)) {
            NuggetControl.Result denied = controlFailure(safeRequest, safeRequest.action(), safeRequest.mode(),
                    false, false, MiddlewareErrorCode.INVALID_ARGUMENT, "nugget control requires an exact self target",
                    "target-rejected", started, this.configuration.clock().instant(), previous, previous, previous,
                    List.of(), null);
            auditControl(safeRequest, denied, null, "deny", "target-rejected");
            return denied;
        }
        if (safeRequest.action() == NuggetControl.Action.KILL_NUGGET && !this.configuration.killEnabled()) {
            NuggetControl.Result disabled = controlDisabled(safeRequest, started, previous, "kill-disabled");
            auditControl(safeRequest, disabled, null, "deny", "kill-disabled");
            return disabled;
        }
        if (safeRequest.action() == NuggetControl.Action.KILL_NUGGET && this.configuration.terminationCapability() == null) {
            NuggetControl.Result unavailable = controlFailure(safeRequest, safeRequest.action(), safeRequest.mode(),
                    false, false, MiddlewareErrorCode.UNAVAILABLE, "nugget termination capability unavailable",
                    "termination-capability-unavailable", started, this.configuration.clock().instant(),
                    previous, previous, previous, List.of(), null);
            auditControl(safeRequest, unavailable, null, "deny", "termination-capability-unavailable");
            return unavailable;
        }
        if (!admitted(controlAdmissionRequest(safeRequest))) {
            NuggetControl.Result denied = controlFailure(safeRequest, safeRequest.action(), safeRequest.mode(),
                    false, false, MiddlewareErrorCode.UNAUTHENTICATED, "nugget control operation requires PIN admission",
                    "admission-required", started, this.configuration.clock().instant(), previous, previous, previous,
                    List.of(), null);
            auditControl(safeRequest, denied, null, "deny", "admission-required");
            return denied;
        }
        if (this.configuration.readOnly()) {
            NuggetControl.Result readOnly = controlFailure(safeRequest, safeRequest.action(), safeRequest.mode(),
                    true, false, MiddlewareErrorCode.PERMISSION_DENIED, "nugget administration is read-only",
                    "read-only", started, this.configuration.clock().instant(), previous, previous, previous,
                    List.of(), null);
            auditControl(safeRequest, readOnly, null, "deny", "read-only");
            return readOnly;
        }
        SecurityContext context = safeRequest.securityContext() == null
                ? SecurityContext.fromInvocationOptions(null, this.runtimeIdentity.runtimeId()) : safeRequest.securityContext();
        PermissionRequest permission = controlPermission(safeRequest, context);
        AuthorizationDecision decision = this.configuration.security().policy().evaluate(context, permission);
        if (!decision.allowed()) {
            NuggetControl.Result denied = controlFailure(safeRequest, safeRequest.action(), safeRequest.mode(),
                    true, false, MiddlewareErrorCode.PERMISSION_DENIED, "nugget control operation denied",
                    "authorization-denied", started, this.configuration.clock().instant(), previous, previous, previous,
                    List.of(), null);
            auditControl(safeRequest, denied, decision, "deny", "authorization-denied");
            return denied;
        }
        if (safeRequest.action() == NuggetControl.Action.DRAIN_NUGGET && this.controlState.get() == NuggetState.STOPPING) {
            NuggetControl.Result inProgress = controlInProgress(safeRequest, started, previous, false);
            auditControl(safeRequest, inProgress, decision, "allow", "control-in-progress");
            return inProgress;
        }
        if ((safeRequest.action() == NuggetControl.Action.SHUTDOWN_NUGGET && this.shutdownSubmitted.get())
                || (safeRequest.action() == NuggetControl.Action.KILL_NUGGET && this.terminationSubmitted.get())) {
            NuggetControl.Result retainedStatus = retainedControlStatusResult(safeRequest, started, previous);
            if (retainedStatus != null) {
                auditControl(safeRequest, retainedStatus, decision, retainedStatus.error() == null ? "allow" : "deny",
                        retainedStatus.error() == null ? "" : retainedStatus.error().details().getOrDefault("reason", "retained-status"));
                return retainedStatus;
            }
            NuggetControl.Result inProgress = controlInProgress(safeRequest, started, previous, true);
            auditControl(safeRequest, inProgress, decision, "allow", "in-progress");
            return inProgress;
        }
        if (confirmationRequired(safeRequest) && safeRequest.confirmation().isBlank()) {
            NuggetControl.Result required = controlConfirmationRequired(safeRequest, started, previous);
            auditControl(safeRequest, required, decision, "allow", "confirmation-required");
            return required;
        }
        if (confirmationRequired(safeRequest) && !consumeConfirmation(safeRequest, previous, started)) {
            NuggetControl.Result rejected = controlFailure(safeRequest, safeRequest.action(), safeRequest.mode(),
                    true, true, MiddlewareErrorCode.PERMISSION_DENIED, "nugget control confirmation rejected",
                    "confirmation-rejected", started, this.configuration.clock().instant(), previous, previous, previous,
                    List.of(), null);
            auditControl(safeRequest, rejected, decision, "deny", "confirmation-rejected");
            return rejected;
        }
        ControlExecution execution = switch (safeRequest.action()) {
            case DRAIN_NUGGET -> executeDrainControl(safeRequest, started, previous);
            case SHUTDOWN_NUGGET -> executeShutdownControl(safeRequest, started, previous);
            case KILL_NUGGET -> executeKillControl(safeRequest, started, previous);
        };
        NuggetControl.Result result = execution.result();
        try {
            auditControl(safeRequest, result, decision, result.error() == null ? "allow" : "deny",
                    result.error() == null ? "" : result.error().details().getOrDefault("reason", "failed"));
            execution.releaseAfterAudit();
        } catch (RuntimeException exception) {
            execution.cancelBeforeAuditBoundary(exception);
            throw exception;
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetDiscovery.Result discover(final NuggetDiscovery.Query query) {
        NuggetDiscovery.Query safeQuery = query == null ? NuggetDiscovery.Query.active() : query;
        Instant now = this.configuration.clock().instant();
        if (this.state.get() != NuggetAdminState.ENABLED) {
            return new NuggetDiscovery.Result(List.of(), 0, 0, now, false, List.of());
        }
        DiscoveryMerge merge = mergedDiscoveryRecords(now);
        List<NuggetDiscovery.Record> records = merge.records();
        List<NuggetDiscovery.Record> selected = records.stream()
                .filter(record -> eligibleForDiscoveryQuery(record, safeQuery.includeStale()))
                .limit(safeQuery.limit())
                .toList();
        this.lastDiscoveryScanAt = now;
        this.lastDiscoveryActiveCount = (int) records.stream()
                .filter(this::activeCompatible)
                .count();
        int incompatibleCount = (int) records.stream()
                .filter(record -> record.compatibility() == NuggetDiscovery.Compatibility.INCOMPATIBLE)
                .count();
        if (incompatibleCount > 0) {
            audit("nugget.discovery.protocol.incompatible", "deny", "protocol-incompatible", safeQuery.correlationId(),
                    Map.of("count", Integer.toString(incompatibleCount),
                            "supportedProtocol", Integer.toString(SUPPORTED_DISCOVERY_PROTOCOL_VERSION)));
        }
        audit("nugget.discovery.scan.completed", "allow", "", safeQuery.correlationId(),
                Map.of("count", Integer.toString(selected.size())));
        int totalAvailable = (int) records.stream()
                .filter(record -> eligibleForDiscoveryQuery(record, safeQuery.includeStale()))
                .count();
        return new NuggetDiscovery.Result(selected, selected.size(), totalAvailable, now, merge.partial(), merge.warnings());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetDiscovery.Resolution resolve(final NuggetDiscovery.Selector selector) {
        NuggetDiscovery.Selector safeSelector = selector == null
                ? new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.ALL, "", "") : selector;
        NuggetDiscovery.Result result = discover(new NuggetDiscovery.Query(true, NuggetDiscovery.MAX_RESULTS, safeSelector.correlationId()));
        List<NuggetDiscovery.Record> candidates = switch (safeSelector.type()) {
            case ALL -> result.records();
            case EXACT_ID -> result.records().stream().filter(record -> record.nuggetId().equals(safeSelector.value())).toList();
            case EXACT_NAME -> result.records().stream().filter(record -> record.nuggetName().equals(safeSelector.value())).toList();
            case ENDPOINT_ID -> result.records().stream().filter(record -> endpointIdentity(record).equals(safeSelector.value())).toList();
        };
        List<NuggetDiscovery.Record> matches = candidates.stream()
                .filter(this::activeCompatible)
                .toList();
        NuggetDiscovery.ResolutionStatus status;
        MiddlewareErrorCode errorCode;
        String reason;
        if (matches.isEmpty()) {
            boolean unresolved = candidates.stream().anyMatch(record -> record.status() == NuggetDiscovery.Status.UNRESOLVED);
            boolean conflicted = candidates.stream().anyMatch(record -> record.status() == NuggetDiscovery.Status.CONFLICTED);
            boolean incompatible = candidates.stream()
                    .anyMatch(record -> record.compatibility() == NuggetDiscovery.Compatibility.INCOMPATIBLE);
            boolean stale = !candidates.isEmpty() && candidates.stream()
                    .allMatch(record -> record.status() == NuggetDiscovery.Status.STALE);
            if (conflicted) {
                status = NuggetDiscovery.ResolutionStatus.CONFLICTED;
                errorCode = MiddlewareErrorCode.INVALID_ARGUMENT;
                reason = "conflicted";
            } else if (incompatible) {
                status = NuggetDiscovery.ResolutionStatus.INCOMPATIBLE;
                errorCode = MiddlewareErrorCode.INVALID_ARGUMENT;
                reason = "protocol-incompatible";
            } else if (unresolved) {
                status = NuggetDiscovery.ResolutionStatus.UNRESOLVED;
                errorCode = MiddlewareErrorCode.UNAVAILABLE;
                reason = "unresolved";
            } else if (stale) {
                status = NuggetDiscovery.ResolutionStatus.STALE;
                errorCode = MiddlewareErrorCode.NOT_FOUND;
                reason = "stale";
            } else {
                status = NuggetDiscovery.ResolutionStatus.NOT_FOUND;
                errorCode = MiddlewareErrorCode.NOT_FOUND;
                reason = "not-found";
            }
        } else if (safeSelector.type() == NuggetDiscovery.SelectorType.EXACT_NAME && matches.size() > 1) {
            status = NuggetDiscovery.ResolutionStatus.AMBIGUOUS;
            errorCode = MiddlewareErrorCode.INVALID_ARGUMENT;
            reason = "ambiguous-name";
        } else {
            status = NuggetDiscovery.ResolutionStatus.RESOLVED;
            errorCode = null;
            reason = "resolved";
        }
        audit("nugget.discovery.resolve", status == NuggetDiscovery.ResolutionStatus.RESOLVED ? "allow" : "deny",
                reason, safeSelector.correlationId(), Map.of("count", Integer.toString(matches.size())));
        return new NuggetDiscovery.Resolution(status, matches, reason, errorCode, this.configuration.clock().instant(), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetDiscovery.StatusSummary discoveryStatus() {
        return new NuggetDiscovery.StatusSummary(
                this.configuration.localDiscoveryEnabled(),
                this.configuration.configuredTargets().size(),
                this.lastDiscoveryPublicationAt,
                this.lastDiscoveryScanAt,
                this.lastDiscoveryActiveCount,
                Map.of());
    }

    private void startLocalDiscoveryPublication() {
        if (!this.configuration.localDiscoveryEnabled()) {
            return;
        }
        if (this.configuration.localDiscoveryDirectory() == null) {
            audit("nugget.discovery.publication.rejected", "deny", "missing-directory", "", Map.of());
            throw safeConfigurationException("nugget discovery directory rejected");
        }
        if (!publishLocalDiscoveryRecord()) {
            throw safeConfigurationException("nugget discovery publication rejected");
        }
        ScheduledExecutorService executor = discoveryExecutor();
        if (executor == null) {
            throw safeConfigurationException("nugget discovery publication rejected");
        }
        long heartbeatMillis = Math.max(1L, this.configuration.discoveryHeartbeat().toMillis());
        this.discoveryHeartbeatTask = executor.scheduleWithFixedDelay(
                this::publishLocalDiscoveryRecord,
                heartbeatMillis,
                heartbeatMillis,
                TimeUnit.MILLISECONDS);
        audit("nugget.discovery.publication.started", "allow", "", "",
                Map.of("source", NuggetDiscovery.Source.LOCAL_RECORD.name()));
    }

    private void stopLocalDiscoveryPublication(final boolean startupFailure) {
        ScheduledFuture<?> task = this.discoveryHeartbeatTask;
        if (task != null) {
            try {
                task.cancel(true);
            } catch (RuntimeException e) {
                audit("nugget.discovery.publication.cancel.failed", "deny", "cancel-failed", "", Map.of());
            } finally {
                this.discoveryHeartbeatTask = null;
            }
        }
        Path directory = this.configuration.localDiscoveryDirectory();
        if (directory == null) {
            return;
        }
        Path record = directory.resolve(discoveryRecordFileName());
        try {
            this.configuration.fileOperations().removeDiscoveryRecord(record);
            audit("nugget.discovery.publication.removed", "allow", startupFailure ? "startup-failure" : "",
                    "", Map.of("source", NuggetDiscovery.Source.LOCAL_RECORD.name()));
        } catch (IOException | RuntimeException e) {
            audit("nugget.discovery.publication.remove.failed", "deny", "remove-failed", "", Map.of());
        }
    }

    private boolean publishLocalDiscoveryRecord() {
        if (this.state.get() != NuggetAdminState.ENABLED) {
            return false;
        }
        try {
            NuggetDiscovery.Record record = localPublicationRecord(this.configuration.clock().instant());
            this.configuration.fileOperations().persistDiscoveryRecord(
                    this.configuration.localDiscoveryDirectory(),
                    discoveryRecordFileName(),
                    NuggetDiscoveryRecordCodec.encode(record));
            if (this.state.get() != NuggetAdminState.ENABLED) {
                stopLocalDiscoveryPublication(false);
                return false;
            }
            this.lastDiscoveryPublicationAt = record.lastSeenAt();
            audit("nugget.discovery.publication.refreshed", "allow", "", "",
                    Map.of("source", NuggetDiscovery.Source.LOCAL_RECORD.name()));
            return true;
        } catch (IOException | RuntimeException e) {
            audit("nugget.discovery.publication.rejected", "deny", "publish-failed", "", Map.of());
            return false;
        }
    }

    private NuggetDiscovery.Record localPublicationRecord(final Instant now) {
        return new NuggetDiscovery.Record(
                this.nuggetId,
                nuggetName(),
                this.runtimeIdentity.runtimeId(),
                NuggetDiscovery.EndpointDescriptor.local(this.nuggetId),
                1,
                1,
                NuggetDiscovery.Compatibility.COMPATIBLE,
                capabilityMap(this.state.get()).keySet().stream().sorted().toList(),
                safeProcessId(),
                safeHostSummary(),
                this.startedAt,
                now,
                now.plus(this.configuration.discoveryTtl()),
                NuggetDiscovery.Source.LOCAL_RECORD,
                NuggetDiscovery.Status.ACTIVE,
                List.of());
    }

    private String discoveryRecordFileName() {
        return safeIdentifier(this.nuggetId, "nugget") + ".j7m-discovery";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close() {
        NuggetAdminState previous = this.state.getAndSet(NuggetAdminState.STOPPING);
        if (previous == NuggetAdminState.STOPPED) {
            this.state.set(NuggetAdminState.STOPPED);
            return;
        }
        setControlState(NuggetState.STOPPING);
        cleanupOwnedResources(false);
        this.activeSourceType = NuggetPinSourceType.NONE;
        this.state.set(NuggetAdminState.STOPPED);
        setControlState(NuggetState.STOPPED);
        audit("nugget.admin.stopped", "allow", "", "", Map.of("adminState", "STOPPED"));
    }

    int throttleBucketCountForTests() {
        NuggetAuthenticationThrottle activeThrottle = this.throttle;
        return activeThrottle == null ? 0 : activeThrottle.bucketCount(this.configuration.monotonicTimeSource().getAsLong());
    }

    int admissionCountForTests() {
        synchronized (this.admissionsLock) {
            ConcurrentHashMap<String, AdmissionSession> activeAdmissions = this.admissions;
            if (activeAdmissions == null) {
                return 0;
            }
            cleanupAdmissionsLocked(this.configuration.clock().instant());
            return activeAdmissions.size();
        }
    }

    void rememberAdmissionForTests(final String admissionId) {
        rememberAdmission(admissionId);
    }

    boolean publishLocalDiscoveryRecordForTests() {
        return publishLocalDiscoveryRecord();
    }

    boolean discoveryExecutorTerminatedForTests() {
        ScheduledExecutorService executor = this.discoveryExecutor;
        return executor == null || executor.isTerminated();
    }

    boolean lifecycleExecutorTerminatedForTests() {
        ExecutorService executor = this.lifecycleExecutor;
        return executor == null || executor.isTerminated();
    }

    boolean controlExecutorTerminatedForTests() {
        ExecutorService executor = this.controlExecutor;
        return executor == null || executor.isTerminated();
    }

    int expiredDiscoveryRecordCountForTests() {
        ConcurrentHashMap<String, Instant> records = this.expiredDiscoveryRecords;
        return records == null ? 0 : records.size();
    }

    void rememberExpiredDiscoveryRecordForTests(final String key, final Instant expiresAt) {
        rememberExpiredDiscoveryRecord(key, expiresAt);
    }

    private void setControlState(final NuggetState nextState) {
        synchronized (this.controlTransitionLock) {
            setControlStateLocked(nextState);
        }
    }

    private void setControlStateLocked(final NuggetState nextState) {
        this.controlState.set(nextState);
        this.controlTransitionVersion++;
    }

    private NuggetAuthenticationThrottle throttle() {
        NuggetAuthenticationThrottle current = this.throttle;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.state.get() != NuggetAdminState.ENABLED) {
                return null;
            }
            if (this.throttle == null) {
                this.throttle = new NuggetAuthenticationThrottle(
                        this.configuration.maxThrottleBuckets(),
                        this.configuration.throttleWindow(),
                        this.configuration.maxThrottleDelay());
            }
            return this.throttle;
        }
    }

    private ConcurrentHashMap<String, AdmissionSession> admissions() {
        ConcurrentHashMap<String, AdmissionSession> current = this.admissions;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.state.get() != NuggetAdminState.ENABLED) {
                return null;
            }
            if (this.admissions == null) {
                this.admissions = new ConcurrentHashMap<>();
            }
            return this.admissions;
        }
    }

    private ExecutorService providerExecutor() {
        ExecutorService current = this.providerExecutor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.state.get() != NuggetAdminState.ENABLED) {
                return null;
            }
            if (this.providerExecutor == null) {
                this.providerExecutor = this.configuration.resourceFactory()
                        .newProviderExecutor(this.configuration, this.nuggetId);
            }
            return this.providerExecutor;
        }
    }

    private ExecutorService lifecycleExecutor() {
        ExecutorService current = this.lifecycleExecutor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.state.get() != NuggetAdminState.ENABLED) {
                return null;
            }
            if (this.lifecycleExecutor == null) {
                this.lifecycleExecutor = this.configuration.resourceFactory()
                        .newLifecycleExecutor(this.configuration, this.nuggetId);
            }
            return this.lifecycleExecutor;
        }
    }

    private ExecutorService controlExecutor() {
        ExecutorService current = this.controlExecutor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.state.get() != NuggetAdminState.ENABLED) {
                return null;
            }
            if (this.controlExecutor == null) {
                this.controlExecutor = this.configuration.resourceFactory()
                        .newControlExecutor(this.configuration, this.nuggetId);
            }
            return this.controlExecutor;
        }
    }

    private ScheduledExecutorService controlMonitorExecutor() {
        ScheduledExecutorService current = this.controlMonitorExecutor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.state.get() != NuggetAdminState.ENABLED) {
                return null;
            }
            if (this.controlMonitorExecutor == null) {
                this.controlMonitorExecutor = this.configuration.resourceFactory()
                        .newControlMonitorExecutor(this.nuggetId);
            }
            return this.controlMonitorExecutor;
        }
    }

    private ScheduledExecutorService discoveryExecutor() {
        ScheduledExecutorService current = this.discoveryExecutor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.state.get() != NuggetAdminState.ENABLED) {
                return null;
            }
            if (this.discoveryExecutor == null) {
                this.discoveryExecutor = this.configuration.resourceFactory().newDiscoveryExecutor(this.nuggetId);
            }
            return this.discoveryExecutor;
        }
    }

    private ConcurrentHashMap<String, Instant> expiredDiscoveryRecords() {
        ConcurrentHashMap<String, Instant> current = this.expiredDiscoveryRecords;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.expiredDiscoveryRecords == null) {
                this.expiredDiscoveryRecords = new ConcurrentHashMap<>();
            }
            return this.expiredDiscoveryRecords;
        }
    }

    private void rollbackFailedStart() {
        cleanupOwnedResources(true);
    }

    private void cleanupOwnedResources(final boolean startupFailure) {
        ControlActionTask currentControlAction = this.activeControlActions.get(Thread.currentThread());
        if (currentControlAction != null) {
            currentControlAction.enterSelfCloseCleanup();
        }
        cleanupStep(() -> stopLocalDiscoveryPublication(startupFailure));
        cleanupStep(this::closeVerifier);
        cleanupStep(() -> {
            ConcurrentHashMap<String, AdmissionSession> current = this.admissions;
            if (current != null) {
                current.clear();
            }
            this.admissions = null;
        });
        cleanupStep(() -> {
            ConcurrentHashMap<String, Instant> current = this.expiredDiscoveryRecords;
            if (current != null) {
                current.clear();
            }
            this.expiredDiscoveryRecords = null;
        });
        cleanupStep(() -> {
            ConcurrentHashMap<String, PendingConfirmation> current = this.confirmations;
            if (current != null) {
                current.clear();
            }
            this.confirmations = null;
        });
        cleanupStep(() -> cancelTrackedControlActions(currentControlAction));
        cleanupStep(() -> this.throttle = null);
        cleanupStep(() -> shutdownControlExecutor(currentControlAction));
        this.controlExecutor = null;
        if (currentControlAction == null) {
            cleanupStep(() -> shutdownExecutor(this.controlMonitorExecutor, this.configuration.controlShutdownWait()));
            this.controlMonitorExecutor = null;
        }
        cleanupStep(() -> shutdownExecutor(this.lifecycleExecutor));
        this.lifecycleExecutor = null;
        cleanupStep(() -> shutdownExecutor(this.providerExecutor));
        this.providerExecutor = null;
        cleanupStep(() -> shutdownExecutor(this.discoveryExecutor));
        this.discoveryExecutor = null;
    }

    private void cleanupStep(final Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            audit("nugget.admin.cleanup.failed", "deny", "cleanup-failed", "", Map.of());
        }
    }

    private void shutdownExecutor(final ExecutorService executor) {
        shutdownExecutor(executor, this.configuration.providerShutdownWait());
    }

    private void shutdownExecutor(final ExecutorService executor, final Duration wait) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(
                    Math.max(1L, wait.toMillis()),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownControlExecutor(final ControlActionTask currentControlAction) {
        ExecutorService executor = this.controlExecutor;
        if (executor == null) {
            return;
        }
        if (currentControlAction != null) {
            executor.shutdown();
            return;
        }
        shutdownExecutor(executor, this.configuration.controlShutdownWait());
    }

    private void shutdownSelfCloseControlMonitor() {
        ScheduledExecutorService executor = this.controlMonitorExecutor;
        if (executor == null) {
            return;
        }
        shutdownExecutor(executor, this.configuration.controlShutdownWait());
        if (this.controlMonitorExecutor == executor) {
            this.controlMonitorExecutor = null;
        }
    }

    private void cancelTrackedControlActions(final ControlActionTask currentControlAction) {
        for (Future<?> future : this.controlActionFutures) {
            if (future != null && !future.isDone()) {
                Future<?> currentFuture = currentControlAction == null ? null : currentControlAction.future();
                if (future != currentFuture) {
                    future.cancel(true);
                    this.controlActionFutures.remove(future);
                }
            }
        }
        if (currentControlAction == null) {
            this.controlActionFutures.clear();
        }
    }

    private void rememberAdmission(final String admissionId) {
        if (admissionId == null || admissionId.isBlank()) {
            return;
        }
        Instant now = this.configuration.clock().instant();
        synchronized (this.admissionsLock) {
            ConcurrentHashMap<String, AdmissionSession> activeAdmissions = admissions();
            if (activeAdmissions == null) {
                return;
            }
            cleanupAdmissionsLocked(now);
            if (activeAdmissions.size() >= MAX_ADMISSIONS) {
                removeOneAdmissionLocked();
            }
            activeAdmissions.put(admissionId, new AdmissionSession(
                    this.nuggetId,
                    this.runtimeIdentity.runtimeId(),
                    now.plus(ADMISSION_TTL)));
        }
    }

    private boolean admitted(final NuggetIntrospection.Request request) {
        if (request == null || request.admissionId().isBlank() || this.state.get() != NuggetAdminState.ENABLED) {
            return false;
        }
        Instant now = this.configuration.clock().instant();
        synchronized (this.admissionsLock) {
            ConcurrentHashMap<String, AdmissionSession> activeAdmissions = this.admissions;
            if (activeAdmissions == null) {
                return false;
            }
            cleanupAdmissionsLocked(now);
            AdmissionSession session = activeAdmissions.get(request.admissionId());
            if (session == null) {
                return false;
            }
            if (session.expired(now)
                    || !this.nuggetId.equals(session.nuggetId())
                    || !this.runtimeIdentity.runtimeId().equals(session.runtimeId())) {
                activeAdmissions.remove(request.admissionId(), session);
                return false;
            }
            return true;
        }
    }

    private NuggetIntrospection.Request controlAdmissionRequest(final NuggetControl.Request request) {
        return new NuggetIntrospection.Request(
                request.admissionId(),
                request.securityContext(),
                request.correlationId(),
                request.requestedAt(),
                Map.of("category", "NUGGET_CONTROL"));
    }

    private boolean controlDeadlineExpired(final NuggetControl.Request request, final Instant now) {
        return request.deadlineAt().compareTo(now) <= 0;
    }

    private boolean exactSelfTarget(final NuggetControl.Request request) {
        long generation = request.targetGeneration();
        return this.nuggetId.equals(request.targetNuggetId())
                && this.runtimeIdentity.runtimeId().equals(request.targetRuntimeId())
                && (generation == 0L || generation == runtimeGeneration());
    }

    private long runtimeGeneration() {
        return 1L;
    }

    private boolean confirmationRequired(final NuggetControl.Request request) {
        return request.action() == NuggetControl.Action.SHUTDOWN_NUGGET
                || request.action() == NuggetControl.Action.KILL_NUGGET;
    }

    private NuggetControl.Result controlConfirmationRequired(
            final NuggetControl.Request request,
            final Instant started,
            final NuggetState previous) {
        CreatedConfirmation created = createConfirmation(request, previous, started);
        PendingConfirmation pending = created.pending();
        NuggetControl.Confirmation confirmation = new NuggetControl.Confirmation(
                pending.operationId(),
                created.challenge(),
                pending.expiresAt(),
                pending.action(),
                pending.nuggetId(),
                pending.runtimeId(),
                pending.generation(),
                pending.callerId(),
                pending.state());
        Instant completed = this.configuration.clock().instant();
        return new NuggetControl.Result(
                pending.operationId(),
                request.correlationId(),
                request.action(),
                request.mode(),
                true,
                true,
                request.targetNuggetId(),
                request.targetRuntimeId(),
                runtimeGeneration(),
                previous,
                previous,
                previous,
                NuggetControl.Outcome.CONFIRMATION_REQUIRED,
                "confirmation required for nugget control operation",
                false,
                false,
                true,
                false,
                false,
                false,
                confirmation,
                List.of(),
                null,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("reason", "confirmation-required"));
    }

    private CreatedConfirmation createConfirmation(
            final NuggetControl.Request request,
            final NuggetState state,
            final Instant now) {
        ConcurrentHashMap<String, PendingConfirmation> store = confirmations();
        String operationId = request.operationId().isBlank() ? "control-" + UUID.randomUUID() : request.operationId();
        String challenge = newChallenge();
        byte[] challengeDigest = digestToken(challenge);
        try {
            PendingConfirmation pending = new PendingConfirmation(
                    operationId,
                    challengeDigest,
                    now.plus(this.configuration.confirmationTtl()),
                    request.action(),
                    request.targetNuggetId(),
                    request.targetRuntimeId(),
                    runtimeGeneration(),
                    callerId(request.securityContext()),
                    state);
            synchronized (this.confirmationsLock) {
                cleanupConfirmationsLocked(store, now);
                if (store.size() >= this.configuration.maxConfirmations() && !store.containsKey(operationId)) {
                    removeOneConfirmationLocked(store);
                }
                store.put(operationId, pending);
                while (store.size() > this.configuration.maxConfirmations()) {
                    removeOneConfirmationLocked(store);
                }
            }
            return new CreatedConfirmation(pending, challenge);
        } finally {
            Arrays.fill(challengeDigest, (byte) 0);
        }
    }

    private ConcurrentHashMap<String, PendingConfirmation> confirmations() {
        ConcurrentHashMap<String, PendingConfirmation> current = this.confirmations;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.confirmations == null) {
                this.confirmations = new ConcurrentHashMap<>();
            }
            return this.confirmations;
        }
    }

    private void cleanupConfirmationsLocked(final ConcurrentHashMap<String, PendingConfirmation> store, final Instant now) {
        int scanned = 0;
        for (Map.Entry<String, PendingConfirmation> entry : store.entrySet()) {
            if (scanned >= this.configuration.maxConfirmations()) {
                return;
            }
            PendingConfirmation pending = entry.getValue();
            if (pending == null || !pending.expiresAt().isAfter(now)) {
                store.remove(entry.getKey(), pending);
            }
            scanned++;
        }
    }

    private void removeOneConfirmationLocked(final ConcurrentHashMap<String, PendingConfirmation> store) {
        for (String key : store.keySet()) {
            store.remove(key);
            return;
        }
    }

    private String newChallenge() {
        byte[] bytes = new byte[18];
        try {
            this.configuration.secureRandom().nextBytes(bytes);
            return "c-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] digestToken(final String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(tokenBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        } finally {
            Arrays.fill(tokenBytes, (byte) 0);
        }
    }

    private boolean consumeConfirmation(
            final NuggetControl.Request request,
            final NuggetState state,
            final Instant now) {
        ConcurrentHashMap<String, PendingConfirmation> store = confirmations();
        PendingConfirmation pending;
        synchronized (this.confirmationsLock) {
            cleanupConfirmationsLocked(store, now);
            pending = store.remove(request.operationId());
        }
        if (pending == null) {
            return false;
        }
        return pending.matches(request, runtimeGeneration(), callerId(request.securityContext()), state, now);
    }

    private String callerId(final SecurityContext context) {
        if (context == null || context.principal() == null || context.principal().id().isBlank()) {
            return "anonymous";
        }
        return safeIdentifier(context.principal().id(), "caller");
    }

    private PermissionRequest controlPermission(final NuggetControl.Request request, final SecurityContext context) {
        PermissionAction action = request.action() == NuggetControl.Action.KILL_NUGGET
                ? PermissionAction.KILL : PermissionAction.ADMIN;
        return new PermissionRequest(
                action,
                "nugget/" + request.action().name(),
                this.runtimeIdentity.registryContext(),
                this.runtimeIdentity.runtimeId(),
                "nugget-admin",
                request.targetNuggetId(),
                "headless",
                "",
                Map.of("action", request.action().name(), "mode", request.mode().name()));
    }

    private ControlExecution executeDrainControl(
            final NuggetControl.Request request,
            final Instant started,
            final NuggetState previous) {
        NuggetState transitionPrevious;
        synchronized (this.controlTransitionLock) {
            NuggetState current = this.controlState.get();
            transitionPrevious = current;
            if (current == NuggetState.STOPPING) {
                return new ControlExecution(controlInProgress(request, started, current, false), null, null);
            }
            if (current == NuggetState.STOPPED) {
                return new ControlExecution(controlFailure(request, request.action(), request.mode(), true, true,
                        MiddlewareErrorCode.PERMISSION_DENIED, "nugget is stopped and cannot be drained",
                        "nugget-stopped", started, this.configuration.clock().instant(),
                        current, current, current, List.of(), null), null, null);
            }
            if (current != NuggetState.DRAINING) {
                setControlStateLocked(NuggetState.DRAINING);
            }
        }
        Instant completed = this.configuration.clock().instant();
        return new ControlExecution(new NuggetControl.Result(
                operationId(request),
                request.correlationId(),
                request.action(),
                request.mode(),
                true,
                true,
                request.targetNuggetId(),
                request.targetRuntimeId(),
                runtimeGeneration(),
                transitionPrevious,
                NuggetState.DRAINING,
                NuggetState.DRAINING,
                NuggetControl.Outcome.PARTIAL,
                "nugget is draining; full work drain is deferred in this pass",
                true,
                false,
                true,
                true,
                false,
                false,
                null,
                List.of("full-work-drain-deferred"),
                null,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("result", "draining", "drainScope", "admin-mutating-work", "fullWorkDrain", "deferred")),
                null, null);
    }

    private ControlExecution executeShutdownControl(
            final NuggetControl.Request request,
            final Instant started,
            final NuggetState previous) {
        if (this.runtimeCloseAction == null) {
            return new ControlExecution(controlFailure(request, request.action(), request.mode(), true, true,
                    MiddlewareErrorCode.UNAVAILABLE, "runtime shutdown capability unavailable",
                    "runtime-control-unavailable", started, this.configuration.clock().instant(),
                    previous, previous, previous, List.of(), null), null, null);
        }
        ControlRollback rollback = claimStoppingTransition(this.shutdownSubmitted);
        boolean submitted = rollback != null;
        ControlActionSubmission submission = null;
        String operationId = operationId(request);
        if (submitted) {
            submission = submitControlAction("j7m-nugget-runtime-close-" + safeIdentifier(this.nuggetId, "nugget"),
                    this.runtimeCloseAction, request, operationId, started);
            if (submission == null) {
                rollback.restore();
                return new ControlExecution(controlFailure(request, request.action(), request.mode(), true, true,
                        MiddlewareErrorCode.OVERLOADED, "nugget control executor rejected shutdown",
                        "control-overloaded", started, this.configuration.clock().instant(),
                        previous, previous, previous, List.of(), null), null, null);
            }
        }
        Instant completed = this.configuration.clock().instant();
        return new ControlExecution(new NuggetControl.Result(
                operationId,
                request.correlationId(),
                request.action(),
                request.mode(),
                true,
                true,
                request.targetNuggetId(),
                request.targetRuntimeId(),
                runtimeGeneration(),
                previous,
                NuggetState.STOPPING,
                NuggetState.STOPPING,
                submitted ? NuggetControl.Outcome.ACCEPTED : NuggetControl.Outcome.IN_PROGRESS,
                submitted ? "nugget shutdown accepted" : "nugget shutdown already in progress",
                true,
                true,
                false,
                false,
                true,
                false,
                null,
                List.of(),
                null,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("result", submitted ? "accepted" : "in-progress")), submission,
                rollback);
    }

    private ControlExecution executeKillControl(
            final NuggetControl.Request request,
            final Instant started,
            final NuggetState previous) {
        Runnable capability = this.configuration.terminationCapability();
        if (capability == null) {
            return new ControlExecution(controlFailure(request, request.action(), request.mode(), true, true,
                    MiddlewareErrorCode.UNAVAILABLE, "nugget termination capability unavailable",
                    "termination-capability-unavailable", started, this.configuration.clock().instant(),
                    previous, previous, previous, List.of(), null), null, null);
        }
        ControlRollback rollback = claimStoppingTransition(this.terminationSubmitted);
        boolean submitted = rollback != null;
        ControlActionSubmission submission = null;
        String operationId = operationId(request);
        if (submitted) {
            submission = submitControlAction(
                    "j7m-nugget-termination-" + safeIdentifier(this.nuggetId, "nugget"),
                    capability,
                    request,
                    operationId,
                    started);
            if (submission == null) {
                rollback.restore();
                return new ControlExecution(controlFailure(request, request.action(), request.mode(), true, true,
                        MiddlewareErrorCode.OVERLOADED, "nugget control executor rejected termination",
                        "control-overloaded", started, this.configuration.clock().instant(),
                        previous, previous, previous, List.of(), null), null, null);
            }
        }
        Instant completed = this.configuration.clock().instant();
        return new ControlExecution(new NuggetControl.Result(
                operationId,
                request.correlationId(),
                request.action(),
                request.mode(),
                true,
                true,
                request.targetNuggetId(),
                request.targetRuntimeId(),
                runtimeGeneration(),
                previous,
                NuggetState.STOPPING,
                NuggetState.STOPPING,
                submitted ? NuggetControl.Outcome.ACCEPTED : NuggetControl.Outcome.IN_PROGRESS,
                submitted ? "nugget termination accepted" : "nugget termination already in progress",
                true,
                true,
                false,
                false,
                true,
                false,
                null,
                List.of(),
                null,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("result", submitted ? "accepted" : "in-progress")), submission,
                rollback);
    }

    private ControlRollback claimStoppingTransition(final AtomicBoolean submittedFlag) {
        synchronized (this.controlTransitionLock) {
            if (submittedFlag.get()) {
                return null;
            }
            submittedFlag.set(true);
            NuggetState previousState = this.controlState.get();
            setControlStateLocked(NuggetState.STOPPING);
            return new ControlRollback(submittedFlag, previousState, this.controlTransitionVersion);
        }
    }

    private ControlActionSubmission submitControlAction(
            final String actionName,
            final Runnable action,
            final NuggetControl.Request request,
            final String operationId,
            final Instant started) {
        ExecutorService executor = controlExecutor();
        ScheduledExecutorService monitor = controlMonitorExecutor();
        if (executor == null || monitor == null) {
            return null;
        }
        long timeoutMillis = controlExecutionTimeoutMillis(request, started);
        ControlActionTask task = new ControlActionTask(actionName, action, request, operationId, started, timeoutMillis);
        try {
            Future<?> future = executor.submit(task);
            task.setFuture(future);
            this.controlActionFutures.add(future);
            ScheduledFuture<?> timeoutFuture = monitor.schedule(task::timeout, timeoutMillis, TimeUnit.MILLISECONDS);
            task.setTimeoutFuture(timeoutFuture);
            return new ControlActionSubmission(task, future);
        } catch (RejectedExecutionException exception) {
            Future<?> future = task.future();
            if (future != null) {
                future.cancel(true);
                this.controlActionFutures.remove(future);
            }
            return null;
        }
    }

    private long controlExecutionTimeoutMillis(final NuggetControl.Request request, final Instant now) {
        long configuredMillis = Math.max(1L, this.configuration.controlTimeout().toMillis());
        if (request == null || request.deadlineAt() == null) {
            return configuredMillis;
        }
        long remainingMillis = Duration.between(now, request.deadlineAt()).toMillis();
        if (remainingMillis <= 0L) {
            return 0L;
        }
        return Math.min(configuredMillis, remainingMillis);
    }

    private NuggetControl.Result controlDisabled(
            final NuggetControl.Request request,
            final Instant started,
            final NuggetState previous,
            final String reason) {
        Instant completed = this.configuration.clock().instant();
        RemoteError error = new RemoteError(
                MiddlewareErrorCode.PERMISSION_DENIED,
                "nugget control operation is disabled",
                Map.of("reason", reason),
                request.correlationId(),
                this.runtimeIdentity.runtimeId(),
                false,
                false);
        return new NuggetControl.Result(
                operationId(request),
                request.correlationId(),
                request.action(),
                request.mode(),
                false,
                false,
                request.targetNuggetId(),
                request.targetRuntimeId(),
                runtimeGeneration(),
                previous,
                previous,
                previous,
                NuggetControl.Outcome.DISABLED,
                "nugget control operation is disabled",
                false,
                false,
                true,
                false,
                false,
                false,
                null,
                List.of(reason),
                error,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("reason", reason));
    }

    private NuggetControl.Result controlInProgress(
            final NuggetControl.Request request,
            final Instant started,
            final NuggetState previous,
            final boolean accepted) {
        Instant completed = this.configuration.clock().instant();
        return new NuggetControl.Result(
                operationId(request),
                request.correlationId(),
                request.action(),
                request.mode(),
                true,
                true,
                request.targetNuggetId(),
                request.targetRuntimeId(),
                runtimeGeneration(),
                previous,
                NuggetState.STOPPING,
                NuggetState.STOPPING,
                NuggetControl.Outcome.IN_PROGRESS,
                "nugget control operation is already in progress",
                accepted,
                true,
                false,
                false,
                true,
                false,
                null,
                List.of(),
                null,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("result", "in-progress"));
    }

    private NuggetControl.Result retainedControlStatusResult(
            final NuggetControl.Request request,
            final Instant started,
            final NuggetState previous) {
        AtomicReference<ControlOperationStatus> statusReference = controlStatusReference(request.action());
        if (statusReference == null) {
            return null;
        }
        ControlOperationStatus status = statusReference.get();
        if (status == null || !status.matches(request)) {
            return null;
        }
        Instant completed = this.configuration.clock().instant();
        RemoteError error = status.errorCode() == null ? null : new RemoteError(
                status.errorCode(),
                status.outcomeText(),
                Map.of("reason", status.reason()),
                request.correlationId(),
                this.runtimeIdentity.runtimeId(),
                false,
                status.outcomeUnknown());
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("result", status.metadataResult());
        metadata.put("reason", status.reason());
        metadata.put("retainedStatus", "true");
        metadata.put("outcomeUnknown", Boolean.toString(status.outcomeUnknown()));
        metadata.put("cleanupPartial", Boolean.toString(status.cleanupPartial()));
        return new NuggetControl.Result(
                status.operationId(),
                request.correlationId(),
                request.action(),
                request.mode(),
                true,
                true,
                request.targetNuggetId(),
                request.targetRuntimeId(),
                runtimeGeneration(),
                previous,
                status.currentState(),
                status.resultingState(),
                status.outcome(),
                status.outcomeText(),
                true,
                false,
                true,
                false,
                status.outcomeUnknown(),
                status.cleanupPartial(),
                null,
                status.warnings(),
                error,
                status.requestedAt(),
                completed,
                elapsed(started, completed),
                metadata);
    }

    private AtomicReference<ControlOperationStatus> controlStatusReference(final NuggetControl.Action action) {
        return switch (action) {
            case SHUTDOWN_NUGGET -> this.shutdownControlStatus;
            case KILL_NUGGET -> this.terminationControlStatus;
            case DRAIN_NUGGET -> null;
        };
    }

    private NuggetControl.Result controlFailure(
            final NuggetControl.Request request,
            final NuggetControl.Action action,
            final NuggetControl.Mode mode,
            final boolean admitted,
            final boolean authorized,
            final MiddlewareErrorCode code,
            final String message,
            final String reason,
            final Instant started,
            final Instant completed,
            final NuggetState previous,
            final NuggetState current,
            final NuggetState resulting,
            final List<String> warnings,
            final NuggetControl.Confirmation confirmation) {
        String correlationId = request == null ? "" : request.correlationId();
        RemoteError error = new RemoteError(
                code,
                message,
                Map.of("reason", reason),
                correlationId,
                this.runtimeIdentity.runtimeId(),
                false,
                false);
        NuggetControl.Outcome outcome = switch (code) {
            case DEADLINE_EXCEEDED -> NuggetControl.Outcome.TIMED_OUT;
            case UNAVAILABLE -> NuggetControl.Outcome.UNAVAILABLE;
            case PERMISSION_DENIED, UNAUTHENTICATED, INVALID_ARGUMENT -> NuggetControl.Outcome.DENIED;
            default -> NuggetControl.Outcome.OUTCOME_UNKNOWN;
        };
        return new NuggetControl.Result(
                request == null ? "" : operationId(request),
                correlationId,
                action,
                mode,
                admitted,
                authorized,
                request == null ? "" : request.targetNuggetId(),
                request == null ? "" : request.targetRuntimeId(),
                request == null ? 0L : runtimeGeneration(),
                previous,
                current,
                resulting,
                outcome,
                message,
                false,
                false,
                true,
                false,
                outcome == NuggetControl.Outcome.OUTCOME_UNKNOWN,
                false,
                confirmation,
                warnings,
                error,
                request == null ? started : request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("reason", reason));
    }

    private String operationId(final NuggetControl.Request request) {
        return request.operationId().isBlank() ? "control-" + UUID.randomUUID() : request.operationId();
    }

    private void auditControl(
            final NuggetControl.Request request,
            final NuggetControl.Result result,
            final AuthorizationDecision decision,
            final String outcome,
            final String reason) {
        SecurityContext context = request == null ? null : request.securityContext();
        String principal = context == null ? "anonymous" : context.principal().id();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("action", result.action().name());
        metadata.put("mode", result.mode().name());
        metadata.put("operationId", result.operationId());
        metadata.put("nuggetId", this.nuggetId);
        metadata.put("runtimeId", this.runtimeIdentity.runtimeId());
        metadata.put("accepted", Boolean.toString(result.accepted()));
        metadata.put("completed", Boolean.toString(result.completed()));
        metadata.put("partial", Boolean.toString(result.partial()));
        metadata.put("outcome", result.outcome().name());
        for (Map.Entry<String, String> entry : result.metadata().entrySet()) {
            metadata.putIfAbsent(entry.getKey(), entry.getValue());
        }
        this.configuration.security().auditSink().record(new AuditEvent(
                this.configuration.clock().instant(),
                this.runtimeIdentity.runtimeId(),
                principal,
                "nugget.control",
                this.nuggetId,
                outcome,
                decision == null ? this.configuration.security().policy().id() : decision.policyId(),
                result.correlationId(),
                "",
                context == null ? "" : context.remoteEndpoint(),
                reason,
                "headless",
                this.runtimeIdentity.registryContext(),
                redacted(metadata)));
    }

    private void cleanupAdmissionsLocked(final Instant now) {
        ConcurrentHashMap<String, AdmissionSession> activeAdmissions = this.admissions;
        if (activeAdmissions == null) {
            return;
        }
        int scanned = 0;
        for (Map.Entry<String, AdmissionSession> entry : activeAdmissions.entrySet()) {
            if (scanned >= MAX_ADMISSION_CLEANUP_SCAN) {
                return;
            }
            AdmissionSession session = entry.getValue();
            if (session == null || session.expired(now)) {
                activeAdmissions.remove(entry.getKey(), session);
            }
            scanned++;
        }
    }

    private void removeOneAdmissionLocked() {
        ConcurrentHashMap<String, AdmissionSession> activeAdmissions = this.admissions;
        if (activeAdmissions == null) {
            return;
        }
        for (String key : activeAdmissions.keySet()) {
            activeAdmissions.remove(key);
            return;
        }
    }

    private NuggetIntrospection.Request normalizeRequest(final NuggetIntrospection.Request request) {
        return request == null
                ? new NuggetIntrospection.Request("", null, "", this.configuration.clock().instant(), Map.of())
                : request;
    }

    private <T> NuggetIntrospection.Result<T> authorized(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Category category,
            final String resource,
            final Supplier<NuggetIntrospection.Result<T>> body) {
        NuggetIntrospection.Request safeRequest = normalizeRequest(request);
        if (!admitted(safeRequest)) {
            NuggetIntrospection.Result<T> denied = failureResult(category, false, false, MiddlewareErrorCode.UNAUTHENTICATED,
                    "nugget introspection requires PIN admission", safeRequest.correlationId(), "admission-required");
            auditRead(category, safeRequest, null, "deny", "admission-required", 0, false);
            return denied;
        }
        SecurityContext context = safeRequest.securityContext() == null
                ? SecurityContext.fromInvocationOptions(null, this.runtimeIdentity.runtimeId()) : safeRequest.securityContext();
        PermissionRequest permission = new PermissionRequest(
                PermissionAction.READ_STATUS,
                resource,
                this.runtimeIdentity.registryContext(),
                this.runtimeIdentity.runtimeId(),
                "nugget-admin",
                category.name(),
                "headless",
                "",
                Map.of("category", category.name()));
        AuthorizationDecision decision = this.configuration.security().policy().evaluate(context, permission);
        if (!decision.allowed()) {
            NuggetIntrospection.Result<T> denied = failureResult(category, true, false, MiddlewareErrorCode.PERMISSION_DENIED,
                    "nugget introspection denied", safeRequest.correlationId(), "authorization-denied");
            auditRead(category, safeRequest, decision, "deny", "authorization-denied", 0, false);
            return denied;
        }
        try {
            NuggetIntrospection.Result<T> result = body.get();
            auditRead(category, safeRequest, decision, "allow", "", resultCount(result.value()), result.partial());
            return result;
        } catch (RuntimeException e) {
            NuggetIntrospection.Result<T> failed = failureResult(category, true, true, MiddlewareErrorCode.INTERNAL,
                    "nugget introspection unavailable", safeRequest.correlationId(), "provider-failed");
            auditRead(category, safeRequest, decision, "deny", "provider-failed", 0, true);
            return failed;
        }
    }

    private <T> NuggetIntrospection.Result<T> success(
            final NuggetIntrospection.Category category,
            final T value,
            final boolean partial,
            final Map<String, String> metadata) {
        return new NuggetIntrospection.Result<>(
                true,
                true,
                category,
                this.configuration.clock().instant(),
                partial,
                value,
                null,
                partial ? List.of("partial") : List.of(),
                redacted(metadata));
    }

    private <T> NuggetIntrospection.Result<T> failureResult(
            final NuggetIntrospection.Category category,
            final boolean admitted,
            final boolean authorized,
            final MiddlewareErrorCode code,
            final String message,
            final String correlationId,
            final String reason) {
        RemoteError error = new RemoteError(code, message, Map.of("reason", reason), correlationId,
                this.runtimeIdentity.runtimeId(), code == MiddlewareErrorCode.OVERLOADED, false);
        return new NuggetIntrospection.Result<>(
                admitted,
                authorized,
                category,
                this.configuration.clock().instant(),
                false,
                null,
                error,
                List.of(),
                Map.of("reason", reason));
    }

    private NuggetIntrospection.Request safeLifecycleAdmissionRequest(final NuggetApplicationLifecycle.Request request) {
        return new NuggetIntrospection.Request(
                request.admissionId(),
                request.securityContext(),
                request.correlationId(),
                request.requestedAt(),
                request.metadata());
    }

    private boolean lifecycleDeadlineExpired(final NuggetApplicationLifecycle.Request request, final Instant now) {
        Instant deadlineAt = request.deadlineAt();
        return deadlineAt == null || !deadlineAt.isAfter(now);
    }

    private PermissionRequest lifecyclePermission(
            final NuggetApplicationLifecycle.Request request,
            final SecurityContext context) {
        PermissionAction action = switch (request.action()) {
            case START -> PermissionAction.LOAD;
            case DRAIN, STOP -> PermissionAction.ADMIN;
            case UNLOAD -> PermissionAction.UNLOAD;
        };
        String target = request.action() == NuggetApplicationLifecycle.Action.START
                ? request.coordinate().toString()
                : request.instanceId().value();
        return new PermissionRequest(
                action,
                "nugget/applications/lifecycle/" + request.action().name().toLowerCase(java.util.Locale.ROOT) + "/" + target,
                this.runtimeIdentity.registryContext(),
                this.runtimeIdentity.runtimeId(),
                "nugget-admin",
                "APPLICATIONS.LIFECYCLE",
                "headless",
                "",
                Map.of(
                        "action", request.action().name(),
                        "dryRun", Boolean.toString(request.dryRun()),
                        "nuggetId", this.nuggetId,
                        "runtimeId", this.runtimeIdentity.runtimeId(),
                        "principal", context.principal().id()));
    }

    private NuggetApplicationLifecycle.Result executeLifecycle(
            final ApplicationDeployer deployer,
            final NuggetApplicationLifecycle.Request request,
            final AuthorizationDecision decision,
            final Instant started) {
        if (request.action() == NuggetApplicationLifecycle.Action.START) {
            return executeStartLifecycle(deployer, request, decision, started);
        }
        return executeInstanceLifecycle(deployer, request, decision, started);
    }

    private NuggetApplicationLifecycle.Result executeLifecycleBounded(
            final ApplicationDeployer deployer,
            final NuggetApplicationLifecycle.Request request,
            final AuthorizationDecision decision,
            final Instant started) {
        long timeoutMillis = lifecycleTimeoutMillis(request);
        if (timeoutMillis <= 0L) {
            return lifecycleFailure(request, request.action(), true, true, MiddlewareErrorCode.DEADLINE_EXCEEDED,
                    "nugget lifecycle operation deadline expired", "deadline-exceeded", started,
                    this.configuration.clock().instant(), null, null, null, null, null);
        }
        Future<NuggetApplicationLifecycle.Result> future;
        try {
            ExecutorService executor = lifecycleExecutor();
            if (executor == null) {
                return lifecycleFailure(request, request.action(), true, true, MiddlewareErrorCode.UNAVAILABLE,
                        "application lifecycle executor unavailable", "executor-unavailable", started,
                        this.configuration.clock().instant(), null, null, null, null, null);
            }
            future = executor.submit(() -> executeLifecycle(deployer, request, decision, started));
        } catch (RejectedExecutionException exception) {
            return lifecycleFailure(request, request.action(), true, true, MiddlewareErrorCode.OVERLOADED,
                    "application lifecycle executor overloaded", "executor-overloaded", started,
                    this.configuration.clock().instant(), null, null, null, null, null);
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return lifecycleFailure(request, request.action(), true, true, MiddlewareErrorCode.DEADLINE_EXCEEDED,
                    "nugget lifecycle operation interrupted", "interrupted", started,
                    this.configuration.clock().instant(), null, null, null, null, null);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return lifecycleFailure(request, request.action(), true, true, MiddlewareErrorCode.DEADLINE_EXCEEDED,
                    "nugget lifecycle operation deadline expired", "deadline-exceeded", started,
                    this.configuration.clock().instant(), null, null, null, null, null);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof MiddlewareException middlewareException) {
                return lifecycleMiddlewareFailure(request, middlewareException, started,
                        previousState(deployer, request.instanceId()));
            }
            return lifecycleFailure(request, request.action(), true, true, MiddlewareErrorCode.INTERNAL,
                    "nugget lifecycle operation failed", "lifecycle-failed", started,
                    this.configuration.clock().instant(), previousState(deployer, request.instanceId()),
                    null, null, null, null);
        }
    }

    private long lifecycleTimeoutMillis(final NuggetApplicationLifecycle.Request request) {
        Instant now = this.configuration.clock().instant();
        long absoluteRemainingMillis = Duration.between(now, request.deadlineAt()).toMillis();
        if (absoluteRemainingMillis <= 0L) {
            return 0L;
        }
        long configuredMillis = Math.max(1L, this.configuration.providerTimeout().toMillis());
        return Math.max(1L, Math.min(absoluteRemainingMillis, configuredMillis));
    }

    private NuggetApplicationLifecycle.Result executeStartLifecycle(
            final ApplicationDeployer deployer,
            final NuggetApplicationLifecycle.Request request,
            final AuthorizationDecision decision,
            final Instant started) {
        ApplicationDeploymentPlan plan = deployer.planActivation(
                request.coordinate(),
                new ActivationOptions(false, lifecycleActivationConfiguration(request)));
        if (request.dryRun() || plan.state() == ApplicationRunState.REJECTED) {
            Instant completed = this.configuration.clock().instant();
            NuggetApplicationLifecycle.Outcome outcome = plan.state() == ApplicationRunState.REJECTED
                    ? NuggetApplicationLifecycle.Outcome.REJECTED
                    : NuggetApplicationLifecycle.Outcome.DRY_RUN;
            RemoteError error = plan.state() == ApplicationRunState.REJECTED
                    ? lifecycleError(MiddlewareErrorCode.PERMISSION_DENIED, "application activation plan rejected",
                            request.correlationId(), "plan-rejected") : null;
            return lifecycleResult(
                    request,
                    decision,
                    started,
                    completed,
                    null,
                    plan.state(),
                    plan.state(),
                    outcome,
                    outcome == NuggetApplicationLifecycle.Outcome.DRY_RUN
                            ? "application activation plan created" : "application activation plan rejected",
                    error == null,
                    false,
                    true,
                    false,
                    false,
                    plan,
                    null,
                    null,
                    error,
                    List.of(),
                    Map.of("result", plan.state().name()));
        }
        if (lifecycleDeadlineExpired(request, this.configuration.clock().instant())) {
            return lifecycleFailure(request, request.action(), true, true, MiddlewareErrorCode.DEADLINE_EXCEEDED,
                    "nugget lifecycle operation deadline expired", "deadline-exceeded", started,
                    this.configuration.clock().instant(), null, plan.state(), null, plan, null);
        }
        SecurityContext context = request.securityContext() == null
                ? SecurityContext.fromInvocationOptions(null, this.runtimeIdentity.runtimeId()) : request.securityContext();
        ApplicationActivation activation = deployer.activate(
                request.coordinate(),
                new ApplicationActivationRequest(
                        context.principal().id(),
                        request.metadata().getOrDefault("environment", "dev"),
                        false,
                        lifecycleActivationConfiguration(request)));
        Instant completed = this.configuration.clock().instant();
        return lifecycleResult(
                request,
                decision,
                started,
                completed,
                null,
                activation.state(),
                activation.state(),
                NuggetApplicationLifecycle.Outcome.COMPLETED,
                "application started",
                true,
                false,
                true,
                false,
                false,
                plan,
                null,
                activation,
                null,
                List.of(),
                Map.of("result", activation.state().name()));
    }

    private NuggetApplicationLifecycle.Result executeInstanceLifecycle(
            final ApplicationDeployer deployer,
            final NuggetApplicationLifecycle.Request request,
            final AuthorizationDecision decision,
            final Instant started) {
        ApplicationRunState previous = previousState(deployer, request.instanceId());
        ApplicationDeploymentResult deploymentResult = switch (request.action()) {
            case DRAIN -> deployer.drain(request.instanceId(), new DeactivationOptions(false));
            case STOP -> deployer.deactivate(request.instanceId(), new DeactivationOptions(false));
            case UNLOAD -> deployer.unload(request.instanceId(), new UnloadOptions(false));
            case START -> throw new IllegalArgumentException("start is not an instance lifecycle operation");
        };
        Instant completed = this.configuration.clock().instant();
        ApplicationRunState current = deploymentResult.state();
        NuggetApplicationLifecycle.Outcome outcome = lifecycleOutcome(request.action(), previous, deploymentResult);
        boolean deploymentSucceeded = deploymentResult.success();
        LifecyclePublicErrorProjection.DetailProjection failureProjection = deploymentSucceeded ? null
                : LifecyclePublicErrorProjection.projectReturnedDeploymentFailure(
                        request.action(),
                        previous,
                        current,
                        true);
        RemoteError error = deploymentSucceeded ? null : lifecycleReturnedDeploymentFailureError(request, failureProjection);
        ApplicationDeploymentResult publicDeploymentResult = deploymentSucceeded ? deploymentResult
                : new ApplicationDeploymentResult(
                        false,
                        current,
                        "application lifecycle operation was not completed",
                        List.of(failureProjection.reason()));
        Map<String, String> metadata = deploymentSucceeded ? Map.of("result", current.name()) : failureProjection.details();
        return lifecycleResult(
                request,
                decision,
                started,
                completed,
                previous,
                current,
                current,
                outcome,
                deploymentSucceeded ? deploymentResult.message() : "application lifecycle operation was not completed",
                deploymentSucceeded,
                false,
                true,
                false,
                !deploymentSucceeded,
                null,
                publicDeploymentResult,
                null,
                error,
                List.of(),
                metadata);
    }

    private RemoteError lifecycleReturnedDeploymentFailureError(
            final NuggetApplicationLifecycle.Request request,
            final LifecyclePublicErrorProjection.DetailProjection projection) {
        return new RemoteError(
                MiddlewareErrorCode.UNAVAILABLE,
                "application lifecycle operation was not completed",
                projection.details(),
                request.correlationId(),
                this.runtimeIdentity.runtimeId(),
                false,
                false);
    }

    private NuggetApplicationLifecycle.Outcome lifecycleOutcome(
            final NuggetApplicationLifecycle.Action action,
            final ApplicationRunState previous,
            final ApplicationDeploymentResult result) {
        if (!result.success()) {
            return NuggetApplicationLifecycle.Outcome.FAILED;
        }
        if ((action == NuggetApplicationLifecycle.Action.DRAIN && previous == ApplicationRunState.DRAINING)
                || (action == NuggetApplicationLifecycle.Action.STOP && previous == ApplicationRunState.STOPPED)
                || (action == NuggetApplicationLifecycle.Action.UNLOAD && previous == ApplicationRunState.UNLOADED)) {
            return NuggetApplicationLifecycle.Outcome.ALREADY_DONE;
        }
        return NuggetApplicationLifecycle.Outcome.COMPLETED;
    }

    private Map<String, String> lifecycleActivationConfiguration(final NuggetApplicationLifecycle.Request request) {
        Map<String, String> source = request.metadata();
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        copyAllowedLifecycleConfiguration(source, values, "verificationProfile");
        copyAllowedLifecycleConfiguration(source, values, "resource.maxCpu");
        copyAllowedLifecycleConfiguration(source, values, "resource.maxMemory");
        copyAllowedLifecycleConfiguration(source, values, "resource.maxConcurrentInvocations");
        copyAllowedLifecycleConfiguration(source, values, "resource.maxInboundPayload");
        return Map.copyOf(values);
    }

    private void copyAllowedLifecycleConfiguration(
            final Map<String, String> source,
            final Map<String, String> target,
            final String key) {
        String value = source.get(key);
        if (value == null || value.length() > 256) {
            return;
        }
        target.put(key, safeText(value));
    }

    private ApplicationRunState previousState(final ApplicationDeployer deployer, final ApplicationInstanceId instanceId) {
        if (deployer == null || instanceId == null) {
            return null;
        }
        try {
            ApplicationActivation activation = deployer.activeApplication(instanceId);
            if (activation != null) {
                return activation.state();
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }

    private NuggetApplicationLifecycle.Result lifecycleFailure(
            final NuggetApplicationLifecycle.Request request,
            final NuggetApplicationLifecycle.Action action,
            final boolean admitted,
            final boolean authorized,
            final MiddlewareErrorCode code,
            final String message,
            final String reason,
            final Instant started,
            final Instant completed,
            final ApplicationRunState previousState,
            final ApplicationRunState currentState,
            final ApplicationActivation activation,
            final ApplicationDeploymentPlan plan,
            final ApplicationDeploymentResult deploymentResult) {
        NuggetApplicationLifecycle.Action safeAction = action == null ? NuggetApplicationLifecycle.Action.START : action;
        NuggetApplicationLifecycle.Request safeRequest = request;
        RemoteError error = lifecycleError(code, message, safeRequest == null ? "" : safeRequest.correlationId(), reason);
        return new NuggetApplicationLifecycle.Result(
                "operation-" + UUID.randomUUID(),
                safeRequest == null ? "" : safeRequest.correlationId(),
                safeAction,
                admitted,
                authorized,
                safeRequest != null && safeRequest.dryRun(),
                this.nuggetId,
                this.runtimeIdentity.runtimeId(),
                safeRequest == null ? null : safeRequest.coordinate(),
                safeRequest == null ? null : safeRequest.instanceId(),
                previousState,
                currentState,
                currentState,
                lifecycleFailureOutcome(code),
                message,
                false,
                false,
                true,
                false,
                true,
                plan == null ? "" : plan.state().name(),
                authorized ? "allowed" : "denied",
                plan,
                deploymentResult,
                activation,
                List.of(reason),
                error,
                safeRequest == null ? started : safeRequest.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("reason", reason));
    }

    private NuggetApplicationLifecycle.Result lifecycleMiddlewareFailure(
            final NuggetApplicationLifecycle.Request request,
            final MiddlewareException exception,
            final Instant started,
            final ApplicationRunState previousState) {
        RemoteError remote = exception.remoteError();
        LifecyclePublicErrorProjection.ProjectedError projectedError = LifecyclePublicErrorProjection.project(
                remote,
                lifecycleMiddlewareFallbackReason(remote.code()),
                request.correlationId(),
                this.runtimeIdentity.runtimeId());
        RemoteError error = projectedError.error();
        MiddlewareErrorCode code = error.code();
        Instant completed = this.configuration.clock().instant();
        String reason = projectedError.reason();
        String message = error.message();
        return new NuggetApplicationLifecycle.Result(
                "operation-" + UUID.randomUUID(),
                request.correlationId(),
                request.action(),
                true,
                true,
                request.dryRun(),
                this.nuggetId,
                this.runtimeIdentity.runtimeId(),
                request.coordinate(),
                request.instanceId(),
                previousState,
                previousState,
                previousState,
                lifecycleFailureOutcome(code),
                message,
                false,
                false,
                true,
                false,
                true,
                "",
                "allowed",
                null,
                null,
                null,
                List.of(reason),
                error,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                Map.of("reason", reason));
    }

    private NuggetApplicationLifecycle.Outcome lifecycleFailureOutcome(final MiddlewareErrorCode code) {
        if (code == MiddlewareErrorCode.DEADLINE_EXCEEDED) {
            return NuggetApplicationLifecycle.Outcome.DEADLINE_EXCEEDED;
        }
        if (code == MiddlewareErrorCode.UNAVAILABLE || code == MiddlewareErrorCode.OVERLOADED) {
            return NuggetApplicationLifecycle.Outcome.UNAVAILABLE;
        }
        if (code == MiddlewareErrorCode.NOT_FOUND
                || code == MiddlewareErrorCode.INVALID_ARGUMENT
                || code == MiddlewareErrorCode.PERMISSION_DENIED
                || code == MiddlewareErrorCode.UNAUTHENTICATED
                || code == MiddlewareErrorCode.CANCELLED) {
            return NuggetApplicationLifecycle.Outcome.REJECTED;
        }
        return NuggetApplicationLifecycle.Outcome.FAILED;
    }

    private String lifecycleMiddlewareFallbackReason(final MiddlewareErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> "not-found";
            case INVALID_ARGUMENT -> "invalid-lifecycle-state";
            case PERMISSION_DENIED -> "permission-or-verification-denied";
            case UNAUTHENTICATED -> "authentication-required";
            case DEADLINE_EXCEEDED -> "deadline-exceeded";
            case CANCELLED -> "cancelled";
            case UNAVAILABLE -> "provider-unavailable";
            case OVERLOADED -> "executor-overloaded";
            default -> "lifecycle-failed";
        };
    }

    private NuggetApplicationLifecycle.Result lifecycleResult(
            final NuggetApplicationLifecycle.Request request,
            final AuthorizationDecision decision,
            final Instant started,
            final Instant completed,
            final ApplicationRunState previousState,
            final ApplicationRunState currentState,
            final ApplicationRunState resultingState,
            final NuggetApplicationLifecycle.Outcome outcome,
            final String outcomeText,
            final boolean accepted,
            final boolean inProgress,
            final boolean completedSynchronously,
            final boolean partial,
            final boolean outcomeUnknown,
            final ApplicationDeploymentPlan plan,
            final ApplicationDeploymentResult deploymentResult,
            final ApplicationActivation activation,
            final RemoteError error,
            final List<String> warnings,
            final Map<String, String> metadata) {
        return new NuggetApplicationLifecycle.Result(
                "operation-" + UUID.randomUUID(),
                request.correlationId(),
                request.action(),
                true,
                decision == null || decision.allowed(),
                request.dryRun(),
                this.nuggetId,
                this.runtimeIdentity.runtimeId(),
                request.coordinate(),
                activation == null ? request.instanceId() : activation.instanceIdentity().instanceId(),
                previousState,
                currentState,
                resultingState,
                outcome,
                outcomeText,
                accepted,
                inProgress,
                completedSynchronously,
                partial,
                outcomeUnknown,
                plan == null ? "" : plan.state().name(),
                decision == null ? "" : decision.reason(),
                plan,
                deploymentResult,
                activation,
                warnings,
                error,
                request.requestedAt(),
                completed,
                elapsed(started, completed),
                redacted(metadata));
    }

    private RemoteError lifecycleError(
            final MiddlewareErrorCode code,
            final String message,
            final String correlationId,
            final String reason) {
        return new RemoteError(
                code,
                message,
                Map.of("reason", reason),
                correlationId,
                this.runtimeIdentity.runtimeId(),
                code == MiddlewareErrorCode.OVERLOADED,
                false);
    }

    private Duration elapsed(final Instant started, final Instant completed) {
        if (started == null || completed == null || completed.isBefore(started)) {
            return Duration.ZERO;
        }
        return Duration.between(started, completed);
    }

    private void auditLifecycle(
            final NuggetApplicationLifecycle.Request request,
            final NuggetApplicationLifecycle.Result result,
            final AuthorizationDecision decision,
            final String outcome,
            final String reason) {
        SecurityContext context = request == null ? null : request.securityContext();
        String principal = context == null ? "anonymous" : context.principal().id();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("action", result.action().name());
        metadata.put("dryRun", Boolean.toString(result.dryRun()));
        metadata.put("outcome", result.outcome().name());
        metadata.put("accepted", Boolean.toString(result.accepted()));
        metadata.put("completed", Boolean.toString(result.completed()));
        metadata.put("partial", Boolean.toString(result.partial()));
        metadata.put("runtimeId", this.runtimeIdentity.runtimeId());
        if (result.coordinate() != null) {
            metadata.put("coordinate", result.coordinate().toString());
        }
        if (result.instanceId() != null) {
            metadata.put("instanceId", result.instanceId().value());
        }
        for (Map.Entry<String, String> entry : result.metadata().entrySet()) {
            metadata.putIfAbsent(entry.getKey(), entry.getValue());
        }
        this.configuration.security().auditSink().record(new AuditEvent(
                this.configuration.clock().instant(),
                this.runtimeIdentity.runtimeId(),
                principal,
                "nugget.application.lifecycle",
                this.nuggetId,
                outcome,
                decision == null ? this.configuration.security().policy().id() : decision.policyId(),
                result.correlationId(),
                "",
                context == null ? "" : context.remoteEndpoint(),
                reason,
                "headless",
                this.runtimeIdentity.registryContext(),
                redacted(metadata)));
    }

    private int resultCount(final Object value) {
        if (value instanceof NuggetIntrospection.CollectionResult<?> collection) {
            return collection.returned();
        }
        return value == null ? 0 : 1;
    }

    private DiscoveryMerge mergedDiscoveryRecords(final Instant now) {
        Map<String, NuggetDiscovery.Record> merged = new LinkedHashMap<>();
        DiscoveryMerge local = localDiscoveryRecords(now);
        for (NuggetDiscovery.Record record : local.records()) {
            putMerged(merged, record, now);
        }
        for (NuggetDiscovery.ConfiguredTarget target : this.configuration.configuredTargets()) {
            putMerged(merged, configuredTargetRecord(target, now), now);
        }
        List<NuggetDiscovery.Record> records = withEndpointConflicts(merged.values(), now).stream()
                .sorted(discoveryRecordComparator())
                .limit(this.configuration.maxDiscoveryRecords())
                .toList();
        return new DiscoveryMerge(records, local.partial(), local.warnings());
    }

    private List<NuggetDiscovery.Record> withEndpointConflicts(
            final java.util.Collection<NuggetDiscovery.Record> records,
            final Instant now) {
        Map<String, String> endpointToNugget = new HashMap<>();
        Set<String> conflictedEndpoints = new LinkedHashSet<>();
        for (NuggetDiscovery.Record record : records) {
            String endpoint = endpointIdentity(record);
            String previous = endpointToNugget.putIfAbsent(endpoint, record.nuggetId());
            if (previous != null && !previous.equals(record.nuggetId())) {
                conflictedEndpoints.add(endpoint);
            }
        }
        return records.stream()
                .map(record -> conflictedEndpoints.contains(endpointIdentity(record))
                        ? conflicted(record, now, "conflicting-endpoint") : record)
                .toList();
    }

    private void putMerged(
            final Map<String, NuggetDiscovery.Record> merged,
            final NuggetDiscovery.Record candidate,
            final Instant now) {
        String key = discoveryKey(candidate);
        NuggetDiscovery.Record existing = merged.get(key);
        if (existing == null) {
            merged.put(key, candidate);
            return;
        }
        if (!existing.nuggetId().isBlank()
                && !candidate.nuggetId().isBlank()
                && !existing.nuggetId().equals(candidate.nuggetId())) {
            merged.put(key, conflicted(existing, now, "conflicting-id"));
            return;
        }
        if (!endpointIdentity(existing).equals(endpointIdentity(candidate))) {
            merged.put(key, conflicted(existing, now, "conflicting-endpoint"));
            return;
        }
        if (sourceRank(candidate.source()) < sourceRank(existing.source())) {
            merged.put(key, candidate);
        } else if (sourceRank(candidate.source()) == sourceRank(existing.source())
                && candidate.lastSeenAt().isAfter(existing.lastSeenAt())
                && existing.status() != NuggetDiscovery.Status.ACTIVE) {
            merged.put(key, candidate);
        }
    }

    private NuggetDiscovery.Record conflicted(
            final NuggetDiscovery.Record record,
            final Instant now,
            final String reason) {
        return new NuggetDiscovery.Record(
                record.nuggetId(),
                record.nuggetName(),
                record.runtimeId(),
                record.endpoint(),
                record.protocolMinimum(),
                record.protocolMaximum(),
                record.compatibility(),
                record.capabilities(),
                "",
                record.hostSummary(),
                record.startedAt(),
                now,
                now,
                record.source(),
                NuggetDiscovery.Status.CONFLICTED,
                List.of(reason));
    }

    private String discoveryKey(final NuggetDiscovery.Record record) {
        if (!record.nuggetId().isBlank()) {
            return "id:" + record.nuggetId();
        }
        return "endpoint:" + endpointIdentity(record);
    }

    private String endpointIdentity(final NuggetDiscovery.Record record) {
        NuggetDiscovery.EndpointDescriptor endpoint = record.endpoint();
        if (!endpoint.localId().isBlank()) {
            return endpoint.scheme() + ":" + endpoint.localId();
        }
        return endpoint.scheme() + "://" + endpoint.host() + ":" + endpoint.port();
    }

    private int sourceRank(final NuggetDiscovery.Source source) {
        return switch (source == null ? NuggetDiscovery.Source.UNKNOWN : source) {
            case LOCAL_RECORD -> 0;
            case CONFIGURED_TARGET -> 1;
            case FIRST_PARTY_REGISTRY -> 2;
            case MULTICAST_BOOTSTRAP -> 3;
            case UNKNOWN -> 4;
        };
    }

    private Comparator<NuggetDiscovery.Record> discoveryRecordComparator() {
        return Comparator.comparingInt((NuggetDiscovery.Record record) -> sourceRank(record.source()))
                .thenComparing(record -> record.nuggetId().isBlank() ? "~" : record.nuggetId())
                .thenComparing(NuggetDiscovery.Record::nuggetName)
                .thenComparing(this::endpointIdentity);
    }

    private DiscoveryMerge localDiscoveryRecords(final Instant now) {
        Path directory = this.configuration.localDiscoveryDirectory();
        if (directory == null) {
            return new DiscoveryMerge(List.of(), false, List.of());
        }
        List<NuggetDiscovery.Record> records = new ArrayList<>();
        boolean partial = false;
        List<String> warnings = new ArrayList<>();
        try {
            this.configuration.fileOperations().ensureProtectedDirectory(directory);
            this.configuration.fileOperations().cleanupDiscoveryTemps(directory, 16);
            NuggetAdminFileOperations.DiscoveryDirectoryEntries entries =
                    this.configuration.fileOperations().listDiscoveryEntries(directory, this.configuration.maxDiscoveryRecords());
            if (entries.incomplete()) {
                partial = true;
                warnings.add("scan-incomplete");
                audit("nugget.discovery.scan.partial", "deny", "scan-bound-exhausted", "", Map.of("source", "LOCAL_RECORD"));
            }
            for (Path path : entries.paths()) {
                if (path == null
                        || Files.isSymbolicLink(path)
                        || !path.getFileName().toString().endsWith(".j7m-discovery")) {
                    continue;
                }
                try {
                    String text = this.configuration.fileOperations().readProtectedBoundedUtf8(path, NuggetDiscoveryRecordCodec.MAX_RECORD_BYTES);
                    NuggetDiscovery.Record record = discoveryRecordWithPolicy(NuggetDiscoveryRecordCodec.decode(text, now), now);
                    if (record.status() == NuggetDiscovery.Status.STALE) {
                        audit("nugget.discovery.record.stale", "deny", "stale-record", "", Map.of("source", "LOCAL_RECORD"));
                    }
                    records.add(record);
                } catch (IOException | RuntimeException e) {
                    audit("nugget.discovery.record.ignored", "deny", "malformed-record", "", Map.of("source", "LOCAL_RECORD"));
                }
            }
        } catch (IOException | RuntimeException e) {
            audit("nugget.discovery.scan.failed", "deny", "local-source-unavailable", "", Map.of("source", "LOCAL_RECORD"));
            partial = true;
            warnings.add("local-source-unavailable");
        }
        return new DiscoveryMerge(List.copyOf(records), partial, warnings);
    }

    private NuggetDiscovery.Record configuredTargetRecord(
            final NuggetDiscovery.ConfiguredTarget target,
            final Instant now) {
        NuggetDiscovery.Status status = target.expectedNuggetId().isBlank()
                ? NuggetDiscovery.Status.UNRESOLVED : NuggetDiscovery.Status.ACTIVE;
        NuggetDiscovery.Record record = new NuggetDiscovery.Record(
                target.expectedNuggetId(),
                target.expectedName(),
                "",
                target.endpoint(),
                target.protocolMinimum(),
                target.protocolMaximum(),
                compatibility(target.protocolMinimum(), target.protocolMaximum(), status),
                List.of(NuggetCapability.DISCOVER_NUGGETS.name()),
                "",
                "",
                Instant.EPOCH,
                now,
                now.plus(this.configuration.discoveryTtl()),
                NuggetDiscovery.Source.CONFIGURED_TARGET,
                status,
                status == NuggetDiscovery.Status.UNRESOLVED ? List.of("unresolved") : List.of());
        audit("nugget.discovery.configured-target.accepted", "allow", "", "",
                Map.of("source", NuggetDiscovery.Source.CONFIGURED_TARGET.name()));
        return record;
    }

    private boolean eligibleForDiscoveryQuery(final NuggetDiscovery.Record record, final boolean includeStale) {
        if (includeStale) {
            return true;
        }
        return record.status() == NuggetDiscovery.Status.UNRESOLVED || activeCompatible(record);
    }

    private boolean activeCompatible(final NuggetDiscovery.Record record) {
        return record.status() == NuggetDiscovery.Status.ACTIVE
                && record.compatibility() == NuggetDiscovery.Compatibility.COMPATIBLE;
    }

    private NuggetDiscovery.Record discoveryRecordWithPolicy(
            final NuggetDiscovery.Record record,
            final Instant now) {
        NuggetDiscovery.Compatibility compatibility =
                compatibility(record.protocolMinimum(), record.protocolMaximum(), record.status());
        NuggetDiscovery.Status status = temporalStatus(record, now);
        return new NuggetDiscovery.Record(
                record.nuggetId(),
                record.nuggetName(),
                record.runtimeId(),
                record.endpoint(),
                record.protocolMinimum(),
                record.protocolMaximum(),
                compatibility,
                record.capabilities(),
                record.processId(),
                record.hostSummary(),
                record.startedAt(),
                record.lastSeenAt(),
                record.expiresAt(),
                record.source(),
                status,
                record.warnings());
    }

    private NuggetDiscovery.Compatibility compatibility(
            final int protocolMinimum,
            final int protocolMaximum,
            final NuggetDiscovery.Status status) {
        if (status == NuggetDiscovery.Status.UNRESOLVED) {
            return NuggetDiscovery.Compatibility.UNKNOWN;
        }
        if (protocolMinimum <= SUPPORTED_DISCOVERY_PROTOCOL_VERSION
                && protocolMaximum >= SUPPORTED_DISCOVERY_PROTOCOL_VERSION) {
            return NuggetDiscovery.Compatibility.COMPATIBLE;
        }
        return NuggetDiscovery.Compatibility.INCOMPATIBLE;
    }

    private NuggetDiscovery.Status temporalStatus(final NuggetDiscovery.Record record, final Instant now) {
        if (record.status() == NuggetDiscovery.Status.UNRESOLVED
                || record.status() == NuggetDiscovery.Status.CONFLICTED) {
            return record.status();
        }
        String key = discoveryKey(record);
        ConcurrentHashMap<String, Instant> expiredRecords = this.expiredDiscoveryRecords;
        Instant observedExpiry = expiredRecords == null ? null : expiredRecords.get(key);
        if (observedExpiry != null && !record.lastSeenAt().isAfter(observedExpiry)) {
            return NuggetDiscovery.Status.STALE;
        }
        if (!temporalRecordValid(record, now)) {
            rememberExpiredDiscoveryRecord(key, record.expiresAt());
            return NuggetDiscovery.Status.STALE;
        }
        if (!record.expiresAt().isAfter(now)) {
            rememberExpiredDiscoveryRecord(key, record.expiresAt());
            return NuggetDiscovery.Status.STALE;
        }
        return NuggetDiscovery.Status.ACTIVE;
    }

    private boolean temporalRecordValid(final NuggetDiscovery.Record record, final Instant now) {
        if (!record.expiresAt().isAfter(record.lastSeenAt())) {
            return false;
        }
        if (Duration.between(record.lastSeenAt(), record.expiresAt()).compareTo(maxDiscoveryLease()) > 0) {
            return false;
        }
        Instant futureLimit = now.plus(MAX_DISCOVERY_CLOCK_SKEW);
        if (record.lastSeenAt().isAfter(futureLimit) || record.startedAt().isAfter(futureLimit)) {
            return false;
        }
        if (record.startedAt().isAfter(record.lastSeenAt().plus(MAX_DISCOVERY_CLOCK_SKEW))) {
            return false;
        }
        return true;
    }

    private Duration maxDiscoveryLease() {
        return this.configuration.discoveryTtl().plus(MAX_DISCOVERY_CLOCK_SKEW);
    }

    private void rememberExpiredDiscoveryRecord(final String key, final Instant expiresAt) {
        if (key == null || key.isBlank()) {
            return;
        }
        ConcurrentHashMap<String, Instant> expiredRecords = expiredDiscoveryRecords();
        if (expiredRecords.size() >= MAX_EXPIRED_DISCOVERY_OBSERVATIONS) {
            expiredRecords.clear();
        }
        expiredRecords.merge(key, expiresAt == null ? Instant.EPOCH : expiresAt,
                (existing, candidate) -> existing.isAfter(candidate) ? existing : candidate);
    }

    private void auditRead(
            final NuggetIntrospection.Category category,
            final NuggetIntrospection.Request request,
            final AuthorizationDecision decision,
            final String outcome,
            final String reason,
            final int resultCount,
            final boolean partial) {
        SecurityContext context = request == null ? null : request.securityContext();
        String principal = context == null ? "anonymous" : context.principal().id();
        String correlationId = request == null ? "" : request.correlationId();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("category", category.name());
        metadata.put("resultCount", Integer.toString(resultCount));
        metadata.put("partial", Boolean.toString(partial));
        this.configuration.security().auditSink().record(new AuditEvent(
                this.configuration.clock().instant(),
                this.runtimeIdentity.runtimeId(),
                principal,
                "nugget.introspection." + category.name().toLowerCase(java.util.Locale.ROOT),
                this.nuggetId,
                outcome,
                decision == null ? this.configuration.security().policy().id() : decision.policyId(),
                correlationId,
                "",
                context == null ? "" : context.remoteEndpoint(),
                reason,
                "headless",
                this.runtimeIdentity.registryContext(),
                redacted(metadata)));
    }

    private Map<String, String> redacted(final Map<String, String> metadata) {
        return sanitizedMetadata(metadata);
    }

    private RuntimeStatus safeRuntimeStatus() {
        ProviderDeadline deadline = requestDeadline();
        for (NuggetIntrospection.Provider provider : this.configuration.introspectionProviders().providers()) {
            if (deadlineExpired(deadline)) {
                return null;
            }
            NuggetIntrospection.ProviderContext context = providerContext("", deadline);
            RuntimeStatus status = callProvider(() -> provider.runtimeStatus(context), null, deadline);
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    private ApplicationRepositoryStatus safeRepositoryStatus() {
        ProviderDeadline deadline = requestDeadline();
        for (NuggetIntrospection.Provider provider : this.configuration.introspectionProviders().providers()) {
            if (deadlineExpired(deadline)) {
                return null;
            }
            NuggetIntrospection.ProviderContext context = providerContext("", deadline);
            ApplicationRepositoryStatus status = callProvider(() -> provider.repositoryStatus(context), null, deadline);
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    private List<ApplicationActivation> safeApplications() {
        ProviderDeadline deadline = requestDeadline();
        List<ApplicationActivation> values = new ArrayList<>();
        for (NuggetIntrospection.Provider provider : this.configuration.introspectionProviders().providers()) {
            if (deadlineExpired(deadline)) {
                break;
            }
            NuggetIntrospection.ProviderContext context = providerContext("", deadline);
            List<ApplicationActivation> supplied = callProvider(() -> provider.applications(context), List.of(), deadline);
            if (supplied != null) {
                values.addAll(supplied.stream().limit(512L).toList());
            }
        }
        return List.copyOf(values);
    }

    private NuggetIntrospection.ProviderContext providerContext(final String correlationId, final ProviderDeadline deadline) {
        Instant now = this.configuration.clock().instant();
        return new NuggetIntrospection.ProviderContext(
                correlationId,
                now,
                deadline == null ? now.plus(this.configuration.providerTimeout()) : deadline.contextDeadline());
    }

    private <T> T callProvider(final Callable<T> task, final T fallback, final ProviderDeadline deadline) {
        long timeoutMillis = remainingMillis(deadline);
        if (timeoutMillis <= 0L) {
            return fallback;
        }
        Future<T> future;
        try {
            ExecutorService executor = providerExecutor();
            if (executor == null) {
                return fallback;
            }
            future = executor.submit(task);
        } catch (RejectedExecutionException e) {
            return fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
        try {
            T value = future.get(Math.max(1L, Math.min(timeoutMillis, this.configuration.providerTimeout().toMillis())),
                    TimeUnit.MILLISECONDS);
            return value == null ? fallback : value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return fallback;
        } catch (TimeoutException e) {
            future.cancel(true);
            return fallback;
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            future.cancel(true);
            return fallback;
        }
    }

    private ProviderDeadline requestDeadline() {
        long nowNanos = this.configuration.monotonicTimeSource().getAsLong();
        return new ProviderDeadline(
                this.configuration.clock().instant().plus(this.configuration.providerTimeout()),
                deadlineNanos(nowNanos, this.configuration.providerTimeout()));
    }

    private boolean deadlineExpired(final ProviderDeadline deadline) {
        return remainingMillis(deadline) <= 0L;
    }

    private long remainingMillis(final ProviderDeadline deadline) {
        if (deadline == null) {
            return Math.max(1L, this.configuration.providerTimeout().toMillis());
        }
        long nowNanos = this.configuration.monotonicTimeSource().getAsLong();
        long remainingNanos = deadline.deadlineNanos() - nowNanos;
        if (remainingNanos <= 0L) {
            return 0L;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return Math.max(1L, millis);
    }

    private static long deadlineNanos(final long nowNanos, final Duration timeout) {
        long delta = Math.max(1L, durationNanosSaturated(timeout));
        long deadline = nowNanos + delta;
        if (((nowNanos ^ deadline) & (delta ^ deadline)) < 0L) {
            return Long.MAX_VALUE;
        }
        return deadline;
    }

    private static long durationNanosSaturated(final Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return 1L;
        }
        long seconds = duration.getSeconds();
        if (seconds > Long.MAX_VALUE / 1_000_000_000L) {
            return Long.MAX_VALUE;
        }
        long nanos = seconds * 1_000_000_000L + duration.getNano();
        return nanos <= 0L ? Long.MAX_VALUE : nanos;
    }

    private NuggetIntrospection.EnvironmentSummary environmentSummary() {
        Instant now = this.configuration.clock().instant();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        List<NuggetIntrospection.KeyValue> collectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .sorted(Comparator.comparing(GarbageCollectorMXBean::getName))
                .map(gc -> new NuggetIntrospection.KeyValue(gc.getName(), "collections=" + gc.getCollectionCount()))
                .toList();
        List<String> environmentNames = System.getenv().keySet().stream()
                .map(this::safeEnvironmentName)
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted()
                .limit(256L)
                .toList();
        List<NuggetIntrospection.KeyValue> args = runtime.getInputArguments().stream()
                .map(arg -> new NuggetIntrospection.KeyValue(argumentName(arg), "<redacted>"))
                .distinct()
                .limit(256L)
                .toList();
        return new NuggetIntrospection.EnvironmentSummary(
                System.getProperty("java.version", ""),
                System.getProperty("java.vendor", ""),
                runtime.getVmName(),
                System.getProperty("os.name", ""),
                System.getProperty("os.version", ""),
                System.getProperty("os.arch", ""),
                safeProcessId(),
                this.startedAt,
                Duration.between(this.startedAt, now).isNegative() ? Duration.ZERO : Duration.between(this.startedAt, now),
                Runtime.getRuntime().availableProcessors(),
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                nonHeap.getCommitted(),
                threads.getThreadCount(),
                threads.getDaemonThreadCount(),
                threads.getPeakThreadCount(),
                threads.getTotalStartedThreadCount(),
                classes.getLoadedClassCount(),
                classes.getTotalLoadedClassCount(),
                collectors,
                cpuLoad(os, "getProcessCpuLoad"),
                cpuLoad(os, "getSystemCpuLoad"),
                cpuTime(os),
                safePath(Path.of("").toAbsolutePath().normalize(), false),
                environmentNames,
                args,
                Map.of("managementBeans", "available"),
                now);
    }

    private DiskCollection diskSummaries(
            final NuggetIntrospection.DiskQuery query) {
        List<NuggetIntrospection.DiskVolumeSummary> values = new ArrayList<>();
        Map<Path, DiskRootGroup> groups = new LinkedHashMap<>();
        int examined = 0;
        for (NuggetDiskRoot root : this.configuration.introspectionProviders().diskRoots()) {
            if (examined >= NuggetIntrospectionProviders.MAX_DISK_ROOTS) {
                break;
            }
            examined++;
            Path normalized = root.path().toAbsolutePath().normalize();
            groups.computeIfAbsent(normalized, ignored -> new DiskRootGroup(normalized)).add(safeDiskRole(root.role()), root.sensitive());
        }
        boolean partial = false;
        ProviderDeadline deadline = requestDeadline();
        for (DiskRootGroup group : groups.values()) {
            if (!matchesDiskPreProbe(group, query)) {
                continue;
            }
            if (deadlineExpired(deadline)) {
                values.add(unavailableDisk(group, "deadline-exhausted"));
                partial = true;
                continue;
            }
            NuggetIntrospection.DiskVolumeSummary summary = callProvider(
                    () -> diskSummary(group),
                    unavailableDisk(group, "deadline-exhausted"),
                    deadline);
            if (summary.availability() == NuggetIntrospection.Availability.UNAVAILABLE
                    && "deadline-exhausted".equals(summary.safeErrorCode())) {
                partial = true;
            }
            values.add(summary);
        }
        values.sort(Comparator.comparing(NuggetIntrospection.DiskVolumeSummary::role)
                .thenComparing(NuggetIntrospection.DiskVolumeSummary::rootId));
        NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary> result =
                page(filterDisks(values, query.page().filters()), query.page(), diskComparator(query.page().sortBy()), partial);
        return new DiskCollection(result, partial);
    }

    private NuggetIntrospection.DiskVolumeSummary diskSummary(final DiskRootGroup group) {
        Instant now = this.configuration.clock().instant();
        Path normalized = group.path();
        try {
            if (Files.isSymbolicLink(normalized) || !Files.exists(normalized)) {
                return unavailableDisk(group, "root-unavailable");
            }
            FileStore store = Files.getFileStore(normalized);
            long total = Math.max(-1L, store.getTotalSpace());
            long usable = Math.max(-1L, store.getUsableSpace());
            long available = Math.max(-1L, store.getUnallocatedSpace());
            long used = total >= 0L && available >= 0L && total >= available ? total - available : -1L;
            double percent = total > 0L && used >= 0L ? (double) used * 100.0D / (double) total : -1.0D;
            NuggetIntrospection.DiskHealth health = percent >= 95.0D
                    ? NuggetIntrospection.DiskHealth.DEGRADED : NuggetIntrospection.DiskHealth.HEALTHY;
            return new NuggetIntrospection.DiskVolumeSummary(
                    group.rootId(),
                    group.primaryRole(),
                    group.roles(),
                    safePath(normalized, group.sensitive()),
                    total,
                    usable,
                    available,
                    used,
                    percent,
                    health,
                    store.isReadOnly(),
                    NuggetIntrospection.Availability.AVAILABLE,
                    now,
                    "",
                    Map.of("unit", "bytes"));
        } catch (RuntimeException | IOException e) {
            return unavailableDisk(group, "root-unavailable");
        }
    }

    private NuggetIntrospection.DiskVolumeSummary unavailableDisk(final DiskRootGroup group, final String reason) {
        Path path = group.path();
        return new NuggetIntrospection.DiskVolumeSummary(
                group.rootId(),
                group.primaryRole(),
                group.roles(),
                safePath(path, group.sensitive()),
                -1L,
                -1L,
                -1L,
                -1L,
                -1.0D,
                NuggetIntrospection.DiskHealth.UNKNOWN,
                false,
                NuggetIntrospection.Availability.UNAVAILABLE,
                this.configuration.clock().instant(),
                reason,
                Map.of());
    }

    private NuggetIntrospection.HealthSummary healthSummary() {
        List<NuggetIntrospection.ComponentSummary> components = components();
        NuggetIntrospection.HealthState state = components.stream().anyMatch(component -> component.required()
                && component.health() == NuggetIntrospection.HealthState.UNHEALTHY)
                ? NuggetIntrospection.HealthState.UNHEALTHY
                : components.stream().anyMatch(component -> component.health() == NuggetIntrospection.HealthState.DEGRADED
                || component.availability() == NuggetIntrospection.Availability.UNAVAILABLE)
                ? NuggetIntrospection.HealthState.DEGRADED
                : NuggetIntrospection.HealthState.HEALTHY;
        boolean partial = components.stream().anyMatch(component -> component.availability() == NuggetIntrospection.Availability.PARTIAL
                || component.availability() == NuggetIntrospection.Availability.UNAVAILABLE);
        return new NuggetIntrospection.HealthSummary(state, components, this.configuration.clock().instant(), partial);
    }

    private NuggetIntrospection.ReadinessSummary readinessSummary() {
        List<NuggetIntrospection.ComponentSummary> components = components();
        NuggetIntrospection.ReadinessState state = components.stream().anyMatch(component -> component.required()
                && component.readiness() == NuggetIntrospection.ReadinessState.NOT_READY)
                ? NuggetIntrospection.ReadinessState.NOT_READY
                : components.stream().anyMatch(component -> component.readiness() == NuggetIntrospection.ReadinessState.DEGRADED
                || component.availability() == NuggetIntrospection.Availability.UNAVAILABLE)
                ? NuggetIntrospection.ReadinessState.DEGRADED
                : NuggetIntrospection.ReadinessState.READY;
        boolean partial = components.stream().anyMatch(component -> component.availability() == NuggetIntrospection.Availability.PARTIAL
                || component.availability() == NuggetIntrospection.Availability.UNAVAILABLE);
        return new NuggetIntrospection.ReadinessSummary(state, components, this.configuration.clock().instant(), partial);
    }

    private List<NuggetIntrospection.ComponentSummary> components() {
        List<NuggetIntrospection.ComponentSummary> components = new ArrayList<>();
        NuggetState publicState = nuggetState(this.state.get());
        NuggetIntrospection.ReadinessState runtimeReadiness = publicState == NuggetState.DRAINING
                || publicState == NuggetState.STOPPING
                ? NuggetIntrospection.ReadinessState.NOT_READY
                : this.state.get() == NuggetAdminState.ENABLED
                ? NuggetIntrospection.ReadinessState.READY
                : NuggetIntrospection.ReadinessState.DEGRADED;
        components.add(new NuggetIntrospection.ComponentSummary(
                "runtime",
                NuggetIntrospection.Availability.AVAILABLE,
                this.state.get() == NuggetAdminState.ENABLED ? NuggetIntrospection.HealthState.HEALTHY : NuggetIntrospection.HealthState.DEGRADED,
                runtimeReadiness,
                true,
                publicState.name(),
                Map.of()));
        components.add(new NuggetIntrospection.ComponentSummary(
                "nugget-admin-admission",
                this.state.get() == NuggetAdminState.ENABLED ? NuggetIntrospection.Availability.AVAILABLE : NuggetIntrospection.Availability.UNAVAILABLE,
                this.state.get() == NuggetAdminState.ENABLED ? NuggetIntrospection.HealthState.HEALTHY : NuggetIntrospection.HealthState.DEGRADED,
                this.state.get() == NuggetAdminState.ENABLED ? NuggetIntrospection.ReadinessState.READY : NuggetIntrospection.ReadinessState.DEGRADED,
                true,
                this.state.get().name(),
                Map.of()));
        boolean providerFailed = false;
        ProviderDeadline deadline = requestDeadline();
        for (NuggetIntrospection.Provider provider : this.configuration.introspectionProviders().providers()) {
            if (deadlineExpired(deadline)) {
                providerFailed = true;
                break;
            }
            NuggetIntrospection.ProviderContext context = providerContext("", deadline);
            List<NuggetIntrospection.ComponentSummary> supplied = callProvider(() -> provider.components(context), null, deadline);
            if (supplied != null) {
                supplied.stream()
                        .limit(128L)
                        .map(this::safeComponent)
                        .forEach(components::add);
            } else {
                providerFailed = true;
            }
        }
        if (providerFailed) {
            components.add(new NuggetIntrospection.ComponentSummary(
                    "optional-provider",
                    NuggetIntrospection.Availability.UNAVAILABLE,
                    NuggetIntrospection.HealthState.DEGRADED,
                    NuggetIntrospection.ReadinessState.DEGRADED,
                    false,
                    "provider-failed",
                    Map.of()));
        }
        return List.copyOf(components);
    }

    private NuggetIntrospection.ComponentSummary safeComponent(final NuggetIntrospection.ComponentSummary component) {
        return new NuggetIntrospection.ComponentSummary(
                safeIdentifier(component.component(), "component"),
                component.availability(),
                component.health(),
                component.readiness(),
                component.required(),
                safeText(component.safeReason()),
                componentMetadata(component.metadata()));
    }

    private NuggetIntrospection.RuntimeSummary runtimeSummary(
            final RuntimeStatus status,
            final boolean partial,
            final NuggetIntrospection.Request request) {
        Instant now = this.configuration.clock().instant();
        RuntimeStatus safeStatus = status;
        List<NuggetIntrospection.ObjectSummary> visibleObjects = visibleObjectSummaries(request, safeStatus);
        int activeObjects = visibleObjects.size();
        int exportedObjects = (int) visibleObjects.stream()
                .filter(object -> object.visibility() == NuggetIntrospection.ObjectVisibility.EXPORTED)
                .count();
        int apps = visibleApplications(request).size();
        long invocations = safeStatus == null ? 0L : safeStatus.invocationCount();
        long failures = safeStatus == null ? 0L : safeStatus.invocationFailureCount();
        Map<String, String> security = safeStatus == null ? Map.of() : runtimeSecurityMetadata(safeStatus.security());
        return new NuggetIntrospection.RuntimeSummary(
                this.runtimeIdentity.runtimeId(),
                this.nuggetId,
                nuggetState(this.state.get()),
                this.startedAt,
                Duration.between(this.startedAt, now).isNegative() ? Duration.ZERO : Duration.between(this.startedAt, now),
                activeObjects,
                exportedObjects,
                apps,
                invocations,
                failures,
                safeStatus == null ? 0 : (int) safeStatus.recentInvocations().stream()
                        .filter(invocation -> !invocation.success()).count(),
                security,
                now);
    }

    private NuggetIntrospection.CollectionResult<NuggetIntrospection.TransportSummary> transportSummaries(
            final NuggetIntrospection.Query query) {
        RuntimeStatus status = safeRuntimeStatus();
        List<NuggetIntrospection.TransportSummary> values = status == null ? List.of() : status.transports().stream()
                .map(transport -> new NuggetIntrospection.TransportSummary(
                        safeIdentifier(transport.transportId(), "transport"),
                        endpointSummary(transport.transportId(), transport.endpoint()),
                        transport.running(),
                        Map.of()))
                .toList();
        return page(filterTransports(values, query.filters()), query, Comparator.comparing(NuggetIntrospection.TransportSummary::transportId));
    }

    private NuggetIntrospection.RegistrySummary registrySummary(final NuggetIntrospection.Request request) {
        RuntimeStatus status = safeRuntimeStatus();
        if (status == null) {
            return new NuggetIntrospection.RegistrySummary(0, 0, false, 0, 0, Map.of(), List.of(), List.of(),
                    this.configuration.clock().instant());
        }
        RegistryStatus registry = status.registry();
        List<NuggetIntrospection.ObjectSummary> visibleObjects = visibleObjectSummaries(request, status).stream()
                .filter(object -> !object.local())
                .toList();
        Map<String, Long> safeMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : registry.metrics().entrySet()) {
            String key = safeMetricId(entry.getKey());
            if (!key.equals("unknown") && entry.getValue() != null && safeMetrics.size() < 32) {
                safeMetrics.put(key, Math.max(0L, entry.getValue()));
            }
        }
        List<String> peers = registry.peerIds().stream()
                .map(peer -> safeIdentifier(peer, "peer"))
                .filter(peer -> !peer.equals("peer"))
                .distinct()
                .sorted()
                .limit(64L)
                .toList();
        List<String> events = registry.recentDiscoveryEvents().stream()
                .map(event -> safeRegistryEvent(event))
                .filter(event -> !event.isBlank())
                .distinct()
                .sorted()
                .limit(64L)
                .toList();
        return new NuggetIntrospection.RegistrySummary(
                visibleObjects.size(),
                peers.size(),
                registry.multicastListenerRunning(),
                0,
                0,
                safeMetrics,
                peers,
                events,
                this.configuration.clock().instant());
    }

    private NuggetIntrospection.RepositorySummary repositorySummary(final ApplicationRepositoryStatus status) {
        if (status == null) {
            return new NuggetIntrospection.RepositorySummary("", 0, 0, Map.of("availability", "not-configured"),
                    this.configuration.clock().instant());
        }
        return new NuggetIntrospection.RepositorySummary(
                safePath(status.root(), true),
                status.artifactCount(),
                status.promotionCount(),
                repositoryMetadata(status.metadata()),
                this.configuration.clock().instant());
    }

    private NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary> applicationSummaries(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Query query) {
        List<NuggetIntrospection.ApplicationSummary> values = visibleApplications(request).stream()
                .map(this::applicationSummary)
                .filter(summary -> matches(summary, query.filters()))
                .toList();
        return page(values, query, applicationComparator(query.sortBy()));
    }

    private List<ApplicationActivation> visibleApplications(final NuggetIntrospection.Request request) {
        return safeApplications().stream()
                .filter(activation -> canSeeApplication(request, activation))
                .toList();
    }

    private NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> applicationDetail(
            final NuggetIntrospection.Request request,
            final String applicationInstanceId) {
        String id = applicationInstanceId == null ? "" : applicationInstanceId;
        for (ApplicationActivation activation : safeApplications()) {
            NuggetIntrospection.ApplicationSummary summary = applicationSummary(activation);
            if (summary.instanceId().equals(id) || summary.applicationId().equals(id) || summary.coordinate().equals(id)) {
                if (!canSeeApplication(request, activation)) {
                    return failureResult(NuggetIntrospection.Category.APPLICATIONS, true, true, MiddlewareErrorCode.NOT_FOUND,
                            "application not found", request == null ? "" : request.correlationId(), "not-found");
                }
                List<NuggetIntrospection.ServiceSummary> services = activation.exportedServices().stream()
                        .map(this::serviceSummary)
                        .toList();
                NuggetIntrospection.ApplicationDetail detail = new NuggetIntrospection.ApplicationDetail(
                        summary,
                        services,
                        moduleNames(activation.metadata()),
                        applicationDetailCapabilities(activation.metadata()),
                        safeStatusText(activation.metadata().get("verificationStatus")),
                        safeStatusText(activation.metadata().get("signatureStatus")),
                        safeStatusText(activation.metadata().get("sbomStatus")),
                        safeStatusText(activation.metadata().get("provenanceStatus")),
                        Map.of("activationEvents", parseLong(activation.metadata().get("auditEvents"))));
                return success(NuggetIntrospection.Category.APPLICATIONS, detail, false, Map.of());
            }
        }
        return failureResult(NuggetIntrospection.Category.APPLICATIONS, true, true, MiddlewareErrorCode.NOT_FOUND,
                "application not found", "", "not-found");
    }

    private boolean canSeeApplication(final NuggetIntrospection.Request request, final ApplicationActivation activation) {
        NuggetIntrospection.ApplicationSummary summary = applicationSummary(activation);
        PermissionRequest permission = new PermissionRequest(
                PermissionAction.READ_STATUS,
                "nugget/applications/" + summary.instanceId(),
                this.runtimeIdentity.registryContext(),
                this.runtimeIdentity.runtimeId(),
                "nugget-admin",
                "APPLICATIONS.DETAIL",
                "headless",
                "",
                Map.of(
                        "applicationId", summary.applicationId(),
                        "instanceId", summary.instanceId(),
                        "nuggetId", this.nuggetId,
                        "runtimeId", this.runtimeIdentity.runtimeId()));
        SecurityContext context = request == null || request.securityContext() == null
                ? SecurityContext.fromInvocationOptions(null, this.runtimeIdentity.runtimeId()) : request.securityContext();
        return this.configuration.security().policy().evaluate(context, permission).allowed();
    }

    private NuggetIntrospection.ApplicationSummary applicationSummary(final ApplicationActivation activation) {
        String coordinate = activation.instanceIdentity().application().coordinate().toString();
        String appId = activation.instanceIdentity().application().coordinate().id().toString();
        String version = activation.instanceIdentity().application().coordinate().version().value();
        String displayName = activation.metadata().getOrDefault("displayName", appId);
        return new NuggetIntrospection.ApplicationSummary(
                safeIdentifier(appId, "application"),
                safeIdentifier(coordinate, "coordinate"),
                safeIdentifier(activation.instanceIdentity().instanceId().value(), "application-instance"),
                safeText(displayName),
                safeIdentifier(version, "version"),
                activation.state().name(),
                safeIdentifier(activation.activationIdentity().value(), "activation"),
                activation.exportedServices().size(),
                activation.state().name().equals("FAILED") ? NuggetIntrospection.HealthState.UNHEALTHY : NuggetIntrospection.HealthState.HEALTHY,
                activation.state().name().equals("RUNNING") ? NuggetIntrospection.ReadinessState.READY : NuggetIntrospection.ReadinessState.DEGRADED,
                safeIdentifier(activation.metadata().getOrDefault("lastSafeErrorCode", ""), ""),
                applicationSummaryMetadata(activation.metadata()));
    }

    private NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary> objectSummaries(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Query query) {
        RuntimeStatus status = safeRuntimeStatus();
        List<NuggetIntrospection.ObjectSummary> values = objectCandidates(status).stream()
                .filter(candidate -> canSeeObject(request, candidate.visibility()))
                .map(this::objectSummary)
                .filter(summary -> matches(summary, query.filters()))
                .toList();
        return page(values, query, objectComparator(query.sortBy()));
    }

    private NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> objectDetail(
            final NuggetIntrospection.Request request,
            final String objectId) {
        String id = objectId == null ? "" : objectId;
        RuntimeStatus status = safeRuntimeStatus();
        if (status != null) {
            for (ObjectCandidate candidate : objectCandidates(status)) {
                if (candidate.ref().objectId().equals(id) || candidate.ref().logicalName().equals(id)) {
                    if (!canSeeObject(request, candidate.visibility())) {
                        return failureResult(NuggetIntrospection.Category.OBJECTS, true, true, MiddlewareErrorCode.NOT_FOUND,
                                "object not found", request == null ? "" : request.correlationId(), "not-found");
                    }
                    NuggetIntrospection.ObjectSummary summary = objectSummary(candidate);
                    NuggetIntrospection.ObjectDetail detail = new NuggetIntrospection.ObjectDetail(
                            summary,
                            objectDescriptorMetadata(candidate.ref().metadata()),
                            objectAnnotationMetadata(candidate.ref().metadata()));
                    return success(NuggetIntrospection.Category.OBJECTS, detail, false, Map.of());
                }
            }
        }
        return failureResult(NuggetIntrospection.Category.OBJECTS, true, true, MiddlewareErrorCode.NOT_FOUND,
                "object not found", request == null ? "" : request.correlationId(), "not-found");
    }

    private boolean canSeeObject(final NuggetIntrospection.Request request, final NuggetIntrospection.ObjectVisibility visibility) {
        if (visibility == NuggetIntrospection.ObjectVisibility.EXPORTED) {
            return true;
        }
        String resource = visibility == NuggetIntrospection.ObjectVisibility.INTERNAL
                ? "nugget/objects/internal" : "nugget/objects/private";
        PermissionRequest permission = new PermissionRequest(
                PermissionAction.READ_STATUS,
                resource,
                this.runtimeIdentity.registryContext(),
                this.runtimeIdentity.runtimeId(),
                "nugget-admin",
                "OBJECTS." + visibility.name(),
                "headless",
                "",
                Map.of("visibility", visibility.name()));
        SecurityContext context = request == null || request.securityContext() == null
                ? SecurityContext.fromInvocationOptions(null, this.runtimeIdentity.runtimeId()) : request.securityContext();
        AuthorizationDecision decision = this.configuration.security().policy().evaluate(context, permission);
        return decision.allowed();
    }

    private NuggetIntrospection.ObjectSummary objectSummary(final ObjectCandidate candidate) {
        ObjectRef ref = candidate.ref();
        Map<String, String> safeMetadata = objectSummaryMetadata(ref.metadata());
        return new NuggetIntrospection.ObjectSummary(
                this.nuggetId,
                safeIdentifier(ref.objectId(), "object"),
                safeIdentifier(ref.logicalName(), "object"),
                safeIdentifier(ref.serviceName(), "service"),
                safeText(ref.metadata().getOrDefault("kind", "service")),
                candidate.visibility(),
                candidate.objectState().name(),
                candidate.registrationState().name(),
                safeText(ref.metadata().getOrDefault("applicationId", "")),
                safeIdentifiers(ref.interfaces(), "interface"),
                safeIdentifier(ref.registryContext(), "registry"),
                candidate.local(),
                endpointSummary(ref.transport(), ref.endpoint()),
                safeIdentifier(ref.transport(), "transport"),
                NuggetIntrospection.HealthState.HEALTHY,
                ref.isLeaseActive(this.configuration.clock().millis())
                        ? NuggetIntrospection.ReadinessState.READY : NuggetIntrospection.ReadinessState.NOT_READY,
                ref.leaseUntil(),
                ref.generation(),
                parseLong(ref.metadata().get("invocationCount")),
                parseLong(ref.metadata().get("failureCount")),
                Instant.EPOCH,
                safeIdentifier(ref.metadata().getOrDefault("lastSafeErrorCode", ""), ""),
                !safeMetadata.equals(ref.metadata()),
                safeMetadata);
    }

    private List<NuggetIntrospection.ObjectSummary> visibleObjectSummaries(
            final NuggetIntrospection.Request request,
            final RuntimeStatus status) {
        return objectCandidates(status).stream()
                .filter(candidate -> canSeeObject(request, candidate.visibility()))
                .map(this::objectSummary)
                .toList();
    }

    private List<ObjectCandidate> objectCandidates(final RuntimeStatus status) {
        if (status == null) {
            return List.of();
        }
        Map<BaseObjectIdentity, ObjectCandidate> localByIdentity = new LinkedHashMap<>();
        for (InstanceStatus instance : status.instances().stream().limit(512L).toList()) {
            ObjectCandidate candidate = new ObjectCandidate(
                    instance.ref(),
                    instance.objectState(),
                    instance.registrationState(),
                    true,
                    classifyVisibility(instance.ref(), instance.objectState(), instance.registrationState(), true));
            localByIdentity.merge(baseObjectIdentity(instance.ref()), candidate, this::currentLocalCandidate);
        }
        List<ObjectCandidate> values = new ArrayList<>(localByIdentity.values());
        Map<BaseObjectIdentity, List<ObjectRef>> registryByIdentity = new LinkedHashMap<>();
        for (ObjectRef ref : status.registryRecords().stream().limit(512L).toList()) {
            BaseObjectIdentity baseIdentity = baseObjectIdentity(ref);
            if (localByIdentity.containsKey(baseIdentity)) {
                continue;
            }
            if (!validRegistryRecord(ref)) {
                continue;
            }
            registryByIdentity.computeIfAbsent(baseIdentity, ignored -> new ArrayList<>()).add(ref);
        }
        for (List<ObjectRef> refs : registryByIdentity.values()) {
            ObjectRef ref = currentRegistryRecord(refs);
            if (ref == null) {
                continue;
            }
            values.add(new ObjectCandidate(
                    ref,
                    ObjectState.EXECUTED,
                    RegistrationState.GLOBAL,
                    false,
                    classifyVisibility(ref, ObjectState.EXECUTED, RegistrationState.GLOBAL, false)));
        }
        return List.copyOf(values);
    }

    private ObjectCandidate currentLocalCandidate(final ObjectCandidate left, final ObjectCandidate right) {
        if (right.ref().generation() > left.ref().generation()) {
            return right;
        }
        if (right.ref().generation() < left.ref().generation()) {
            return left;
        }
        int leftRank = visibilityRank(left.visibility());
        int rightRank = visibilityRank(right.visibility());
        return rightRank > leftRank ? right : left;
    }

    private int visibilityRank(final NuggetIntrospection.ObjectVisibility visibility) {
        return switch (visibility == null ? NuggetIntrospection.ObjectVisibility.PRIVATE : visibility) {
            case EXPORTED -> 0;
            case INTERNAL -> 1;
            case PRIVATE -> 2;
        };
    }

    private boolean validRegistryRecord(final ObjectRef ref) {
        return ref != null
                && ref.generation() > 0L
                && !ref.runtimeId().isBlank()
                && !ref.objectId().isBlank()
                && ref.isLeaseActive(this.configuration.clock().millis());
    }

    private ObjectRef currentRegistryRecord(final List<ObjectRef> refs) {
        long generation = -1L;
        ObjectRef selected = null;
        int sameGeneration = 0;
        for (ObjectRef ref : refs) {
            ConcreteObjectGeneration concrete = concreteObjectGeneration(ref);
            if (concrete.generation() > generation) {
                generation = concrete.generation();
                selected = ref;
                sameGeneration = 1;
            } else if (concrete.generation() == generation) {
                sameGeneration++;
            }
        }
        if (generation <= 0L || sameGeneration != 1) {
            return null;
        }
        return selected;
    }

    private BaseObjectIdentity baseObjectIdentity(final ObjectRef ref) {
        return new BaseObjectIdentity(ref.runtimeId(), ref.objectId(), ref.registryContext());
    }

    private ConcreteObjectGeneration concreteObjectGeneration(final ObjectRef ref) {
        return new ConcreteObjectGeneration(baseObjectIdentity(ref), ref.generation());
    }

    private NuggetIntrospection.MetricsSummary metricsSummary(final NuggetIntrospection.Request request) {
        List<IndexedMetric> metrics = new ArrayList<>();
        int ordinal = 0;
        RuntimeStatus status = safeRuntimeStatus();
        boolean partial = status == null;
        if (status != null) {
            List<NuggetIntrospection.ObjectSummary> objects = visibleObjectSummaries(request, status);
            metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry("runtime.invocations", "count", status.invocationCount(), Map.of())), ordinal++));
            metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry("runtime.invocation.failures", "count", status.invocationFailureCount(), Map.of())), ordinal++));
            metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry("runtime.objects", "count", objects.size(), Map.of())), ordinal++));
            metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry("runtime.objects.exported", "count", objects.stream()
                    .filter(object -> object.visibility() == NuggetIntrospection.ObjectVisibility.EXPORTED).count(), Map.of())), ordinal++));
            metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry("runtime.registry.records", "count", objects.stream()
                    .filter(object -> !object.local()).count(), Map.of())), ordinal++));
        }
        metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry("runtime.applications", "count", visibleApplications(request).size(), Map.of())), ordinal++));
        ConcurrentHashMap<String, AdmissionSession> activeAdmissions = this.admissions;
        metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry(
                "nugget.auth.admissions",
                "count",
                activeAdmissions == null ? 0 : activeAdmissions.size(),
                Map.of())), ordinal++));
        ProviderDeadline deadline = requestDeadline();
        for (NuggetIntrospection.Provider provider : this.configuration.introspectionProviders().providers()) {
            if (deadlineExpired(deadline)) {
                partial = true;
                metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry(
                        "metrics.provider.unavailable", "count", 1.0D, Map.of("result", "deadline-exhausted"))), ordinal++));
                break;
            }
            NuggetIntrospection.ProviderContext context = providerContext(request == null ? "" : request.correlationId(), deadline);
            List<NuggetIntrospection.MetricEntry> supplied = callProvider(() -> provider.metrics(context), null, deadline);
            if (supplied != null) {
                int suppliedCount = supplied.size();
                if (suppliedCount > 256) {
                    partial = true;
                }
                int accepted = 0;
                for (NuggetIntrospection.MetricEntry entry : supplied) {
                    if (accepted >= 256) {
                        break;
                    }
                    accepted++;
                    NuggetIntrospection.MetricEntry safe = safeMetric(entry);
                    if (!safe.id().equals("unknown")) {
                        metrics.add(new IndexedMetric(safe, ordinal++));
                    } else {
                        partial = true;
                    }
                }
            } else {
                partial = true;
                metrics.add(new IndexedMetric(safeMetric(new NuggetIntrospection.MetricEntry(
                        "metrics.provider.unavailable", "count", 1.0D, Map.of("result", "provider-failed"))), ordinal++));
            }
        }
        List<NuggetIntrospection.MetricEntry> sortedMetrics = metrics.stream()
                .sorted(metricComparator())
                .map(IndexedMetric::metric)
                .limit(512L)
                .toList();
        if (metrics.size() > 512) {
            partial = true;
        }
        return new NuggetIntrospection.MetricsSummary(sortedMetrics, this.configuration.clock().instant(), partial);
    }

    private NuggetIntrospection.MetricEntry safeMetric(final NuggetIntrospection.MetricEntry metric) {
        String id = safeMetricId(metric.id());
        String unit = SAFE_METRIC_UNITS.contains(metric.unit()) ? metric.unit() : "count";
        double value = Double.isFinite(metric.value()) ? metric.value() : 0.0D;
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : metric.labels().entrySet()) {
            String key = entry.getKey();
            String valueLabel = entry.getValue();
            if (key != null && SAFE_METRIC_LABELS.contains(key) && valueLabel != null
                    && SAFE_LABEL_VALUE.matcher(valueLabel).matches() && !sensitive(valueLabel)) {
                labels.put(key, bounded(valueLabel));
            }
            if (labels.size() >= 8) {
                break;
            }
        }
        return new NuggetIntrospection.MetricEntry(id, unit, value, labels);
    }

    private String safeMetricId(final String id) {
        if (id != null && id.equals("nugget.auth.admissions")) {
            return id;
        }
        if (id == null || !SAFE_METRIC_ID.matcher(id).matches() || sensitive(id)) {
            return "unknown";
        }
        return id;
    }

    private NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry> recentErrorSummaries(
            final NuggetIntrospection.RecentErrorQuery query) {
        List<IndexedError> values = new ArrayList<>();
        int ordinal = 0;
        boolean providerFailed = false;
        ProviderDeadline deadline = requestDeadline();
        for (NuggetIntrospection.Provider provider : this.configuration.introspectionProviders().providers()) {
            if (deadlineExpired(deadline)) {
                providerFailed = true;
                break;
            }
            NuggetIntrospection.ProviderContext context = providerContext("", deadline);
            List<NuggetIntrospection.RecentErrorEntry> supplied = callProvider(() -> provider.recentErrors(context), null, deadline);
            if (supplied != null) {
                if (supplied.size() > 256) {
                    providerFailed = true;
                }
                int accepted = 0;
                for (NuggetIntrospection.RecentErrorEntry entry : supplied) {
                    if (accepted >= 256) {
                        break;
                    }
                    accepted++;
                    NuggetIntrospection.RecentErrorEntry safe = redactedError(entry);
                    if (query.component().isBlank() || safe.component().equals(query.component())) {
                        values.add(new IndexedError(safe, ordinal++));
                    }
                }
            } else {
                providerFailed = true;
            }
        }
        if (providerFailed && (query.component().isBlank() || "errors".equals(query.component()))) {
            values.add(new IndexedError(new NuggetIntrospection.RecentErrorEntry(
                    this.configuration.clock().instant(),
                    "",
                    "errors",
                    "provider",
                    MiddlewareErrorCode.INTERNAL,
                    "recent error provider unavailable",
                    false,
                    "",
                    1L,
                Map.of()), ordinal++));
        }
        return pageIndexedErrors(values, query.page(), providerFailed);
    }

    private <T> NuggetIntrospection.CollectionResult<T> page(
            final List<T> values,
            final NuggetIntrospection.Query query,
            final Comparator<T> comparator) {
        return page(values, query, comparator, false);
    }

    private <T> NuggetIntrospection.CollectionResult<T> page(
            final List<T> values,
            final NuggetIntrospection.Query query,
            final Comparator<T> comparator,
            final boolean partial) {
        List<T> sorted = new ArrayList<>(values);
        sorted.sort(query.direction() == NuggetIntrospection.SortDirection.DESC ? comparator.reversed() : comparator);
        int from = Math.min(query.offset(), sorted.size());
        int to = Math.min(from + query.limit(), sorted.size());
        List<T> page = sorted.subList(from, to);
        return new NuggetIntrospection.CollectionResult<>(
                page,
                query.offset(),
                query.limit(),
                page.size(),
                sorted.size(),
                true,
                query.sortBy(),
                query.direction(),
                query.filters(),
                this.configuration.clock().instant(),
                partial,
                Map.of());
    }

    private NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry> pageIndexedErrors(
            final List<IndexedError> values,
            final NuggetIntrospection.Query query,
            final boolean partial) {
        List<IndexedError> sorted = new ArrayList<>(values);
        Comparator<IndexedError> comparator = errorComparator();
        sorted.sort(query.direction() == NuggetIntrospection.SortDirection.DESC ? comparator.reversed() : comparator);
        int from = Math.min(query.offset(), sorted.size());
        int to = Math.min(from + query.limit(), sorted.size());
        List<NuggetIntrospection.RecentErrorEntry> page = sorted.subList(from, to).stream()
                .map(IndexedError::entry)
                .toList();
        return new NuggetIntrospection.CollectionResult<>(
                page,
                query.offset(),
                query.limit(),
                page.size(),
                sorted.size(),
                true,
                query.sortBy(),
                query.direction(),
                query.filters(),
                this.configuration.clock().instant(),
                partial,
                Map.of());
    }

    private List<NuggetIntrospection.TransportSummary> filterTransports(
            final List<NuggetIntrospection.TransportSummary> values,
            final Map<String, String> filters) {
        return values.stream()
                .filter(value -> filters.get("transportId") == null || value.transportId().equals(filters.get("transportId")))
                .toList();
    }

    private boolean validTransportQuery(final NuggetIntrospection.Query query) {
        return ("id".equals(query.sortBy()) || "transportId".equals(query.sortBy()))
                && query.filters().keySet().stream().allMatch(key -> key.equals("transportId"));
    }

    private boolean validDiskQuery(final NuggetIntrospection.DiskQuery query) {
        NuggetIntrospection.Query page = query.page();
        if (!query.role().isBlank() && !validDiskQueryIdentifier(query.role())) {
            return false;
        }
        if (!("id".equals(page.sortBy()) || "rootId".equals(page.sortBy()) || "role".equals(page.sortBy()))) {
            return false;
        }
        for (Map.Entry<String, String> entry : page.filters().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!("role".equals(key) || "rootId".equals(key))) {
                return false;
            }
            if (!validDiskQueryIdentifier(value)) {
                return false;
            }
        }
        return true;
    }

    private boolean validApplicationQuery(final NuggetIntrospection.Query query) {
        return ("id".equals(query.sortBy()) || "state".equals(query.sortBy()) || "coordinate".equals(query.sortBy()))
                && query.filters().keySet().stream().allMatch(key ->
                key.equals("applicationId") || key.equals("state") || key.equals("coordinate"));
    }

    private boolean validObjectQuery(final NuggetIntrospection.Query query) {
        return ("id".equals(query.sortBy()) || "serviceName".equals(query.sortBy()) || "visibility".equals(query.sortBy()))
                && query.filters().keySet().stream().allMatch(key ->
                key.equals("nuggetId")
                        || key.equals("serviceName")
                        || key.equals("objectKind")
                        || key.equals("visibility")
                        || key.equals("lifecycleState")
                        || key.equals("health")
                        || key.equals("interfaceName")
                        || key.equals("registryContext"));
    }

    private boolean validRecentErrorQuery(final NuggetIntrospection.Query query) {
        return ("id".equals(query.sortBy()) || "timestamp".equals(query.sortBy())) && query.filters().isEmpty();
    }

    private boolean matchesDiskPreProbe(
            final DiskRootGroup group,
            final NuggetIntrospection.DiskQuery query) {
        String role = query.role();
        if (!role.isBlank() && !group.roles().contains(role)) {
            return false;
        }
        String pageRole = query.page().filters().get("role");
        if (pageRole != null && !group.roles().contains(pageRole)) {
            return false;
        }
        String rootId = query.page().filters().get("rootId");
        return rootId == null || group.rootId().equals(rootId);
    }

    private List<NuggetIntrospection.DiskVolumeSummary> filterDisks(
            final List<NuggetIntrospection.DiskVolumeSummary> values,
            final Map<String, String> filters) {
        return values.stream()
                .filter(value -> filters.get("role") == null || value.roles().contains(filters.get("role")))
                .filter(value -> filters.get("rootId") == null || value.rootId().equals(filters.get("rootId")))
                .toList();
    }

    private Comparator<NuggetIntrospection.DiskVolumeSummary> diskComparator(final String sortBy) {
        return switch (sortBy) {
            case "role" -> Comparator.comparing(NuggetIntrospection.DiskVolumeSummary::role)
                    .thenComparing(NuggetIntrospection.DiskVolumeSummary::rootId);
            default -> Comparator.comparing(NuggetIntrospection.DiskVolumeSummary::rootId)
                    .thenComparing(NuggetIntrospection.DiskVolumeSummary::role);
        };
    }

    private boolean validDiskQueryIdentifier(final String value) {
        return value != null
                && SAFE_DISK_QUERY_IDENTIFIER.matcher(value).matches()
                && !sensitive(value)
                && safeIdentifier(value, "").equals(value);
    }

    private boolean matches(final NuggetIntrospection.ApplicationSummary summary, final Map<String, String> filters) {
        return (filters.get("applicationId") == null || summary.applicationId().equals(filters.get("applicationId")))
                && (filters.get("state") == null || summary.state().equals(filters.get("state")))
                && (filters.get("coordinate") == null || summary.coordinate().equals(filters.get("coordinate")));
    }

    private boolean matches(final NuggetIntrospection.ObjectSummary summary, final Map<String, String> filters) {
        return (filters.get("nuggetId") == null || summary.nuggetId().equals(filters.get("nuggetId")))
                && (filters.get("serviceName") == null || summary.serviceName().equals(filters.get("serviceName")))
                && (filters.get("objectKind") == null || summary.kind().equals(filters.get("objectKind")))
                && (filters.get("visibility") == null || summary.visibility().name().equals(filters.get("visibility")))
                && (filters.get("lifecycleState") == null || summary.lifecycleState().equals(filters.get("lifecycleState")))
                && (filters.get("health") == null || summary.health().name().equals(filters.get("health")))
                && (filters.get("interfaceName") == null || summary.interfaces().contains(filters.get("interfaceName")))
                && (filters.get("registryContext") == null || summary.registryContext().equals(filters.get("registryContext")));
    }

    private Comparator<NuggetIntrospection.ApplicationSummary> applicationComparator(final String sortBy) {
        return switch (sortBy) {
            case "state" -> Comparator.comparing(NuggetIntrospection.ApplicationSummary::state)
                    .thenComparing(NuggetIntrospection.ApplicationSummary::coordinate)
                    .thenComparing(NuggetIntrospection.ApplicationSummary::instanceId);
            case "coordinate" -> Comparator.comparing(NuggetIntrospection.ApplicationSummary::coordinate)
                    .thenComparing(NuggetIntrospection.ApplicationSummary::instanceId);
            default -> Comparator.comparing(NuggetIntrospection.ApplicationSummary::instanceId)
                    .thenComparing(NuggetIntrospection.ApplicationSummary::coordinate);
        };
    }

    private Comparator<NuggetIntrospection.ObjectSummary> objectComparator(final String sortBy) {
        return switch (sortBy) {
            case "serviceName" -> Comparator.comparing(NuggetIntrospection.ObjectSummary::serviceName)
                    .thenComparing(NuggetIntrospection.ObjectSummary::generation)
                    .thenComparing(NuggetIntrospection.ObjectSummary::objectId);
            case "visibility" -> Comparator.comparing((NuggetIntrospection.ObjectSummary summary) -> summary.visibility().name())
                    .thenComparing(NuggetIntrospection.ObjectSummary::serviceName)
                    .thenComparing(NuggetIntrospection.ObjectSummary::generation)
                    .thenComparing(NuggetIntrospection.ObjectSummary::objectId);
            default -> Comparator.comparing(NuggetIntrospection.ObjectSummary::objectId)
                    .thenComparing(NuggetIntrospection.ObjectSummary::generation);
        };
    }

    private Comparator<IndexedMetric> metricComparator() {
        return Comparator.comparing((IndexedMetric indexed) -> indexed.metric().id())
                .thenComparing(indexed -> indexed.metric().unit())
                .thenComparing(indexed -> canonicalLabels(indexed.metric().labels()))
                .thenComparingInt(IndexedMetric::ordinal);
    }

    private Comparator<IndexedError> errorComparator() {
        return Comparator.comparing((IndexedError indexed) -> indexed.entry().timestamp()).reversed()
                .thenComparing(indexed -> indexed.entry().correlationId())
                .thenComparing(indexed -> indexed.entry().component())
                .thenComparing(indexed -> indexed.entry().errorCode().name())
                .thenComparingInt(IndexedError::ordinal);
    }

    private String canonicalLabels(final Map<String, String> labels) {
        StringBuilder builder = new StringBuilder();
        labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder
                        .append(entry.getKey().length())
                        .append(':')
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue().length())
                        .append(':')
                        .append(entry.getValue())
                        .append(';'));
        return builder.toString();
    }

    private NuggetIntrospection.ObjectVisibility classifyVisibility(
            final ObjectRef ref,
            final ObjectState objectState,
            final RegistrationState registrationState,
            final boolean local) {
        if (ref == null || objectState == null || registrationState == null) {
            return NuggetIntrospection.ObjectVisibility.PRIVATE;
        }
        if (objectState == ObjectState.FENCED || objectState == ObjectState.ZOMBIE || objectState == ObjectState.DEAD) {
            return NuggetIntrospection.ObjectVisibility.PRIVATE;
        }
        if (registrationState == RegistrationState.GLOBAL || registrationState == RegistrationState.LOCAL_AND_GLOBAL) {
            return NuggetIntrospection.ObjectVisibility.EXPORTED;
        }
        if (local && registrationState == RegistrationState.LOCAL
                && (objectState == ObjectState.INITIALIZED || objectState == ObjectState.EXECUTED)) {
            return NuggetIntrospection.ObjectVisibility.INTERNAL;
        }
        return NuggetIntrospection.ObjectVisibility.PRIVATE;
    }

    private NuggetIntrospection.ServiceSummary serviceSummary(final RuntimeServiceIdentity service) {
        return new NuggetIntrospection.ServiceSummary(
                safeIdentifier(service.serviceName(), "service"),
                safeIdentifier(service.interfaceName(), "interface"),
                safeText(service.implementationClass()));
    }

    private List<String> moduleNames(final Map<String, String> metadata) {
        String value = metadata.get("moduleNames");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(name -> safeIdentifier(name, "module"))
                .filter(name -> !name.isBlank())
                .sorted()
                .toList();
    }

    private Map<String, String> componentMetadata(final Map<String, String> metadata) {
        return Map.of();
    }

    private Map<String, String> runtimeSecurityMetadata(final Map<String, String> metadata) {
        return allowlistedMetadata(metadata, Set.of("mode", "securityMode"));
    }

    private Map<String, String> repositoryMetadata(final Map<String, String> metadata) {
        return allowlistedMetadata(metadata, Set.of("availability", "signatureVerification"));
    }

    private Map<String, String> applicationSummaryMetadata(final Map<String, String> metadata) {
        return Map.of();
    }

    private Map<String, String> applicationDetailCapabilities(final Map<String, String> metadata) {
        return allowlistedMetadata(metadata, Set.of("moduleMode"));
    }

    private Map<String, String> objectSummaryMetadata(final Map<String, String> metadata) {
        return Map.of();
    }

    private Map<String, String> objectDescriptorMetadata(final Map<String, String> metadata) {
        return Map.of();
    }

    private Map<String, String> objectAnnotationMetadata(final Map<String, String> metadata) {
        return Map.of();
    }

    private Map<String, String> recentErrorMetadata(final Map<String, String> metadata) {
        return Map.of();
    }

    private Map<String, String> allowlistedMetadata(final Map<String, String> metadata, final Set<String> allowedKeys) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        allowedKeys.stream().sorted().forEach(key -> {
            String value = metadata.get(key);
            if (value != null && values.size() < 16) {
                String safeKey = safeIdentifier(key, "");
                String safeValue = safeStatusText(value);
                if (!safeKey.isBlank() && !safeValue.equals("unknown")) {
                    values.put(safeKey, safeValue);
                }
            }
        });
        return Map.copyOf(values);
    }

    private NuggetIntrospection.RecentErrorEntry redactedError(final NuggetIntrospection.RecentErrorEntry entry) {
        return new NuggetIntrospection.RecentErrorEntry(
                entry.timestamp(),
                safeIdentifier(entry.correlationId(), ""),
                safeIdentifier(entry.component(), "component"),
                this.configuration.security().redactor().redact("sensitive.error.resource", entry.resourceSummary()),
                entry.errorCode(),
                this.configuration.security().redactor().redact("sensitive.error.message", entry.safeMessage()),
                entry.retryable(),
                safeIdentifier(entry.remoteRuntimeId(), ""),
                entry.recurrenceCount(),
                recentErrorMetadata(entry.metadata()));
    }

    private String argumentName(final String argument) {
        return NuggetEnvironmentRedaction.argumentName(argument);
    }

    private String safeEnvironmentName(final String name) {
        return NuggetEnvironmentRedaction.safeEnvironmentName(name);
    }

    private String safeDiskRole(final String role) {
        String value = safeIdentifier(role, "");
        if (value.isBlank() || value.equals("metadata")) {
            return "unknown";
        }
        return value;
    }

    private List<String> safeIdentifiers(final List<String> values, final String fallback) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> safeIdentifier(value, fallback))
                .filter(value -> !value.equals(fallback))
                .distinct()
                .sorted()
                .limit(64L)
                .toList();
    }

    private String safeStatusText(final String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String safe = safeIdentifier(value, "unknown");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String safeRegistryEvent(final String value) {
        if (value == null || value.isBlank() || sensitive(value)) {
            return "";
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "state-ready", "ready" -> "STATE_READY";
            case "state-degraded", "degraded" -> "STATE_DEGRADED";
            case "state-unavailable", "unavailable" -> "STATE_UNAVAILABLE";
            case "registered" -> "REGISTERED";
            case "unregistered" -> "UNREGISTERED";
            case "lookup-failed" -> "LOOKUP_FAILED";
            default -> "";
        };
    }

    private String safePath(final Path path, final boolean sensitive) {
        if (path == null) {
            return "";
        }
        if (sensitive) {
            return "<redacted>";
        }
        Path fileName = path.getFileName();
        return fileName == null ? safeText(path.toString()) : safeText(fileName.toString());
    }

    private String endpointSummary(final String transport, final String endpoint) {
        if (endpoint == null || endpoint.isBlank() || sensitive(endpoint) || endpoint.length() > 512) {
            return safeIdentifier(transport, "transport") + ":<redacted-endpoint>";
        }
        try {
            URI uri = new URI(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return safeIdentifier(transport, "transport") + ":<redacted-endpoint>";
            }
            String host = uri.getHost();
            if (host == null || host.isBlank() || sensitive(host)) {
                host = "<redacted-host>";
            }
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return safeIdentifier(uri.getScheme(), "scheme") + "://" + safeText(host) + port;
        } catch (URISyntaxException e) {
            return safeIdentifier(transport, "transport") + ":<redacted-endpoint>";
        }
    }

    private Map<String, String> sanitizedMetadata(final Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (values.size() >= 64) {
                break;
            }
            String key = safeIdentifier(entry.getKey(), "metadata");
            String value = safeText(entry.getValue());
            values.put(key, value);
        }
        return this.configuration.security().redactor().redactMetadata(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    private String safeIdentifier(final String value, final String fallback) {
        if (value == null || value.isBlank() || value.length() > 128 || sensitive(value)) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 128));
        for (int index = 0; index < value.length() && builder.length() < 128; index++) {
            char c = value.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ':') {
                builder.append(c);
            }
        }
        return builder.isEmpty() ? fallback : builder.toString();
    }

    private String safeText(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (sensitive(value)) {
            return "<redacted>";
        }
        return bounded(value);
    }

    private boolean sensitive(final String value) {
        return containsAny(value, SENSITIVE_WORDS);
    }

    private boolean containsAny(final String value, final Set<String> words) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private double cpuLoad(final OperatingSystemMXBean bean, final String methodName) {
        try {
            java.lang.reflect.Method method = bean.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(bean);
            return value instanceof Number number ? number.doubleValue() : -1.0D;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1.0D;
        }
    }

    private long cpuTime(final OperatingSystemMXBean bean) {
        try {
            java.lang.reflect.Method method = bean.getClass().getMethod("getProcessCpuTime");
            method.setAccessible(true);
            Object value = method.invoke(bean);
            return value instanceof Number number ? number.longValue() : -1L;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1L;
        }
    }

    private long parseLong(final String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private StartMaterial resolveStartMaterial() {
        if (this.configuration.security().mode() == SecurityMode.PRODUCTION && this.configuration.unsafeDeveloperMode()) {
            throw new IllegalArgumentException("unsafe developer mode rejected");
        }
        int active = this.configuration.sources().size() + (this.configuration.generatedDevelopmentPinRequested() ? 1 : 0);
        if (active != 1) {
            throw new IllegalArgumentException("exactly one PIN source is required");
        }
        if (this.configuration.generatedDevelopmentPinRequested()) {
            if (!this.configuration.unsafeDeveloperMode()) {
                throw new IllegalArgumentException("generated development PIN requires unsafe developer mode");
            }
            char[] generated = generatedDevelopmentPin();
            try {
                NuggetPinVerifier.validatePinInput(generated, this.configuration.maxPinBytes());
                this.configuration.generatedDevelopmentPinReceiver().accept(Arrays.copyOf(generated, generated.length));
                NuggetPinVerifier created = NuggetPinVerifier.create(
                        generated,
                        this.configuration.iterations(),
                        this.configuration.secureRandom(),
                        this.configuration.clock().instant(),
                        "generated-development");
                return new StartMaterial(created, NuggetPinSourceType.GENERATED_DEVELOPMENT_PIN, true);
            } finally {
                Arrays.fill(generated, '\0');
            }
        }
        NuggetAdminConfiguration.PinSourceConfig source = this.configuration.sources().getFirst();
        if (source.lowerSafety()) {
            audit("nugget.pin.source.lowerSafety", "allow", "direct-process-argument", "", Map.of("source", source.type().name()));
        }
        if (source.type() == NuggetPinSourceType.PROTECTED_VERIFIER_FILE) {
            NuggetPinVerifier loaded = loadVerifierFile(source.path());
            this.activeVerifierPath = source.path();
            return new StartMaterial(loaded, source.type(), source.lowerSafety());
        }
        this.activeVerifierPath = null;
        char[] pin = resolvePin(source);
        try {
            NuggetPinVerifier.validatePinInput(pin, this.configuration.maxPinBytes());
            NuggetPinVerifier created = NuggetPinVerifier.create(
                    pin,
                    this.configuration.iterations(),
                    this.configuration.secureRandom(),
                    this.configuration.clock().instant(),
                    "phase-7.1-baseline");
            return new StartMaterial(created, source.type(), source.lowerSafety());
        } finally {
            if (pin != null) {
                Arrays.fill(pin, '\0');
            }
        }
    }

    private char[] resolvePin(final NuggetAdminConfiguration.PinSourceConfig source) {
        return switch (source.type()) {
            case PROGRAMMATIC_PROVIDER, INTERACTIVE_BOOTSTRAP -> {
                if (source.provider() == null) {
                    throw new IllegalArgumentException("PIN source rejected");
                }
                yield source.provider().resolve();
            }
            case ENVIRONMENT_VARIABLE -> resolveEnvironment(source.description(), source.environmentLookup());
            case PROTECTED_FILE -> readProtectedPinFile(source.path());
            case DIRECT_PROCESS_ARGUMENT -> {
                if (source.provider() == null) {
                    throw new IllegalArgumentException("PIN source rejected");
                }
                yield source.provider().resolve();
            }
            default -> throw new IllegalArgumentException("PIN source rejected");
        };
    }

    private char[] resolveEnvironment(final String variableName, final java.util.function.Function<String, String> lookup) {
        if (!NuggetEnvironmentRedaction.validEnvironmentVariableName(variableName)) {
            throw new IllegalArgumentException("environment PIN source rejected");
        }
        String value = lookup == null ? null : lookup.apply(variableName);
        if (value == null) {
            throw new IllegalArgumentException("environment PIN source rejected");
        }
        return value.toCharArray();
    }

    private char[] readProtectedPinFile(final Path path) {
        validateProtectedFile(path);
        try {
            return this.configuration.fileOperations().readBoundedUtf8Chars(path, this.configuration.maxPinBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("protected PIN file rejected", e);
        }
    }

    private NuggetPinVerifier loadVerifierFile(final Path path) {
        validateProtectedFile(path);
        try {
            return NuggetPinVerifier.parse(this.configuration.fileOperations().readBoundedUtf8(path, NuggetPinVerifier.MAX_RECORD_BYTES));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("protected verifier file rejected", e);
        }
    }

    private void validateProtectedFile(final Path path) {
        try {
            this.configuration.fileOperations().validateProtectedFile(path);
        } catch (NuggetAdminFileSecurityUnavailableException e) {
            handleUnverifiedFileSecurity();
        } catch (IOException e) {
            throw new IllegalArgumentException("protected file rejected", e);
        }
    }

    private void handleUnverifiedFileSecurity() {
        if (this.configuration.security().mode() == SecurityMode.PRODUCTION) {
            throw new IllegalArgumentException("protected file permissions unavailable");
        }
        audit("nugget.pin.file.permission.unverified", "allow", "unsafe-developer-warning", "", Map.of());
    }

    private boolean validPinInput(final char[] pin) {
        try {
            NuggetPinVerifier.validatePinInput(pin, this.configuration.maxPinBytes());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private NuggetAuthenticationResult failure(
            final NuggetAuthenticationRequest request,
            final NuggetAuthenticationOutcome outcome,
            final NuggetAuthenticationFailureReason reason,
            final MiddlewareErrorCode code,
            final String message,
            final Duration retryAfter,
            final Map<String, String> metadata) {
        RemoteError error = new RemoteError(code, message, Map.of("reason", reason.name()), request.correlationId(),
                this.runtimeIdentity.runtimeId(), code == MiddlewareErrorCode.OVERLOADED, false);
        return new NuggetAuthenticationResult(
                outcome,
                reason,
                this.nuggetId,
                request.correlationId(),
                "",
                retryAfter,
                this.configuration.clock().instant(),
                error,
                metadata);
    }

    private void audit(
            final String action,
            final String decision,
            final String reason,
            final String correlationId,
            final Map<String, String> metadata) {
        SecurityAuditSink sink = this.configuration.security().auditSink();
        Redactor redactor = this.configuration.security().redactor();
        sink.record(new AuditEvent(
                this.configuration.clock().instant(),
                this.runtimeIdentity.runtimeId(),
                "anonymous",
                action,
                this.nuggetId,
                decision,
                "nugget-pin-admission",
                correlationId,
                "",
                "",
                reason,
                "headless",
                this.runtimeIdentity.registryContext(),
                redactor.redactMetadata(metadata)));
    }

    private Map<String, String> sourceMetadata(final NuggetAuthenticationRequest request) {
        return sourceMetadata(request, Map.of());
    }

    private Map<String, String> sourceMetadata(final NuggetAuthenticationRequest request, final Map<String, String> extra) {
        java.util.HashMap<String, String> metadata = new java.util.HashMap<>(extra);
        metadata.put("source", request.source().name());
        metadata.put("trustedSource", Boolean.toString(request.trustedSource()));
        if (request.trustedSource() && !request.sourceId().isBlank()) {
            metadata.put("sourceId", bounded(request.sourceId()));
        }
        metadata.put("sourceType", this.activeSourceType.name());
        return Map.copyOf(metadata);
    }

    private String throttleKey(final NuggetAuthenticationRequest request) {
        String base = this.nuggetId + ":" + request.source().name();
        if ((request.source() == NuggetAuthenticationSource.LOCAL || request.trustedSource()) && !request.sourceId().isBlank()) {
            return base + ":" + bounded(request.sourceId());
        }
        return base;
    }

    private char[] generatedDevelopmentPin() {
        char[] generated = new char[24];
        for (int index = 0; index < generated.length; index++) {
            generated[index] = DEVELOPMENT_PIN_ALPHABET[this.configuration.secureRandom().nextInt(DEVELOPMENT_PIN_ALPHABET.length)];
        }
        return generated;
    }

    private String nuggetName() {
        if (this.configuration.nuggetName().isBlank()) {
            return this.runtimeIdentity.runtimeId();
        }
        return this.configuration.nuggetName();
    }

    private Map<String, String> capabilityMap(final NuggetAdminState adminState) {
        if (adminState != NuggetAdminState.ENABLED) {
            return Map.of(NuggetCapability.READ_INFO.name(), "1");
        }
        Map<String, String> capabilities = new LinkedHashMap<>();
        capabilities.put(NuggetCapability.READ_INFO.name(), "1");
        capabilities.put(NuggetCapability.PIN_ADMISSION.name(), "1");
        capabilities.put(NuggetCapability.READ_RUNTIME_STATUS.name(), "1");
        capabilities.put(NuggetCapability.READ_ENVIRONMENT.name(), "1");
        capabilities.put(NuggetCapability.READ_DISK.name(), "1");
        capabilities.put(NuggetCapability.READ_HEALTH.name(), "1");
        capabilities.put(NuggetCapability.READ_READINESS.name(), "1");
        capabilities.put(NuggetCapability.READ_TRANSPORTS.name(), "1");
        capabilities.put(NuggetCapability.READ_REGISTRY.name(), "1");
        capabilities.put(NuggetCapability.READ_REPOSITORY.name(), "1");
        capabilities.put(NuggetCapability.READ_APPLICATIONS.name(), "1");
        capabilities.put(NuggetCapability.READ_OBJECTS.name(), "1");
        capabilities.put(NuggetCapability.READ_METRICS.name(), "1");
        capabilities.put(NuggetCapability.READ_ERRORS.name(), "1");
        if (this.configuration.applicationDeployer() != null && !this.configuration.readOnly()) {
            if (this.configuration.startApplicationEnabled()) {
                capabilities.put(NuggetCapability.START_APPLICATION.name(), "1");
            }
            capabilities.put(NuggetCapability.DRAIN_APPLICATION.name(), "1");
            capabilities.put(NuggetCapability.STOP_APPLICATION.name(), "1");
            capabilities.put(NuggetCapability.UNLOAD_APPLICATION.name(), "1");
        }
        if (!this.configuration.readOnly()) {
            capabilities.put(NuggetCapability.DRAIN_NUGGET.name(), "1");
            if (this.runtimeCloseAction != null) {
                capabilities.put(NuggetCapability.SHUTDOWN_NUGGET.name(), "1");
            }
            if (this.configuration.killEnabled() && this.configuration.terminationCapability() != null) {
                capabilities.put(NuggetCapability.FORCE_TERMINATION.name(), "1");
            }
        }
        capabilities.put(NuggetCapability.DISCOVER_NUGGETS.name(), "1");
        return Collections.unmodifiableMap(capabilities);
    }

    private NuggetState nuggetState(final NuggetAdminState adminState) {
        NuggetState current = this.controlState.get();
        if (current == NuggetState.DRAINING
                || current == NuggetState.STOPPING
                || current == NuggetState.STOPPED
                || current == NuggetState.DEGRADED) {
            return current;
        }
        return switch (adminState) {
            case STARTING -> NuggetState.STARTING;
            case ENABLED -> NuggetState.RUNNING;
            case DISABLED, STOPPED -> NuggetState.STOPPED;
            case FAILED -> NuggetState.DEGRADED;
            case STOPPING -> NuggetState.STOPPING;
        };
    }

    private String safeProcessId() {
        try {
            return Long.toString(ProcessHandle.current().pid());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String safeHostSummary() {
        return "LOCAL";
    }

    private String j7mVersion() {
        Package pkg = NuggetAdminController.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "" : version;
    }

    private String bounded(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 64));
        boolean previousSeparator = false;
        for (int index = 0; index < value.length() && builder.length() < 64; index++) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n' || current == '\t' || Character.isWhitespace(current)) {
                if (!builder.isEmpty() && !previousSeparator) {
                    builder.append('-');
                    previousSeparator = true;
                }
            } else if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '.'
                    || current == '_'
                    || current == '-'
                    || current == ':') {
                builder.append(current);
                previousSeparator = false;
            } else {
                return "";
            }
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '-') {
            builder.setLength(length - 1);
        }
        return builder.toString();
    }

    private MiddlewareException safeConfigurationException(final String message) {
        return MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, message);
    }

    private void closeVerifier() {
        this.verifierLock.writeLock().lock();
        try {
            NuggetPinVerifier existing = this.verifier;
            this.verifier = null;
            if (existing != null) {
                existing.close();
            }
        } finally {
            this.verifierLock.writeLock().unlock();
        }
    }

    private void upgradeVerifierIfRequired(final NuggetPinVerifier expected, final char[] pin, final NuggetAuthenticationRequest request) {
        if (expected == null || !expected.needsUpgrade(this.configuration.iterations())) {
            return;
        }
        this.verifierLock.writeLock().lock();
        try {
            NuggetPinVerifier current = this.verifier;
            if (current != expected || current == null || !current.needsUpgrade(this.configuration.iterations())
                    || this.state.get() != NuggetAdminState.ENABLED) {
                return;
            }
            audit("nugget.pin.verifier.upgrade.attempted", "allow", "upgrade-on-success", request.correlationId(),
                    Map.of("format", current.formatVersion()));
            NuggetPinVerifier upgraded = null;
            try {
                upgraded = NuggetPinVerifier.create(
                        pin,
                        this.configuration.iterations(),
                        this.configuration.secureRandom(),
                        this.configuration.clock().instant(),
                        "phase-7.1-upgrade");
                this.configuration.lifecycleHooks().beforeVerifierUpgradePersistence();
                persistVerifierUpgradeIfRequired(upgraded);
                this.verifier = upgraded;
                current.close();
                audit("nugget.pin.verifier.upgrade.completed", "allow", "upgrade-on-success", request.correlationId(),
                        Map.of("format", upgraded.formatVersion()));
            } catch (RuntimeException e) {
                if (upgraded != null) {
                    upgraded.close();
                }
                audit("nugget.pin.verifier.upgrade.failed", "deny", "upgrade-failed", request.correlationId(), Map.of());
            }
        } finally {
            this.verifierLock.writeLock().unlock();
        }
    }

    private void persistVerifierUpgradeIfRequired(final NuggetPinVerifier upgraded) {
        Path path = this.activeVerifierPath;
        if (path == null) {
            return;
        }
        validateProtectedFile(path);
        byte[] bytes = upgraded.encodedRecordBytes();
        if (bytes.length == 0 || bytes.length > NuggetPinVerifier.MAX_RECORD_BYTES) {
            throw new IllegalStateException("verifier upgrade persistence failed");
        }
        try {
            this.configuration.fileOperations().persistVerifierRecord(path, bytes);
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalStateException("verifier upgrade persistence failed", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static final class DiskRootGroup {
        private final Path path;
        private final LinkedHashSet<String> roles;
        private boolean sensitive;

        private DiskRootGroup(final Path path) {
            this.path = path;
            this.roles = new LinkedHashSet<>();
        }

        private void add(final String role, final boolean sensitiveRole) {
            this.roles.add(role == null || role.isBlank() ? "unknown" : role);
            this.sensitive = this.sensitive || sensitiveRole;
        }

        private Path path() {
            return this.path;
        }

        private String primaryRole() {
            return this.roles().getFirst();
        }

        private List<String> roles() {
            return this.roles.stream().sorted().limit(32L).toList();
        }

        private boolean sensitive() {
            return this.sensitive;
        }

        private String rootId() {
            return this.primaryRole() + ":" + Integer.toUnsignedString(this.path.toString().hashCode());
        }
    }

    private record BaseObjectIdentity(String runtimeId, String objectId, String registryContext) {
    }

    private record ConcreteObjectGeneration(BaseObjectIdentity baseIdentity, long generation) {
    }

    private record ObjectCandidate(
            ObjectRef ref,
            ObjectState objectState,
            RegistrationState registrationState,
            boolean local,
            NuggetIntrospection.ObjectVisibility visibility) {
    }

    private record IndexedMetric(NuggetIntrospection.MetricEntry metric, int ordinal) {
    }

    private record IndexedError(NuggetIntrospection.RecentErrorEntry entry, int ordinal) {
    }

    private record DiscoveryMerge(List<NuggetDiscovery.Record> records, boolean partial, List<String> warnings) {
        private DiscoveryMerge {
            records = records == null ? List.of() : List.copyOf(records);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    private record DiskCollection(
            NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary> result,
            boolean partial) {
    }

    private record ProviderDeadline(Instant contextDeadline, long deadlineNanos) {
        private ProviderDeadline {
            contextDeadline = contextDeadline == null ? Instant.EPOCH : contextDeadline;
        }
    }

    private record StartMaterial(NuggetPinVerifier verifier, NuggetPinSourceType sourceType, boolean lowerSafety) {
    }

    private record ControlExecution(
            NuggetControl.Result result,
            ControlActionSubmission submission,
            ControlRollback rollback) {
        private void releaseAfterAudit() {
            if (this.submission != null) {
                this.submission.release();
            }
        }

        private void cancelBeforeAuditBoundary(final RuntimeException auditFailure) {
            try {
                if (this.submission != null) {
                    this.submission.cancel();
                }
            } catch (RuntimeException exception) {
                auditFailure.addSuppressed(exception);
            } finally {
                if (this.rollback != null) {
                    this.rollback.restore();
                }
            }
        }
    }

    private final class ControlRollback {
        private final AtomicBoolean submittedFlag;
        private final NuggetState previousState;
        private final long transitionVersion;

        private ControlRollback(
                final AtomicBoolean submittedFlag,
                final NuggetState previousState,
                final long transitionVersion) {
            this.submittedFlag = submittedFlag;
            this.previousState = previousState;
            this.transitionVersion = transitionVersion;
        }

        private void restore() {
            synchronized (controlTransitionLock) {
                this.submittedFlag.set(false);
                if (controlTransitionVersion == this.transitionVersion
                        && controlState.get() == NuggetState.STOPPING) {
                    setControlStateLocked(this.previousState);
                }
            }
        }
    }

    private record ControlActionSubmission(ControlActionTask task, Future<?> future) {
        private void release() {
            this.task.release();
        }

        private void cancel() {
            this.future.cancel(true);
        }
    }

    private record ControlOperationStatus(
            NuggetControl.Action action,
            String operationId,
            Instant requestedAt,
            NuggetState currentState,
            NuggetState resultingState,
            NuggetControl.Outcome outcome,
            String outcomeText,
            MiddlewareErrorCode errorCode,
            String reason,
            boolean outcomeUnknown,
            boolean cleanupPartial,
            List<String> warnings,
            String metadataResult) {
        private ControlOperationStatus {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            operationId = operationId == null ? "" : operationId;
            requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
            currentState = currentState == null ? NuggetState.UNKNOWN : currentState;
            resultingState = resultingState == null ? NuggetState.UNKNOWN : resultingState;
            outcome = outcome == null ? NuggetControl.Outcome.OUTCOME_UNKNOWN : outcome;
            outcomeText = outcomeText == null ? "" : outcomeText;
            reason = reason == null ? "" : reason;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadataResult = metadataResult == null ? "" : metadataResult;
        }

        private boolean matches(final NuggetControl.Request request) {
            return request != null
                    && this.action == request.action()
                    && !this.operationId.isBlank()
                    && this.operationId.equals(request.operationId());
        }
    }

    private final class ControlActionTask implements Runnable {
        private final CountDownLatch startGate;
        private final String actionName;
        private final Runnable action;
        private final NuggetControl.Request request;
        private final String operationId;
        private final Instant started;
        private final long timeoutMillis;
        private final AtomicInteger actionState;
        private final AtomicBoolean selfCloseCleanupEntered;
        private volatile Future<?> future;
        private volatile ScheduledFuture<?> timeoutFuture;

        private ControlActionTask(
                final String actionName,
                final Runnable action,
                final NuggetControl.Request request,
                final String operationId,
                final Instant started,
                final long timeoutMillis) {
            this.startGate = new CountDownLatch(1);
            this.actionName = actionName;
            this.action = action;
            this.request = request;
            this.operationId = operationId;
            this.started = started;
            this.timeoutMillis = Math.max(0L, timeoutMillis);
            this.actionState = new AtomicInteger(CONTROL_ACTION_WAITING);
            this.selfCloseCleanupEntered = new AtomicBoolean();
        }

        private void setFuture(final Future<?> value) {
            this.future = value;
        }

        private Future<?> future() {
            return this.future;
        }

        private void setTimeoutFuture(final ScheduledFuture<?> value) {
            this.timeoutFuture = value;
            if (this.actionState.get() != CONTROL_ACTION_WAITING
                    && this.actionState.get() != CONTROL_ACTION_RUNNING
                    && value != null) {
                value.cancel(false);
            }
        }

        private void release() {
            this.startGate.countDown();
        }

        private void enterSelfCloseCleanup() {
            this.selfCloseCleanupEntered.set(true);
        }

        private void timeout() {
            int previousState = this.actionState.get();
            while (previousState == CONTROL_ACTION_WAITING || previousState == CONTROL_ACTION_RUNNING) {
                if (this.actionState.compareAndSet(previousState, CONTROL_ACTION_TIMED_OUT)) {
                    Future<?> current = this.future;
                    if (current != null && !this.selfCloseCleanupEntered.get()) {
                        current.cancel(true);
                    }
                    markControlTimeout();
                    return;
                }
                previousState = this.actionState.get();
            }
        }

        @Override
        public void run() {
            Thread currentThread = Thread.currentThread();
            activeControlActions.put(currentThread, this);
            try {
                this.startGate.await();
                if (!this.actionState.compareAndSet(CONTROL_ACTION_WAITING, CONTROL_ACTION_RUNNING)) {
                    return;
                }
                try {
                    this.action.run();
                    completeControlAction();
                } catch (RuntimeException exception) {
                    failControlAction();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancelControlAction();
            } finally {
                ScheduledFuture<?> currentTimeout = this.timeoutFuture;
                if (currentTimeout != null && this.actionState.get() != CONTROL_ACTION_TIMED_OUT) {
                    currentTimeout.cancel(false);
                }
                Future<?> current = this.future;
                if (current != null) {
                    controlActionFutures.remove(current);
                }
                activeControlActions.remove(currentThread, this);
                if (this.selfCloseCleanupEntered.get()) {
                    shutdownSelfCloseControlMonitor();
                }
            }
        }

        private void completeControlAction() {
            if (this.actionState.compareAndSet(CONTROL_ACTION_RUNNING, CONTROL_ACTION_COMPLETED)) {
                audit("nugget.control.async.completed", "allow", "", this.request.correlationId(),
                        Map.of("action", this.request.action().name(),
                                "actionName", this.actionName,
                                "mode", this.request.mode().name(),
                                "operationId", this.operationId,
                                "outcome", NuggetControl.Outcome.COMPLETED.name()));
            }
        }

        private void failControlAction() {
            if (this.actionState.compareAndSet(CONTROL_ACTION_RUNNING, CONTROL_ACTION_FAILED)) {
                retainControlStatus(new ControlOperationStatus(
                        this.request.action(),
                        this.operationId,
                        this.request.requestedAt(),
                        controlState.get(),
                        NuggetState.DEGRADED,
                        NuggetControl.Outcome.OUTCOME_UNKNOWN,
                        "nugget control callback failed; final outcome is unknown",
                        MiddlewareErrorCode.INTERNAL,
                        "async-control-failed",
                        true,
                        true,
                        List.of("control-outcome-unknown"),
                        "outcome-unknown"));
                audit("nugget.control.async.failed", "deny", "async-control-failed", this.request.correlationId(),
                        Map.of("action", this.request.action().name(),
                                "actionName", this.actionName,
                                "mode", this.request.mode().name(),
                                "operationId", this.operationId,
                                "outcome", NuggetControl.Outcome.OUTCOME_UNKNOWN.name(),
                                "outcomeUnknown", "true"));
            }
        }

        private void cancelControlAction() {
            int previousState = this.actionState.get();
            while (previousState == CONTROL_ACTION_WAITING || previousState == CONTROL_ACTION_RUNNING) {
                if (this.actionState.compareAndSet(previousState, CONTROL_ACTION_CANCELLED)) {
                    retainControlStatus(new ControlOperationStatus(
                            this.request.action(),
                            this.operationId,
                            this.request.requestedAt(),
                            controlState.get(),
                            NuggetState.DEGRADED,
                            NuggetControl.Outcome.OUTCOME_UNKNOWN,
                            "nugget control callback was cancelled; final outcome is unknown",
                            MiddlewareErrorCode.CANCELLED,
                            "async-control-cancelled",
                            true,
                            true,
                            List.of("control-outcome-unknown"),
                            "outcome-unknown"));
                    audit("nugget.control.async.cancelled", "deny", "async-control-cancelled", this.request.correlationId(),
                            Map.of("action", this.request.action().name(),
                                    "actionName", this.actionName,
                                    "mode", this.request.mode().name(),
                                    "operationId", this.operationId,
                                    "outcome", NuggetControl.Outcome.OUTCOME_UNKNOWN.name(),
                                    "outcomeUnknown", "true"));
                    return;
                }
                previousState = this.actionState.get();
            }
        }

        private void markControlTimeout() {
            retainControlStatus(new ControlOperationStatus(
                    this.request.action(),
                    this.operationId,
                    this.request.requestedAt(),
                    NuggetState.DEGRADED,
                    NuggetState.DEGRADED,
                    NuggetControl.Outcome.TIMED_OUT,
                    "nugget control callback exceeded the server control timeout; final outcome is unknown",
                    MiddlewareErrorCode.DEADLINE_EXCEEDED,
                    "control-timeout",
                    true,
                    true,
                    List.of("control-outcome-unknown"),
                    "timed-out"));
            setControlState(NuggetState.DEGRADED);
            audit("nugget.control.async.timedOut", "deny", "control-timeout", this.request.correlationId(),
                    Map.of("action", this.request.action().name(),
                            "actionName", this.actionName,
                            "mode", this.request.mode().name(),
                            "operationId", this.operationId,
                            "elapsedMillis", Long.toString(elapsed(
                                    this.started,
                                    NuggetAdminController.this.configuration.clock().instant()).toMillis()),
                            "timeoutMillis", Long.toString(this.timeoutMillis),
                            "outcome", NuggetControl.Outcome.TIMED_OUT.name(),
                            "outcomeUnknown", "true",
                            "state", NuggetState.DEGRADED.name()));
        }
    }

    private void retainControlStatus(final ControlOperationStatus status) {
        AtomicReference<ControlOperationStatus> statusReference = controlStatusReference(status.action());
        if (statusReference != null) {
            statusReference.compareAndSet(null, status);
        }
    }

    private record CreatedConfirmation(PendingConfirmation pending, String challenge) {
    }

    private record PendingConfirmation(
            String operationId,
            byte[] challengeDigest,
            Instant expiresAt,
            NuggetControl.Action action,
            String nuggetId,
            String runtimeId,
            long generation,
            String callerId,
            NuggetState state) {
        private PendingConfirmation {
            challengeDigest = challengeDigest == null ? new byte[0] : Arrays.copyOf(challengeDigest, challengeDigest.length);
        }

        private boolean matches(
                final NuggetControl.Request request,
                final long currentGeneration,
                final String currentCallerId,
                final NuggetState currentState,
                final Instant now) {
            byte[] requestDigest = digestToken(request.confirmation());
            try {
                return this.expiresAt.isAfter(now)
                        && this.operationId.equals(request.operationId())
                        && MessageDigest.isEqual(this.challengeDigest, requestDigest)
                        && this.action == request.action()
                        && this.nuggetId.equals(request.targetNuggetId())
                        && this.runtimeId.equals(request.targetRuntimeId())
                        && this.generation == currentGeneration
                        && this.callerId.equals(currentCallerId)
                        && this.state == currentState;
            } finally {
                Arrays.fill(requestDigest, (byte) 0);
            }
        }
    }

    private record AdmissionSession(String nuggetId, String runtimeId, Instant expiresAt) {
        private boolean expired(final Instant now) {
            Instant safeNow = now == null ? Instant.EPOCH : now;
            return !this.expiresAt.isAfter(safeNow);
        }
    }
}
