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

package org.j7m.middleware.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.Benchmark;
import org.j7m.middleware.annotations.Audit;
import org.j7m.middleware.annotations.Confidential;
import org.j7m.middleware.annotations.Initialize;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.RequiresTrustDomain;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InstanceStatus;
import org.j7m.middleware.api.InvocationMetadata;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.api.PreparedObjectRegistration;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.api.RegistrationState;
import org.j7m.middleware.api.RegistryStatus;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.api.TransportStatus;
import org.j7m.middleware.api.reliability.DedupeEntry;
import org.j7m.middleware.api.reliability.DedupeResult;
import org.j7m.middleware.api.reliability.DuplicateDetectionPolicy;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.api.reliability.ReliabilityDecision;
import org.j7m.middleware.transport.api.AsyncMethodDescriptor;
import org.j7m.middleware.transport.api.InvocationBoundary;
import org.j7m.middleware.transport.api.InvocationCancellation;
import org.j7m.middleware.transport.api.InvocationCompletionFactory;
import org.j7m.middleware.transport.api.InvocationCompletionScope;
import org.j7m.middleware.codec.api.CodecContext;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.ha.HpCandidateScore;
import org.j7m.middleware.ha.HpSelection;
import org.j7m.middleware.ha.HpSelector;
import org.j7m.middleware.ha.ObjectInvocationStats;
import org.j7m.middleware.metrics.SlidingWindowStatistics;
import org.j7m.middleware.metrics.StatisticsSnapshot;
import org.j7m.middleware.reliability.InMemoryDedupeStore;
import org.j7m.middleware.reliability.ReliabilityMetadataResolver;
import org.j7m.middleware.reliability.ReliabilityPolicyEvaluator;
import org.j7m.middleware.registry.api.DiscoveryEvent;
import org.j7m.middleware.registry.api.DiscoveryStatistics;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryDiagnostics;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.ApplicationContextId;
import org.j7m.middleware.security.api.ResourcePolicyAction;
import org.j7m.middleware.security.api.ResourcePolicyDecision;
import org.j7m.middleware.security.api.ResourcePolicyRequest;
import org.j7m.middleware.security.api.ResourceQuota;
import org.j7m.middleware.security.api.ResourceUsageSnapshot;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.transport.api.ExportDescriptor;
import org.j7m.middleware.transport.api.ExportedObject;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.ProxyReferenceSupport;
import org.j7m.middleware.transport.api.RuntimeTransportContext;
import org.j7m.middleware.transport.api.TransportEndpoint;
import org.j7m.middleware.transport.api.TransportProvider;

/**
 * Default Phase 1 middleware runtime implementation.
 *
 * <p>The runtime is thread-safe. It owns its registry client, transport
 * providers, heartbeat scheduler, exported object descriptors, and invocation
 * log. Public failures are normalized into {@link MiddlewareException}.</p>
 */
public final class DefaultMiddlewareRuntime implements MiddlewareRuntime, RuntimeTransportContext {
    private static final int MAX_ENCODED_SIZE_DEPTH = 16;
    private static final int MAX_ENCODED_SIZE_OBJECTS = 4096;
    private static final int MAX_CONTAINER_ENTRIES = 1024;
    private static final int MAX_CALLBACK_EXPORTS = 1024;
    private static final int MAX_HP_STATS = 4096;
    private static final int MAX_BENCHMARK_CACHE = 4096;
    private static final int BENCHMARK_ACTIVE = 8;
    private static final int BENCHMARK_QUEUED = 32;
    private static final long BENCHMARK_TIMEOUT_MILLIS = 100L;
    private static final Duration HP_STATS_RETENTION = Duration.ofMinutes(5L);
    private static final int ONE_WAY_ACTIVE = 64;
    private static final int ONE_WAY_QUEUED = 1024;
    private static final String RELIABILITY_DEADLINE_EPOCH_MILLIS = InvocationReliabilityOptions.PREFIX + "deadlineEpochMillis";
    private static final int MAX_PREPARE_CLEANUP_SUPPRESSED = 4;
    private final RuntimeConfiguration configuration;
    private final RegistryClient registry;
    private final ValueCodec codec;
    private final SecurityConfiguration security;
    private final ConcurrentMap<String, RuntimeObject> objects;
    private final ConcurrentMap<Object, ObjectRef> localReferences;
    private final ConcurrentMap<String, TransportProvider> transports;
    private final List<TransportStatus> transportStatuses;
    private final ScheduledExecutorService heartbeatExecutor;
    private static final int MAX_PENDING_READINESS_PUBLICATIONS = 1024;
    private final Semaphore readinessPublicationPermits = new Semaphore(MAX_PENDING_READINESS_PUBLICATIONS);
    private final AtomicLong readinessPublicationFailures = new AtomicLong();
    private final AtomicLong readinessPublicationDeferred = new AtomicLong();
    private final AtomicBoolean closed;
    private final AtomicLong generation;
    private final AtomicLong invocationCount;
    private final AtomicLong invocationFailureCount;
    private final AtomicLong authorizationDenyCount;
    private final AtomicLong unauthenticatedCount;
    private final AtomicLong redactionCount;
    private final AtomicLong overloadRejectionCount;
    private final AtomicLong replayRejectionCount;
    private final AtomicLong timestampRejectionCount;
    private final AtomicLong resourcePolicyDenyCount;
    private final AtomicLong outboundLimitRejectionCount;
    private final AtomicLong reliabilityIdempotencyKeyUsageCount;
    private final AtomicLong reliabilityDuplicateDetectionDecisionCount;
    private final AtomicLong reliabilityFencingTokenRejectionCount;
    private final AtomicLong reliabilityRetrySafeDecisionCount;
    private final AtomicLong reliabilityBackpressureDecisionCount;
    private final AtomicLong reliabilityContextPropagationCount;
    private final Semaphore invocationPermits;
    private final ConcurrentMap<String, Long> replayCache;
    private final ReliabilityMetadataResolver reliabilityMetadataResolver;
    private final ReliabilityPolicyEvaluator reliabilityPolicyEvaluator;
    private final InMemoryDedupeStore reliabilityDedupeStore;
    private final HpSelector hpSelector;
    private final ConcurrentMap<String, SlidingWindowStatistics> objectStatistics;
    private final ConcurrentMap<String, BenchmarkResult> benchmarkCache;
    private final ThreadPoolExecutor oneWayExecutor;
    private final ThreadPoolExecutor benchmarkExecutor;
    private final Semaphore benchmarkPermits;
    private final AtomicLong benchmarkCount;
    private final AtomicLong benchmarkFailures;
    private final AtomicLong benchmarkCacheHits;
    private final AtomicLong oneWayRejectedCount;
    private final AtomicLong oneWayFailureCount;
    private final AtomicLong benchmarkLatencyTotal;
    private final Object hpDecisionLock;
    private final Object objectStatisticsLock;
    private final Object benchmarkCacheLock;
    private final Deque<String> recentHpDecisions;
    private final Object invocationLogLock;
    private final Deque<InvocationMetadata> recentInvocations;
    private final Object publicationLock;
    private final List<AutoCloseable> ownedResources;

    /**
     * Creates and starts a runtime from configuration.
     *
     * @param configuration runtime configuration
     */
    public DefaultMiddlewareRuntime(final RuntimeConfiguration configuration) {
        this(configuration, List.of());
    }

