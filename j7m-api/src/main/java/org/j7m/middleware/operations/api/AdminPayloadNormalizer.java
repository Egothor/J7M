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

package org.j7m.middleware.operations.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.application.api.ActivationIdentity;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.ApplicationVersion;
import org.j7m.middleware.application.api.RuntimeServiceIdentity;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;

final class AdminPayloadNormalizer {
    private static final int MAX_DISCOVERY_CAPABILITIES = 32;
    private static final int MAX_DISCOVERY_RECORDS = NuggetDiscovery.MAX_RESULTS;
    private static final int MAX_INTROSPECTION_ITEMS = NuggetAdminContract.MAX_PAGE_LIMIT;
    private static final int MAX_APPLICATION_STEPS = 32;
    private static final int MAX_EXPORTED_SERVICES = 64;
    private static final String DETAILS_OMITTED = "details-omitted";

    private AdminPayloadNormalizer() {
    }

    static boolean supports(final Class<?> payloadClass) {
        return payloadClass == NuggetAdminCapabilities.class
                || payloadClass == NuggetDiscovery.Query.class
                || payloadClass == NuggetDiscovery.Selector.class
                || payloadClass == NuggetDiscovery.Result.class
                || payloadClass == NuggetDiscovery.Resolution.class
                || payloadClass == NuggetDiscovery.StatusSummary.class
                || payloadClass == NuggetAuthenticationRequest.class
                || payloadClass == NuggetAuthenticationResult.class
                || payloadClass == NuggetIntrospection.Request.class
                || payloadClass == NuggetIntrospection.Query.class
                || payloadClass == NuggetIntrospection.DiskQuery.class
                || payloadClass == NuggetIntrospection.RecentErrorQuery.class
                || payloadClass == NuggetAdminQuery.class
                || payloadClass == NuggetStatus.class
                || payloadClass == NuggetIntrospection.Result.class
                || payloadClass == NuggetApplicationLifecycle.Request.class
                || payloadClass == NuggetApplicationLifecycle.Result.class
                || payloadClass == NuggetControl.Request.class
                || payloadClass == NuggetControl.Result.class;
    }

    static Object normalize(final Object payload) {
        if (payload instanceof NuggetAdminCapabilities value) {
            return capabilities(value);
        }
        if (payload instanceof NuggetDiscovery.Query value) {
            return discoveryQuery(value);
        }
        if (payload instanceof NuggetDiscovery.Selector value) {
            return discoverySelector(value);
        }
        if (payload instanceof NuggetDiscovery.Result value) {
            return discoveryResult(value);
        }
        if (payload instanceof NuggetDiscovery.Resolution value) {
            return discoveryResolution(value);
        }
        if (payload instanceof NuggetDiscovery.StatusSummary value) {
            return discoveryStatusSummary(value);
        }
        if (payload instanceof NuggetAuthenticationRequest value) {
            return authenticationRequest(value);
        }
        if (payload instanceof NuggetAuthenticationResult value) {
            return authenticationResult(value);
        }
        if (payload instanceof NuggetIntrospection.Request value) {
            return introspectionRequest(value);
        }
        if (payload instanceof NuggetIntrospection.Query value) {
            return introspectionQuery(value);
        }
        if (payload instanceof NuggetIntrospection.DiskQuery value) {
            return new NuggetIntrospection.DiskQuery(
                    safeText(value.role()),
                    introspectionQuery(value.page()));
        }
        if (payload instanceof NuggetIntrospection.RecentErrorQuery value) {
            return new NuggetIntrospection.RecentErrorQuery(
                    safeText(value.component()),
                    introspectionQuery(value.page()));
        }
        if (payload instanceof NuggetAdminQuery value) {
            return value;
        }
        if (payload instanceof NuggetStatus value) {
            return nuggetStatus(value);
        }
        if (payload instanceof NuggetIntrospection.Result<?> value) {
            return introspectionResult(value);
        }
        if (payload instanceof NuggetApplicationLifecycle.Request value) {
            return lifecycleRequest(value);
        }
        if (payload instanceof NuggetApplicationLifecycle.Result value) {
            return lifecycleResult(value);
        }
        if (payload instanceof NuggetControl.Request value) {
            return controlRequest(value);
        }
        if (payload instanceof NuggetControl.Result value) {
            return controlResult(value);
        }
        throw new IllegalArgumentException("admin command payload is missing public contract validation coverage");
    }

    static RemoteError remoteError(final RemoteError error) {
        if (error == null) {
            return null;
        }
        final MiddlewareErrorCode code = error.code() == null ? MiddlewareErrorCode.INTERNAL : error.code();
        String message = safeDiagnosticText(error.message());
        if (message.isBlank()) {
            message = code.name();
        }
        return new RemoteError(
                code,
                message,
                metadata(error.details()),
                tokenNonSensitive(error.invocationId()),
                tokenNonSensitive(error.remoteRuntimeId()),
                error.retryable(),
                error.dirtyTarget());
    }

    private static NuggetAdminCapabilities capabilities(final NuggetAdminCapabilities value) {
        return new NuggetAdminCapabilities(
                value.available(),
                value.protocolVersion(),
                value.jsonFormatName(),
                value.jsonFormatVersion(),
                enumList(value.capabilities(), NuggetAdminCapabilities.MAX_CAPABILITIES, "capabilities"),
                enumList(value.operationKinds(), NuggetAdminCapabilities.MAX_OPERATION_KINDS, "operationKinds"),
                value.maxPageLimit(),
                value.maxMetadataEntries(),
                instant(value.collectedAt()),
                metadata(value.metadata()));
    }

