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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.InstanceStatus;
import org.j7m.middleware.api.InvocationMetadata;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.api.RegistrationState;
import org.j7m.middleware.api.RegistryStatus;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.api.TransportStatus;
import org.j7m.middleware.application.api.ActivationIdentity;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.ApplicationRepositoryStatus;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVersion;
import org.j7m.middleware.application.api.RuntimeServiceIdentity;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.runtime.NuggetAdminRuntime;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.junit.jupiter.api.Test;

/**
 * Phase 7.2 read-only nugget introspection coverage.
 */
public final class NuggetIntrospectionTest {
    private static final RuntimeIdentity RUNTIME_ID = new RuntimeIdentity("runtime-introspection", "default", Map.of());
    private static final char[] PIN = "correct-pin".toCharArray();
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Test
    public void introspectionRequiresAdmissionAndAuthorization() {
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        NuggetAdminService service = service(audit, new AllowAllAuthorizationPolicy());

        NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> notAdmitted =
                service.environment(new NuggetIntrospection.Request("", context(Set.of("admin")), "no-admission", NOW, Map.of()));
        assertFalse(notAdmitted.authorized());
        assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, notAdmitted.error().code());

        NuggetAdminService deniedService = service(audit, new DenyObjectsPolicy(true));
        String admission = deniedService.authenticate(authRequest("authz"), copyPin()).admissionId();
        NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> denied =
                deniedService.environment(request(admission, context(Set.of())));
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, denied.error().code());
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().startsWith("nugget.introspection")));
    }

    @Test
    public void requestMetadataIsBoundedRedactedAndImmutableBeforeAuthorization() {
        String secret = "introspection-secret-token-sentinel";
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) {
            metadata.put("safe" + index, "value" + index);
        }
        metadata.put("pin", secret);
        metadata.put("path", "/secret/" + secret);
        NuggetIntrospection.Request request = new NuggetIntrospection.Request(
                "admission-" + secret,
                null,
                "correlation-" + secret,
                NOW,
                metadata);
        metadata.put("safe0", secret);

        assertEquals("", request.admissionId());
        assertEquals("", request.correlationId());
        assertTrue(request.metadata().containsKey("detailsOmitted"));
        assertTrue(request.metadata().size() <= 16);
        assertFalse(request.metadata().toString().contains(secret));
        assertFalse(request.toString().contains(secret));

        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        NuggetAdminService service = service(audit, new AllowAllAuthorizationPolicy());
        NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> denied = service.environment(request);

        assertFalse(denied.admitted());
        assertFalse(audit.recentEvents().toString().contains(secret));
    }

    @Test
    public void environmentUsesSafeFieldsAndRedactedArguments() {
        NuggetAdminService service = service(new InMemoryAuditSink(32), new AllowAllAuthorizationPolicy());
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.EnvironmentSummary environment = service.environment(request).value();

        assertNotNull(environment.jvmVersion());
        assertTrue(environment.heapUsedBytes() >= 0L);
        assertTrue(environment.processUptime().compareTo(Duration.ZERO) >= 0);
        assertTrue(environment.jvmArgumentSummaries().stream().allMatch(argument -> argument.value().equals("<redacted>")));
        assertTrue(environment.environmentVariableNames().stream().noneMatch(name -> name.contains("=")));
        assertFalse(environment.jvmArgumentSummaries().toString().contains("SECRET-SENTINEL"));
    }

    @Test
    public void diskRootsAreBoundedDeduplicatedAndSensitivePathsAreRedacted() {
        NuggetAdminService service = service(new InMemoryAuditSink(32), new AllowAllAuthorizationPolicy());
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary> disks =
                service.disk(request, new NuggetIntrospection.DiskQuery("", NuggetIntrospection.Query.all())).value();

        assertTrue(disks.items().stream().anyMatch(root -> root.role().equals("working-directory")));
        assertEquals(1L, disks.items().stream()
                .filter(root -> root.roles().contains("repository") && root.roles().contains("durable-log"))
                .count());
        assertTrue(disks.items().stream()
                .filter(root -> root.roles().contains("repository") && root.roles().contains("durable-log"))
                .allMatch(root -> root.pathSummary().equals("<redacted>")));
        assertFalse(disks.toString().contains("secret-token-role"));
        assertTrue(disks.items().stream().allMatch(root -> root.totalBytes() >= -1L));
    }

    @Test
    public void diskQueryValidationRejectsUnsupportedInputBeforeDiskProbe() {
        AtomicInteger monotonicCalls = new AtomicInteger();
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(() -> {
                    monotonicCalls.incrementAndGet();
                    return 0L;
                })
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .storageRoot("repository", Path.of("/secret/repository-root"), true)
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        try {
            NuggetIntrospection.Request request = admittedRequest(service);
            monotonicCalls.set(0);

            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.disk(request, new NuggetIntrospection.DiskQuery(
                    "secret/token",
                    NuggetIntrospection.Query.all())).error().code());
            assertEquals(0, monotonicCalls.get());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.disk(request, new NuggetIntrospection.DiskQuery(
                    "",
                    new NuggetIntrospection.Query(
                            0,
                            10,
                            "path",
                            NuggetIntrospection.SortDirection.ASC,
                            Map.of()))).error().code());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.disk(request, new NuggetIntrospection.DiskQuery(
                    "",
                    new NuggetIntrospection.Query(
                            0,
                            10,
                            "id",
                            NuggetIntrospection.SortDirection.ASC,
                            Map.of("path", "repository")))).error().code());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.disk(request, new NuggetIntrospection.DiskQuery(
                    "",
                    new NuggetIntrospection.Query(
                            0,
                            10,
                            "id",
                            NuggetIntrospection.SortDirection.ASC,
                            Map.of("role", "secret-token-role")))).error().code());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new NuggetIntrospection.Query(-1, 1, "id", NuggetIntrospection.SortDirection.ASC, Map.of()));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new NuggetIntrospection.Query(0, 501, "id", NuggetIntrospection.SortDirection.ASC, Map.of()));
        } finally {
            service.close();
        }
    }

    @Test
    public void diskQuerySupportsRoleRootIdFiltersAndStableSorts() {
        NuggetAdminService service = service(new InMemoryAuditSink(32), new AllowAllAuthorizationPolicy());
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary> all =
                service.disk(request, new NuggetIntrospection.DiskQuery("", NuggetIntrospection.Query.all())).value();
        String repositoryRootId = all.items().stream()
                .filter(root -> root.roles().contains("repository"))
                .findFirst()
                .orElseThrow()
                .rootId();

        NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary> byRole =
                service.disk(request, new NuggetIntrospection.DiskQuery("", new NuggetIntrospection.Query(
                        0,
                        10,
                        "role",
                        NuggetIntrospection.SortDirection.ASC,
                        Map.of("role", "repository")))).value();
        NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary> byRoot =
                service.disk(request, new NuggetIntrospection.DiskQuery("repository", new NuggetIntrospection.Query(
                        0,
                        10,
                        "rootId",
                        NuggetIntrospection.SortDirection.DESC,
                        Map.of("rootId", repositoryRootId)))).value();

        assertTrue(byRole.items().stream().allMatch(root -> root.roles().contains("repository")));
        assertEquals(1, byRoot.returned());
        assertEquals(repositoryRootId, byRoot.items().getFirst().rootId());
        assertEquals("rootId", byRoot.sortedBy());
    }

    @Test
    public void healthReadinessMetricsErrorsAndRepositoryAreSafeAndPartialAware() {
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        NuggetAdminService service = service(audit, new AllowAllAuthorizationPolicy());
        NuggetIntrospection.Request request = admittedRequest(service);

        assertEquals(NuggetIntrospection.HealthState.DEGRADED, service.health(request).value().state());
        assertEquals(NuggetIntrospection.ReadinessState.DEGRADED, service.readiness(request).value().state());
        assertTrue(service.metrics(request).value().metrics().stream()
                .anyMatch(metric -> metric.id().equals("runtime.invocations")));
        assertFalse(service.metrics(request).value().toString().contains("SECRET-SENTINEL"));
        NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry> errors =
                service.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", NuggetIntrospection.Query.all())).value();
        assertEquals("<redacted>", errors.items().getFirst().safeMessage());
        assertEquals("<redacted>", errors.items().getFirst().resourceSummary());
        assertEquals("<redacted>", service.repository(request).value().rootSummary());
        assertEquals(Map.of("signatureVerification", "present"), service.repository(request).value().metadata());
        assertEquals(Map.of("mode", "DEV"), service.runtime(request).value().security());
        assertFalse(service.registry(request).value().toString().contains("SECRET-SENTINEL"));
        assertEquals(1, service.registry(request).value().recordCount());
        assertTrue(service.registry(request).value().recentEvents().contains("STATE_READY"));
        assertFalse(service.registry(request).value().recentEvents().contains("state-ready"));
        assertFalse(service.registry(request).value().recentEvents().toString().contains("registered"));
        assertTrue(errors.items().stream().allMatch(error -> error.metadata().isEmpty()));
    }

    @Test
    public void metricsPartialReflectsProviderFailuresTimeoutsCapsAndNormalization() {
        NuggetAdminService failingService = serviceWithProvider(new NuggetIntrospection.Provider() {
            @Override
            public List<NuggetIntrospection.MetricEntry> metrics(final NuggetIntrospection.ProviderContext context) {
                throw new IllegalStateException("provider failed");
            }
        }, Duration.ofMillis(10L), new AtomicLong());
        try {
            NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> failing =
                    failingService.metrics(admittedRequest(failingService));
            assertTrue(failing.partial());
            assertTrue(failing.value().partial());
            assertTrue(failing.value().metrics().stream().anyMatch(metric -> metric.id().equals("metrics.provider.unavailable")));
        } finally {
            failingService.close();
        }

        CountDownLatch blockingEntered = new CountDownLatch(1);
        NuggetAdminService blockingService = serviceWithProvider(new NuggetIntrospection.Provider() {
            @Override
            public List<NuggetIntrospection.MetricEntry> metrics(final NuggetIntrospection.ProviderContext context) {
                blockingEntered.countDown();
                try {
                    new CountDownLatch(1).await(1L, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        }, Duration.ofMillis(10L), new AtomicLong());
        try {
            NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> blocking =
                    blockingService.metrics(admittedRequest(blockingService));
            assertTrue(blocking.partial());
            assertTrue(blocking.value().partial());
        } finally {
            blockingService.close();
        }

        NuggetAdminService cappedService = serviceWithProvider(new NuggetIntrospection.Provider() {
            @Override
            public List<NuggetIntrospection.MetricEntry> metrics(final NuggetIntrospection.ProviderContext context) {
                List<NuggetIntrospection.MetricEntry> metrics = new java.util.ArrayList<>();
                for (int index = 0; index < 300; index++) {
                    metrics.add(new NuggetIntrospection.MetricEntry("custom.metric." + index, "count", index, Map.of()));
                }
                metrics.add(new NuggetIntrospection.MetricEntry("custom.secret.metric", "count", 1.0D, Map.of()));
                return metrics;
            }
        }, Duration.ofSeconds(1L), new AtomicLong());
        try {
            NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> capped =
                    cappedService.metrics(admittedRequest(cappedService));
            assertTrue(capped.partial());
            assertTrue(capped.value().partial());
            assertTrue(capped.value().metrics().size() <= 512);
            assertFalse(capped.value().toString().contains("secret"));
        } finally {
            cappedService.close();
        }

        NuggetAdminService completeService = serviceWithProvider(new NuggetIntrospection.Provider() {
            @Override
            public RuntimeStatus runtimeStatus(final NuggetIntrospection.ProviderContext context) {
                return NuggetIntrospectionTest.runtimeStatus();
            }

            @Override
            public List<ApplicationActivation> applications(final NuggetIntrospection.ProviderContext context) {
                return List.of();
            }

            @Override
            public List<NuggetIntrospection.MetricEntry> metrics(final NuggetIntrospection.ProviderContext context) {
                return List.of(new NuggetIntrospection.MetricEntry("custom.safe.metric", "count", 1.0D, Map.of("component", "orders")));
            }
        }, Duration.ofSeconds(1L), new AtomicLong());
        try {
            NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> complete =
                    completeService.metrics(admittedRequest(completeService));
            assertFalse(complete.partial());
            assertFalse(complete.value().partial());
        } finally {
            completeService.close();
        }
    }

    @Test
    public void recentErrorsPreservePartialThroughFilteringAndPaging() {
        NuggetAdminService failingService = serviceWithProvider(new NuggetIntrospection.Provider() {
            @Override
            public List<NuggetIntrospection.RecentErrorEntry> recentErrors(final NuggetIntrospection.ProviderContext context) {
                throw new IllegalStateException("provider failed");
            }
        }, Duration.ofMillis(10L), new AtomicLong());
        try {
            NuggetIntrospection.Request request = admittedRequest(failingService);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> failing =
                    failingService.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", NuggetIntrospection.Query.all()));
            assertTrue(failing.partial());
            assertTrue(failing.value().partial());

            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> emptyPartial =
                    failingService.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("missing", NuggetIntrospection.Query.all()));
            assertEquals(0, emptyPartial.value().returned());
            assertTrue(emptyPartial.partial());
            assertTrue(emptyPartial.value().partial());
        } finally {
            failingService.close();
        }

        CountDownLatch blockingEntered = new CountDownLatch(1);
        NuggetAdminService blockingService = serviceWithProvider(new NuggetIntrospection.Provider() {
            @Override
            public List<NuggetIntrospection.RecentErrorEntry> recentErrors(final NuggetIntrospection.ProviderContext context) {
                blockingEntered.countDown();
                try {
                    new CountDownLatch(1).await(1L, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        }, Duration.ofMillis(10L), new AtomicLong());
        try {
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> blocking =
                    blockingService.recentErrors(admittedRequest(blockingService),
                            new NuggetIntrospection.RecentErrorQuery("", NuggetIntrospection.Query.all()));
            assertTrue(blocking.partial());
            assertTrue(blocking.value().partial());
        } finally {
            blockingService.close();
        }

        NuggetAdminService cappedService = serviceWithProvider(new NuggetIntrospection.Provider() {
            @Override
            public List<NuggetIntrospection.RecentErrorEntry> recentErrors(final NuggetIntrospection.ProviderContext context) {
                List<NuggetIntrospection.RecentErrorEntry> errors = new java.util.ArrayList<>();
                for (int index = 0; index < 300; index++) {
                    errors.add(new NuggetIntrospection.RecentErrorEntry(
                            NOW.plusSeconds(index),
                            "corr-" + index,
                            "runtime",
                            "resource",
                            MiddlewareErrorCode.INTERNAL,
                            "safe",
                            false,
                            "",
                            1L,
                            Map.of()));
                }
                return errors;
            }
        }, Duration.ofSeconds(1L), new AtomicLong());
        try {
            NuggetIntrospection.Request request = admittedRequest(cappedService);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> firstPage =
                    cappedService.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", new NuggetIntrospection.Query(
                            0,
                            1,
                            "timestamp",
                            NuggetIntrospection.SortDirection.ASC,
                            Map.of())));
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> secondPage =
                    cappedService.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", new NuggetIntrospection.Query(
                            1,
                            1,
                            "timestamp",
                            NuggetIntrospection.SortDirection.ASC,
                            Map.of())));
            assertTrue(firstPage.partial());
            assertTrue(firstPage.value().partial());
            assertTrue(secondPage.partial());
            assertTrue(secondPage.value().partial());
            assertEquals(1, secondPage.value().returned());
        } finally {
            cappedService.close();
        }
    }

    @Test
    public void applicationsSupportFilteringSortingPagingAndDetails() {
        NuggetAdminService service = service(new InMemoryAuditSink(32), new AllowAllAuthorizationPolicy());
        NuggetIntrospection.Request request = admittedRequest(service);
        NuggetIntrospection.Query query = new NuggetIntrospection.Query(
                0,
                1,
                "coordinate",
                NuggetIntrospection.SortDirection.ASC,
                Map.of("state", "RUNNING"));

        NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary> applications =
                service.applications(request, query).value();

        assertEquals(1, applications.returned());
        assertEquals(2, applications.totalAvailable());
        assertEquals("RUNNING", applications.items().getFirst().state());
        NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> detail =
                service.application(request, "orders-1");
        assertTrue(detail.value().declaredServices().stream().anyMatch(serviceSummary -> serviceSummary.serviceName().equals("orders")));
        assertTrue(detail.value().summary().metadata().isEmpty());
        assertTrue(detail.value().capabilities().isEmpty());
        assertFalse(detail.toString().contains("SecretImplementation"));
        assertFalse(service.applications(request, NuggetIntrospection.Query.all()).value().toString().contains("SECRET-SENTINEL"));
        assertFalse(service.applications(request, NuggetIntrospection.Query.all()).value().toString().contains("business free text"));
    }

    @Test
    public void applicationsAreAuthorizedBeforeCountingAndPaging() {
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        NuggetAdminService service = service(audit, new DenyApplicationPolicy("billing-1"));
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary> applications =
                service.applications(request, new NuggetIntrospection.Query(
                        0,
                        10,
                        "coordinate",
                        NuggetIntrospection.SortDirection.ASC,
                        Map.of("state", "RUNNING"))).value();

        assertEquals(1, applications.returned());
        assertEquals(1, applications.totalAvailable());
        assertEquals("orders-1", applications.items().getFirst().instanceId());
        NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> denied = service.application(request, "billing-1");
        assertEquals(MiddlewareErrorCode.NOT_FOUND, denied.error().code());
        assertFalse(denied.toString().contains("billing-1"));
        assertFalse(audit.recentEvents().toString().contains("billing-1"));
    }

    @Test
    public void objectsApplyVisibilityAuthorizationAndDoNotExposeSecrets() {
        NuggetAdminService service = service(new InMemoryAuditSink(32), new DenyObjectsPolicy(false));
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary> objects =
                service.objects(request, NuggetIntrospection.Query.all()).value();

        assertTrue(objects.items().stream().anyMatch(object -> object.visibility() == NuggetIntrospection.ObjectVisibility.EXPORTED));
        assertTrue(objects.items().stream().anyMatch(object -> object.visibility() == NuggetIntrospection.ObjectVisibility.INTERNAL));
        assertFalse(objects.items().stream().anyMatch(object -> object.visibility() == NuggetIntrospection.ObjectVisibility.PRIVATE));
        assertFalse(objects.items().toString().contains("SECRET-SENTINEL"));
        assertEquals(objects.totalAvailable(), (int) service.metrics(request).value().metrics().stream()
                .filter(metric -> metric.id().equals("runtime.objects"))
                .findFirst()
                .orElseThrow()
                .value());
        assertEquals(objects.totalAvailable(), service.runtime(request).value().activeObjectCount());
        NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> hidden = service.object(request, "private-object");
        assertEquals(MiddlewareErrorCode.NOT_FOUND, hidden.error().code());
    }

    @Test
    public void metadataCannotUpgradeObjectVisibilityAndEndpointsAreSanitized() {
        NuggetAdminService service = service(new InMemoryAuditSink(64), new DenyObjectsPolicy(false));
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary> objects =
                service.objects(request, NuggetIntrospection.Query.all()).value();
        assertFalse(objects.items().stream().anyMatch(object -> object.objectId().equals("private-object")));
        assertEquals(1L, objects.items().stream().filter(object -> object.objectId().equals("remote-object")).count());
        assertEquals(7L, objects.items().stream()
                .filter(object -> object.objectId().equals("remote-object"))
                .findFirst()
                .orElseThrow()
                .generation());
        assertEquals(1L, objects.items().stream().filter(object -> object.objectId().equals("internal-object")).count());
        assertEquals(1L, objects.items().stream().filter(object -> object.objectId().equals("exported-object")).count());
        assertFalse(objects.items().stream().anyMatch(object -> object.objectId().equals("expired-object")));
        assertFalse(objects.items().stream().anyMatch(object -> object.objectId().equals("ambiguous-object")));
        assertTrue(objects.items().stream().allMatch(object -> object.metadata().isEmpty()));
        assertTrue(service.object(request, "exported-object").value().descriptorMetadata().isEmpty());
        assertTrue(service.object(request, "exported-object").value().annotationMetadata().isEmpty());
        assertFalse(objects.toString().contains("endpoint-secret-token"));
        assertFalse(objects.toString().contains("business free text"));
        assertFalse(service.transports(request, NuggetIntrospection.Query.all()).value().toString().contains("secret-endpoint-token"));
    }

    @Test
    public void queryBoundsAndSortingAreDeterministic() {
        NuggetAdminService service = service(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy());
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary> first =
                service.objects(request, new NuggetIntrospection.Query(
                        0,
                        2,
                        "visibility",
                        NuggetIntrospection.SortDirection.ASC,
                        Map.of())).value();
        NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary> second =
                service.objects(request, new NuggetIntrospection.Query(
                        0,
                        2,
                        "visibility",
                        NuggetIntrospection.SortDirection.ASC,
                        Map.of())).value();
        NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary> descendingFirst =
                service.objects(request, new NuggetIntrospection.Query(
                        0,
                        2,
                        "visibility",
                        NuggetIntrospection.SortDirection.DESC,
                        Map.of())).value();
        NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary> descendingSecond =
                service.objects(request, new NuggetIntrospection.Query(
                        0,
                        2,
                        "visibility",
                        NuggetIntrospection.SortDirection.DESC,
                        Map.of())).value();

        assertEquals(first.items(), second.items());
        assertEquals(descendingFirst.items(), descendingSecond.items());
        assertEquals(0, service.objects(request, new NuggetIntrospection.Query(
                0,
                10,
                "id",
                NuggetIntrospection.SortDirection.ASC,
                Map.of("serviceName", "missing"))).value().returned());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.objects(request, new NuggetIntrospection.Query(
                0,
                10,
                "unknown",
                NuggetIntrospection.SortDirection.ASC,
                Map.of())).error().code());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.objects(request, new NuggetIntrospection.Query(
                0,
                10,
                "id",
                NuggetIntrospection.SortDirection.ASC,
                Map.of("applicationId", "orders"))).error().code());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.applications(request, new NuggetIntrospection.Query(
                0,
                10,
                "visibility",
                NuggetIntrospection.SortDirection.ASC,
                Map.of())).error().code());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, service.recentErrors(request, new NuggetIntrospection.RecentErrorQuery(
                "",
                new NuggetIntrospection.Query(
                        0,
                        10,
                        "visibility",
                        NuggetIntrospection.SortDirection.ASC,
                        Map.of()))).error().code());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new NuggetIntrospection.Query(-1, 1, "id", NuggetIntrospection.SortDirection.ASC, Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new NuggetIntrospection.Query(0, 501, "id", NuggetIntrospection.SortDirection.ASC, Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new NuggetIntrospection.Query(0, 1, "id", NuggetIntrospection.SortDirection.ASC,
                        Map.of("serviceName", "x".repeat(257))));
        assertEquals(
                service.metrics(request).value().metrics(),
                service.metrics(request).value().metrics());
        List<Double> customMetricValues = service.metrics(request).value().metrics().stream()
                .filter(metric -> metric.id().equals("custom.queue.depth"))
                .map(NuggetIntrospection.MetricEntry::value)
                .toList();
        assertEquals(List.of(2.0D, 3.0D, 4.0D), customMetricValues);
        assertEquals(List.of(2.0D, 3.0D), customMetricValues.subList(0, 2));
        assertEquals(List.of(4.0D), customMetricValues.subList(2, 3));
        assertEquals(
                service.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", NuggetIntrospection.Query.all())).value().items(),
                service.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", NuggetIntrospection.Query.all())).value().items());
        NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry> firstErrorPage =
                service.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", new NuggetIntrospection.Query(
                        0,
                        1,
                        "id",
                        NuggetIntrospection.SortDirection.ASC,
                        Map.of()))).value();
        NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry> secondErrorPage =
                service.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", new NuggetIntrospection.Query(
                        1,
                        1,
                        "id",
                        NuggetIntrospection.SortDirection.ASC,
                        Map.of()))).value();
        NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry> descendingErrorPage =
                service.recentErrors(request, new NuggetIntrospection.RecentErrorQuery("", new NuggetIntrospection.Query(
                        0,
                        1,
                        "id",
                        NuggetIntrospection.SortDirection.DESC,
                        Map.of()))).value();
        assertEquals(1L, firstErrorPage.items().getFirst().recurrenceCount());
        assertEquals(2L, secondErrorPage.items().getFirst().recurrenceCount());
        assertEquals(2L, descendingErrorPage.items().getFirst().recurrenceCount());
    }

    @Test
    public void providerFailuresAndTimeoutsArePartialAndBounded() {
        CountDownLatch entered = new CountDownLatch(1);
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .providerExecutionPolicy(1, Duration.ofMillis(10L))
                .introspectionProvider(new NuggetIntrospection.Provider() {
                    @Override
                    public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
                        entered.countDown();
                        try {
                            new CountDownLatch(1).await(1L, TimeUnit.MINUTES);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of();
                    }
                })
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        NuggetIntrospection.Request request = admittedRequest(service);

        NuggetIntrospection.HealthSummary health = service.health(request).value();

        assertEquals(0L, entered.getCount());
        assertTrue(health.partial());
        assertTrue(health.components().stream().anyMatch(component -> component.safeReason().equals("provider-failed")));
        service.close();
    }

    @Test
    public void providerQueueIsBoundedAndOverloadIsSafe() throws Exception {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NuggetAdminRuntimeConfiguration.builder().providerExecutionPolicy(0, 0, Duration.ofSeconds(1L), Duration.ofSeconds(1L)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NuggetAdminRuntimeConfiguration.builder().providerExecutionPolicy(1, 9, Duration.ofSeconds(1L), Duration.ofSeconds(1L)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NuggetAdminRuntimeConfiguration.builder().providerExecutionPolicy(1, 0, Duration.ZERO, Duration.ofSeconds(1L)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NuggetAdminRuntimeConfiguration.builder().providerExecutionPolicy(1, 0, Duration.ofSeconds(31L), Duration.ofSeconds(1L)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NuggetAdminRuntimeConfiguration.builder().providerExecutionPolicy(1, 0, Duration.ofSeconds(1L), Duration.ofSeconds(11L)));
        NuggetAdminRuntimeConfiguration.Builder providers = NuggetAdminRuntimeConfiguration.builder();
        for (int index = 0; index < 8; index++) {
            providers.introspectionProvider(new NuggetIntrospection.Provider() {
            });
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> providers.introspectionProvider(new NuggetIntrospection.Provider() {
                }));
        NuggetAdminRuntimeConfiguration.Builder roots = NuggetAdminRuntimeConfiguration.builder();
        for (int index = 0; index < 15; index++) {
            roots.storageRoot("root-" + index, Path.of("/tmp/j7m-root-" + index), true);
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> roots.storageRoot("root-overflow", Path.of("/tmp/j7m-root-overflow"), true));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .providerExecutionPolicy(1, 0, Duration.ofSeconds(5L), Duration.ofMillis(100L))
                .introspectionProvider(new NuggetIntrospection.Provider() {
                    @Override
                    public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
                        entered.countDown();
                        try {
                            release.await(1L, TimeUnit.MINUTES);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of();
                    }
                })
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        NuggetIntrospection.Request request = admittedRequest(service);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<NuggetIntrospection.Result<NuggetIntrospection.HealthSummary>> active =
                    callers.submit(() -> service.health(request));
            assertTrue(entered.await(5L, TimeUnit.SECONDS));
            NuggetIntrospection.HealthSummary overloaded = service.health(request).value();
            assertTrue(overloaded.partial());
            release.countDown();
            assertNotNull(active.get(5L, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            callers.shutdownNow();
            service.close();
        }
    }

    @Test
    public void providerGlobalDeadlineUsesMonotonicBudgetAcrossProviders() {
        AtomicLong monotonicNanos = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger secondProviderCalls = new AtomicInteger();
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(monotonicNanos::get)
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .providerExecutionPolicy(1, 0, Duration.ofMillis(10L), Duration.ofMillis(100L))
                .introspectionProvider(new NuggetIntrospection.Provider() {
                    @Override
                    public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
                        entered.countDown();
                        monotonicNanos.set(TimeUnit.MILLISECONDS.toNanos(20L));
                        try {
                            new CountDownLatch(1).await(1L, TimeUnit.MINUTES);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of();
                    }
                })
                .introspectionProvider(new NuggetIntrospection.Provider() {
                    @Override
                    public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
                        secondProviderCalls.incrementAndGet();
                        return List.of(new NuggetIntrospection.ComponentSummary(
                                "second",
                                NuggetIntrospection.Availability.AVAILABLE,
                                NuggetIntrospection.HealthState.HEALTHY,
                                NuggetIntrospection.ReadinessState.READY,
                                false,
                                "",
                                Map.of()));
                    }
                })
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        try {
            NuggetIntrospection.HealthSummary health = service.health(admittedRequest(service)).value();

            assertEquals(0L, entered.getCount());
            assertTrue(health.partial());
            assertEquals(0, secondProviderCalls.get());
        } finally {
            service.close();
        }
    }

    @Test
    public void diskStatusMarksUnavailableWhenGlobalDeadlineIsExhausted() {
        AtomicInteger monotonicCalls = new AtomicInteger();
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(() -> monotonicCalls.getAndIncrement() == 0
                        ? 0L
                        : TimeUnit.MILLISECONDS.toNanos(20L))
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .providerExecutionPolicy(1, 0, Duration.ofMillis(1L), Duration.ofMillis(100L))
                .storageRoot("repository", Path.of("/tmp/j7m-disk-deadline"), true)
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        try {
            NuggetIntrospection.Request request = admittedRequest(service);
            monotonicCalls.set(0);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> result =
                    service.disk(request, new NuggetIntrospection.DiskQuery("", NuggetIntrospection.Query.all()));

            assertTrue(result.partial());
            assertTrue(result.value().partial());
            assertTrue(result.value().items().stream()
                    .anyMatch(item -> item.availability() == NuggetIntrospection.Availability.UNAVAILABLE
                            && item.safeErrorCode().equals("deadline-exhausted")));
        } finally {
            service.close();
        }
    }

    @Test
    public void providerLateCompletionCannotMutateReturnedSnapshot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .providerExecutionPolicy(1, 0, Duration.ofMillis(10L), Duration.ofMillis(100L))
                .introspectionProvider(new NuggetIntrospection.Provider() {
                    @Override
                    public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
                        boolean interrupted = false;
                        entered.countDown();
                        while (release.getCount() > 0L) {
                            try {
                                release.await(1L, TimeUnit.MINUTES);
                            } catch (InterruptedException e) {
                                interrupted = true;
                            }
                        }
                        completed.countDown();
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of(new NuggetIntrospection.ComponentSummary(
                                "late-component",
                                NuggetIntrospection.Availability.AVAILABLE,
                                NuggetIntrospection.HealthState.HEALTHY,
                                NuggetIntrospection.ReadinessState.READY,
                                false,
                                "late",
                                Map.of("secretLate", "SECRET-SENTINEL")));
                    }
                })
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        try {
            NuggetIntrospection.HealthSummary health = service.health(admittedRequest(service)).value();
            assertEquals(0L, entered.getCount());
            assertTrue(health.partial());
            assertFalse(health.toString().contains("late-component"));
            release.countDown();
            assertTrue(completed.await(5L, TimeUnit.SECONDS));
            assertFalse(health.toString().contains("late-component"));
            assertFalse(health.toString().contains("SECRET-SENTINEL"));
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    public void providerShutdownCancelsActiveAndQueuedWorkSafely() throws Exception {
        CountDownLatch activeEntered = new CountDownLatch(1);
        CountDownLatch activeInterrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch activeCompleted = new CountDownLatch(1);
        CountDownLatch queuedStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .providerExecutionPolicy(1, 1, Duration.ofMillis(200L), Duration.ofMillis(50L))
                .introspectionProvider(new NuggetIntrospection.Provider() {
                    @Override
                    public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
                        int call = calls.incrementAndGet();
                        if (call == 1) {
                            boolean interrupted = false;
                            activeEntered.countDown();
                            while (release.getCount() > 0L) {
                                try {
                                    release.await(1L, TimeUnit.MINUTES);
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                    activeInterrupted.countDown();
                                }
                            }
                            activeCompleted.countDown();
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        }
                        queuedStarted.countDown();
                        return List.of(new NuggetIntrospection.ComponentSummary(
                                "queued-component",
                                NuggetIntrospection.Availability.AVAILABLE,
                                NuggetIntrospection.HealthState.HEALTHY,
                                NuggetIntrospection.ReadinessState.READY,
                                false,
                                "queued",
                                Map.of()));
                    }
                })
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        NuggetIntrospection.Request request = admittedRequest(service);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<NuggetIntrospection.Result<NuggetIntrospection.HealthSummary>> active =
                    callers.submit(() -> service.health(request));
            assertTrue(activeEntered.await(5L, TimeUnit.SECONDS));
            Future<NuggetIntrospection.Result<NuggetIntrospection.HealthSummary>> queued =
                    callers.submit(() -> service.health(request));
            service.close();
            assertTrue(activeInterrupted.await(5L, TimeUnit.SECONDS));
            NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> afterClose = service.health(request);
            assertFalse(afterClose.authorized());
            release.countDown();
            assertNotNull(active.get(5L, TimeUnit.SECONDS));
            assertNotNull(queued.get(5L, TimeUnit.SECONDS));
            assertEquals(1L, queuedStarted.getCount());
            assertTrue(activeCompleted.await(5L, TimeUnit.SECONDS));
            service.close();
        } finally {
            release.countDown();
            callers.shutdownNow();
            service.close();
        }
    }

    private static NuggetAdminService service(final InMemoryAuditSink audit, final AuthorizationPolicy policy) {
        AtomicLong nanos = new AtomicLong();
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(audit, policy))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .introspectionProvider(new TestProvider())
                .storageRoot("repository", Path.of("/secret/repository-root"), true)
                .storageRoot("durable-log", Path.of("/secret/repository-root"), true)
                .storageRoot("secret-token-role", Path.of("/secret/repository-root"), true)
                .storageRoot("repository", Path.of("/secret/repository-root"), true)
                .build();
        return NuggetAdminRuntime.start(RUNTIME_ID, configuration);
    }

    private static NuggetAdminService serviceWithProvider(
            final NuggetIntrospection.Provider provider,
            final Duration providerTimeout,
            final AtomicLong monotonicNanos) {
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(monotonicNanos::get)
                .pinFromProgrammaticProvider("protected", NuggetIntrospectionTest::copyPin)
                .providerExecutionPolicy(1, 0, providerTimeout, Duration.ofMillis(100L))
                .introspectionProvider(provider)
                .build();
        return NuggetAdminRuntime.start(RUNTIME_ID, configuration);
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit, final AuthorizationPolicy policy) {
        return new SecurityConfiguration(SecurityMode.DEV, policy, audit, new DefaultRedactor(),
                false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())));
    }

    private static RuntimeStatus runtimeStatus() {
        ObjectRef exported = objectRef("exported-object", "orders", "EXPORTED", 3L,
                Map.of("applicationId", "com.example:orders", "unknownBusinessText", "business free text"));
        ObjectRef internal = objectRef("internal-object", "internal", "EXPORTED", 5L, Map.of("applicationId", "runtime"));
        ObjectRef privateObject = objectRef("private-object", "private", "EXPORTED", 5L, Map.of("secretMetadata", "SECRET-SENTINEL"));
        ObjectRef remote = new ObjectRef("remote-object", "remote/remote-object", "remote", "remote-runtime", "default",
                List.of("com.example.remote.Service"), "rmi", "rmi://remote.example.invalid:9999/path?token=SECRET-SENTINEL",
                NOW.toEpochMilli() + 1000L, 7L, Map.of());
        ObjectRef remoteOld = new ObjectRef("remote-object", "remote/remote-object-old", "remote", "remote-runtime", "default",
                List.of("com.example.remote.Service"), "rmi", "rmi://remote.example.invalid:9999/path-old",
                NOW.toEpochMilli() + 1000L, 6L, Map.of());
        ObjectRef ambiguousOne = new ObjectRef("ambiguous-object", "remote/ambiguous-one", "remote", "remote-runtime", "default",
                List.of("com.example.remote.Service"), "rmi", "rmi://remote.example.invalid:9999/a",
                NOW.toEpochMilli() + 1000L, 3L, Map.of());
        ObjectRef ambiguousTwo = new ObjectRef("ambiguous-object", "remote/ambiguous-two", "remote", "remote-runtime", "default",
                List.of("com.example.remote.Service"), "rmi", "rmi://remote.example.invalid:9999/b",
                NOW.toEpochMilli() + 1000L, 3L, Map.of());
        ObjectRef expired = new ObjectRef("expired-object", "remote/expired-object", "remote", "remote-runtime", "default",
                List.of("com.example.remote.Service"), "rmi", "rmi://remote.example.invalid:9999/expired",
                NOW.toEpochMilli() - 1000L, 1L, Map.of());
        return new RuntimeStatus(
                RUNTIME_ID,
                List.of(
                        new InstanceStatus(exported, ObjectState.EXECUTED, RegistrationState.LOCAL_AND_GLOBAL),
                        new InstanceStatus(internal, ObjectState.EXECUTED, RegistrationState.LOCAL),
                        new InstanceStatus(privateObject, ObjectState.EXECUTED, RegistrationState.NONE)),
                List.of(
                        exported.withMetadata(Map.of("j7m.visibility", "EXPORTED")),
                        withGeneration(internal, 6L, NOW.toEpochMilli() + 1000L, Map.of("j7m.visibility", "EXPORTED")),
                        withGeneration(privateObject, 4L, NOW.toEpochMilli() + 1000L, Map.of("j7m.visibility", "EXPORTED")),
                        remote,
                        remoteOld,
                        ambiguousOne,
                        ambiguousTwo,
                        expired),
                List.of(new TransportStatus("local", "secret-endpoint-token", true)),
                new RegistryStatus(
                        99,
                        2,
                        false,
                        "none",
                        "registry://host/path?token=SECRET-SENTINEL",
                        1L,
                        2L,
                        3,
                        4,
                        5,
                        6,
                        7,
                        Map.of("registry.secret.metric", 9L, "registry.queries", 3L),
                        List.of("peer-SECRET-SENTINEL", "peer-safe"),
                        List.of("registered SECRET-SENTINEL", "state-ready", "business-safe-free-form"),
                        List.of()),
                7L,
                1L,
                List.of(new InvocationMetadata("inv-1", exported, "call", 1L, 2L, false, MiddlewareErrorCode.INTERNAL,
                        Map.of("secretArg", "SECRET-SENTINEL"))),
                Map.of("mode", "DEV", "secretValue", "SECRET-SENTINEL"));
    }

    private static ObjectRef objectRef(
            final String objectId,
            final String service,
            final String visibility,
            final long generation,
            final Map<String, String> extra) {
        java.util.LinkedHashMap<String, String> metadata = new java.util.LinkedHashMap<>(extra);
        metadata.put("j7m.visibility", visibility);
        metadata.put("failureCount", "1");
        metadata.put("invocationCount", "3");
        return new ObjectRef(objectId, service + "/" + objectId, service, RUNTIME_ID.runtimeId(), "default",
                List.of("com.example." + service + ".Service"), "local", "endpoint-secret-token", NOW.toEpochMilli() + 1000L, generation, metadata);
    }

    private static ObjectRef withGeneration(
            final ObjectRef ref,
            final long generation,
            final long leaseUntil,
            final Map<String, String> metadata) {
        return new ObjectRef(ref.objectId(), ref.logicalName(), ref.serviceName(), ref.runtimeId(), ref.registryContext(),
                ref.interfaces(), ref.transport(), ref.endpoint(), leaseUntil, generation, metadata);
    }

    private static List<ApplicationActivation> applications() {
        return List.of(application("orders-1", "com.example", "orders", "1.0.0", ApplicationRunState.RUNNING),
                application("billing-1", "com.example", "billing", "1.0.0", ApplicationRunState.RUNNING),
                application("failed-1", "com.example", "failed", "1.0.0", ApplicationRunState.FAILED));
    }

    private static final class TestProvider implements NuggetIntrospection.Provider {
        @Override
        public RuntimeStatus runtimeStatus(final NuggetIntrospection.ProviderContext context) {
            return NuggetIntrospectionTest.runtimeStatus();
        }

        @Override
        public List<ApplicationActivation> applications(final NuggetIntrospection.ProviderContext context) {
            return NuggetIntrospectionTest.applications();
        }

        @Override
        public ApplicationRepositoryStatus repositoryStatus(final NuggetIntrospection.ProviderContext context) {
            return new ApplicationRepositoryStatus(Path.of("/secret/repository-root"), 3, 2,
                    Map.of("signatureVerification", "present", "secretToken", "SECRET-SENTINEL"));
        }

        @Override
        public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
            return List.of(new NuggetIntrospection.ComponentSummary(
                    "optional-metrics",
                    NuggetIntrospection.Availability.UNAVAILABLE,
                    NuggetIntrospection.HealthState.DEGRADED,
                    NuggetIntrospection.ReadinessState.DEGRADED,
                    false,
                    "provider-unavailable",
                    Map.of()));
        }

        @Override
        public List<NuggetIntrospection.RecentErrorEntry> recentErrors(final NuggetIntrospection.ProviderContext context) {
            return List.of(
                    new NuggetIntrospection.RecentErrorEntry(
                            NOW,
                            "corr",
                            "runtime",
                            "/secret/path/SECRET-SENTINEL",
                            MiddlewareErrorCode.INTERNAL,
                            "failure SECRET-SENTINEL",
                            false,
                            "remote",
                            1L,
                            Map.of("secretValue", "SECRET-SENTINEL")),
                    new NuggetIntrospection.RecentErrorEntry(
                            NOW,
                            "corr",
                            "runtime",
                            "/secret/path/SECRET-SENTINEL-2",
                            MiddlewareErrorCode.INTERNAL,
                            "failure SECRET-SENTINEL-2",
                            false,
                            "remote",
                            2L,
                            Map.of()));
        }

        @Override
        public List<NuggetIntrospection.MetricEntry> metrics(final NuggetIntrospection.ProviderContext context) {
            return List.of(
                    new NuggetIntrospection.MetricEntry("custom.queue.depth", "count", 2.0D, Map.of("component", "orders")),
                    new NuggetIntrospection.MetricEntry("custom.queue.depth", "count", 3.0D,
                            Map.of("result", "ok", "component", "orders")),
                    new NuggetIntrospection.MetricEntry("custom.queue.depth", "count", 4.0D,
                            Map.of("component", "orders", "result", "ok")),
                    new NuggetIntrospection.MetricEntry("custom.secret.metric", "count", 1.0D,
                            Map.of("url", "https://example.invalid/?token=SECRET-SENTINEL")));
        }
    }

    private static ApplicationActivation application(
            final String instanceId,
            final String group,
            final String name,
            final String version,
            final ApplicationRunState state) {
        ApplicationCoordinate coordinate = new ApplicationCoordinate(new ApplicationId(group, name), new ApplicationVersion(version));
        ApplicationIdentity application = new ApplicationIdentity(coordinate, name, "default");
        ApplicationInstanceIdentity instance = new ApplicationInstanceIdentity(new ApplicationInstanceId(instanceId), application, RUNTIME_ID, "JPMS");
        return new ApplicationActivation(new ActivationIdentity("act-" + instanceId), instance, state,
                List.of(new RuntimeServiceIdentity(name, "com.example." + name + ".Service", "com.example.SecretImplementation")),
                Map.of(
                        "displayName", name.equals("billing") ? "SECRET-SENTINEL display" : name,
                        "moduleNames", "com.example." + name,
                        "signatureStatus", "present",
                        "sbomStatus", "present",
                        "provenanceStatus", "present",
                        "businessFreeText", "business free text",
                        "pathMetadata", "/safe/looking/path",
                        "urlMetadata", "https://example.invalid/public",
                        "secretConfig", "SECRET-SENTINEL"));
    }

    private static NuggetIntrospection.Request admittedRequest(final NuggetAdminService service) {
        String admission = service.authenticate(authRequest("admit"), copyPin()).admissionId();
        return request(admission, context(Set.of("admin")));
    }

    private static NuggetIntrospection.Request request(final String admission, final SecurityContext context) {
        return new NuggetIntrospection.Request(admission, context, "corr", NOW, Map.of());
    }

    private static NuggetAuthenticationRequest authRequest(final String correlation) {
        return NuggetAuthenticationRequest.of(correlation, NuggetAuthenticationSource.LOCAL, "test", true, NOW);
    }

    private static SecurityContext context(final Set<String> roles) {
        Principal principal = new Principal("operator", "operator", roles, Set.of());
        return new SecurityContext(principal, "caller", new TrustDomain("default"), new Credential("test", "1", Map.of()),
                "corr", "inv", "local", true, TransportSecurityLevel.TEST_AUTHENTICATED, roles, Map.of(), NOW);
    }

    private static char[] copyPin() {
        return java.util.Arrays.copyOf(PIN, PIN.length);
    }

    private static final class DenyObjectsPolicy implements AuthorizationPolicy {
        private final boolean denyAll;

        private DenyObjectsPolicy(final boolean denyAll) {
            this.denyAll = denyAll;
        }

        @Override
        public String id() {
            return "deny-objects";
        }

        @Override
        public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
            if (this.denyAll || request.resource().contains("private")) {
                return AuthorizationDecision.deny(this.id(), "denied");
            }
            return AuthorizationDecision.allow(this.id());
        }
    }

    private static final class DenyApplicationPolicy implements AuthorizationPolicy {
        private final String deniedInstanceId;

        private DenyApplicationPolicy(final String deniedInstanceId) {
            this.deniedInstanceId = deniedInstanceId;
        }

        @Override
        public String id() {
            return "deny-application";
        }

        @Override
        public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
            if (request.resource().contains(this.deniedInstanceId)) {
                return AuthorizationDecision.deny(this.id(), "denied");
            }
            return AuthorizationDecision.allow(this.id());
        }
    }
}