    /**
     * Creates and starts a runtime from configuration and additional
     * lifecycle-owned resources.
     *
     * <p>The supplied resources are closed during runtime shutdown before
     * exported objects, executors, transports, and the registry are closed. The
     * kernel treats these resources only as generic {@link AutoCloseable}
     * instances; callers must not rely on kernel knowledge of higher-level
     * resource semantics.</p>
     *
     * @param configuration runtime configuration
     * @param ownedResources additional resources owned by the runtime
     */
    public DefaultMiddlewareRuntime(final RuntimeConfiguration configuration, final List<? extends AutoCloseable> ownedResources) {
        this.configuration = configuration;
        this.ownedResources = ownedResources == null ? List.of() : List.copyOf(ownedResources);
        this.registry = configuration.registry();
        this.codec = configuration.codec();
        this.security = configuration.security();
        this.objects = new ConcurrentHashMap<>();
        this.localReferences = new ConcurrentHashMap<>();
        this.transports = new ConcurrentHashMap<>();
        this.transportStatuses = new ArrayList<>();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("j7m-heartbeat-", 0L).factory());
        this.closed = new AtomicBoolean(false);
        this.generation = new AtomicLong(1L);
        this.invocationCount = new AtomicLong();
        this.invocationFailureCount = new AtomicLong();
        this.authorizationDenyCount = new AtomicLong();
        this.unauthenticatedCount = new AtomicLong();
        this.redactionCount = new AtomicLong();
        this.overloadRejectionCount = new AtomicLong();
        this.replayRejectionCount = new AtomicLong();
        this.timestampRejectionCount = new AtomicLong();
        this.resourcePolicyDenyCount = new AtomicLong();
        this.outboundLimitRejectionCount = new AtomicLong();
        this.reliabilityIdempotencyKeyUsageCount = new AtomicLong();
        this.reliabilityDuplicateDetectionDecisionCount = new AtomicLong();
        this.reliabilityFencingTokenRejectionCount = new AtomicLong();
        this.reliabilityRetrySafeDecisionCount = new AtomicLong();
        this.reliabilityBackpressureDecisionCount = new AtomicLong();
        this.reliabilityContextPropagationCount = new AtomicLong();
        this.invocationPermits = new Semaphore(this.security.maxConcurrentInvocations());
        this.replayCache = new ConcurrentHashMap<>();
        this.reliabilityMetadataResolver = new ReliabilityMetadataResolver();
        this.reliabilityPolicyEvaluator = new ReliabilityPolicyEvaluator();
        this.reliabilityDedupeStore = new InMemoryDedupeStore();
        this.hpSelector = new HpSelector();
        this.objectStatistics = new ConcurrentHashMap<>();
        this.benchmarkCache = new ConcurrentHashMap<>();
        this.oneWayExecutor = new ThreadPoolExecutor(ONE_WAY_ACTIVE, ONE_WAY_ACTIVE, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(ONE_WAY_QUEUED), work -> {
                    Thread thread = Thread.ofVirtual().inheritInheritableThreadLocals(false)
                            .name("j7m-one-way").unstarted(work);
                    thread.setContextClassLoader(DefaultMiddlewareRuntime.class.getClassLoader());
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.oneWayExecutor.allowCoreThreadTimeOut(true);
        this.benchmarkExecutor = new ThreadPoolExecutor(BENCHMARK_ACTIVE, BENCHMARK_ACTIVE, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(BENCHMARK_QUEUED), Thread.ofVirtual().name("j7m-benchmark-", 0L).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.benchmarkExecutor.allowCoreThreadTimeOut(true);
        this.benchmarkPermits = new Semaphore(BENCHMARK_ACTIVE + BENCHMARK_QUEUED);
        this.benchmarkCount = new AtomicLong();
        this.benchmarkFailures = new AtomicLong();
        this.benchmarkCacheHits = new AtomicLong();
        this.oneWayRejectedCount = new AtomicLong();
        this.oneWayFailureCount = new AtomicLong();
        this.benchmarkLatencyTotal = new AtomicLong();
        this.hpDecisionLock = new Object();
        this.objectStatisticsLock = new Object();
        this.benchmarkCacheLock = new Object();
        this.recentHpDecisions = new ArrayDeque<>(128);
        this.invocationLogLock = new Object();
        this.recentInvocations = new ArrayDeque<>(128);
        this.publicationLock = new Object();
        if (this.security.credentialProvider() != null) {
            this.security.credentialProvider().addRotationListener(snapshot -> this.security.auditSink().record(
                    new AuditEvent(this.configuration.clock().instant(), this.identity().runtimeId(), "system",
                            "credential-rotated", "runtime/" + this.identity().runtimeId(), "allow",
                            this.security.policy().id(), "", "", "", "", "", this.identity().registryContext(),
                            Map.of("credentialVersion", snapshot.credential().version()))));
        }
        for (TransportProvider provider : configuration.transports()) {
            TransportEndpoint endpoint = provider.start(this);
            this.transports.put(provider.id(), provider);
            this.transportStatuses.add(new TransportStatus(provider.id(), endpoint.endpoint(), true));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RuntimeIdentity identity() {
        return this.configuration.identity();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValueCodec codec() {
        return this.codec;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> ObjectRegistration<T> export(final String serviceName, final T instance, final Class<T> primaryInterface, final ExportOptions options) {
        PreparedObjectRegistration<T> prepared = this.prepareExport(serviceName, instance, primaryInterface, options);
        boolean committed = false;
        try {
            ObjectRegistration<T> registration = prepared.commit();
            committed = true;
            return registration;
        } finally {
            if (!committed) {
                prepared.close();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> PreparedObjectRegistration<T> prepareExport(
            final String serviceName,
            final T instance,
            final Class<T> primaryInterface,
            final ExportOptions options) {
        this.ensureOpen();
        ExportOptions safeOptions = options == null ? ExportOptions.defaults() : options;
        this.validateExport(serviceName, instance, primaryInterface);
        this.enforceResourcePolicy(this.currentSecurityContext(), ResourcePolicyAction.EXPORT, serviceName, "", "",
                new ResourceUsageSnapshot(this.activeInvocationCount(), this.objects.size(), this.callbackExportCount(), 0, 0), safeOptions.metadata());
        this.authorize(this.currentSecurityContext(), PermissionAction.EXPORT, serviceName, serviceName, safeOptions.registryContext(), "", safeOptions.transportId(), "", safeOptions.metadata());
        String transportId = safeOptions.localOnly() && "local".equals(safeOptions.transportId()) ? this.defaultTransportId() : safeOptions.transportId();
        TransportProvider provider = this.transports.get(transportId);
        if (provider == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.TRANSPORT_ERROR, "transport provider is not available: " + transportId);
        }
        long nowMillis = this.configuration.clock().millis();
        long leaseUntil = safeOptions.localOnly() ? 0L : nowMillis + Math.max(1L, safeOptions.leaseDuration().toMillis());
        long exportGeneration = this.generation.getAndIncrement();
        List<String> interfaces = this.exposedInterfaces(instance, primaryInterface, safeOptions);
        String objectId = this.identity().runtimeId() + "-" + UUID.randomUUID();
        String logicalName = serviceName + "/" + objectId;
        String endpoint = this.endpointFor(transportId);
        Map<String, String> exportMetadata = this.exportMetadata(primaryInterface, safeOptions.metadata(), exportGeneration);
        ObjectRef ref = new ObjectRef(objectId, logicalName, serviceName, this.identity().runtimeId(), safeOptions.registryContext(),
                interfaces, transportId, endpoint, leaseUntil, exportGeneration, exportMetadata);
        ExportDescriptor descriptor = this.exportDescriptor(primaryInterface, exportMetadata);
        RuntimeObject runtimeObject = new RuntimeObject(ref, instance, primaryInterface, descriptor, provider, safeOptions);
        PreparedExportState preparation = new PreparedExportState(runtimeObject);
        try {
            this.invokeLifecycle(instance, Initialize.class);
            preparation.lifecycleInitialized = true;
            runtimeObject.objectState = ObjectState.INITIALIZED;
            this.invokeLifecycle(instance, Startup.class);
            preparation.lifecycleStarted = true;
            preparation.providerExportAttempted = true;
            ExportedObject exportedObject = provider.export(ref, instance, descriptor);
            preparation.providerExportSucceeded = true;
            runtimeObject.exportedObject = exportedObject;
            return new PreparedRuntimeObjectRegistration<>(runtimeObject, this, nowMillis);
        } catch (MiddlewareException exception) {
            this.rollbackFailedPreparation(preparation, exception);
            throw exception;
        } catch (RuntimeException exception) {
            MiddlewareException normalized = new MiddlewareException(
                    new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to prepare object export", Map.of(), null, null, false, true),
                    exception);
            this.rollbackFailedPreparation(preparation, normalized);
            throw normalized;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ObjectRegistration<?>> commitPreparedExports(final List<? extends PreparedObjectRegistration<?>> preparedExports) {
        this.ensureOpen();
        if (preparedExports == null || preparedExports.isEmpty()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export group is required");
        }
        List<PreparedRuntimeObjectRegistration<?>> prepared = new ArrayList<>(preparedExports.size());
        for (PreparedObjectRegistration<?> registration : preparedExports) {
            if (!(registration instanceof PreparedRuntimeObjectRegistration<?> preparedRegistration)
                    || preparedRegistration.runtime != this) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export belongs to another runtime");
            }
            prepared.add(preparedRegistration);
        }
        synchronized (this.publicationLock) {
            this.validatePreparedGroup(prepared);
            for (PreparedRuntimeObjectRegistration<?> registration : prepared) {
                synchronized (registration.runtimeObject) { registration.runtimeObject.codecSelectionFrozen = true; }
            }
            List<RuntimeObject> runtimeObjects = new ArrayList<>(prepared.size());
            List<ObjectRef> boundRefs = new ArrayList<>(prepared.size());
            List<RuntimeObject> heartbeatStarted = new ArrayList<>(prepared.size());
            try {
                for (PreparedRuntimeObjectRegistration<?> preparedRegistration : prepared) {
                    RuntimeObject runtimeObject = preparedRegistration.runtimeObject;
                    runtimeObject.registrationState = runtimeObject.options.localOnly() ? RegistrationState.LOCAL : RegistrationState.LOCAL_AND_GLOBAL;
                    this.objects.put(runtimeObject.ref.objectId(), runtimeObject);
                    this.localReferences.put(runtimeObject.instance, runtimeObject.ref);
                    runtimeObjects.add(runtimeObject);
                }
                for (PreparedRuntimeObjectRegistration<?> preparedRegistration : prepared) {
                    RuntimeObject runtimeObject = preparedRegistration.runtimeObject;
                    if (!runtimeObject.options.localOnly()) {
                        this.registry.bind(new RegistryRecord(runtimeObject.ref, runtimeObject.ref.metadata(),
                                preparedRegistration.preparedAtMillis, this.configuration.clock().millis(),
                                runtimeObject.ref.leaseUntil(), ObjectState.EXECUTED));
                        boundRefs.add(runtimeObject.ref);
                    }
                }
                for (RuntimeObject runtimeObject : runtimeObjects) {
                    if (!runtimeObject.options.localOnly()) {
                        this.startHeartbeat(runtimeObject);
                        heartbeatStarted.add(runtimeObject);
                    }
                }
                List<ObjectRegistration<?>> committed = new ArrayList<>(prepared.size());
                for (PreparedRuntimeObjectRegistration<?> preparedRegistration : prepared) {
                    RuntimeObject runtimeObject = preparedRegistration.runtimeObject;
                    runtimeObject.objectState = ObjectState.EXECUTED;
                    ObjectRegistration<?> registration = new RuntimeObjectRegistration<>(runtimeObject, this);
                    preparedRegistration.markCommitted(registration);
                    committed.add(registration);
                }
                return List.copyOf(committed);
            } catch (RuntimeException exception) {
                this.rollbackPreparedPublication(runtimeObjects, heartbeatStarted, boundRefs, exception);
                throw this.normalizedCommitFailure(exception);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T lookup(final String serviceName, final Class<T> api, final LookupOptions options) {
        this.ensureOpen();
        LookupOptions safeOptions = options == null ? LookupOptions.defaults() : options;
        if (serviceName == null || serviceName.isBlank() || api == null || !api.isInterface()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "service name and interface are required");
        }
        this.authorize(this.currentSecurityContext(), PermissionAction.LOOKUP, serviceName, serviceName, safeOptions.registryContext(), "", "", "", safeOptions.requiredParams());
        Optional<T> local = this.findLocal(serviceName, api, safeOptions);
        if (local.isPresent() && safeOptions.preferLocal() && !safeOptions.highAvailability()) {
            return local.get();
        }
        if (safeOptions.highAvailability()) {
            return this.createHaProxy(serviceName, api, safeOptions);
        }
        long selectionDeadlineNanos = this.selectionDeadlineNanos(safeOptions);
        List<ObjectRef> refs = this.list(serviceName, List.of(api.getName()), safeOptions.requiredParams(), safeOptions);
        if (refs.isEmpty()) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "service not found");
        }
        ObjectRef selected = this.selectPreferred(refs, selectionDeadlineNanos);
        return this.proxyFor(selected, api);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ObjectRef> list(final String serviceName, final List<String> interfaces, final Map<String, String> requiredParams, final LookupOptions options) {
        this.ensureOpen();
        LookupOptions safeOptions = options == null ? LookupOptions.defaults() : options;
        this.authorize(this.currentSecurityContext(), PermissionAction.LIST, serviceName, serviceName, safeOptions.registryContext(), "", "", "", requiredParams);
        List<String> requiredInterfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        Map<String, String> params = requiredParams == null ? Map.of() : Map.copyOf(requiredParams);
        List<ObjectRef> refs = new ArrayList<>();
        long nowMillis = this.configuration.clock().millis();
        long deadlineMillis = nowMillis + Math.max(1L, safeOptions.deadline().toMillis());
        synchronized (this.publicationLock) {
            for (RuntimeObject runtimeObject : this.objects.values()) {
                ObjectRef ref = runtimeObject.ref;
                if (runtimeObject.objectState == ObjectState.EXECUTED
                        && ref.serviceName().equals(serviceName)
                        && ref.registryContext().equals(safeOptions.registryContext())
                        && ref.interfaces().containsAll(requiredInterfaces)
                        && ref.metadata().entrySet().containsAll(params.entrySet())
                        && this.eligibleReference(ref, nowMillis)
                        && ref.isLeaseActive(nowMillis)) {
                    refs.add(ref);
                }
            }
        }
        RegistryQuery query = new RegistryQuery(serviceName, requiredInterfaces, params, safeOptions.registryContext(), nowMillis, 1024,
                deadlineMillis, 32, 3, org.j7m.middleware.registry.api.DiscoverySource.UNKNOWN);
        for (RegistryRecord record : this.registry.list(query)) {
            if (this.eligibleRegistryRecord(record, serviceName, requiredInterfaces, params, safeOptions.registryContext(), nowMillis)
                    && refs.stream().noneMatch(existing -> existing.objectId().equals(record.ref().objectId()))) {
                refs.add(record.ref());
            }
        }
        refs.sort(Comparator.comparing((ObjectRef ref) -> ref.runtimeId().equals(this.identity().runtimeId()) ? 0 : 1)
                .thenComparing(ObjectRef::runtimeId)
                .thenComparing(ObjectRef::objectId));
        return List.copyOf(refs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RuntimeStatus status() {
        this.authorize(this.currentSecurityContext(), PermissionAction.READ_STATUS, this.identity().runtimeId(), "", this.identity().registryContext(), "", "", "", Map.of());
        List<InstanceStatus> instanceStatuses = new ArrayList<>();
        List<ObjectRef> registryRefs = new ArrayList<>();
        synchronized (this.publicationLock) {
            for (RuntimeObject runtimeObject : this.objects.values()) {
                instanceStatuses.add(new InstanceStatus(runtimeObject.ref, runtimeObject.objectState, runtimeObject.registrationState));
            }
        }
        long statusNowMillis = this.configuration.clock().millis();
        long statusDeadlineMillis = statusNowMillis + 5000L;
        for (RegistryRecord record : this.registry.list(new RegistryQuery("", List.of(), Map.of(), this.identity().registryContext(), statusNowMillis,
                4096, statusDeadlineMillis, 32, 3, org.j7m.middleware.registry.api.DiscoverySource.UNKNOWN))) {
            registryRefs.add(record.ref());
        }
        List<InvocationMetadata> invocations;
        synchronized (this.invocationLogLock) {
            invocations = List.copyOf(this.recentInvocations);
        }
        List<RegistryPeerStatus> peers = this.registry.peers(new PeerQuery(this.identity().registryContext(), true, 4096));
        RegistryStatus registryStatus = this.registryStatus(registryRefs.size(), peers);
        return new RuntimeStatus(this.identity(), instanceStatuses, registryRefs, List.copyOf(this.transportStatuses),
                registryStatus,
                this.invocationCount.get(), this.invocationFailureCount.get(), invocations, this.securityMetadata());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException primaryFailure = null;
        for (AutoCloseable resource : this.ownedResources) {
            try {
                resource.close();
            } catch (RuntimeException exception) {
                primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
            } catch (Exception exception) {
                primaryFailure = this.recordCleanupFailure(primaryFailure,
                        new RuntimeException("owned runtime resource close failed", exception));
            }
        }
        for (RuntimeObject runtimeObject : this.objects.values()) {
            try {
                this.closeObject(runtimeObject, false);
            } catch (RuntimeException exception) {
                primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
            }
        }
        this.objects.clear();
        this.localReferences.clear();
        try {
            this.heartbeatExecutor.shutdownNow();
        } catch (RuntimeException exception) {
            primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
        }
        try {
            this.oneWayExecutor.shutdownNow();
        } catch (RuntimeException exception) {
            primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
        }
        try {
            this.benchmarkExecutor.shutdownNow();
        } catch (RuntimeException exception) {
            primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
        }
        for (TransportProvider provider : this.transports.values()) {
            try {
                provider.close();
            } catch (RuntimeException exception) {
                primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
            }
        }
        try {
            this.registry.close();
        } catch (RuntimeException exception) {
            primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
        }
        if (primaryFailure != null) {
            if (this.containsInterrupted(primaryFailure)) {
                Thread.currentThread().interrupt();
            }
            if (primaryFailure instanceof MiddlewareException middlewareException) {
                throw middlewareException;
            }
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "runtime close failed",
                    Map.of(), null, this.identity().runtimeId(), false, true), primaryFailure);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EncodedValue encodeValue(final Object value, final Class<?> declaredType) {
        this.rejectLocalInvocationCapability(value, declaredType);
        return this.codec.encode(value, declaredType, new RuntimeCodecContext());
    }

    private void rejectLocalInvocationCapability(final Object value, final Class<?> declaredType) {
        if (value instanceof org.j7m.middleware.application.api.InvocationControl
                || value instanceof InvocationCompletionScope || value instanceof InvocationCompletionFactory
                || declaredType != null && (org.j7m.middleware.application.api.InvocationControl.class.isAssignableFrom(declaredType)
                    || InvocationCompletionScope.class.isAssignableFrom(declaredType)
                    || InvocationCompletionFactory.class.isAssignableFrom(declaredType))) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "Invocation control is host-local and cannot cross a transport");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object decodeValue(final EncodedValue value, final Class<?> expectedType) {
        return this.codec.decode(value, expectedType, new RuntimeCodecContext());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EncodedValue dispatch(final ObjectRef target, final MethodDescriptor method, final List<EncodedValue> arguments, final InvocationOptions options) {
        return this.dispatchInternal(target, method, arguments, options, null, false, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EncodedValue dispatchTrusted(
            final ObjectRef target,
            final MethodDescriptor method,
            final List<EncodedValue> arguments,
            final InvocationOptions options,
            final SecurityContext trustedContext) {
        return this.dispatchInternal(target, method, arguments, options, trustedContext, false, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EncodedValue dispatchLogicalAsyncTrusted(final ObjectRef target, final MethodDescriptor method,
            final List<EncodedValue> arguments, final InvocationOptions options, final SecurityContext trustedContext,
            final InvocationCancellation cancellation) {
        return this.dispatchInternal(target, method, arguments, options, trustedContext, true, cancellation);
    }

    private EncodedValue dispatchInternal(
            final ObjectRef target,
            final MethodDescriptor method,
            final List<EncodedValue> arguments,
            final InvocationOptions options,
            final SecurityContext trustedContext, final boolean logicalAsync, final InvocationCancellation cancellation) {
        this.ensureOpen();
        long startedAtMillis = this.configuration.clock().millis();
        long startedNanos = System.nanoTime();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        InvocationReliabilityOptions reliabilityOptions = safeOptions.reliabilityOptions();
        this.recordReliabilityMetadataUsage(reliabilityOptions);
        if (target == null || method == null) {
            throw MiddlewareException.remote(MiddlewareErrorCode.INVALID_ARGUMENT, "target and method are required",
                    safeOptions.invocationId(), this.identity().runtimeId(), false, false);
        }
        SecurityContext incomingContext = this.incomingSecurityContext(safeOptions, target.runtimeId(), trustedContext);
        this.rejectExpiredOrCancelledBeforeDecode(safeOptions, startedAtMillis);
        this.validateReplayAndTimestamp(incomingContext, safeOptions, target, method);
        int inboundBytes = this.estimateEncodedBytes(arguments);
        if (inboundBytes > this.security.maxInboundMessageBytes()) {
            throw MiddlewareException.remote(MiddlewareErrorCode.INVALID_ARGUMENT, "inbound invocation payload exceeds configured limit",
                    safeOptions.invocationId(), this.identity().runtimeId(), false, false);
        }
        if (!this.invocationPermits.tryAcquire()) {
            this.overloadRejectionCount.incrementAndGet();
            this.reliabilityBackpressureDecisionCount.incrementAndGet();
            this.auditSecurityRejection(incomingContext, PermissionAction.INVOKE, target, method, "runtime-invocation-limit");
            throw MiddlewareException.remote(MiddlewareErrorCode.OVERLOADED, "runtime invocation limit exceeded",
                    safeOptions.invocationId(), this.identity().runtimeId(), true, true);
        }
        InvocationCompletionScope lifetime = null;
        AutoCloseable cancellationListener = null;
        boolean rootAttempted = false;
        boolean successful = false;
        try (var trace = org.j7m.middleware.application.api.InvocationTraceContext.open(safeOptions.metadata())) {
            RuntimeObject runtimeObject = this.validateInboundTarget(target, method.declaringInterfaceName(), safeOptions.invocationId());
            if (!runtimeObject.ready()) {
                throw MiddlewareException.remote(MiddlewareErrorCode.UNAVAILABLE, "target readiness is unavailable",
                        safeOptions.invocationId(), this.identity().runtimeId(), true, true);
            }
            this.enforceResourcePolicy(incomingContext, ResourcePolicyAction.INVOKE, runtimeObject.ref.serviceName(), runtimeObject.ref.objectId(),
                    method.toString(), new ResourceUsageSnapshot(this.activeInvocationCount(), this.objects.size(), this.callbackExportCount(), inboundBytes, 0), Map.of());
            Method javaMethod = this.resolveMethod(runtimeObject, method, safeOptions.invocationId());
            ReliabilityDecision reliabilityDecision = this.reliabilityPolicyEvaluator.evaluateBeforeDispatch(javaMethod, reliabilityOptions, inboundBytes);
            this.enforceReliabilityDecision(reliabilityDecision, safeOptions);
            this.enforceDuplicateDetection(reliabilityOptions, safeOptions);
            this.authorizeInvocation(incomingContext, runtimeObject, javaMethod, method, safeOptions);
            Class<?>[] parameterTypes = javaMethod.getParameterTypes();
            if (parameterTypes.length != arguments.size()) {
                throw MiddlewareException.remote(MiddlewareErrorCode.INVALID_ARGUMENT, "argument count mismatch",
                        safeOptions.invocationId(), this.identity().runtimeId(), false, false);
            }
            AsyncMethodDescriptor asyncShape = logicalAsync ? AsyncMethodDescriptor.fromMethod(javaMethod) : null;
            if (!logicalAsync && java.util.concurrent.CompletionStage.class.isAssignableFrom(javaMethod.getReturnType())) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Async results require the logical async protocol");
            }
            InvocationCompletionFactory factory = runtimeObject.completionFactory;
            if (logicalAsync && factory == null) {
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Async export requires trusted physical ownership");
            }
            if (factory != null) {
                lifetime = factory.open(new InvocationBoundary(target, method,
                        this.admittedBudget(runtimeObject.primaryInterface, javaMethod, safeOptions, startedNanos), incomingContext, inboundBytes));
                if (lifetime == null) throw new IllegalStateException("Invocation factory returned no lifetime");
                if (cancellation != null) cancellationListener = cancellation.listen(lifetime::requestCancellation);
            }
            InvocationCompletionScope owned = lifetime;
            ValueCodec exportCodec = runtimeObject.valueCodec == null ? this.codec : runtimeObject.valueCodec;
            java.util.concurrent.Callable<Object> entry = () -> {
                Object[] decodedArguments = new Object[arguments.size()];
                for (int index = 0; index < arguments.size(); index++) {
                    decodedArguments[index] = exportCodec.decode(arguments.get(index), parameterTypes[index], new RuntimeCodecContext());
                }
                return this.invokeWithSecurityContext(incomingContext, javaMethod, runtimeObject.instance, decodedArguments);
            };
            rootAttempted = true;
            Object result;
            boolean rootFailed = false;
            try { result = owned == null ? entry.call() : owned.call(entry); }
            catch (Exception failure) {
                if (owned == null || !owned.supportsResultFinalizers()) throw failure;
                // The host mirror must first settle required result/transaction finalizers.
                result = null; rootFailed = true;
            }
            Class<?> resultType = logicalAsync ? asyncShape.payloadType() : javaMethod.getReturnType();
            if (logicalAsync && !rootFailed && !(result instanceof java.util.concurrent.CompletionStage<?>)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INTERNAL, "Async target returned no logical result stage");
            }
            if (owned != null && (logicalAsync || owned.supportsResultFinalizers())) {
                try { result = owned.logicalResult().toCompletableFuture().join(); }
                catch (java.util.concurrent.CancellationException cancelled) {
                    throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.CANCELLED,
                            "Async result cancelled", Map.of("outcome", "UNKNOWN"), safeOptions.invocationId(),
                            this.identity().runtimeId(), false, false));
                } catch (java.util.concurrent.CompletionException failed) {
                    if (failed.getCause() instanceof java.util.concurrent.CancellationException) {
                        throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.CANCELLED,
                                "Async result cancelled", Map.of("outcome", "UNKNOWN"), safeOptions.invocationId(),
                                this.identity().runtimeId(), false, false));
                    }
                    if (failed.getCause() instanceof InvocationTargetException invocation) throw invocation;
                    if (failed.getCause() instanceof MiddlewareException middleware) throw middleware;
                    throw new InvocationTargetException(failed.getCause());
                }
            }
            Object logicalResult = result;
            Class<?> logicalType = resultType;
            this.rejectLocalInvocationCapability(logicalResult, logicalType);
            EncodedValue encoded = owned == null ? exportCodec.encode(logicalResult, logicalType, new RuntimeCodecContext())
                    : owned.continueInContext(() -> exportCodec.encode(logicalResult, logicalType, new RuntimeCodecContext())).toCompletableFuture().join();
            int outboundBytes = this.estimateEncodedBytes(encoded);
            ReliabilityDecision resultReliabilityDecision = this.reliabilityPolicyEvaluator.evaluateResult(reliabilityOptions, outboundBytes);
            this.enforceReliabilityDecision(resultReliabilityDecision, safeOptions);
            if (outboundBytes > this.security.maxOutboundMessageBytes()) {
                this.outboundLimitRejectionCount.incrementAndGet();
                this.auditSecurityRejection(incomingContext, PermissionAction.INVOKE, target, method, "outbound-payload-limit");
                throw MiddlewareException.remote(MiddlewareErrorCode.OVERLOADED, "outbound invocation payload exceeds configured limit",
                        safeOptions.invocationId(), this.identity().runtimeId(), true, false);
            }
            this.recordInvocation(safeOptions, target, method.methodName(), startedAtMillis, startedNanos, true, null);
            successful = true;
            return encoded;
        } catch (InvocationTargetException exception) {
            Throwable targetException = exception.getTargetException();
            if (targetException instanceof MiddlewareException middlewareException) {
                this.recordInvocation(safeOptions, target, method.methodName(), startedAtMillis, startedNanos, false, middlewareException.code());
                throw middlewareException;
            }
            this.recordInvocation(safeOptions, target, method.methodName(), startedAtMillis, startedNanos, false, MiddlewareErrorCode.REMOTE_EXCEPTION);
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.REMOTE_EXCEPTION,
                    "remote method failed",
                    Map.of(), safeOptions.invocationId(), this.identity().runtimeId(), false, false), targetException);
        } catch (MiddlewareException exception) {
            this.recordInvocation(safeOptions, target, method.methodName(), startedAtMillis, startedNanos, false, exception.code());
            throw exception;
        } catch (Exception exception) {
            this.recordInvocation(safeOptions, target, method.methodName(), startedAtMillis, startedNanos, false, MiddlewareErrorCode.INTERNAL);
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "dispatch failed", Map.of(),
                    safeOptions.invocationId(), this.identity().runtimeId(), false, true), exception);
        } finally {
            if (lifetime == null) {
                this.invocationPermits.release();
            } else {
                InvocationCompletionScope owned = lifetime;
                AutoCloseable listener = cancellationListener;
                try {
                    if (!rootAttempted) owned.abortBeforeTarget();
                    owned.close();
                }
                finally {
                    // Reply completion never substitutes for actual application/codec cleanup.
                    owned.physicalCompletion().whenComplete((ignored, failure) -> {
                        try { if (listener != null) listener.close(); }
                        catch (Exception detachFailure) {
                            System.getLogger(DefaultMiddlewareRuntime.class.getName()).log(System.Logger.Level.DEBUG,
                                    "Invocation cancellation listener detach failed: " + detachFailure.getClass().getName());
                        }
                        finally { this.invocationPermits.release(); }
                    });
                    if (successful) owned.physicalCompletion().toCompletableFuture().join();
                }
            }
        }
    }

    private InvocationOptions admittedBudget(final Class<?> contract, final Method method,
            final InvocationOptions options, final long startedNanos) {
        long budget = Math.min(options.deadline().toNanos(), Duration.ofDays(1).toNanos());
        Long annotation = this.methodDeadline(contract, method);
        if (annotation != null) budget = Math.min(budget, Duration.ofMillis(Math.min(86400000L, annotation)).toNanos());
        long epoch = parsePositiveLong(options.metadata().get(RELIABILITY_DEADLINE_EPOCH_MILLIS));
        if (epoch > 0) budget = Math.min(budget, Duration.ofMillis(Math.max(0, epoch - this.configuration.clock().millis())).toNanos());
        budget -= Math.max(0, System.nanoTime() - startedNanos);
        if (budget <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Invocation admission budget expired");
        return new InvocationOptions(options.invocationId(), Duration.ofNanos(budget), options.metadata());
    }

    private Long methodDeadline(final Class<?> contract, final Method method) {
        Long value = null;
        for (Method declaration : contract.getDeclaredMethods()) {
            if (declaration.getName().equals(method.getName()) && java.util.Arrays.equals(declaration.getParameterTypes(), method.getParameterTypes())) {
                var annotation = declaration.getAnnotation(org.j7m.middleware.annotations.Deadline.class);
                if (annotation != null) value = annotation.millis();
            }
        }
        for (Class<?> parent : contract.getInterfaces()) {
            Long inherited = this.methodDeadline(parent, method);
            if (inherited != null) {
                if (value != null && !value.equals(inherited)) throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Conflicting method deadlines");
                value = inherited;
            }
        }
        if (value != null && value <= 0) throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Invalid method deadline");
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectRef exportCallback(final Object value, final Class<?> declaredType) {
        this.ensureOpen();
        this.rejectLocalInvocationCapability(value, declaredType);
        if (value == null || declaredType == null || !declaredType.isInterface()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "callback value must implement an interface");
        }
        ObjectRef existing = this.localReferences.get(value);
        if (existing != null) {
            return existing;
        }
        if (this.callbackExportCount() >= MAX_CALLBACK_EXPORTS) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "callback export limit exceeded");
        }
        this.enforceResourcePolicy(this.currentSecurityContext(), ResourcePolicyAction.CALLBACK_EXPORT, "__callback/" + declaredType.getName(), "",
                "", new ResourceUsageSnapshot(this.activeInvocationCount(), this.objects.size(), this.callbackExportCount(), 0, 0),
                Map.of("callbackType", declaredType.getName()));
        @SuppressWarnings("unchecked")
        Class<Object> callbackType = (Class<Object>) declaredType;
        Object castValue = callbackType.cast(value);
        ExportOptions options = new ExportOptions(true, this.defaultTransportId(), this.identity().registryContext(), List.of(declaredType.getName()),
                Map.of("callback", "true", "callback.auto", "true"), Duration.ZERO, Duration.ZERO);
        return this.export("__callback/" + declaredType.getName(), castValue, callbackType, options).ref();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T proxyFor(final ObjectRef ref, final Class<T> api) {
        return this.proxyFor(ref, api, ProxyOptions.defaults());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T reference(final ObjectRef reference, final Class<T> contract, final ProxyOptions options) {
        this.ensureOpen();
        if (reference == null || contract == null || !contract.isInterface() || options == null
                || options.invocationDeadline().isNegative() || options.invocationDeadline().isZero()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Invalid exact-reference connection");
        }
        if (!reference.interfaces().contains(contract.getName())) {
            throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "Exact reference does not expose requested contract");
        }
        if (reference.generation() <= 0 || !reference.isLeaseActive(System.currentTimeMillis())) {
            throw MiddlewareException.of(MiddlewareErrorCode.LEASE_EXPIRED, "Exact reference is not live");
        }
        return this.proxyFor(reference, contract, options);
    }

    private <T> T proxyFor(final ObjectRef ref, final Class<T> api, final ProxyOptions options) {
        TransportProvider provider = this.transports.get(ref.transport());
        if (provider == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.TRANSPORT_ERROR, "transport provider is not available: " + ref.transport());
        }
        return provider.createProxy(ref, api, options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateTarget(final ObjectRef target, final String requiredInterface) {
        this.validateInboundTarget(target, requiredInterface == null ? "" : requiredInterface, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> describeTarget(final ObjectRef target) {
        RuntimeObject runtimeObject = this.validateInboundTarget(target, "", null);
        return runtimeObject.ref.interfaces();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseInvocationCallbacks(final List<EncodedValue> encodedArguments) {
        if (encodedArguments == null || encodedArguments.isEmpty()) {
            return;
        }
        Set<String> callbackObjectIds = new HashSet<>();
        for (EncodedValue argument : encodedArguments) {
            this.collectCallbackObjectIds(argument, callbackObjectIds, 0);
        }
        for (String objectId : callbackObjectIds) {
            RuntimeObject runtimeObject = this.objects.get(objectId);
            if (runtimeObject != null && "true".equals(runtimeObject.ref.metadata().get("callback.auto"))) {
                this.closeObject(runtimeObject, false);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void executeOneWay(final Runnable task) {
        this.ensureOpen();
        if (task == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "one-way task is required");
        }
        SecurityContext capturedContext = SecurityContexts.current();
        try {
            this.oneWayExecutor.execute(() -> {
                try {
                    SecurityContexts.run(capturedContext, task);
                } catch (RuntimeException exception) {
                    this.oneWayFailureCount.incrementAndGet();
                }
            });
        } catch (RejectedExecutionException exception) {
            this.oneWayRejectedCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.OVERLOADED, "one-way invocation limit exceeded",
                    Map.of(), null, this.identity().runtimeId(), true, true), exception);
        }
    }

    private void rejectExpiredOrCancelledBeforeDecode(final InvocationOptions options, final long nowMillis) {
        if (options.deadline().isNegative() || options.deadline().isZero()) {
            throw MiddlewareException.remote(MiddlewareErrorCode.DEADLINE_EXCEEDED, "invocation deadline has already expired",
                    options.invocationId(), this.identity().runtimeId(), false, true);
        }
        InvocationReliabilityOptions reliabilityOptions = options.reliabilityOptions();
        if (reliabilityOptions.cancellationRequested()) {
            throw MiddlewareException.remote(MiddlewareErrorCode.CANCELLED, "invocation was cancelled before dispatch",
                    options.invocationId(), this.identity().runtimeId(), false, true);
        }
        long deadlineMillis = parsePositiveLong(options.metadata().get(RELIABILITY_DEADLINE_EPOCH_MILLIS));
        if (deadlineMillis > 0L && deadlineMillis <= nowMillis) {
            throw MiddlewareException.remote(MiddlewareErrorCode.DEADLINE_EXCEEDED, "invocation deadline has already expired",
                    options.invocationId(), this.identity().runtimeId(), false, true);
        }
    }

    private SecurityContext incomingSecurityContext(
            final InvocationOptions options,
            final String fallbackRuntimeId,
            final SecurityContext trustedContext) {
        if (trustedContext != null && trustedContext.authenticated()) {
            return SecurityContext.fromTrustedBoundary(trustedContext, options);
        }
        return trustedContext == null
                ? SecurityContext.fromInvocationOptions(options, fallbackRuntimeId)
                : SecurityContext.fromTrustedBoundary(trustedContext, options);
    }

    private void validateExport(final String serviceName, final Object instance, final Class<?> primaryInterface) {
        if (serviceName == null || serviceName.isBlank()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "service name is required");
        }
        if (instance == null || primaryInterface == null || !primaryInterface.isInterface()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "instance and primary interface are required");
        }
        if (!primaryInterface.isInstance(instance)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "instance does not implement primary interface");
        }
    }

    private RuntimeObject validateInboundTarget(
            final ObjectRef target,
            final String requiredInterface,
            final String invocationId) {
        if (target == null) {
            throw MiddlewareException.remote(MiddlewareErrorCode.INVALID_ARGUMENT, "target reference is required",
                    invocationId, this.identity().runtimeId(), false, false);
        }
        RuntimeObject runtimeObject = this.objects.get(target.objectId());
        if (runtimeObject == null || runtimeObject.objectState != ObjectState.EXECUTED) {
            throw MiddlewareException.remote(MiddlewareErrorCode.NOT_FOUND, "target object is not available",
                    invocationId, this.identity().runtimeId(), true, true);
        }
        ObjectRef authoritative = runtimeObject.ref;
        long nowMillis = this.configuration.clock().millis();
        if (!this.identity().runtimeId().equals(target.runtimeId())
                || !authoritative.runtimeId().equals(target.runtimeId())
                || !authoritative.objectId().equals(target.objectId())
                || authoritative.generation() != target.generation()
                || !authoritative.registryContext().equals(target.registryContext())
                || !authoritative.serviceName().equals(target.serviceName())
                || !authoritative.transport().equals(target.transport())
                || !authoritative.endpoint().equals(target.endpoint())
                || !authoritative.isLeaseActive(nowMillis)
                || runtimeObject.registrationState == RegistrationState.NONE) {
            throw MiddlewareException.remote(MiddlewareErrorCode.NOT_FOUND, "target reference is not authoritative",
                    invocationId, this.identity().runtimeId(), true, true);
        }
        if (requiredInterface != null && !requiredInterface.isBlank() && !authoritative.interfaces().contains(requiredInterface)) {
            throw MiddlewareException.remote(MiddlewareErrorCode.INTERFACE_MISMATCH, "target does not expose requested interface",
                    invocationId, this.identity().runtimeId(), false, false);
        }
        for (String interfaceName : target.interfaces()) {
            if (!authoritative.interfaces().contains(interfaceName)) {
                throw MiddlewareException.remote(MiddlewareErrorCode.INTERFACE_MISMATCH, "target reference contains a non-authoritative interface",
                        invocationId, this.identity().runtimeId(), false, false);
            }
        }
        return runtimeObject;
    }

    private static long parsePositiveLong(final String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private List<String> exposedInterfaces(final Object instance, final Class<?> primaryInterface, final ExportOptions options) {
        Set<String> names = new HashSet<>();
        this.collectInterfaceNames(primaryInterface, names);
        names.addAll(options.interfaces());
        Class<?>[] instanceInterfaces = instance.getClass().getInterfaces();
        for (Class<?> candidate : instanceInterfaces) {
            this.collectInterfaceNames(candidate, names);
        }
        return List.copyOf(names);
    }

    private void collectInterfaceNames(final Class<?> interfaceType, final Set<String> names) {
        if (interfaceType == null || !interfaceType.isInterface()) {
            return;
        }
        names.add(interfaceType.getName());
        Class<?>[] parents = interfaceType.getInterfaces();
        for (Class<?> parent : parents) {
            this.collectInterfaceNames(parent, names);
        }
    }

    private ExportDescriptor exportDescriptor(final Class<?> primaryInterface, final Map<String, String> metadata) {
        List<MethodDescriptor> methods = new ArrayList<>();
        for (Method method : primaryInterface.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                methods.add(MethodDescriptor.fromMethod(method));
            }
        }
        return new ExportDescriptor(primaryInterface.getName(), methods, metadata);
    }

    private void invokeLifecycle(final Object instance, final Class<? extends java.lang.annotation.Annotation> annotation) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (method.getAnnotation(annotation) != null) {
                if (method.getParameterCount() != 0) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "lifecycle method must not declare parameters");
                }
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (ReflectiveOperationException exception) {
                    throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "lifecycle method failed", Map.of(), null, null, false, true), exception);
                }
            }
        }
    }

    private void startHeartbeat(final RuntimeObject runtimeObject) {
        long intervalMillis = Math.max(1L, runtimeObject.options.heartbeatInterval().toMillis());
        ScheduledFuture<?> future = this.heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (this.closed.get() || runtimeObject.objectState != ObjectState.EXECUTED) { return; }
            long leaseUntil = this.configuration.clock().millis() + Math.max(1L, runtimeObject.options.leaseDuration().toMillis());
            synchronized (runtimeObject) {
                runtimeObject.ref = runtimeObject.ref.withLeaseUntil(leaseUntil);
                runtimeObject.refreshReadinessMetadata();
            }
            if (runtimeObject.readiness != null) { this.scheduleReadinessPublication(runtimeObject); }
            else {
                try { this.registry.heartbeat(runtimeObject.ref, leaseUntil); }
                catch (MiddlewareException exception) { runtimeObject.objectState = ObjectState.FENCED; }
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        runtimeObject.heartbeat = future;
    }

    private <T> Optional<T> findLocal(final String serviceName, final Class<T> api, final LookupOptions options) {
        synchronized (this.publicationLock) {
            for (RuntimeObject runtimeObject : this.objects.values()) {
                if (runtimeObject.objectState == ObjectState.EXECUTED
                        && runtimeObject.ref.serviceName().equals(serviceName)
                        && runtimeObject.ref.registryContext().equals(options.registryContext())
                        && runtimeObject.ref.interfaces().contains(api.getName())
                        && runtimeObject.readiness == null
                        && runtimeObject.completionFactory == null
                        && runtimeObject.valueCodec == null
                        && runtimeObject.ready()
                        && api.isInstance(runtimeObject.instance)
                        && runtimeObject.ref.metadata().entrySet().containsAll(options.requiredParams().entrySet())) {
                    return Optional.of(api.cast(runtimeObject.instance));
                }
            }
        }
        return Optional.empty();
    }

    private ObjectRef selectPreferred(final List<ObjectRef> refs, final long selectionDeadlineNanos) {
        long nowMillis = this.configuration.clock().millis();
        List<ObjectRef> active = new ArrayList<>(refs.size());
        for (ObjectRef ref : refs) {
            if (this.eligibleReference(ref, nowMillis)) {
                active.add(this.withBenchmarkMetadata(ref, selectionDeadlineNanos));
            }
        }
        if (active.isEmpty()) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "no active service candidates");
        }
        HpSelection selection = this.hpSelector.select(active, this.identity().runtimeId(),
                this.registry.peers(new PeerQuery(this.identity().registryContext(), true, 4096)),
                objectId -> {
                    SlidingWindowStatistics window = this.objectStatistics.get(objectId);
                    if (window == null) {
                        return new ObjectInvocationStats(0L, 0L, 0L, 0.0D);
                    }
                    StatisticsSnapshot snapshot = window.snapshot();
                    return new ObjectInvocationStats(snapshot.totalCount(), snapshot.failureCount(), snapshot.averageLatencyNanos(), 0.0D);
                }, this.configuration.clock());
        this.recordHpSelection(selection);
        return selection.selected();
    }

    private boolean eligibleRegistryRecord(
            final RegistryRecord record,
            final String serviceName,
            final List<String> requiredInterfaces,
            final Map<String, String> params,
            final String registryContext,
            final long nowMillis) {
        if (record == null || !record.activeAt(nowMillis)) {
            return false;
        }
        if (!"trusted".equals(record.authMetadata().get("trustStatus"))) {
            return false;
        }
        ObjectRef ref = record.ref();
        return this.eligibleReference(ref, nowMillis)
                && ref.serviceName().equals(serviceName)
                && ref.registryContext().equals(registryContext)
                && ref.interfaces().containsAll(requiredInterfaces)
                && ref.metadata().entrySet().containsAll(params.entrySet());
    }

    private boolean eligibleReference(final ObjectRef ref, final long nowMillis) {
        return ref != null
                && ref.generation() > 0L
                && ref.isLeaseActive(nowMillis)
                && "trusted".equals(ref.metadata().get("trustStatus"))
                && this.positiveLongMetadata(ref.metadata(), "fencingToken")
                && this.positiveLongMetadata(ref.metadata(), "topologyGeneration")
                && this.readinessEligible(ref, nowMillis);
    }

    private boolean readinessEligible(final ObjectRef ref, final long nowMillis) {
        RuntimeObject owned = this.objects.get(ref.objectId());
        if (owned != null && owned.ref.generation() == ref.generation() && !owned.ready()) { return false; }
        if (!ref.metadata().containsKey("j7m.readiness.ready")) { return true; }
        return "true".equals(ref.metadata().get("j7m.readiness.ready"))
                && parsePositiveLong(ref.metadata().get("j7m.readiness.until")) > nowMillis;
    }

    private void setReadiness(final RuntimeObject object, final boolean ready, final Duration validity) {
        if (validity == null || validity.compareTo(Duration.ofMillis(1)) < 0 || validity.compareTo(Duration.ofMinutes(5)) > 0) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "invalid readiness validity");
        }
        synchronized (object) {
            if (this.closed.get() || object.objectState == ObjectState.ZOMBIE || object.objectState == ObjectState.DEAD
                    || object.objectState == ObjectState.FENCED) {
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "export is unavailable");
            }
            object.readiness = new Readiness(ready, System.nanoTime() + validity.toNanos(),
                    this.configuration.clock().millis() + validity.toMillis());
            object.refreshReadinessMetadata();
        }
        this.scheduleReadinessPublication(object);
    }

    private void scheduleReadinessPublication(final RuntimeObject object) {
        if (this.closed.get() || object.options.localOnly() || object.objectState != ObjectState.EXECUTED
                || !object.readinessPublicationPending.compareAndSet(false, true)) { return; }
        if (!this.readinessPublicationPermits.tryAcquire()) {
            object.readinessPublicationPending.set(false);
            this.readinessPublicationDeferred.incrementAndGet();
            return;
        }
        try { this.heartbeatExecutor.execute(() -> this.publishReadiness(object)); }
        catch (RejectedExecutionException failure) {
            this.readinessPublicationPermits.release();
            object.readinessPublicationPending.set(false);
            this.readinessPublicationFailures.incrementAndGet();
        }
    }

    private void publishReadiness(final RuntimeObject object) {
        Readiness observed = object.readiness;
        try {
            ObjectRef ref;
            synchronized (object) {
                if (this.closed.get() || object.objectState != ObjectState.EXECUTED) { return; }
                object.refreshReadinessMetadata();
                observed = object.readiness;
                ref = object.ref;
            }
            long now = this.configuration.clock().millis();
            this.registry.bind(new RegistryRecord(ref, ref.metadata(), now, now, ref.leaseUntil(), ObjectState.EXECUTED));
            if (this.closed.get() || object.objectState != ObjectState.EXECUTED) { this.registry.unbind(ref); }
        } catch (RuntimeException failure) { this.readinessPublicationFailures.incrementAndGet(); }
        finally {
            this.readinessPublicationPermits.release();
            object.readinessPublicationPending.set(false);
            if (object.readiness != observed) { this.scheduleReadinessPublication(object); }
        }
    }

    private <T> T createHaProxy(final String serviceName, final Class<T> api, final LookupOptions options) {
        AtomicReference<ObjectRef> currentTarget = new AtomicReference<>();
        AtomicBoolean dirty = new AtomicBoolean(true);
        Object proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, (Object ignoredProxy, Method method, Object[] args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return this.invokeHaObjectMethod(serviceName, method, args);
            }
            boolean retryAllowed = method.getAnnotation(Idempotent.class) != null && method.getAnnotation(NonIdempotent.class) == null;
            long selectionDeadlineNanos = this.selectionDeadlineNanos(options);
            ProxyOptions proxyOptions = this.haProxyOptions(options);
            List<ObjectRef> candidates = new ArrayList<>(this.list(serviceName, List.of(api.getName()), options.requiredParams(), options));
            MiddlewareException lastFailure = null;
            while (!candidates.isEmpty()) {
                ObjectRef candidate = currentTarget.get();
                String candidateObjectId = candidate == null ? "" : candidate.objectId();
                if (dirty.get() || candidate == null || candidates.stream().noneMatch(ref -> ref.objectId().equals(candidateObjectId))) {
                    candidate = this.selectPreferred(candidates, selectionDeadlineNanos);
                    currentTarget.set(candidate);
                    dirty.set(false);
                }
                try {
                    long startedNanos = System.nanoTime();
                    T target = this.proxyFor(candidate, api, proxyOptions);
                    Object result = method.invoke(target, args);
                    this.recordObjectStats(candidate, startedNanos, true, null);
                    return result;
                } catch (InvocationTargetException exception) {
                    Throwable cause = exception.getTargetException();
                    if (cause instanceof MiddlewareException middlewareException) {
                        this.recordObjectStats(candidate, 0L, false, middlewareException.code());
                        lastFailure = middlewareException;
                        boolean dirtyTarget = middlewareException.dirtyTarget();
                        if (dirtyTarget) {
                            dirty.set(true);
                            candidates.remove(candidate);
                        }
                        if (!retryAllowed || !middlewareException.retryable() || !dirtyTarget) {
                            throw middlewareException;
                        }
                    } else {
                        throw cause;
                    }
                } catch (MiddlewareException exception) {
                    this.recordObjectStats(candidate, 0L, false, exception.code());
                    lastFailure = exception;
                    boolean dirtyTarget = exception.dirtyTarget();
                    if (dirtyTarget) {
                        dirty.set(true);
                        candidates.remove(candidate);
                    }
                    if (!retryAllowed || !exception.retryable() || !dirtyTarget) {
                        throw exception;
                    }
                }
            }
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "no service candidates");
        });
        return api.cast(proxy);
    }

    private ProxyOptions haProxyOptions(final LookupOptions options) {
        long nowMillis = System.currentTimeMillis();
        long deadlineMillis = nowMillis + Math.max(1L, options.deadline().toMillis());
        Map<String, String> metadata = Map.of(RELIABILITY_DEADLINE_EPOCH_MILLIS, Long.toString(deadlineMillis));
        return new ProxyOptions(Duration.ofMillis(Math.max(1L, deadlineMillis - nowMillis)), metadata);
    }

    private Object invokeHaObjectMethod(final String serviceName, final Method method, final Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "HA proxy -> " + serviceName;
            case "hashCode" -> serviceName.hashCode();
            case "equals" -> Boolean.valueOf(args != null && args.length == 1 && args[0] == this);
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    private Method resolveMethod(final RuntimeObject runtimeObject, final MethodDescriptor descriptor, final String invocationId) {
        if (!runtimeObject.ref.interfaces().contains(descriptor.declaringInterfaceName())) {
            throw MiddlewareException.remote(MiddlewareErrorCode.INTERFACE_MISMATCH, "target does not expose requested interface",
                    invocationId, this.identity().runtimeId(), false, false);
        }
        try {
            Class<?>[] parameterTypes = new Class<?>[descriptor.parameterTypeNames().size()];
            for (int index = 0; index < parameterTypes.length; index++) {
                parameterTypes[index] = this.classFor(descriptor.parameterTypeNames().get(index));
            }
            Class<?> declaring = this.classFor(descriptor.declaringInterfaceName());
            Method method = declaring.getMethod(descriptor.methodName(), parameterTypes);
            if (declaring.isInstance(runtimeObject.instance)) {
                return method;
            }
        } catch (MiddlewareException | NoSuchMethodException exception) {
            return this.resolveExportedOrThrow(runtimeObject, descriptor, invocationId);
        }
        return this.resolveExportedOrThrow(runtimeObject, descriptor, invocationId);
    }

    private Method resolveExportedOrThrow(
            final RuntimeObject runtimeObject,
            final MethodDescriptor descriptor,
            final String invocationId) {
        Method exportedMethod = this.resolveExportedInstanceMethod(runtimeObject, descriptor);
        if (exportedMethod != null) {
            return exportedMethod;
        }
        throw MiddlewareException.remote(MiddlewareErrorCode.METHOD_NOT_FOUND, "target method is not exposed",
                invocationId, this.identity().runtimeId(), false, false);
    }

    private Method resolveExportedInstanceMethod(final RuntimeObject runtimeObject, final MethodDescriptor descriptor) {
        Method method = this.resolveExportedInterfaceMethod(runtimeObject.primaryInterface, descriptor);
        if (method != null) {
            return method;
        }
        Class<?>[] interfaces = runtimeObject.instance.getClass().getInterfaces();
        for (Class<?> candidate : interfaces) {
            method = this.resolveExportedInterfaceMethod(candidate, descriptor);
            if (method != null) {
                return method;
            }
        }
        return this.resolveExportedInterfaceMethod(runtimeObject.instance.getClass(), descriptor);
    }

    private Method resolveExportedInterfaceMethod(final Class<?> type, final MethodDescriptor descriptor) {
        if (type == null) {
            return null;
        }
        for (Method method : type.getMethods()) {
            if (methodMatchesDescriptor(method, descriptor)) {
                return method;
            }
        }
        return null;
    }

    private static boolean methodMatchesDescriptor(final Method method, final MethodDescriptor descriptor) {
        if (!method.getName().equals(descriptor.methodName())
                || !method.getDeclaringClass().getName().equals(descriptor.declaringInterfaceName())
                || !method.getReturnType().getName().equals(descriptor.returnTypeName())) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != descriptor.parameterTypeNames().size()) {
            return false;
        }
        for (int index = 0; index < parameterTypes.length; index++) {
            if (!parameterTypes[index].getName().equals(descriptor.parameterTypeNames().get(index))) {
                return false;
            }
        }
        return true;
    }

    private RegistryStatus registryStatus(final int recordCount, final List<RegistryPeerStatus> peers) {
        if (this.registry instanceof RegistryDiagnostics diagnostics) {
            DiscoveryStatistics discovery = diagnostics.discoveryStatistics();
            Map<String, Long> metrics = new java.util.LinkedHashMap<>(discovery.metadata());
            metrics.put("registryQueryCount", Long.valueOf(discovery.registryQueryCount()));
            metrics.put("multicastMessagesSent", Long.valueOf(discovery.multicastMessagesSent()));
            metrics.put("multicastMessagesReceived", Long.valueOf(discovery.multicastMessagesReceived()));
            metrics.put("bootstrapQueries", Long.valueOf(discovery.bootstrapQueries()));
            metrics.put("peerContactSuccesses", Long.valueOf(discovery.peerContactSuccesses()));
            metrics.put("peerContactFailures", Long.valueOf(discovery.peerContactFailures()));
            metrics.put("hpBenchmarkCount", Long.valueOf(this.benchmarkCount.get()));
            metrics.put("hpBenchmarkFailures", Long.valueOf(this.benchmarkFailures.get()));
            metrics.put("hpBenchmarkCacheHits", Long.valueOf(this.benchmarkCacheHits.get()));
            metrics.put("hpBenchmarkAverageLatencyNanos", Long.valueOf(this.benchmarkCount.get() == 0L ? 0L : this.benchmarkLatencyTotal.get() / this.benchmarkCount.get()));
            List<String> events = diagnostics.recentDiscoveryEvents().stream()
                    .map(DiscoveryEvent::message)
                    .toList();
            List<String> hpDecisions;
            synchronized (this.hpDecisionLock) {
                hpDecisions = List.copyOf(this.recentHpDecisions);
            }
            return new RegistryStatus(recordCount, peers.size(), diagnostics.multicastListenerRunning(),
                    diagnostics.multicastAdapterType(), diagnostics.multicastEndpoint(),
                    discovery.multicastMessagesSent(), discovery.multicastMessagesReceived(), diagnostics.activeDiscoveryQuestions(),
                    peers.size(), diagnostics.bootstrapHintCount(), diagnostics.contactedBootstrapPeerCount(), diagnostics.lookupCacheSize(),
                    metrics, peers.stream().map(RegistryPeerStatus::peerId).toList(), events, hpDecisions);
        }
        List<String> hpDecisions;
        synchronized (this.hpDecisionLock) {
            hpDecisions = List.copyOf(this.recentHpDecisions);
        }
        return new RegistryStatus(recordCount, peers.size(), false, "none", "", 0L, 0L, 0, peers.size(), 0, 0, 0,
                Map.of(
                        "hpBenchmarkCount", Long.valueOf(this.benchmarkCount.get()),
                        "hpBenchmarkFailures", Long.valueOf(this.benchmarkFailures.get()),
                        "hpBenchmarkCacheHits", Long.valueOf(this.benchmarkCacheHits.get())),
                peers.stream().map(RegistryPeerStatus::peerId).toList(), List.of(), hpDecisions);
    }

    private Map<String, String> exportMetadata(final Class<?> primaryInterface, final Map<String, String> metadata, final long generation) {
        Map<String, String> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (values.keySet().stream().anyMatch(key -> key.startsWith("j7m.readiness."))) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "readiness metadata is host-owned");
        }
        String generationText = Long.toString(generation);
        values.put("trustStatus", "trusted");
        values.put("fencingToken", generationText);
        values.put("topologyGeneration", generationText);
        for (Method method : primaryInterface.getMethods()) {
            if (method.getAnnotation(Benchmark.class) != null && method.getParameterCount() == 0) {
                values.putIfAbsent("benchmarkInterface", method.getDeclaringClass().getName());
                values.putIfAbsent("benchmarkMethod", method.getName());
                values.putIfAbsent("benchmarkReturnType", method.getReturnType().getName());
                break;
            }
        }
        return Map.copyOf(values);
    }

    private ObjectRef withBenchmarkMetadata(final ObjectRef ref, final long selectionDeadlineNanos) {
        Method benchmarkMethod = this.trustedBenchmarkMethod(ref);
        if (benchmarkMethod == null) {
            return this.stripBenchmarkMetadata(ref);
        }
        long nowMillis = this.configuration.clock().millis();
        BenchmarkResult cached;
        synchronized (this.benchmarkCacheLock) {
            cached = this.benchmarkCache.get(ref.objectId());
        }
        if (cached != null && cached.expiresAtMillis >= nowMillis) {
            this.benchmarkCacheHits.incrementAndGet();
            return this.refWithBenchmark(ref, cached);
        }
        if (this.remainingNanos(selectionDeadlineNanos) <= 0L) {
            return this.stripBenchmarkMetadata(ref);
        }
        BenchmarkResult result = this.probeBenchmark(ref, benchmarkMethod, nowMillis, selectionDeadlineNanos);
        if (result == null) {
            return this.stripBenchmarkMetadata(ref);
        }
        synchronized (this.benchmarkCacheLock) {
            this.benchmarkCache.put(ref.objectId(), result);
            this.trimMap(this.benchmarkCache, MAX_BENCHMARK_CACHE, ref.objectId());
        }
        return this.refWithBenchmark(ref, result);
    }

    private ObjectRef refWithBenchmark(final ObjectRef ref, final BenchmarkResult result) {
        Map<String, String> metadata = new LinkedHashMap<>(ref.metadata());
        metadata.put("benchmarkLatencyNanos", Long.toString(result.latencyNanos));
        metadata.put("benchmarkFailed", Boolean.toString(!result.success));
        return ref.withMetadata(metadata);
    }

    private ObjectRef stripBenchmarkMetadata(final ObjectRef ref) {
        if (!ref.metadata().containsKey("benchmarkLatencyNanos") && !ref.metadata().containsKey("benchmarkFailed")) {
            return ref;
        }
        Map<String, String> metadata = new LinkedHashMap<>(ref.metadata());
        metadata.remove("benchmarkLatencyNanos");
        metadata.remove("benchmarkFailed");
        return ref.withMetadata(metadata);
    }

    private Method trustedBenchmarkMethod(final ObjectRef ref) {
        if (ref == null || !ref.runtimeId().equals(this.identity().runtimeId())) {
            return null;
        }
        RuntimeObject runtimeObject = this.objects.get(ref.objectId());
        if (runtimeObject == null || runtimeObject.objectState != ObjectState.EXECUTED
                || !this.matchesTrustedLocalExport(ref, runtimeObject.ref)) {
            return null;
        }
        for (Method method : runtimeObject.primaryInterface.getMethods()) {
            if (method.getAnnotation(Benchmark.class) != null && method.getParameterCount() == 0) {
                return method;
            }
        }
        return null;
    }

    private BenchmarkResult probeBenchmark(
            final ObjectRef ref,
            final Method method,
            final long nowMillis,
            final long selectionDeadlineNanos) {
        long startedNanos = System.nanoTime();
        long remainingNanos = this.remainingNanos(selectionDeadlineNanos);
        if (remainingNanos <= 0L) {
            return null;
        }
        this.benchmarkCount.incrementAndGet();
        if (!this.benchmarkPermits.tryAcquire()) {
            this.benchmarkFailures.incrementAndGet();
            return new BenchmarkResult(System.nanoTime() - startedNanos, false, nowMillis + 2_000L);
        }
        Future<Boolean> future = null;
        try {
            remainingNanos = this.remainingNanos(selectionDeadlineNanos);
            if (remainingNanos <= 0L) {
                return null;
            }
            RuntimeObject runtimeObject = this.objects.get(ref.objectId());
            if (runtimeObject == null || !this.matchesTrustedLocalExport(ref, runtimeObject.ref)) {
                this.benchmarkFailures.incrementAndGet();
                return new BenchmarkResult(System.nanoTime() - startedNanos, false, nowMillis + 2_000L);
            }
            future = this.benchmarkExecutor.submit(() -> {
                if (this.remainingNanos(selectionDeadlineNanos) <= 0L) {
                    return Boolean.FALSE;
                }
                try {
                    method.invoke(runtimeObject.instance);
                } catch (ReflectiveOperationException exception) {
                    throw new BenchmarkInvocationException(exception);
                }
                return Boolean.TRUE;
            });
            long timeoutNanos = Math.min(this.remainingNanos(selectionDeadlineNanos), TimeUnit.MILLISECONDS.toNanos(BENCHMARK_TIMEOUT_MILLIS));
            if (timeoutNanos <= 0L) {
                future.cancel(true);
                return null;
            }
            Boolean executed = future.get(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!Boolean.TRUE.equals(executed)) {
                return null;
            }
            long latency = System.nanoTime() - startedNanos;
            this.benchmarkLatencyTotal.addAndGet(latency);
            return new BenchmarkResult(latency, true, nowMillis + 2_000L);
        } catch (RejectedExecutionException exception) {
            this.benchmarkFailures.incrementAndGet();
            return new BenchmarkResult(System.nanoTime() - startedNanos, false, nowMillis + 2_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (future != null) {
                future.cancel(true);
            }
            this.benchmarkFailures.incrementAndGet();
            return new BenchmarkResult(System.nanoTime() - startedNanos, false, nowMillis + 2_000L);
        } catch (TimeoutException exception) {
            if (future != null) {
                future.cancel(true);
            }
            this.benchmarkFailures.incrementAndGet();
            return new BenchmarkResult(System.nanoTime() - startedNanos, false, nowMillis + 2_000L);
        } catch (java.util.concurrent.ExecutionException | RuntimeException exception) {
            this.benchmarkFailures.incrementAndGet();
            return new BenchmarkResult(System.nanoTime() - startedNanos, false, nowMillis + 2_000L);
        } finally {
            this.benchmarkPermits.release();
        }
    }

    private long selectionDeadlineNanos(final LookupOptions options) {
        Duration deadline = options == null ? LookupOptions.defaults().deadline() : options.deadline();
        long requestedNanos;
        try {
            requestedNanos = deadline.toNanos();
        } catch (ArithmeticException exception) {
            requestedNanos = Long.MAX_VALUE;
        }
        if (requestedNanos <= 0L) {
            requestedNanos = 1L;
        }
        if (requestedNanos >= Long.MAX_VALUE / 2L) {
            return Long.MAX_VALUE;
        }
        long nowNanos = System.nanoTime();
        if (nowNanos > Long.MAX_VALUE - requestedNanos) {
            return Long.MAX_VALUE;
        }
        return nowNanos + requestedNanos;
    }

    private long remainingNanos(final long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return deadlineNanos - System.nanoTime();
    }

    private void recordObjectStats(final ObjectRef ref, final long startedNanos, final boolean success, final MiddlewareErrorCode errorCode) {
        long duration = startedNanos == 0L ? 0L : System.nanoTime() - startedNanos;
        SlidingWindowStatistics statistics;
        synchronized (this.objectStatisticsLock) {
            statistics = this.objectStatistics.computeIfAbsent(ref.objectId(),
                    ignored -> new SlidingWindowStatistics(256, HP_STATS_RETENTION, this.configuration.clock()::millis));
            this.trimMap(this.objectStatistics, MAX_HP_STATS, ref.objectId());
        }
        statistics.record(ref.objectId(), this.configuration.clock().millis(), duration, success, errorCode);
    }

    private boolean matchesTrustedLocalExport(final ObjectRef candidate, final ObjectRef published) {
        if (candidate == null || published == null) {
            return false;
        }
        return candidate.objectId().equals(published.objectId())
                && candidate.logicalName().equals(published.logicalName())
                && candidate.serviceName().equals(published.serviceName())
                && candidate.runtimeId().equals(published.runtimeId())
                && candidate.registryContext().equals(published.registryContext())
                && candidate.interfaces().equals(published.interfaces())
                && candidate.transport().equals(published.transport())
                && candidate.endpoint().equals(published.endpoint())
                && candidate.leaseUntil() == published.leaseUntil()
                && candidate.generation() == published.generation()
                && "trusted".equals(published.metadata().get("trustStatus"))
                && candidate.metadata().getOrDefault("trustStatus", "").equals(published.metadata().get("trustStatus"))
                && candidate.metadata().getOrDefault("fencingToken", "").equals(published.metadata().get("fencingToken"))
                && candidate.metadata().getOrDefault("topologyGeneration", "").equals(published.metadata().get("topologyGeneration"));
    }

    private boolean positiveLongMetadata(final Map<String, String> metadata, final String key) {
        String value = metadata == null ? null : metadata.get(key);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private <T> void trimMap(final ConcurrentMap<String, T> map, final int maxSize, final String retainedKey) {
        if (map.size() <= maxSize) {
            return;
        }
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (key.equals(retainedKey) && map.size() <= maxSize) {
                return;
            }
            if (key.equals(retainedKey)) {
                continue;
            }
            map.remove(key);
            if (map.size() <= maxSize) {
                return;
            }
        }
    }

    private void recordHpSelection(final HpSelection selection) {
        HpCandidateScore selected = selection.scores().stream()
                .filter(score -> score.ref().objectId().equals(selection.selected().objectId()))
                .findFirst()
                .orElse(selection.scores().getFirst());
        String line = selection.selected().serviceName() + " -> " + selection.selected().runtimeId()
                + "/" + selection.selected().objectId() + " score=" + selected.score();
        synchronized (this.hpDecisionLock) {
            if (this.recentHpDecisions.size() >= 128) {
                this.recentHpDecisions.removeFirst();
            }
            this.recentHpDecisions.addLast(line);
        }
    }

    private Object invokeWithSecurityContext(
            final SecurityContext context,
            final Method method,
            final Object instance,
            final Object[] arguments) throws InvocationTargetException, IllegalAccessException {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<ReflectiveOperationException> failure = new AtomicReference<>();
        SecurityContexts.run(context, () -> {
            try {
                result.set(method.invoke(instance, arguments));
            } catch (InvocationTargetException | IllegalAccessException exception) {
                failure.set(exception);
            }
        });
        ReflectiveOperationException exception = failure.get();
        if (exception instanceof InvocationTargetException invocationTargetException) {
            throw invocationTargetException;
        }
        if (exception instanceof IllegalAccessException illegalAccessException) {
            throw illegalAccessException;
        }
        return result.get();
    }

    private void authorizeInvocation(
            final SecurityContext context,
            final RuntimeObject runtimeObject,
            final Method javaMethod,
            final MethodDescriptor descriptor,
            final InvocationOptions options) {
        Map<String, String> metadata = this.invocationAuditMetadata(javaMethod);
        this.enforceAnnotations(context, runtimeObject, javaMethod, descriptor, options);
        this.authorize(context, PermissionAction.INVOKE, runtimeObject.ref.objectId(), runtimeObject.ref.serviceName(),
                runtimeObject.ref.registryContext(), descriptor.toString(), runtimeObject.ref.transport(), "", metadata);
    }

    private void enforceAnnotations(
            final SecurityContext context,
            final RuntimeObject runtimeObject,
            final Method javaMethod,
            final MethodDescriptor descriptor,
            final InvocationOptions options) {
        RequiresRole role = this.annotation(javaMethod, runtimeObject.primaryInterface, RequiresRole.class);
        if (role != null && !context.roles().contains(role.value())) {
            this.denyByAnnotation(context, runtimeObject, descriptor, options, "role required: " + role.value());
        }
        RequiresTrustDomain trustDomain = this.annotation(javaMethod, runtimeObject.primaryInterface, RequiresTrustDomain.class);
        if (trustDomain != null && !context.trustDomain().id().equals(trustDomain.value())) {
            this.denyByAnnotation(context, runtimeObject, descriptor, options, "trust domain required: " + trustDomain.value());
        }
        Confidential confidential = this.annotation(javaMethod, runtimeObject.primaryInterface, Confidential.class);
        if (confidential != null
                && this.security.mode() == SecurityMode.PRODUCTION
                && context.transportSecurityLevel() != TransportSecurityLevel.AUTHENTICATED_ENCRYPTED) {
            this.unauthenticatedCount.incrementAndGet();
            throw MiddlewareException.remote(MiddlewareErrorCode.UNAUTHENTICATED, "confidential method requires authenticated encrypted transport",
                    options.invocationId(), this.identity().runtimeId(), false, false);
        }
        RequiresPermission permission = this.annotation(javaMethod, runtimeObject.primaryInterface, RequiresPermission.class);
        if (permission != null) {
            Map<String, String> metadata = Map.of("requiredPermission", permission.value(), "requiredResource", permission.resource());
            this.authorize(context, PermissionAction.INVOKE, permission.resource().isBlank() ? runtimeObject.ref.objectId() : permission.resource(),
                    runtimeObject.ref.serviceName(), runtimeObject.ref.registryContext(), descriptor.toString(), runtimeObject.ref.transport(), "", metadata);
        }
    }

    private <A extends java.lang.annotation.Annotation> A annotation(final Method method, final Class<?> type, final Class<A> annotationType) {
        A annotation = method.getAnnotation(annotationType);
        if (annotation != null) {
            return annotation;
        }
        annotation = type.getAnnotation(annotationType);
        if (annotation != null) {
            return annotation;
        }
        return method.getDeclaringClass().getAnnotation(annotationType);
    }

    private void denyByAnnotation(
            final SecurityContext context,
            final RuntimeObject runtimeObject,
            final MethodDescriptor descriptor,
            final InvocationOptions options,
            final String reason) {
        PermissionRequest request = new PermissionRequest(PermissionAction.INVOKE, runtimeObject.ref.objectId(), runtimeObject.ref.registryContext(),
                runtimeObject.ref.runtimeId(), runtimeObject.ref.serviceName(), descriptor.toString(), runtimeObject.ref.transport(), "", Map.of("annotation", reason));
        AuthorizationDecision decision = AuthorizationDecision.deny("annotations", reason);
        this.audit(context, request, decision);
        this.authorizationDenyCount.incrementAndGet();
        throw MiddlewareException.remote(MiddlewareErrorCode.PERMISSION_DENIED, reason, options.invocationId(), this.identity().runtimeId(), false, false);
    }

    private Map<String, String> invocationAuditMetadata(final Method method) {
        Map<String, String> metadata = new LinkedHashMap<>();
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            if (parameters[index].getAnnotation(Sensitive.class) != null) {
                metadata.put("arg" + index, this.security.redactor().redact("sensitive.arg" + index, "sensitive"));
                this.redactionCount.incrementAndGet();
            }
        }
        if (method.getAnnotation(Audit.class) != null || method.getDeclaringClass().getAnnotation(Audit.class) != null) {
            metadata.put("audit", "forced");
        }
        return metadata;
    }

    private void authorize(
            final SecurityContext context,
            final PermissionAction action,
            final String resource,
            final String serviceName,
            final String registryContext,
            final String methodDescriptor,
            final String transportId,
            final String registryPeerId,
            final Map<String, String> metadata) {
        PermissionRequest request = new PermissionRequest(action, resource, registryContext, this.identity().runtimeId(), serviceName,
                methodDescriptor, transportId, registryPeerId, metadata == null ? Map.of() : metadata);
        if (this.security.requireAuthentication() && !context.authenticated()) {
            AuthorizationDecision decision = AuthorizationDecision.unauthenticated(this.security.policy().id(), "authentication required");
            this.audit(context, request, decision);
            this.unauthenticatedCount.incrementAndGet();
            throw MiddlewareException.of(MiddlewareErrorCode.UNAUTHENTICATED, "authentication required");
        }
        AuthorizationDecision decision = this.security.policy().evaluate(context, request);
        this.audit(context, request, decision);
        if (!decision.allowed()) {
            this.authorizationDenyCount.incrementAndGet();
            MiddlewareErrorCode code = decision.authenticatedRequired() ? MiddlewareErrorCode.UNAUTHENTICATED : MiddlewareErrorCode.PERMISSION_DENIED;
            throw MiddlewareException.of(code, decision.reason().isBlank() ? code.name() : decision.reason());
        }
    }

    private void audit(final SecurityContext context, final PermissionRequest request, final AuthorizationDecision decision) {
        Map<String, String> metadata = this.security.redactor().redactMetadata(request.metadata());
        this.security.auditSink().record(new AuditEvent(this.configuration.clock().instant(), this.identity().runtimeId(), context.principal().id(),
                request.action().wireName(), request.resource(), decision.allowed() ? "allow" : "deny", decision.policyId(),
                context.correlationId(), context.invocationId(), context.remoteEndpoint(), decision.reason(), request.transportId(), request.registryContext(), metadata));
    }

    private SecurityContext currentSecurityContext() {
        SecurityContext current = SecurityContexts.current();
        if (current != null) {
            return current;
        }
        return new SecurityContext(org.j7m.middleware.security.api.Principal.anonymous(), this.identity().runtimeId(), new org.j7m.middleware.security.api.TrustDomain(this.identity().registryContext()),
                this.security.credentialProvider() == null ? null : this.security.credentialProvider().current().credential(), "", "", "",
                false, TransportSecurityLevel.INSECURE, Set.of(), Map.of(), this.configuration.clock().instant());
    }

    private Map<String, String> securityMetadata() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("mode", this.security.mode().name());
        values.put("readinessPublicationFailures", Long.toString(this.readinessPublicationFailures.get()));
        values.put("readinessPublicationDeferred", Long.toString(this.readinessPublicationDeferred.get()));
        values.put("readinessPublicationPending", Integer.toString(MAX_PENDING_READINESS_PUBLICATIONS - this.readinessPublicationPermits.availablePermits()));
        values.put("transportSecurityLevel", this.security.allowInsecureProductionTransport() ? "insecure-allowed" : "strict");
        values.put("authorizationPolicy", this.security.policy().id());
        values.put("auditEvents", Long.toString(this.security.auditSink().eventCount()));
        values.put("denyCount", Long.toString(this.authorizationDenyCount.get()));
        values.put("unauthenticatedCount", Long.toString(this.unauthenticatedCount.get()));
        values.put("redactionCount", Long.toString(this.redactionCount.get()));
        values.put("overloadRejectedCount", Long.toString(this.overloadRejectionCount.get()));
        values.put("replayRejectedCount", Long.toString(this.replayRejectionCount.get()));
        values.put("timestampRejectedCount", Long.toString(this.timestampRejectionCount.get()));
        values.put("resourcePolicyDeniedCount", Long.toString(this.resourcePolicyDenyCount.get()));
        values.put("outboundLimitRejectedCount", Long.toString(this.outboundLimitRejectionCount.get()));
        values.put("reliability.idempotencyKeyUsageCount", Long.toString(this.reliabilityIdempotencyKeyUsageCount.get()));
        values.put("reliability.duplicateDetectionDecisionCount", Long.toString(this.reliabilityDuplicateDetectionDecisionCount.get()));
        values.put("reliability.fencingTokenRejectedCount", Long.toString(this.reliabilityFencingTokenRejectionCount.get()));
        values.put("reliability.retrySafeDecisionCount", Long.toString(this.reliabilityRetrySafeDecisionCount.get()));
        values.put("reliability.backpressureDecisionCount", Long.toString(this.reliabilityBackpressureDecisionCount.get()));
        values.put("reliability.contextPropagationCount", Long.toString(this.reliabilityContextPropagationCount.get()));
        values.put("reliability.dedupeStoreDurability", "non-durable");
        values.put("reliability.exactlyOnce", "unsupported");
        values.put("callback.maxLive", Integer.toString(MAX_CALLBACK_EXPORTS));
        values.put("callback.live", Integer.toString(this.callbackExportCount()));
        values.put("oneWay.queuedLimit", Integer.toString(ONE_WAY_QUEUED));
        values.put("oneWay.activeLimit", Integer.toString(ONE_WAY_ACTIVE));
        values.put("oneWay.rejectedCount", Long.toString(this.oneWayRejectedCount.get()));
        values.put("oneWay.failureCount", Long.toString(this.oneWayFailureCount.get()));
        values.put("strictRegistryTrust", Boolean.toString(this.security.strictRegistryTrust()));
        values.put("replayProtectionEnabled", Boolean.toString(this.security.replayProtectionEnabled()));
        values.put("resourcePolicy", this.security.resourcePolicy().id());
        values.put("maxInboundMessageBytes", Integer.toString(this.security.maxInboundMessageBytes()));
        values.put("maxOutboundMessageBytes", Integer.toString(this.security.maxOutboundMessageBytes()));
        values.put("maxConcurrentInvocations", Integer.toString(this.security.maxConcurrentInvocations()));
        values.put("activeInvocations", Integer.toString(this.activeInvocationCount()));
        if (this.security.credentialProvider() != null) {
            values.put("credentialVersion", this.security.credentialProvider().current().credential().version());
            values.put("credentialRotatedAt", this.security.credentialProvider().current().rotatedAt().toString());
        }
        return Map.copyOf(values);
    }

    private void enforceResourcePolicy(
            final SecurityContext context,
            final ResourcePolicyAction action,
            final String serviceName,
            final String objectId,
            final String methodDescriptor,
            final ResourceUsageSnapshot usage,
            final Map<String, String> metadata) {
        ResourcePolicyRequest request = new ResourcePolicyRequest(action, new ApplicationContextId(this.identity().registryContext()),
                this.identity().runtimeId(), serviceName, objectId, methodDescriptor,
                new ResourceQuota(this.security.maxConcurrentInvocations(), this.security.maxInboundMessageBytes(),
                        this.security.maxOutboundMessageBytes(), 0),
                usage, metadata);
        ResourcePolicyDecision decision = this.security.resourcePolicy().evaluate(context, request);
        if (decision.allowed()) {
            return;
        }
        this.resourcePolicyDenyCount.incrementAndGet();
        PermissionRequest auditRequest = new PermissionRequest(PermissionAction.INVOKE, objectId.isBlank() ? serviceName : objectId,
                this.identity().registryContext(), this.identity().runtimeId(), serviceName, methodDescriptor, "", "",
                Map.of("resourceAction", action.name(), "resourceReason", decision.reason()));
        this.audit(context, auditRequest, AuthorizationDecision.deny(decision.policyId(), decision.reason()));
        if (decision.overloaded()) {
            this.overloadRejectionCount.incrementAndGet();
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, decision.reason().isBlank() ? "resource policy overload" : decision.reason());
        }
        throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, decision.reason().isBlank() ? "resource policy denied" : decision.reason());
    }

    private int activeInvocationCount() {
        return this.security.maxConcurrentInvocations() - this.invocationPermits.availablePermits();
    }

    private int callbackExportCount() {
        int count = 0;
        for (RuntimeObject runtimeObject : this.objects.values()) {
            if ("true".equals(runtimeObject.ref.metadata().get("callback"))) {
                count++;
            }
        }
        return count;
    }

    private void collectCallbackObjectIds(final EncodedValue value, final Set<String> objectIds, final int depth) {
        if (value == null || depth > MAX_ENCODED_SIZE_DEPTH) {
            return;
        }
        Object raw = value.value();
        if (raw instanceof ObjectRef ref && "true".equals(ref.metadata().get("callback.auto"))) {
            objectIds.add(ref.objectId());
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof EncodedValue encodedValue) {
                    this.collectCallbackObjectIds(encodedValue, objectIds, depth + 1);
                }
            }
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (item instanceof EncodedValue encodedValue) {
                    this.collectCallbackObjectIds(encodedValue, objectIds, depth + 1);
                }
            }
        }
    }

    private void validateReplayAndTimestamp(
            final SecurityContext context,
            final InvocationOptions options,
            final ObjectRef target,
            final MethodDescriptor method) {
        if (!this.security.replayProtectionEnabled()) {
            return;
        }
        long nowMillis = this.configuration.clock().millis();
        long createdAtMillis = context.createdAt().toEpochMilli();
        long skewMillis = Math.abs(nowMillis - createdAtMillis);
        if (skewMillis > this.security.allowedClockSkew().toMillis()) {
            this.timestampRejectionCount.incrementAndGet();
            this.auditSecurityRejection(context, PermissionAction.INVOKE, target, method, "timestamp-outside-allowed-skew");
            throw MiddlewareException.remote(MiddlewareErrorCode.INVALID_ARGUMENT, "request timestamp is outside allowed skew",
                    options.invocationId(), this.identity().runtimeId(), false, false);
        }
        this.evictReplayEntries(nowMillis);
        String key = context.principal().id() + "|" + target.registryContext() + "|" + target.objectId() + "|" + options.invocationId();
        long expiresAtMillis = nowMillis + Math.max(1L, this.security.allowedClockSkew().toMillis()) + Math.max(1L, options.deadline().toMillis());
        Long previous = this.replayCache.putIfAbsent(key, expiresAtMillis);
        if (previous != null) {
            if (previous.longValue() >= nowMillis) {
                this.replayRejectionCount.incrementAndGet();
                this.auditSecurityRejection(context, PermissionAction.INVOKE, target, method, "replayed-invocation-id");
                throw MiddlewareException.remote(MiddlewareErrorCode.INVALID_ARGUMENT, "replayed invocation id",
                        options.invocationId(), this.identity().runtimeId(), false, false);
            }
            this.replayCache.replace(key, previous, Long.valueOf(expiresAtMillis));
        }
        if (this.replayCache.size() > this.security.maxReplayEntries()) {
            this.evictReplayEntries(nowMillis);
            if (this.replayCache.size() > this.security.maxReplayEntries()) {
                this.replayCache.remove(key, Long.valueOf(expiresAtMillis));
                this.replayRejectionCount.incrementAndGet();
                this.auditSecurityRejection(context, PermissionAction.INVOKE, target, method, "replay-cache-capacity");
                throw MiddlewareException.remote(MiddlewareErrorCode.OVERLOADED, "replay cache capacity exceeded",
                        options.invocationId(), this.identity().runtimeId(), true, false);
            }
        }
    }

    private void evictReplayEntries(final long nowMillis) {
        for (Map.Entry<String, Long> entry : this.replayCache.entrySet()) {
            if (entry.getValue().longValue() < nowMillis) {
                this.replayCache.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void auditSecurityRejection(
            final SecurityContext context,
            final PermissionAction action,
            final ObjectRef target,
            final MethodDescriptor method,
            final String reason) {
        PermissionRequest request = new PermissionRequest(action, target.objectId(), target.registryContext(), target.runtimeId(),
                target.serviceName(), method.toString(), target.transport(), "", Map.of("reasonCode", reason));
        this.audit(context, request, AuthorizationDecision.deny(this.security.policy().id(), reason));
    }

    private int estimateEncodedBytes(final List<EncodedValue> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (EncodedValue argument : arguments) {
            total += this.estimateEncodedBytes(argument);
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private int estimateEncodedBytes(final EncodedValue value) {
        if (value == null) {
            return 0;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        long total = utf8Bytes(value.kind()) + utf8Bytes(value.typeName());
        total += estimateEncodedPayloadBytes(value.value(), 0, seen);
        for (Map.Entry<String, String> entry : value.metadata().entrySet()) {
            total += utf8Bytes(entry.getKey()) + utf8Bytes(entry.getValue());
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static long estimateEncodedPayloadBytes(final Object value, final int depth, final Set<Object> seen) {
        if (value == null) {
            return 0L;
        }
        if (depth > MAX_ENCODED_SIZE_DEPTH) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "encoded invocation payload structure exceeds configured depth");
        }
        if (!isSimpleEncodedPayload(value) && !seen.add(value)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "encoded invocation payload contains a cycle");
        }
        if (seen.size() > MAX_ENCODED_SIZE_OBJECTS) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "encoded invocation payload structure exceeds configured count");
        }
        if (value instanceof EncodedValue encodedValue) {
            return encodedValueSize(encodedValue, depth + 1, seen);
        }
        if (value instanceof String text) {
            return utf8Bytes(text);
        }
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        if (value instanceof Number) {
            return Long.BYTES + Long.BYTES;
        }
        if (value instanceof Boolean) {
            return 1L;
        }
        if (value instanceof Character) {
            return Character.BYTES;
        }
        if (value instanceof ObjectRef ref) {
            return objectRefBytes(ref, depth + 1, seen);
        }
        if (value instanceof List<?> list) {
            return listBytes(list, depth + 1, seen);
        }
        if (value instanceof Map<?, ?> map) {
            return mapBytes(map, depth + 1, seen);
        }
        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "encoded invocation payload contains unsupported structure");
    }

    private static boolean isSimpleEncodedPayload(final Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character;
    }

    private static long encodedValueSize(final EncodedValue value, final int depth, final Set<Object> seen) {
        long total = utf8Bytes(value.kind()) + utf8Bytes(value.typeName());
        total += estimateEncodedPayloadBytes(value.value(), depth, seen);
        for (Map.Entry<String, String> entry : value.metadata().entrySet()) {
            total += utf8Bytes(entry.getKey()) + utf8Bytes(entry.getValue());
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return total;
    }

    private static long objectRefBytes(final ObjectRef ref, final int depth, final Set<Object> seen) {
        long total = utf8Bytes(ref.objectId())
                + utf8Bytes(ref.logicalName())
                + utf8Bytes(ref.serviceName())
                + utf8Bytes(ref.runtimeId())
                + utf8Bytes(ref.registryContext())
                + utf8Bytes(ref.transport())
                + utf8Bytes(ref.endpoint())
                + Long.BYTES
                + Long.BYTES;
        total += listBytes(ref.interfaces(), depth + 1, seen);
        total += mapBytes(ref.metadata(), depth + 1, seen);
        return total;
    }

    private static long listBytes(final List<?> list, final int depth, final Set<Object> seen) {
        if (list.size() > MAX_CONTAINER_ENTRIES) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "encoded invocation payload collection exceeds configured count");
        }
        long total = Integer.BYTES;
        for (Object item : list) {
            total += estimateEncodedPayloadBytes(item, depth, seen);
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return total;
    }

    private static long mapBytes(final Map<?, ?> map, final int depth, final Set<Object> seen) {
        if (map.size() > MAX_CONTAINER_ENTRIES) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "encoded invocation payload map exceeds configured count");
        }
        long total = Integer.BYTES;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String textKey)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "encoded invocation payload contains non-string map key");
            }
            total += utf8Bytes(textKey);
            total += estimateEncodedPayloadBytes(entry.getValue(), depth, seen);
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return total;
    }

    private static long utf8Bytes(final String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
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
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return total;
    }

    private Class<?> classFor(final String typeName) {
        try {
            return switch (typeName) {
                case "void" -> Void.TYPE;
                case "boolean" -> Boolean.TYPE;
                case "byte" -> Byte.TYPE;
                case "short" -> Short.TYPE;
                case "int" -> Integer.TYPE;
                case "long" -> Long.TYPE;
                case "float" -> Float.TYPE;
                case "double" -> Double.TYPE;
                case "char" -> Character.TYPE;
                default -> Class.forName(typeName);
            };
        } catch (ClassNotFoundException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERFACE_MISMATCH, "method parameter type is not available", Map.of(), null, null, false, false), exception);
        }
    }

    private String defaultTransportId() {
        if (this.transports.containsKey("grpc")) {
            return "grpc";
        }
        return this.transports.keySet().stream().findFirst()
                .orElseThrow(() -> MiddlewareException.of(MiddlewareErrorCode.TRANSPORT_ERROR, "no transport providers configured"));
    }

    private String endpointFor(final String transportId) {
        for (TransportStatus status : this.transportStatuses) {
            if (status.transportId().equals(transportId)) {
                return status.endpoint();
            }
        }
        throw MiddlewareException.of(MiddlewareErrorCode.TRANSPORT_ERROR, "transport endpoint is not available: " + transportId);
    }

    private void closeObject(final RuntimeObject runtimeObject, final boolean authorize) {
        if (authorize) {
            this.authorize(this.currentSecurityContext(), PermissionAction.UNBIND, runtimeObject.ref.objectId(), runtimeObject.ref.serviceName(),
                    runtimeObject.ref.registryContext(), "", runtimeObject.ref.transport(), "", Map.of());
        }
        RuntimeException primaryFailure = null;
        synchronized (this.publicationLock) { runtimeObject.objectState = ObjectState.ZOMBIE; }
        ScheduledFuture<?> heartbeat = runtimeObject.heartbeat;
        if (heartbeat != null) {
            heartbeat.cancel(true);
        }
        try {
            this.invokeLifecycle(runtimeObject.instance, Shutdown.class);
        } catch (MiddlewareException exception) {
            primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
        }
        try {
            runtimeObject.provider.unexport(runtimeObject.ref);
        } catch (RuntimeException exception) {
            primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
        }
        if (runtimeObject.registrationState == RegistrationState.LOCAL_AND_GLOBAL || runtimeObject.registrationState == RegistrationState.GLOBAL) {
            try {
                this.registry.unbind(runtimeObject.ref);
            } catch (RuntimeException exception) {
                primaryFailure = this.recordCleanupFailure(primaryFailure, exception);
            }
        }
        runtimeObject.objectState = ObjectState.DEAD;
        runtimeObject.registrationState = RegistrationState.NONE;
        this.objects.remove(runtimeObject.ref.objectId());
        this.localReferences.remove(runtimeObject.instance);
        if (primaryFailure != null) {
            if (this.containsInterrupted(primaryFailure)) {
                Thread.currentThread().interrupt();
            }
            throw primaryFailure;
        }
    }

    private void validatePreparedGroup(final List<PreparedRuntimeObjectRegistration<?>> prepared) {
        Set<String> objectIds = new HashSet<>(prepared.size());
        for (PreparedRuntimeObjectRegistration<?> preparedRegistration : prepared) {
            if (preparedRegistration.isClosed()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export is already closed");
            }
            if (preparedRegistration.isCommitted()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export is already committed");
            }
            RuntimeObject runtimeObject = preparedRegistration.runtimeObject;
            if (runtimeObject.objectState != ObjectState.INITIALIZED
                    || runtimeObject.registrationState != RegistrationState.NONE
                    || runtimeObject.exportedObject == null) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export is not publishable");
            }
            if (!objectIds.add(runtimeObject.ref.objectId())) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export group contains a duplicate object");
            }
            if (this.objects.containsKey(runtimeObject.ref.objectId())) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export object is already published");
            }
        }
    }

    private void rollbackPreparedPublication(
            final List<RuntimeObject> runtimeObjects,
            final List<RuntimeObject> heartbeatStarted,
            final List<ObjectRef> boundRefs,
            final RuntimeException originalFailure) {
        for (RuntimeObject runtimeObject : heartbeatStarted) {
            ScheduledFuture<?> heartbeat = runtimeObject.heartbeat;
            if (heartbeat != null) {
                heartbeat.cancel(true);
                runtimeObject.heartbeat = null;
            }
        }
        for (int index = boundRefs.size() - 1; index >= 0; index--) {
            try {
                this.registry.unbind(boundRefs.get(index));
            } catch (RuntimeException cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
        for (RuntimeObject runtimeObject : runtimeObjects) {
            this.objects.remove(runtimeObject.ref.objectId());
            this.localReferences.remove(runtimeObject.instance);
            runtimeObject.registrationState = RegistrationState.NONE;
            runtimeObject.objectState = ObjectState.INITIALIZED;
        }
    }

    private MiddlewareException normalizedCommitFailure(final RuntimeException exception) {
        if (exception instanceof MiddlewareException middlewareException) {
            return middlewareException;
        }
        return new MiddlewareException(new RemoteError(
                MiddlewareErrorCode.INTERNAL,
                "failed to commit object export",
                Map.of(),
                null,
                null,
                false,
                true), exception);
    }

    private void rollbackPreparedObject(final RuntimeObject runtimeObject) {
        runtimeObject.objectState = ObjectState.ZOMBIE;
        try {
            this.invokeLifecycle(runtimeObject.instance, Shutdown.class);
        } catch (MiddlewareException ignored) {
            // Prepared export rollback continues after shutdown hook failures.
        }
        runtimeObject.provider.unexport(runtimeObject.ref);
        runtimeObject.registrationState = RegistrationState.NONE;
        runtimeObject.objectState = ObjectState.DEAD;
    }

    private void rollbackFailedPreparation(final PreparedExportState preparation, final RuntimeException originalFailure) {
        RuntimeObject runtimeObject = preparation.runtimeObject;
        this.objects.remove(runtimeObject.ref.objectId());
        this.localReferences.remove(runtimeObject.instance);
        runtimeObject.registrationState = RegistrationState.NONE;
        if (preparation.providerExportAttempted || preparation.providerExportSucceeded) {
            try {
                runtimeObject.provider.unexport(runtimeObject.ref);
            } catch (RuntimeException cleanupFailure) {
                this.suppressPreparationCleanupFailure(originalFailure, cleanupFailure);
            }
        }
        if (preparation.lifecycleStarted || preparation.lifecycleInitialized) {
            try {
                this.invokeLifecycle(runtimeObject.instance, Shutdown.class);
            } catch (MiddlewareException cleanupFailure) {
                this.suppressPreparationCleanupFailure(originalFailure, cleanupFailure);
            }
        }
        runtimeObject.exportedObject = null;
        runtimeObject.objectState = ObjectState.DEAD;
        if (this.containsInterrupted(originalFailure)) {
            Thread.currentThread().interrupt();
        }
    }

    private void suppressPreparationCleanupFailure(final RuntimeException originalFailure, final RuntimeException cleanupFailure) {
        if (originalFailure.getSuppressed().length < MAX_PREPARE_CLEANUP_SUPPRESSED) {
            originalFailure.addSuppressed(cleanupFailure);
        }
        if (this.containsInterrupted(cleanupFailure)) {
            Thread.currentThread().interrupt();
        }
    }

    private RuntimeException recordCleanupFailure(final RuntimeException primaryFailure, final RuntimeException cleanupFailure) {
        if (primaryFailure == null) {
            return cleanupFailure;
        }
        if (primaryFailure.getSuppressed().length < MAX_PREPARE_CLEANUP_SUPPRESSED) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
        if (this.containsInterrupted(cleanupFailure)) {
            Thread.currentThread().interrupt();
        }
        return primaryFailure;
    }

    private boolean containsInterrupted(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void recordReliabilityMetadataUsage(final InvocationReliabilityOptions options) {
        if (options.idempotencyKey() != null) {
            this.reliabilityIdempotencyKeyUsageCount.incrementAndGet();
        }
        if (options.causalContext() != null || options.correlationContext() != null) {
            this.reliabilityContextPropagationCount.incrementAndGet();
        }
    }

    private void enforceReliabilityDecision(final ReliabilityDecision decision, final InvocationOptions options) {
        if (decision.retrySafe()) {
            this.reliabilityRetrySafeDecisionCount.incrementAndGet();
        }
        if (decision.accepted()) {
            return;
        }
        if (decision.errorCode() == MiddlewareErrorCode.STALE_FENCING_TOKEN) {
            this.reliabilityFencingTokenRejectionCount.incrementAndGet();
        }
        if (decision.errorCode() == MiddlewareErrorCode.OVERLOADED) {
            this.reliabilityBackpressureDecisionCount.incrementAndGet();
        }
        throw MiddlewareException.remote(decision.errorCode(), decision.message(), options.invocationId(), this.identity().runtimeId(),
                decision.errorCode() == MiddlewareErrorCode.OVERLOADED, false);
    }

    private void enforceDuplicateDetection(final InvocationReliabilityOptions reliabilityOptions, final InvocationOptions options) {
        if (reliabilityOptions.duplicateDetectionPolicy() == DuplicateDetectionPolicy.DISABLED || reliabilityOptions.dedupeKey() == null) {
            return;
        }
        DedupeEntry entry = new DedupeEntry(reliabilityOptions.dedupeKey(), reliabilityOptions.durableOperationId(),
                this.configuration.clock().millis(), Map.of("invocationId", options.invocationId()));
        DedupeResult result = this.reliabilityDedupeStore.checkAndRecord(entry, reliabilityOptions.duplicateDetectionPolicy());
        this.reliabilityDuplicateDetectionDecisionCount.incrementAndGet();
        if (!result.accepted()) {
            throw MiddlewareException.remote(MiddlewareErrorCode.DUPLICATE_OPERATION, "duplicate operation rejected",
                    options.invocationId(), this.identity().runtimeId(), false, false);
        }
    }

    private void recordInvocation(
            final InvocationOptions options,
            final ObjectRef target,
            final String method,
            final long startedAtMillis,
            final long startedNanos,
            final boolean success,
            final MiddlewareErrorCode errorCode) {
        this.invocationCount.incrementAndGet();
        if (!success) {
            this.invocationFailureCount.incrementAndGet();
        }
        InvocationMetadata metadata = new InvocationMetadata(options.invocationId(), target, method, startedAtMillis,
                System.nanoTime() - startedNanos, success, errorCode,
                this.reliabilityMetadataResolver.redactedMetadata(options.reliabilityOptions()));
        this.recordObjectStats(target, startedNanos, success, errorCode);
        synchronized (this.invocationLogLock) {
            if (this.recentInvocations.size() >= 128) {
                this.recentInvocations.removeFirst();
            }
            this.recentInvocations.addLast(metadata);
        }
    }

    private void ensureOpen() {
        if (this.closed.get()) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "runtime is closed");
        }
    }

    private final class RuntimeCodecContext implements CodecContext {
        @Override
        public Optional<ObjectRef> referenceFor(final Object value, final Class<?> declaredType) {
            rejectLocalInvocationCapability(value, declaredType);
            Optional<ObjectRef> proxyRef = ProxyReferenceSupport.objectRef(value);
            if (proxyRef.isPresent()) {
                return proxyRef;
            }
            ObjectRef existing = localReferences.get(value);
            if (existing != null) {
                return Optional.of(existing);
            }
            if (declaredType != null && declaredType.isInterface() && value != null && declaredType.isInstance(value) && shouldPassByReference(value, declaredType)) {
                return Optional.of(exportCallback(value, declaredType));
            }
            return Optional.empty();
        }

        @Override
        public Object proxyFor(final ObjectRef ref, final Class<?> expectedType) {
            if (expectedType == ObjectRef.class || expectedType == Object.class) {
                return ref;
            }
            if (!expectedType.isInterface()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "remote reference target must be decoded as an interface");
            }
            @SuppressWarnings("unchecked")
            Class<Object> api = (Class<Object>) expectedType;
            return DefaultMiddlewareRuntime.this.proxyFor(ref, api);
        }

        private boolean shouldPassByReference(final Object value, final Class<?> declaredType) {
            if (value.getClass().getAnnotation(NoRemoteExport.class) != null || declaredType.getAnnotation(NoRemoteExport.class) != null) {
                return false;
            }
            Package valuePackage = value.getClass().getPackage();
            String packageName = valuePackage == null ? "" : valuePackage.getName();
            return !packageName.startsWith("java.") && !declaredType.getName().startsWith("java.");
        }
    }

    private record BenchmarkResult(long latencyNanos, boolean success, long expiresAtMillis) {
    }

    private static final class BenchmarkInvocationException extends RuntimeException {
        private BenchmarkInvocationException(final Throwable cause) {
            super(cause);
        }
    }

    private record Readiness(boolean ready, long untilNanos, long untilMillis) { }

    private static final class RuntimeObject {
        private volatile Readiness readiness;
        private volatile InvocationCompletionFactory completionFactory;
        private volatile ValueCodec valueCodec;
        private boolean codecSelectionFrozen;
        private final AtomicBoolean readinessPublicationPending = new AtomicBoolean();

        private boolean ready() {
            Readiness current = this.readiness;
            return current == null || current.ready && System.nanoTime() - current.untilNanos < 0L;
        }

        private void refreshReadinessMetadata() {
            Readiness current = this.readiness;
            if (current == null) { return; }
            Map<String, String> metadata = new LinkedHashMap<>(this.ref.metadata());
            metadata.put("j7m.readiness.ready", Boolean.toString(this.ready()));
            metadata.put("j7m.readiness.until", Long.toString(current.untilMillis));
            this.ref = this.ref.withMetadata(Map.copyOf(metadata));
        }
        private volatile ObjectRef ref;
        private final Object instance;
        private final Class<?> primaryInterface;
        private final ExportDescriptor descriptor;
        private final TransportProvider provider;
        private final ExportOptions options;
        private volatile ObjectState objectState;
        private volatile RegistrationState registrationState;
        private volatile ExportedObject exportedObject;
        private volatile ScheduledFuture<?> heartbeat;

        private RuntimeObject(
                final ObjectRef ref,
                final Object instance,
                final Class<?> primaryInterface,
                final ExportDescriptor descriptor,
                final TransportProvider provider,
                final ExportOptions options) {
            this.ref = ref;
            this.instance = instance;
            this.primaryInterface = primaryInterface;
            this.descriptor = descriptor;
            this.provider = provider;
            this.options = options;
            this.objectState = ObjectState.CONSTRUCTED;
            this.registrationState = RegistrationState.NONE;
        }
    }

    private static final class RuntimeObjectRegistration<T> implements ObjectRegistration<T> {
        private final RuntimeObject runtimeObject;
        private final DefaultMiddlewareRuntime runtime;
        private final AtomicBoolean closed;

        private RuntimeObjectRegistration(final RuntimeObject runtimeObject, final DefaultMiddlewareRuntime runtime) {
            this.runtimeObject = runtimeObject;
            this.runtime = runtime;
            this.closed = new AtomicBoolean(false);
        }

        @Override
        public void setReadiness(final boolean ready, final Duration validity) {
            if (this.closed.get()) { throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "export registration is closed"); }
            this.runtime.setReadiness(this.runtimeObject, ready, validity);
        }

        @Override
        public ObjectRef ref() {
            return this.runtimeObject.ref;
        }

        @Override
        public T instance() {
            @SuppressWarnings("unchecked")
            T instance = (T) this.runtimeObject.instance;
            return instance;
        }

        @Override
        public InstanceStatus status() {
            return new InstanceStatus(this.runtimeObject.ref, this.runtimeObject.objectState, this.runtimeObject.registrationState);
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.runtime.closeObject(this.runtimeObject, true);
            }
        }
    }

    private static final class PreparedRuntimeObjectRegistration<T> implements PreparedObjectRegistration<T> {
        private final RuntimeObject runtimeObject;
        private final DefaultMiddlewareRuntime runtime;
        private final long preparedAtMillis;
        private final AtomicBoolean closed;
        private final AtomicReference<ObjectRegistration<T>> committed;

        private PreparedRuntimeObjectRegistration(
                final RuntimeObject runtimeObject,
                final DefaultMiddlewareRuntime runtime,
                final long preparedAtMillis) {
            this.runtimeObject = runtimeObject;
            this.runtime = runtime;
            this.preparedAtMillis = preparedAtMillis;
            this.closed = new AtomicBoolean(false);
            this.committed = new AtomicReference<>();
        }

        @Override
        public synchronized void setInvocationCompletionFactory(final InvocationCompletionFactory factory) {
            if (this.closed.get() || this.committed.get() != null) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Invocation ownership must be installed before commit");
            }
            this.runtimeObject.completionFactory = java.util.Objects.requireNonNull(factory, "factory");
        }

        @Override
        public synchronized void setValueCodec(final ValueCodec codec) {
            synchronized (this.runtimeObject) {
                if (this.closed.get() || this.committed.get() != null || this.runtimeObject.valueCodec != null || this.runtimeObject.codecSelectionFrozen) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Export codec must be installed once before publication");
                }
                this.runtimeObject.valueCodec = java.util.Objects.requireNonNull(codec, "codec");
            }
        }

        @Override
        public void setReadiness(final boolean ready, final Duration validity) {
            if (this.closed.get()) { throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "export registration is closed"); }
            this.runtime.setReadiness(this.runtimeObject, ready, validity);
        }

        @Override
        public ObjectRef ref() {
            return this.runtimeObject.ref;
        }

        @Override
        public T instance() {
            @SuppressWarnings("unchecked")
            T instance = (T) this.runtimeObject.instance;
            return instance;
        }

        @Override
        public synchronized ObjectRegistration<T> commit() {
            ObjectRegistration<T> existing = this.committed.get();
            if (existing != null) {
                return existing;
            }
            if (this.closed.get()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export is already closed");
            }
            List<ObjectRegistration<?>> registrations;
            try {
                registrations = this.runtime.commitPreparedExports(List.of(this));
            } catch (MiddlewareException exception) {
                ObjectRegistration<T> committedRegistration = this.committed.get();
                if (committedRegistration != null) {
                    return committedRegistration;
                }
                throw exception;
            }
            @SuppressWarnings("unchecked")
            ObjectRegistration<T> registration = (ObjectRegistration<T>) registrations.getFirst();
            return registration;
        }

        @Override
        public synchronized void close() {
            synchronized (this.runtime.publicationLock) {
                if (!this.closed.compareAndSet(false, true)) {
                    return;
                }
                ObjectRegistration<T> registration = this.committed.get();
                if (registration != null) {
                    registration.close();
                    return;
                }
                this.runtime.rollbackPreparedObject(this.runtimeObject);
            }
        }

        private boolean isClosed() {
            return this.closed.get();
        }

        private boolean isCommitted() {
            return this.committed.get() != null;
        }

        private void markCommitted(final ObjectRegistration<?> registration) {
            @SuppressWarnings("unchecked")
            ObjectRegistration<T> typedRegistration = (ObjectRegistration<T>) registration;
            if (!this.committed.compareAndSet(null, typedRegistration)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "prepared export is already committed");
            }
        }
    }

    private static final class PreparedExportState {
        private final RuntimeObject runtimeObject;
        private boolean lifecycleInitialized;
        private boolean lifecycleStarted;
        private boolean providerExportAttempted;
        private boolean providerExportSucceeded;

        private PreparedExportState(final RuntimeObject runtimeObject) {
            this.runtimeObject = runtimeObject;
        }
    }
}