    private static NuggetDiscovery.Query discoveryQuery(final NuggetDiscovery.Query value) {
        return new NuggetDiscovery.Query(value.includeStale(), value.limit(), token(value.correlationId()));
    }

    private static NuggetDiscovery.Selector discoverySelector(final NuggetDiscovery.Selector value) {
        return new NuggetDiscovery.Selector(value.type(), safeText(value.value()), token(value.correlationId()));
    }

    private static NuggetDiscovery.Result discoveryResult(final NuggetDiscovery.Result value) {
        final List<NuggetDiscovery.Record> records = objectList(
                value.records(),
                MAX_DISCOVERY_RECORDS,
                AdminPayloadNormalizer::discoveryRecord,
                "discovery records");
        final int returned = records.size();
        final int totalAvailable = Math.max(returned, value.totalAvailable());
        return new NuggetDiscovery.Result(
                records,
                returned,
                totalAvailable,
                instant(value.collectedAt()),
                value.partial() || value.returned() > returned,
                warnings(value.warnings()));
    }

    private static NuggetDiscovery.Resolution discoveryResolution(final NuggetDiscovery.Resolution value) {
        return new NuggetDiscovery.Resolution(
                value.status(),
                objectList(value.records(), MAX_DISCOVERY_RECORDS, AdminPayloadNormalizer::discoveryRecord, "resolution records"),
                token(value.safeReason()),
                value.errorCode(),
                instant(value.resolvedAt()),
                warnings(value.warnings()));
    }

    private static NuggetDiscovery.StatusSummary discoveryStatusSummary(final NuggetDiscovery.StatusSummary value) {
        return new NuggetDiscovery.StatusSummary(
                value.localPublicationEnabled(),
                nonNegativeInt(value.configuredTargetCount(), "configuredTargetCount"),
                instant(value.lastPublicationAt()),
                instant(value.lastScanAt()),
                nonNegativeInt(value.activeRecordCount(), "activeRecordCount"),
                metadata(value.metadata()));
    }

    private static NuggetDiscovery.Record discoveryRecord(final NuggetDiscovery.Record value) {
        if (value == null) {
            throw new IllegalArgumentException("discovery record must not be null");
        }
        return new NuggetDiscovery.Record(
                token(value.nuggetId()),
                safeText(value.nuggetName()),
                token(value.runtimeId()),
                discoveryEndpoint(value.endpoint()),
                value.protocolMinimum(),
                value.protocolMaximum(),
                value.compatibility(),
                stringList(value.capabilities(), MAX_DISCOVERY_CAPABILITIES, "discovery capabilities"),
                token(value.processId()),
                token(value.hostSummary()),
                instant(value.startedAt()),
                instant(value.lastSeenAt()),
                instant(value.expiresAt()),
                value.source(),
                value.status(),
                warnings(value.warnings()));
    }

    private static NuggetDiscovery.EndpointDescriptor discoveryEndpoint(final NuggetDiscovery.EndpointDescriptor value) {
        if (value == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        return new NuggetDiscovery.EndpointDescriptor(
                tokenOrDefault(value.transport(), "local"),
                tokenOrDefault(value.scheme(), "j7m-local"),
                safeText(value.host()),
                value.port(),
                token(value.localId()));
    }

    private static NuggetAuthenticationRequest authenticationRequest(final NuggetAuthenticationRequest value) {
        return NuggetAuthenticationRequest.of(
                token(value.correlationId()),
                value.source(),
                value.trustedSource() ? tokenNonSensitive(value.sourceId()) : "",
                value.trustedSource(),
                instant(value.requestedAt()));
    }

    private static NuggetAuthenticationResult authenticationResult(final NuggetAuthenticationResult value) {
        return new NuggetAuthenticationResult(
                value.outcome(),
                value.failureReason(),
                token(value.nuggetId()),
                token(value.correlationId()),
                token(value.admissionId()),
                duration(value.retryAfter(), NuggetAdminContract.MAX_DURATION),
                instant(value.timestamp()),
                remoteError(value.error()),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.Request introspectionRequest(final NuggetIntrospection.Request value) {
        return new NuggetIntrospection.Request(
                token(value.admissionId()),
                securityContext(value.securityContext()),
                token(value.correlationId()),
                instant(value.requestedAt()),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.Query introspectionQuery(final NuggetIntrospection.Query value) {
        return new NuggetIntrospection.Query(
                value.offset(),
                value.limit(),
                tokenOrDefault(value.sortBy(), "id"),
                value.direction(),
                metadata(value.filters()));
    }

    private static NuggetStatus nuggetStatus(final NuggetStatus value) {
        return new NuggetStatus(
                nuggetIdentity(value.identity()),
                configuration(value.configuration()),
                instant(value.statusAt()),
                metadata(value.metadata()));
    }

    private static NuggetIdentity nuggetIdentity(final NuggetIdentity value) {
        if (value == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        return new NuggetIdentity(
                requireToken(value.nuggetId(), "nuggetId"),
                safeText(value.nuggetName()),
                runtimeIdentity(value.runtimeIdentity()),
                instant(value.startedAt()),
                token(value.processId()),
                safeText(value.hostSummary()),
                token(value.j7mVersion()),
                safeText(value.jvmVersion()),
                value.state(),
                metadata(value.capabilities()),
                metadata(value.metadata()));
    }

    private static NuggetConfigurationSummary configuration(final NuggetConfigurationSummary value) {
        if (value == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        return new NuggetConfigurationSummary(
                value.adminState(),
                value.adminEnabled(),
                value.unsafeDeveloperMode(),
                value.pinSourceType(),
                value.lowerSafetyPinSource(),
                token(value.verifierFormat()),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.Result<?> introspectionResult(final NuggetIntrospection.Result<?> value) {
        return new NuggetIntrospection.Result<>(
                value.admitted(),
                value.authorized(),
                value.category(),
                instant(value.collectedAt()),
                value.partial(),
                introspectionValue(value.value()),
                remoteError(value.error()),
                warnings(value.warnings()),
                metadata(value.metadata()));
    }

    private static Object introspectionValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return safeText(text);
        }
        if (value instanceof Number number) {
            return scalarNumber(number);
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof NuggetIntrospection.CollectionResult<?> result) {
            return collectionResult(result);
        }
        if (value instanceof NuggetIntrospection.Overview result) {
            return overview(result);
        }
        if (value instanceof NuggetIntrospection.EnvironmentSummary result) {
            return environment(result);
        }
        if (value instanceof NuggetIntrospection.DiskVolumeSummary result) {
            return diskVolume(result);
        }
        if (value instanceof NuggetIntrospection.HealthSummary result) {
            return health(result);
        }
        if (value instanceof NuggetIntrospection.ReadinessSummary result) {
            return readiness(result);
        }
        if (value instanceof NuggetIntrospection.RuntimeSummary result) {
            return runtimeSummary(result);
        }
        if (value instanceof NuggetIntrospection.TransportSummary result) {
            return transport(result);
        }
        if (value instanceof NuggetIntrospection.RegistrySummary result) {
            return registry(result);
        }
        if (value instanceof NuggetIntrospection.RepositorySummary result) {
            return repository(result);
        }
        if (value instanceof NuggetIntrospection.ApplicationSummary result) {
            return applicationSummary(result);
        }
        if (value instanceof NuggetIntrospection.ApplicationDetail result) {
            return applicationDetail(result);
        }
        if (value instanceof NuggetIntrospection.ObjectSummary result) {
            return objectSummary(result);
        }
        if (value instanceof NuggetIntrospection.ObjectDetail result) {
            return objectDetail(result);
        }
        if (value instanceof NuggetIntrospection.ServiceSummary result) {
            return service(result);
        }
        if (value instanceof NuggetIntrospection.RecentErrorEntry result) {
            return recentError(result);
        }
        if (value instanceof NuggetIntrospection.MetricsSummary result) {
            return metrics(result);
        }
        if (value instanceof NuggetIntrospection.MetricEntry result) {
            return metric(result);
        }
        if (value instanceof NuggetIntrospection.KeyValue result) {
            return keyValue(result);
        }
        throw new IllegalArgumentException("introspection result value is not a public contract DTO");
    }

    private static Long scalarNumber(final Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            final long scalar = value.longValue();
            if (scalar < 0L) {
                throw new IllegalArgumentException("introspection scalar number outside public contract bounds");
            }
            return Long.valueOf(scalar);
        }
        if (value instanceof BigInteger integer) {
            if (integer.signum() < 0 || integer.bitLength() > 63) {
                throw new IllegalArgumentException("introspection scalar number outside public contract bounds");
            }
            return Long.valueOf(integer.longValue());
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return scalarNumber(decimal.toBigIntegerExact());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("introspection scalar number outside public contract bounds", exception);
            }
        }
        if (value instanceof Double || value instanceof Float) {
            final double scalar = value.doubleValue();
            if (!Double.isFinite(scalar) || scalar < 0.0d || scalar > Long.MAX_VALUE || Math.rint(scalar) != scalar) {
                throw new IllegalArgumentException("introspection scalar number outside public contract bounds");
            }
            final long integer = (long) scalar;
            if ((double) integer != scalar) {
                throw new IllegalArgumentException("introspection scalar number outside public contract bounds");
            }
            return Long.valueOf(integer);
        }
        throw new IllegalArgumentException("introspection scalar number type is not part of the public contract");
    }

    private static NuggetIntrospection.CollectionResult<?> collectionResult(
            final NuggetIntrospection.CollectionResult<?> value) {
        final List<Object> items = objectList(
                value.items(),
                MAX_INTROSPECTION_ITEMS,
                AdminPayloadNormalizer::introspectionValue,
                "introspection items");
        final int returned = items.size();
        final int limit = value.limit() <= 0 ? NuggetIntrospection.DEFAULT_LIMIT : Math.min(value.limit(), MAX_INTROSPECTION_ITEMS);
        final int totalAvailable = value.totalAvailable() < returned ? returned : value.totalAvailable();
        return new NuggetIntrospection.CollectionResult<>(
                items,
                nonNegativeInt(value.offset(), "offset"),
                limit,
                returned,
                totalAvailable,
                value.totalAvailableAuthorized(),
                tokenOrDefault(value.sortedBy(), "id"),
                value.direction(),
                metadata(value.filters()),
                instant(value.collectedAt()),
                value.partial() || value.returned() > returned,
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.Overview overview(final NuggetIntrospection.Overview value) {
        return new NuggetIntrospection.Overview(
                nuggetStatus(value.status()),
                value.runtime() == null ? null : runtimeSummary(value.runtime()),
                value.health() == null ? null : health(value.health()),
                value.readiness() == null ? null : readiness(value.readiness()),
                value.partial(),
                warnings(value.warnings()));
    }

    private static NuggetIntrospection.EnvironmentSummary environment(
            final NuggetIntrospection.EnvironmentSummary value) {
        return new NuggetIntrospection.EnvironmentSummary(
                safeText(value.jvmVersion()),
                safeText(value.jvmVendor()),
                safeText(value.vmName()),
                safeText(value.osName()),
                safeText(value.osVersion()),
                token(value.architecture()),
                token(value.processId()),
                instant(value.nuggetStartTime()),
                duration(value.processUptime(), NuggetAdminContract.MAX_DURATION),
                nonNegativeInt(value.availableProcessors(), "availableProcessors"),
                nonNegativeLong(value.heapUsedBytes(), "heapUsedBytes"),
                nonNegativeLong(value.heapCommittedBytes(), "heapCommittedBytes"),
                negativeOneOrNonNegative(value.heapMaxBytes(), "heapMaxBytes"),
                nonNegativeLong(value.nonHeapUsedBytes(), "nonHeapUsedBytes"),
                nonNegativeLong(value.nonHeapCommittedBytes(), "nonHeapCommittedBytes"),
                nonNegativeInt(value.liveThreadCount(), "liveThreadCount"),
                nonNegativeInt(value.daemonThreadCount(), "daemonThreadCount"),
                nonNegativeInt(value.peakThreadCount(), "peakThreadCount"),
                nonNegativeLong(value.totalStartedThreadCount(), "totalStartedThreadCount"),
                nonNegativeInt(value.loadedClassCount(), "loadedClassCount"),
                nonNegativeLong(value.totalLoadedClassCount(), "totalLoadedClassCount"),
                objectList(value.garbageCollectors(), 64, AdminPayloadNormalizer::keyValue, "garbage collectors"),
                finiteOrDefault(value.processCpuLoad(), -1.0d),
                finiteOrDefault(value.systemCpuLoad(), -1.0d),
                negativeOneOrNonNegative(value.processCpuTimeNanos(), "processCpuTimeNanos"),
                safeText(value.workingDirectorySummary()),
                stringList(value.environmentVariableNames(), MAX_INTROSPECTION_ITEMS, "environment names"),
                objectList(value.jvmArgumentSummaries(), MAX_INTROSPECTION_ITEMS, AdminPayloadNormalizer::keyValue, "jvm arguments"),
                metadata(value.capabilities()),
                instant(value.collectedAt()));
    }

    private static NuggetIntrospection.DiskVolumeSummary diskVolume(
            final NuggetIntrospection.DiskVolumeSummary value) {
        return new NuggetIntrospection.DiskVolumeSummary(
                tokenOrDefault(value.rootId(), "unknown"),
                tokenOrDefault(value.role(), "unknown"),
                stringList(value.roles(), 32, "disk roles"),
                safeText(value.pathSummary()),
                negativeOneOrNonNegative(value.totalBytes(), "totalBytes"),
                negativeOneOrNonNegative(value.usableBytes(), "usableBytes"),
                negativeOneOrNonNegative(value.availableBytes(), "availableBytes"),
                negativeOneOrNonNegative(value.usedBytes(), "usedBytes"),
                finiteOrDefault(value.percentUsed(), -1.0d),
                value.health(),
                value.readOnly(),
                value.availability(),
                instant(value.collectedAt()),
                token(value.safeErrorCode()),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.HealthSummary health(final NuggetIntrospection.HealthSummary value) {
        return new NuggetIntrospection.HealthSummary(
                value.state(),
                objectList(value.components(), MAX_INTROSPECTION_ITEMS, AdminPayloadNormalizer::component, "health components"),
                instant(value.collectedAt()),
                value.partial());
    }

    private static NuggetIntrospection.ReadinessSummary readiness(final NuggetIntrospection.ReadinessSummary value) {
        return new NuggetIntrospection.ReadinessSummary(
                value.state(),
                objectList(value.components(), MAX_INTROSPECTION_ITEMS, AdminPayloadNormalizer::component, "readiness components"),
                instant(value.collectedAt()),
                value.partial());
    }

    private static NuggetIntrospection.ComponentSummary component(final NuggetIntrospection.ComponentSummary value) {
        if (value == null) {
            throw new IllegalArgumentException("component must not be null");
        }
        return new NuggetIntrospection.ComponentSummary(
                tokenOrDefault(value.component(), "unknown"),
                value.availability(),
                value.health(),
                value.readiness(),
                value.required(),
                safeText(value.safeReason()),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.RuntimeSummary runtimeSummary(final NuggetIntrospection.RuntimeSummary value) {
        return new NuggetIntrospection.RuntimeSummary(
                token(value.runtimeId()),
                token(value.nuggetId()),
                value.lifecycleState(),
                instant(value.startTime()),
                duration(value.uptime(), NuggetAdminContract.MAX_DURATION),
                nonNegativeInt(value.activeObjectCount(), "activeObjectCount"),
                nonNegativeInt(value.exportedObjectCount(), "exportedObjectCount"),
                nonNegativeInt(value.hostedApplicationCount(), "hostedApplicationCount"),
                nonNegativeLong(value.invocationCount(), "invocationCount"),
                nonNegativeLong(value.invocationFailureCount(), "invocationFailureCount"),
                nonNegativeInt(value.recentFailureCount(), "recentFailureCount"),
                metadata(value.security()),
                instant(value.collectedAt()));
    }

    private static NuggetIntrospection.TransportSummary transport(final NuggetIntrospection.TransportSummary value) {
        return new NuggetIntrospection.TransportSummary(
                token(value.transportId()),
                safeText(value.endpointSummary()),
                value.running(),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.RegistrySummary registry(final NuggetIntrospection.RegistrySummary value) {
        return new NuggetIntrospection.RegistrySummary(
                nonNegativeInt(value.recordCount(), "recordCount"),
                nonNegativeInt(value.peerCount(), "peerCount"),
                value.multicastListenerRunning(),
                nonNegativeInt(value.bootstrapHintCount(), "bootstrapHintCount"),
                nonNegativeInt(value.lookupCacheSize(), "lookupCacheSize"),
                longMap(value.metrics()),
                stringList(value.peerIds(), MAX_INTROSPECTION_ITEMS, "peer ids"),
                stringList(value.recentEvents(), MAX_INTROSPECTION_ITEMS, "recent events"),
                instant(value.collectedAt()));
    }

    private static NuggetIntrospection.RepositorySummary repository(final NuggetIntrospection.RepositorySummary value) {
        return new NuggetIntrospection.RepositorySummary(
                safeText(value.rootSummary()),
                nonNegativeInt(value.artifactCount(), "artifactCount"),
                nonNegativeInt(value.promotionCount(), "promotionCount"),
                metadata(value.metadata()),
                instant(value.collectedAt()));
    }

    private static NuggetIntrospection.ApplicationSummary applicationSummary(
            final NuggetIntrospection.ApplicationSummary value) {
        return new NuggetIntrospection.ApplicationSummary(
                token(value.applicationId()),
                token(value.coordinate()),
                token(value.instanceId()),
                safeText(value.displayName()),
                token(value.version()),
                token(value.state()),
                token(value.activationId()),
                nonNegativeInt(value.exportedServiceCount(), "exportedServiceCount"),
                value.health(),
                value.readiness(),
                token(value.lastSafeErrorCode()),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.ApplicationDetail applicationDetail(
            final NuggetIntrospection.ApplicationDetail value) {
        return new NuggetIntrospection.ApplicationDetail(
                applicationSummary(value.summary()),
                objectList(value.declaredServices(), MAX_INTROSPECTION_ITEMS, AdminPayloadNormalizer::service, "declared services"),
                stringList(value.moduleNames(), MAX_INTROSPECTION_ITEMS, "module names"),
                metadata(value.capabilities()),
                tokenOrDefault(value.verificationStatus(), "unknown"),
                tokenOrDefault(value.signatureStatus(), "unknown"),
                tokenOrDefault(value.sbomStatus(), "unknown"),
                tokenOrDefault(value.provenanceStatus(), "unknown"),
                longMap(value.auditCounters()));
    }

    private static NuggetIntrospection.ObjectSummary objectSummary(final NuggetIntrospection.ObjectSummary value) {
        return new NuggetIntrospection.ObjectSummary(
                token(value.nuggetId()),
                token(value.objectId()),
                safeText(value.logicalName()),
                safeText(value.serviceName()),
                token(value.kind()),
                value.visibility(),
                token(value.lifecycleState()),
                token(value.registrationState()),
                token(value.ownerApplication()),
                stringList(value.interfaces(), MAX_INTROSPECTION_ITEMS, "interfaces"),
                tokenOrDefault(value.registryContext(), "default"),
                value.local(),
                safeText(value.endpointSummary()),
                token(value.transportId()),
                value.health(),
                value.readiness(),
                nonNegativeLong(value.leaseExpiryMillis(), "leaseExpiryMillis"),
                nonNegativeLong(value.generation(), "generation"),
                nonNegativeLong(value.invocationCount(), "invocationCount"),
                nonNegativeLong(value.failureCount(), "failureCount"),
                instant(value.lastInvocationTime()),
                token(value.lastSafeErrorCode()),
                value.sensitive(),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.ObjectDetail objectDetail(final NuggetIntrospection.ObjectDetail value) {
        return new NuggetIntrospection.ObjectDetail(
                objectSummary(value.summary()),
                metadata(value.descriptorMetadata()),
                metadata(value.annotationMetadata()));
    }

    private static NuggetIntrospection.ServiceSummary service(final NuggetIntrospection.ServiceSummary value) {
        if (value == null) {
            throw new IllegalArgumentException("service summary must not be null");
        }
        return new NuggetIntrospection.ServiceSummary(
                safeText(value.serviceName()),
                safeText(value.interfaceName()),
                "");
    }

    private static NuggetIntrospection.RecentErrorEntry recentError(final NuggetIntrospection.RecentErrorEntry value) {
        return new NuggetIntrospection.RecentErrorEntry(
                instant(value.timestamp()),
                token(value.correlationId()),
                token(value.component()),
                safeText(value.resourceSummary()),
                value.errorCode(),
                safeDiagnosticText(value.safeMessage()),
                value.retryable(),
                token(value.remoteRuntimeId()),
                nonNegativeLong(value.recurrenceCount(), "recurrenceCount"),
                metadata(value.metadata()));
    }

    private static NuggetIntrospection.MetricsSummary metrics(final NuggetIntrospection.MetricsSummary value) {
        return new NuggetIntrospection.MetricsSummary(
                objectList(value.metrics(), MAX_INTROSPECTION_ITEMS, AdminPayloadNormalizer::metric, "metrics"),
                instant(value.collectedAt()),
                value.partial());
    }

    private static NuggetIntrospection.MetricEntry metric(final NuggetIntrospection.MetricEntry value) {
        if (value == null) {
            throw new IllegalArgumentException("metric must not be null");
        }
        return new NuggetIntrospection.MetricEntry(
                tokenOrDefault(value.id(), "unknown"),
                tokenOrDefault(value.unit(), "count"),
                finiteOrDefault(value.value(), 0.0d),
                metadata(value.labels()));
    }

    private static NuggetIntrospection.KeyValue keyValue(final NuggetIntrospection.KeyValue value) {
        if (value == null) {
            throw new IllegalArgumentException("key/value pair must not be null");
        }
        return new NuggetIntrospection.KeyValue(tokenNonSensitive(value.key()), safeDiagnosticText(value.value()));
    }

    private static NuggetApplicationLifecycle.Request lifecycleRequest(final NuggetApplicationLifecycle.Request value) {
        return new NuggetApplicationLifecycle.Request(
                token(value.admissionId()),
                securityContext(value.securityContext()),
                token(value.correlationId()),
                value.action(),
                value.coordinate() == null ? null : coordinate(value.coordinate()),
                value.instanceId() == null ? null : instanceId(value.instanceId()),
                value.dryRun(),
                instant(value.requestedAt()),
                instant(value.deadlineAt()),
                metadata(value.metadata()));
    }

    private static NuggetApplicationLifecycle.Result lifecycleResult(final NuggetApplicationLifecycle.Result value) {
        return new NuggetApplicationLifecycle.Result(
                token(value.operationId()),
                token(value.correlationId()),
                value.action(),
                value.admitted(),
                value.authorized(),
                value.dryRun(),
                token(value.nuggetId()),
                token(value.runtimeId()),
                value.coordinate() == null ? null : coordinate(value.coordinate()),
                value.instanceId() == null ? null : instanceId(value.instanceId()),
                value.previousState(),
                value.currentState(),
                value.resultingState(),
                value.outcome(),
                safeDiagnosticText(value.outcomeText()),
                value.accepted(),
                value.inProgress(),
                value.completed(),
                value.partial(),
                value.outcomeUnknown(),
                safeDiagnosticText(value.verificationSummary()),
                safeDiagnosticText(value.authorizationSummary()),
                value.plan() == null ? null : deploymentPlan(value.plan()),
                value.deploymentResult() == null ? null : deploymentResult(value.deploymentResult()),
                value.activation() == null ? null : activation(value.activation()),
                warnings(value.warnings()),
                remoteError(value.error()),
                instant(value.requestedAt()),
                instant(value.completedAt()),
                duration(value.elapsed(), NuggetAdminContract.MAX_DURATION),
                metadata(value.metadata()));
    }

    private static NuggetControl.Request controlRequest(final NuggetControl.Request value) {
        return new NuggetControl.Request(
                token(value.admissionId()),
                securityContext(value.securityContext()),
                token(value.operationId()),
                token(value.correlationId()),
                value.action(),
                value.mode(),
                requireToken(value.targetNuggetId(), "targetNuggetId"),
                requireToken(value.targetRuntimeId(), "targetRuntimeId"),
                nonNegativeLong(value.targetGeneration(), "targetGeneration"),
                token(value.confirmation()),
                instant(value.requestedAt()),
                instant(value.deadlineAt()),
                metadata(value.metadata()));
    }

    private static NuggetControl.Result controlResult(final NuggetControl.Result value) {
        return new NuggetControl.Result(
                token(value.operationId()),
                token(value.correlationId()),
                value.action(),
                value.mode(),
                value.admitted(),
                value.authorized(),
                requireToken(value.targetNuggetId(), "targetNuggetId"),
                requireToken(value.targetRuntimeId(), "targetRuntimeId"),
                nonNegativeLong(value.targetGeneration(), "targetGeneration"),
                value.previousState(),
                value.currentState(),
                value.resultingState(),
                value.outcome(),
                safeDiagnosticText(value.outcomeText()),
                value.accepted(),
                value.inProgress(),
                value.completed(),
                value.partial(),
                value.outcomeUnknown(),
                value.cleanupPartial(),
                value.confirmation() == null ? null : confirmation(value.confirmation()),
                warnings(value.warnings()),
                remoteError(value.error()),
                instant(value.requestedAt()),
                instant(value.completedAt()),
                duration(value.elapsed(), NuggetAdminContract.MAX_DURATION),
                metadata(value.metadata()));
    }

    private static NuggetControl.Confirmation confirmation(final NuggetControl.Confirmation value) {
        return new NuggetControl.Confirmation(
                token(value.operationId()),
                token(value.challenge()),
                instant(value.expiresAt()),
                value.action(),
                requireToken(value.nuggetId(), "nuggetId"),
                requireToken(value.runtimeId(), "runtimeId"),
                nonNegativeLong(value.generation(), "generation"),
                token(value.callerId()),
                value.state());
    }

    private static ApplicationDeploymentPlan deploymentPlan(final ApplicationDeploymentPlan value) {
        return new ApplicationDeploymentPlan(
                coordinate(value.coordinate()),
                value.state(),
                stringList(value.steps(), MAX_APPLICATION_STEPS, "deployment plan steps"));
    }

    private static ApplicationDeploymentResult deploymentResult(final ApplicationDeploymentResult value) {
        return new ApplicationDeploymentResult(
                value.success(),
                value.state(),
                safeDiagnosticText(value.message()),
                stringList(value.details(), MAX_APPLICATION_STEPS, "deployment details"));
    }

    private static ApplicationActivation activation(final ApplicationActivation value) {
        return new ApplicationActivation(
                new ActivationIdentity(requireToken(value.activationIdentity().value(), "activationIdentity")),
                instanceIdentity(value.instanceIdentity()),
                value.state(),
                objectList(value.exportedServices(), MAX_EXPORTED_SERVICES, AdminPayloadNormalizer::runtimeService, "exported services"),
                metadata(value.metadata()));
    }

    private static RuntimeServiceIdentity runtimeService(final RuntimeServiceIdentity value) {
        if (value == null) {
            throw new IllegalArgumentException("runtime service identity must not be null");
        }
        return new RuntimeServiceIdentity(
                requireSafeText(value.serviceName(), "serviceName"),
                requireSafeText(value.interfaceName(), "interfaceName"),
                "");
    }

    private static ApplicationInstanceIdentity instanceIdentity(final ApplicationInstanceIdentity value) {
        if (value == null) {
            throw new IllegalArgumentException("application instance identity must not be null");
        }
        return new ApplicationInstanceIdentity(
                instanceId(value.instanceId()),
                applicationIdentity(value.application()),
                runtimeIdentity(value.runtime()),
                tokenOrDefault(value.classLoaderMode(), "CLASSPATH"));
    }

    private static ApplicationIdentity applicationIdentity(final ApplicationIdentity value) {
        if (value == null) {
            throw new IllegalArgumentException("application identity must not be null");
        }
        return new ApplicationIdentity(
                coordinate(value.coordinate()),
                safeText(value.displayName()),
                tokenOrDefault(value.trustDomain(), "default"));
    }

    private static ApplicationCoordinate coordinate(final ApplicationCoordinate value) {
        if (value == null) {
            throw new IllegalArgumentException("application coordinate must not be null");
        }
        return new ApplicationCoordinate(
                new ApplicationId(
                        requireToken(value.id().group(), "applicationGroup"),
                        requireToken(value.id().name(), "applicationName")),
                new ApplicationVersion(requireToken(value.version().value(), "applicationVersion")));
    }

    private static ApplicationInstanceId instanceId(final ApplicationInstanceId value) {
        if (value == null) {
            throw new IllegalArgumentException("application instance id must not be null");
        }
        return new ApplicationInstanceId(requireToken(value.value(), "applicationInstanceId"));
    }

    private static RuntimeIdentity runtimeIdentity(final RuntimeIdentity value) {
        if (value == null) {
            throw new IllegalArgumentException("runtime identity must not be null");
        }
        return new RuntimeIdentity(
                requireToken(value.runtimeId(), "runtimeId"),
                tokenOrDefault(value.registryContext(), "default"),
                metadata(value.metadata()));
    }

    private static SecurityContext securityContext(final SecurityContext value) {
        if (value == null) {
            return null;
        }
        final Principal principal = value.principal() == null ? Principal.anonymous() : value.principal();
        final Credential credential = value.credential() == null ? new Credential("none", "0", Map.of()) : value.credential();
        final TrustDomain trustDomain = value.trustDomain() == null ? new TrustDomain("default") : value.trustDomain();
        final Set<String> roles = stringSet(value.roles(), 32, "roles");
        return new SecurityContext(
                new Principal(
                        tokenOrDefault(principal.id(), "anonymous"),
                        safeText(principal.displayName()),
                        roles,
                        stringSet(principal.tags(), 32, "tags")),
                token(value.callerRuntimeId()),
                new TrustDomain(tokenOrDefault(trustDomain.id(), "default")),
                new Credential(tokenOrDefault(credential.type(), "none"), tokenOrDefault(credential.version(), "0"),
                        metadata(credential.metadata())),
                token(value.correlationId()),
                token(value.invocationId()),
                safeText(value.remoteEndpoint()),
                value.authenticated(),
                value.transportSecurityLevel() == null ? TransportSecurityLevel.UNKNOWN : value.transportSecurityLevel(),
                roles,
                metadata(value.requestMetadata()),
                instant(value.createdAt()));
    }

    private static Map<String, String> metadata(final Map<String, String> value) {
        return AdminContractSupport.orderedStringMap(value, NuggetAdminContract.MAX_METADATA_ENTRIES);
    }

    private static Map<String, Long> longMap(final Map<String, Long> source) {
        if (source == null) {
            return Map.of();
        }
        final LinkedHashMap<String, Long> copy = new LinkedHashMap<>(NuggetAdminContract.MAX_METADATA_ENTRIES);
        int examined = 0;
        final Iterator<Map.Entry<String, Long>> iterator = source.entrySet().iterator();
        while (iterator.hasNext()) {
            if (examined >= NuggetAdminContract.MAX_METADATA_ENTRIES) {
                break;
            }
            final Map.Entry<String, Long> entry = iterator.next();
            examined++;
            final String key = token(entry.getKey());
            if (!key.isBlank() && entry.getValue() != null && entry.getValue().longValue() >= 0L) {
                copy.putIfAbsent(key, entry.getValue());
            }
        }
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    private static List<String> warnings(final List<String> source) {
        return AdminContractSupport.safeStringList(
                source,
                NuggetAdminContract.MAX_WARNING_ENTRIES,
                NuggetAdminContract.MAX_WARNING_UTF8_BYTES);
    }

    private static List<String> stringList(final List<String> source, final int maximum, final String name) {
        if (source == null || maximum <= 0) {
            return List.of();
        }
        final ArrayList<String> copy = new ArrayList<>(Math.min(maximum, 8));
        final Iterator<String> iterator = source.iterator();
        boolean truncated = false;
        int examined = 0;
        while (iterator.hasNext()) {
            if (examined >= maximum) {
                truncated = true;
                break;
            }
            examined++;
            final String value = safeText(iterator.next());
            if (!value.isBlank() && !sensitive(value)) {
                copy.add(value);
                if (copy.size() >= maximum) {
                    truncated = iterator.hasNext();
                    break;
                }
            }
        }
        if (truncated) {
            if (copy.size() < maximum) {
                copy.add(DETAILS_OMITTED);
            } else if (!copy.isEmpty()) {
                copy.set(copy.size() - 1, DETAILS_OMITTED);
            }
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }

    private static <E> List<E> enumList(final List<E> source, final int maximum, final String name) {
        if (source == null) {
            return List.of();
        }
        final ArrayList<E> copy = new ArrayList<>(Math.min(maximum, 8));
        final Iterator<E> iterator = source.iterator();
        while (iterator.hasNext()) {
            if (copy.size() >= maximum) {
                throw new IllegalArgumentException(name + " outside nugget admin bounds");
            }
            final E value = iterator.next();
            if (value == null) {
                throw new IllegalArgumentException(name + " must not contain null values");
            }
            copy.add(value);
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }

    private static <S, T> List<T> objectList(
            final List<S> source,
            final int maximum,
            final Function<S, T> mapper,
            final String name) {
        if (source == null) {
            return List.of();
        }
        final ArrayList<T> copy = new ArrayList<>(Math.min(maximum, 8));
        final Iterator<S> iterator = source.iterator();
        while (iterator.hasNext()) {
            if (copy.size() >= maximum) {
                break;
            }
            final T value = mapper.apply(iterator.next());
            if (value == null) {
                throw new IllegalArgumentException(name + " must not contain null values");
            }
            copy.add(value);
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }

    private static Set<String> stringSet(final Set<String> source, final int maximum, final String name) {
        if (source == null || maximum <= 0) {
            return Set.of();
        }
        final LinkedHashSet<String> copy = new LinkedHashSet<>();
        final Iterator<String> iterator = source.iterator();
        int examined = 0;
        while (iterator.hasNext()) {
            if (examined >= maximum || copy.size() >= maximum) {
                break;
            }
            examined++;
            final String value = token(iterator.next());
            if (!value.isBlank()) {
                copy.add(value);
            }
        }
        return copy.isEmpty() ? Set.of() : Set.copyOf(copy);
    }

    private static Instant instant(final Instant value) {
        return value == null ? Instant.EPOCH : value;
    }

    private static Duration duration(final Duration value, final Duration maximum) {
        return AdminContractSupport.boundedDuration(value, Duration.ZERO, maximum);
    }

    private static String token(final String value) {
        return AdminContractSupport.optionalToken(value);
    }

    private static String tokenOrDefault(final String value, final String defaultValue) {
        final String token = AdminContractSupport.optionalToken(value);
        return token.isBlank() ? defaultValue : token;
    }

    private static String tokenNonSensitive(final String value) {
        final String token = AdminContractSupport.optionalToken(value);
        return sensitive(token) ? "" : token;
    }

    private static String requireToken(final String value, final String name) {
        final String token = AdminContractSupport.optionalToken(value);
        if (token.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty safe token");
        }
        return token;
    }

    private static String safeText(final String value) {
        return AdminContractSupport.optionalText(value);
    }

    private static String safeDiagnosticText(final String value) {
        final String text = AdminContractSupport.optionalText(value);
        return sensitive(text) ? "" : text;
    }

    private static String requireSafeText(final String value, final String name) {
        final String text = AdminContractSupport.optionalText(value);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-empty safe text");
        }
        return text;
    }

    private static int nonNegativeInt(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static long nonNegativeLong(final long value, final String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static long negativeOneOrNonNegative(final long value, final String name) {
        if (value < -1L) {
            throw new IllegalArgumentException(name + " outside nugget admin bounds");
        }
        return value;
    }

    private static double finiteOrDefault(final double value, final double defaultValue) {
        return Double.isFinite(value) ? value : defaultValue;
    }

    private static boolean sensitive(final String text) {
        final String normalized = normalized(text);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("private")
                || normalized.contains("stack")
                || normalized.contains("exception")
                || normalized.contains("throwable")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || normalized.contains("challenge")
                || normalized.contains("oauth")
                || normalized.contains("path")
                || normalized.contains("manifest")
                || normalized.contains("certificate")
                || normalized.contains("signature")
                || normalized.contains("key")
                || normalized.contains("implementation");
    }

    private static String normalized(final String text) {
        final StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char value = text.charAt(index);
            if (value >= 'A' && value <= 'Z') {
                builder.append((char) (value + ('a' - 'A')));
            } else if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
                builder.append(value);
            }
        }
        return builder.toString();
    }
}
