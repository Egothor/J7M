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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.operations.api.NuggetCapability;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityAuditSink;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.junit.jupiter.api.Test;

/**
 * Deterministic internal coverage for nugget verifier file and lifecycle seams.
 */
public final class NuggetAdminInternalCoverageTest {
    private static final Path VERIFIER_PATH = Path.of("/virtual/j7m/verifier.txt");
    private static final char[] PIN = "correct-pin".toCharArray();

    @Test
    public void throttleOverflowBucketCountsTowardCapacityAndResetPolicyIsShared() {
        NuggetAuthenticationThrottle throttle = new NuggetAuthenticationThrottle(
                2,
                Duration.ofMinutes(15L),
                Duration.ofSeconds(4L));
        long now = 1L;

        throttle.recordFailure("first", now);
        throttle.recordFailure("second", now);
        throttle.recordFailure("third", now);

        assertEquals(2, throttle.bucketCount(now));
        NuggetAuthenticationThrottle.Attempt firstOverflowAttempt = throttle.beforeAttempt("new-overflow-principal", now);
        throttle.reset(firstOverflowAttempt, now);
        assertEquals(1, throttle.bucketCount(now));
        throttle.recordFailure("fourth", now);
        throttle.recordFailure("fifth", now);
        assertEquals(2, throttle.bucketCount(now));
        NuggetAuthenticationThrottle.Attempt secondOverflowAttempt = throttle.beforeAttempt("new-overflow-principal", now);
        throttle.reset(secondOverflowAttempt, now);
        assertEquals(1, throttle.bucketCount(now));
    }

    @Test
    public void throttleSuccessfulPreciseAttemptDoesNotResetUnrelatedOverflowPressure() {
        NuggetAuthenticationThrottle throttle = new NuggetAuthenticationThrottle(
                2,
                Duration.ofNanos(100L),
                Duration.ofSeconds(4L));

        throttle.recordFailure("first", 1L);
        throttle.recordFailure("second", 2L);
        throttle.recordFailure("third", 3L);
        throttle.recordFailure("fourth", 50L);
        throttle.recordFailure("fifth", 60L);

        long now = 104L;
        assertEquals(1, throttle.bucketCount(now));

        NuggetAuthenticationThrottle.Attempt preciseAttempt = throttle.beforeAttempt("new-precise-principal", now);
        assertEquals(Duration.ZERO, preciseAttempt.retryAfter());
        throttle.reset(preciseAttempt, now);

        assertEquals(1, throttle.bucketCount(now));
        assertFalse(throttle.beforeAttempt("overflow", now).retryAfter().isZero());
        assertEquals(Duration.ZERO, throttle.beforeAttempt("new-precise-principal", now).retryAfter());
    }

    @Test
    public void throttleOverflowBucketIsTheOnlyBucketWhenCapacityIsOne() {
        NuggetAuthenticationThrottle throttle = new NuggetAuthenticationThrottle(
                1,
                Duration.ofMinutes(15L),
                Duration.ofSeconds(4L));
        long now = 1L;

        throttle.recordFailure("first", now);
        throttle.recordFailure("second", now);
        throttle.recordFailure("third", now);

        assertEquals(1, throttle.bucketCount(now));
        NuggetAuthenticationThrottle.Attempt overflowAttempt = throttle.beforeAttempt("overflow", now);
        throttle.reset(overflowAttempt, now);
        assertEquals(0, throttle.bucketCount(now));
    }

    @Test
    public void environmentRedactionClassifiesHostileNamesAndJvmArguments() {
        List<String> rejectedEnvironmentNames = List.of(
                "PIN",
                "API_TOKEN",
                "PASSWORD_FILE",
                "CREDENTIAL_PATH",
                "1INVALID",
                "TOO-LONG",
                "SAFE_NAME".repeat(20));
        for (String name : rejectedEnvironmentNames) {
            assertEquals("", NuggetEnvironmentRedaction.safeEnvironmentName(name), name);
        }
        assertEquals("J7M_VISIBLE", NuggetEnvironmentRedaction.safeEnvironmentName("J7M_VISIBLE"));
        assertEquals("system-property", NuggetEnvironmentRedaction.argumentName("-Dsecret=value"));
        assertEquals("system-property", NuggetEnvironmentRedaction.argumentName("-Dpassword=sentinel"));
        assertEquals("agent-option", NuggetEnvironmentRedaction.argumentName("-javaagent:/secret/agent.jar=token"));
        assertEquals("agent-option", NuggetEnvironmentRedaction.argumentName("-agentpath:C:\\secret\\agent.dll"));
        assertEquals("path-option", NuggetEnvironmentRedaction.argumentName("--class-path=/secret/lib"));
        assertEquals("path-option", NuggetEnvironmentRedaction.argumentName("--module-path=C:\\secret\\mods"));
        assertEquals("redacted-option", NuggetEnvironmentRedaction.argumentName("--service-url=https://user:pass@example.invalid"));
        assertEquals("redacted-option", NuggetEnvironmentRedaction.argumentName("--credential-file=/secret/pin.txt"));
        assertEquals("jvm-tuning-option", NuggetEnvironmentRedaction.argumentName("-XX:+UseZGC"));
        assertEquals("enable-preview", NuggetEnvironmentRedaction.argumentName("--enable-preview"));
    }

    @Test
    public void protectedVerifierFileValidationFailuresAreDeterministic() {
        List<FileValidationMode> rejectedModes = List.of(
                FileValidationMode.SYMBOLIC_LINK,
                FileValidationMode.UNEXPECTED_OWNER,
                FileValidationMode.UNSAFE_FILE_PERMISSIONS,
                FileValidationMode.UNSAFE_DIRECTORY_PERMISSIONS,
                FileValidationMode.UNSAFE_DIRECTORY_OWNER,
                FileValidationMode.NON_REGULAR);

        for (FileValidationMode mode : rejectedModes) {
            ControlledFileOperations files = new ControlledFileOperations();
            files.validationMode = mode;
            files.records.put(VERIFIER_PATH, verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS));

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> start(config(SecurityMode.DEV, new InMemoryAuditSink(8), files, NuggetAdminLifecycleHooks.noop(),
                            NuggetPinVerifier.MIN_ITERATIONS)));

            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code(), mode.name());
            assertEquals(0, files.reads, mode.name());
        }
    }

    @Test
    public void unavailableFileSecurityFailsClosedInProduction() {
        ControlledFileOperations files = new ControlledFileOperations();
        files.validationMode = FileValidationMode.UNAVAILABLE;
        files.records.put(VERIFIER_PATH, verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS));

        MiddlewareException failure = assertThrows(MiddlewareException.class,
                () -> start(config(SecurityMode.PRODUCTION, new InMemoryAuditSink(8), files, NuggetAdminLifecycleHooks.noop(),
                        NuggetPinVerifier.MIN_ITERATIONS)));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        assertEquals(0, files.reads);
    }

    @Test
    public void unavailableFileSecurityInDeveloperModeIsAuditedAndExplicit() {
        ControlledFileOperations files = new ControlledFileOperations();
        files.validationMode = FileValidationMode.UNAVAILABLE;
        files.records.put(VERIFIER_PATH, verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS));
        InMemoryAuditSink audit = new InMemoryAuditSink(32);

        NuggetAdminController controller = start(config(SecurityMode.DEV, audit, files, NuggetAdminLifecycleHooks.noop(),
                NuggetPinVerifier.MIN_ITERATIONS));

        assertTrue(controller.authenticate(request("dev-unverified"), copyPin()).accepted());
        assertTrue(audit.recentEvents().stream()
                .anyMatch(event -> event.action().equals("nugget.pin.file.permission.unverified")));
    }

    @Test
    public void oversizedVerifierRecordIsRejectedWithBoundedRead() {
        ControlledFileOperations files = new ControlledFileOperations();
        files.records.put(VERIFIER_PATH, "x".repeat(NuggetPinVerifier.MAX_RECORD_BYTES + 1));

        MiddlewareException failure = assertThrows(MiddlewareException.class,
                () -> start(config(SecurityMode.DEV, new InMemoryAuditSink(8), files, NuggetAdminLifecycleHooks.noop(),
                        NuggetPinVerifier.MIN_ITERATIONS)));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        assertEquals(NuggetPinVerifier.MAX_RECORD_BYTES, files.lastBoundedReadLimit);
        assertEquals(1, files.reads);
    }

    @Test
    public void protectedClearPinFileUsesBoundedUtf8Read() {
        ControlledFileOperations files = new ControlledFileOperations();
        files.records.put(VERIFIER_PATH, "correct-pin");

        NuggetAdminController controller = start(configWithProtectedPinFile(SecurityMode.DEV, new InMemoryAuditSink(8), files));

        assertTrue(controller.authenticate(request("protected-pin"), copyPin()).accepted());
        assertEquals(NuggetPinVerifier.MAX_PIN_BYTES, files.lastBoundedReadLimit);
        assertEquals(0, files.unboundedReads);
    }

    @Test
    public void successfulUpgradePersistsOnceAndActivatesNewVerifier() {
        ControlledFileOperations files = new ControlledFileOperations();
        files.records.put(VERIFIER_PATH, verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS));
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        NuggetAdminController controller = start(config(SecurityMode.DEV, audit, files, NuggetAdminLifecycleHooks.noop(),
                NuggetPinVerifier.MIN_ITERATIONS + 1));

        assertTrue(controller.authenticate(request("upgrade"), copyPin()).accepted());
        assertTrue(files.records.get(VERIFIER_PATH).contains("|" + (NuggetPinVerifier.MIN_ITERATIONS + 1) + "|"));
        assertTrue(controller.authenticate(request("after-upgrade"), copyPin()).accepted());
        assertEquals(1, files.persistCalls);
        assertTrue(audit.recentEvents().stream()
                .anyMatch(event -> event.action().equals("nugget.pin.verifier.upgrade.completed")));
    }

    @Test
    public void failedPersistencePathsKeepExistingVerifierAndCleanTemporaryState() {
        for (PersistFailure failure : List.of(
                PersistFailure.ATOMIC_REPLACEMENT_UNAVAILABLE,
                PersistFailure.WRITE_FAILURE,
                PersistFailure.FORCE_FAILURE,
                PersistFailure.REVALIDATION_FAILURE,
                PersistFailure.MOVE_FAILURE)) {
            ControlledFileOperations files = new ControlledFileOperations();
            String original = verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS);
            files.records.put(VERIFIER_PATH, original);
            files.persistFailure = failure;
            InMemoryAuditSink audit = new InMemoryAuditSink(64);
            NuggetAdminController controller = start(config(SecurityMode.DEV, audit, files, NuggetAdminLifecycleHooks.noop(),
                    NuggetPinVerifier.MIN_ITERATIONS + 1));

            assertTrue(controller.authenticate(request("upgrade-" + failure.name()), copyPin()).accepted());
            assertEquals(original, files.records.get(VERIFIER_PATH), failure.name());
            assertFalse(files.temporaryFilePresent, failure.name());
            assertTrue(files.partialTemporaryContentCreated, failure.name());
            assertTrue(controller.authenticate(request("still-valid-" + failure.name()), copyPin()).accepted());
            String auditText = audit.recentEvents().toString();
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.pin.verifier.upgrade.failed")), failure.name());
            assertFalse(auditText.contains(new String(PIN)), failure.name());
        }
    }

    @Test
    public void authenticationConcurrentWithVerifierUpgradeCompletesWithoutDeadlock() throws Exception {
        ControlledFileOperations files = new ControlledFileOperations();
        files.records.put(VERIFIER_PATH, verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS));
        files.blockPersistence = true;
        NuggetAdminController controller = start(config(SecurityMode.DEV, new InMemoryAuditSink(64), files,
                NuggetAdminLifecycleHooks.noop(), NuggetPinVerifier.MIN_ITERATIONS + 1));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> upgradingAuth = executor.submit(() -> controller.authenticate(request("upgrade"), copyPin()).accepted());
            assertTrue(files.persistenceEntered.await(5L, TimeUnit.SECONDS), "upgrade persistence was not reached");

            Future<Boolean> concurrentAuth = executor.submit(() -> controller.authenticate(request("during-upgrade"), copyPin()).accepted());
            files.releasePersistence.countDown();

            assertTrue(upgradingAuth.get(5L, TimeUnit.SECONDS));
            assertTrue(concurrentAuth.get(5L, TimeUnit.SECONDS));
            assertEquals(1, files.persistCalls);
        }
    }

    @Test
    public void authenticationInProgressBlocksShutdownUntilVerificationCompletes() throws Exception {
        BlockingVerifyHooks hooks = new BlockingVerifyHooks();
        NuggetAdminController controller = start(configWithProgrammaticPin(new InMemoryAuditSink(32), hooks));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<NuggetAuthenticationOutcome> auth = executor.submit(() -> controller.authenticate(request("auth"), copyPin()).outcome());
            assertTrue(hooks.verificationEntered.await(5L, TimeUnit.SECONDS), "verification did not start");

            Future<?> close = executor.submit(() -> {
                hooks.closeStarted.countDown();
                controller.close();
                return null;
            });
            assertTrue(hooks.closeStarted.await(5L, TimeUnit.SECONDS), "close did not start");

            hooks.releaseVerification.countDown();
            assertEquals(NuggetAuthenticationOutcome.ACCEPTED, auth.get(5L, TimeUnit.SECONDS));
            close.get(5L, TimeUnit.SECONDS);
            assertEquals(NuggetAuthenticationOutcome.UNAVAILABLE,
                    controller.authenticate(request("after-close"), copyPin()).outcome());
        }
    }

    @Test
    public void authenticationBeginningAfterShutdownIsRejected() {
        NuggetAdminController controller = start(configWithProgrammaticPin(new InMemoryAuditSink(16), NuggetAdminLifecycleHooks.noop()));

        controller.close();

        assertEquals(NuggetAuthenticationOutcome.UNAVAILABLE,
                controller.authenticate(request("after-shutdown"), copyPin()).outcome());
    }

    @Test
    public void disabledAdminAllocatesNoLifecycleExecutorsOrSessionState() {
        RecordingResourceFactory resources = new RecordingResourceFactory();
        NuggetAdminController controller = start(NuggetAdminConfiguration.builder()
                .enabled(false)
                .security(security(SecurityMode.DEV, new InMemoryAuditSink(16)))
                .resourceFactory(resources)
                .build());

        assertEquals(0, resources.providerCreations.get());
        assertEquals(0, resources.discoveryCreations.get());
        assertEquals(0, controller.admissionCountForTests());
        assertEquals(0, controller.throttleBucketCountForTests());
        assertEquals(0, controller.expiredDiscoveryRecordCountForTests());

        controller.close();
        controller.close();

        assertEquals(0, resources.providerCreations.get());
        assertEquals(0, resources.discoveryCreations.get());
        assertEquals(0, resources.providerShutdowns.get());
        assertEquals(0, resources.discoveryShutdowns.get());
    }

    @Test
    public void failedStartRollbackAttemptsAllPartialResourceCleanup() {
        ControlledFileOperations files = new ControlledFileOperations();
        files.discoveryRemoveFailure = true;
        RecordingResourceFactory resources = new RecordingResourceFactory();
        resources.failDiscoverySchedule = true;
        Path directory = Path.of("/virtual/discovery");

        MiddlewareException failure = assertThrows(MiddlewareException.class,
                () -> start(NuggetAdminConfiguration.builder()
                        .security(security(SecurityMode.DEV, new InMemoryAuditSink(32)))
                        .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                        .localDiscovery(directory, true)
                        .fileOperations(files)
                        .resourceFactory(resources)
                        .build()));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        assertEquals(1, files.discoveryPersistCalls);
        assertEquals(1, files.discoveryRemoveCalls);
        assertEquals(1, resources.discoveryCreations.get());
        assertEquals(1, resources.discoveryScheduleCalls.get());
        assertEquals(1, resources.discoveryShutdowns.get());
    }

    @Test
    public void closeIsIdempotentAndReleasesOwnedLifecycleState() {
        ControlledFileOperations files = new ControlledFileOperations();
        RecordingResourceFactory resources = new RecordingResourceFactory();
        Path directory = Path.of("/virtual/discovery");
        NuggetAdminController controller = start(NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, new InMemoryAuditSink(64)))
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .localDiscovery(directory, true)
                .fileOperations(files)
                .resourceFactory(resources)
                .introspectionProvider(new NuggetIntrospection.Provider() {
                    @Override
                    public List<NuggetIntrospection.ComponentSummary> components(final NuggetIntrospection.ProviderContext context) {
                        return List.of(new NuggetIntrospection.ComponentSummary(
                                "owned-component",
                                NuggetIntrospection.Availability.AVAILABLE,
                                NuggetIntrospection.HealthState.HEALTHY,
                                NuggetIntrospection.ReadinessState.READY,
                                false,
                                "",
                                Map.of()));
                    }
                })
                .build());

        String admission = controller.authenticate(request("admit"), copyPin()).admissionId();
        assertTrue(controller.health(new NuggetIntrospection.Request(admission, null, "health", Instant.EPOCH, Map.of())).admitted());
        controller.rememberExpiredDiscoveryRecordForTests("expired", Instant.EPOCH);
        assertEquals(1, resources.discoveryCreations.get());
        assertEquals(1, resources.providerCreations.get());
        assertTrue(controller.admissionCountForTests() > 0);
        assertEquals(1, controller.expiredDiscoveryRecordCountForTests());
        assertEquals(1, files.discoveryRecordCount());

        controller.close();
        controller.close();

        assertEquals(1, resources.providerShutdowns.get());
        assertEquals(1, resources.discoveryShutdowns.get());
        assertEquals(0, controller.admissionCountForTests());
        assertEquals(0, controller.throttleBucketCountForTests());
        assertEquals(0, controller.expiredDiscoveryRecordCountForTests());
        assertEquals(0, files.discoveryRecordCount());
        assertTrue(controller.discoveryExecutorTerminatedForTests());
        assertEquals(NuggetAuthenticationOutcome.UNAVAILABLE,
                controller.authenticate(request("closed"), copyPin()).outcome());
    }

    @Test
    public void discoveryRecordRemovalRunsWhenHeartbeatCancelFails() {
        ControlledFileOperations files = new ControlledFileOperations();
        RecordingResourceFactory resources = new RecordingResourceFactory();
        resources.failDiscoveryCancel = true;
        Path directory = Path.of("/virtual/discovery");
        NuggetAdminController controller = start(NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, new InMemoryAuditSink(64)))
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .localDiscovery(directory, true)
                .fileOperations(files)
                .resourceFactory(resources)
                .build());

        assertEquals(1, files.discoveryRecordCount());

        controller.close();

        assertEquals(1, resources.discoveryCancelCalls.get());
        assertEquals(1, files.discoveryRemoveCalls);
        assertEquals(0, files.discoveryRecordCount());
        assertEquals(1, resources.discoveryShutdowns.get());
    }

    @Test
    public void admissionHandlesRemainBoundedAndRejectUnknownToken() throws Exception {
        NuggetAdminController controller = start(configWithProgrammaticPin(new InMemoryAuditSink(16), NuggetAdminLifecycleHooks.noop()));

        for (int index = 0; index < 1_100; index++) {
            controller.rememberAdmissionForTests("admission-" + index);
        }

        assertTrue(controller.admissionCountForTests() <= 1_024);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < 256; index++) {
                final int admissionIndex = index;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5L, TimeUnit.SECONDS), "concurrent admission insert was not released");
                    controller.rememberAdmissionForTests("concurrent-admission-" + admissionIndex);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5L, TimeUnit.SECONDS);
            }
        }
        assertTrue(controller.admissionCountForTests() <= 1_024);
        assertFalse(controller.environment(new NuggetIntrospection.Request("not-issued", null, "wrong-token", Instant.EPOCH, Map.of())).admitted());
    }

    @Test
    public void simultaneousSuccessfulAuthenticationsPersistOneConsistentUpgrade() throws Exception {
        ControlledFileOperations files = new ControlledFileOperations();
        files.records.put(VERIFIER_PATH, verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS));
        NuggetAdminController controller = start(config(SecurityMode.DEV, new InMemoryAuditSink(64), files,
                NuggetAdminLifecycleHooks.noop(), NuggetPinVerifier.MIN_ITERATIONS + 1));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS), "first auth was not released");
                return controller.authenticate(request("first"), copyPin()).accepted();
            });
            Future<Boolean> second = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS), "second auth was not released");
                return controller.authenticate(request("second"), copyPin()).accepted();
            });
            start.countDown();

            assertTrue(first.get(5L, TimeUnit.SECONDS));
            assertTrue(second.get(5L, TimeUnit.SECONDS));
            assertEquals(1, files.persistCalls);
            assertTrue(files.records.get(VERIFIER_PATH).contains("|" + (NuggetPinVerifier.MIN_ITERATIONS + 1) + "|"));
        }
    }

    @Test
    public void failedConcurrentUpgradeLeavesPreviousVerifierAndShutdownClearsIt() throws Exception {
        ControlledFileOperations files = new ControlledFileOperations();
        String original = verifierRecord(PIN, NuggetPinVerifier.MIN_ITERATIONS);
        files.records.put(VERIFIER_PATH, original);
        files.persistFailure = PersistFailure.MOVE_FAILURE;
        NuggetAdminController controller = start(config(SecurityMode.DEV, new InMemoryAuditSink(64), files,
                NuggetAdminLifecycleHooks.noop(), NuggetPinVerifier.MIN_ITERATIONS + 1));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS), "first auth was not released");
                return controller.authenticate(request("failed-first"), copyPin()).accepted();
            });
            Future<Boolean> second = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS), "second auth was not released");
                return controller.authenticate(request("failed-second"), copyPin()).accepted();
            });
            start.countDown();

            assertTrue(first.get(5L, TimeUnit.SECONDS));
            assertTrue(second.get(5L, TimeUnit.SECONDS));
            assertEquals(original, files.records.get(VERIFIER_PATH));
            assertTrue(files.persistCalls >= 1);
        }

        assertTrue(controller.authenticate(request("previous-active"), copyPin()).accepted());
        controller.close();
        controller.close();
        assertEquals(NuggetAuthenticationOutcome.UNAVAILABLE,
                controller.authenticate(request("closed"), copyPin()).outcome());
    }

    @Test
    public void unsafeLocalDiscoveryDirectoryFailsStartupDeterministically() {
        ControlledFileOperations files = new ControlledFileOperations();
        files.validationMode = FileValidationMode.UNSAFE_DIRECTORY_OWNER;

        MiddlewareException failure = assertThrows(MiddlewareException.class,
                () -> start(discoveryConfig(new InMemoryAuditSink(32), files, Path.of("/virtual/discovery"))));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
    }

    @Test
    public void discoveryPublicationFailurePreservesExistingRecord() {
        ControlledFileOperations files = new ControlledFileOperations();
        Path directory = Path.of("/virtual/discovery");
        Path previous = directory.resolve("old.j7m-discovery");
        files.records.put(previous, "previous-record");
        files.discoveryPersistFailure = true;

        MiddlewareException failure = assertThrows(MiddlewareException.class,
                () -> start(discoveryConfig(new InMemoryAuditSink(32), files, directory)));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        assertEquals("previous-record", files.records.get(previous));
    }

    @Test
    public void protectedDiscoveryReadFailuresAreIgnoredSafely() {
        for (DiscoveryReadFailure failure : List.of(
                DiscoveryReadFailure.FILE_CHANGED,
                DiscoveryReadFailure.SYMLINK_REPLACED,
                DiscoveryReadFailure.OWNER_CHANGED,
                DiscoveryReadFailure.PERMISSIONS_CHANGED,
                DiscoveryReadFailure.IDENTITY_CHANGED,
                DiscoveryReadFailure.GROWS_ABOVE_LIMIT,
                DiscoveryReadFailure.UNAVAILABLE_STABLE_IDENTITY)) {
            ControlledFileOperations files = new ControlledFileOperations();
            files.discoveryReadFailure = failure;
            InMemoryAuditSink audit = new InMemoryAuditSink(64);
            NuggetAdminController controller = start(discoveryConfig(audit, files, Path.of("/virtual/discovery")));

            assertEquals(0, controller.discover(new NuggetDiscovery.Query(true, 10, failure.name())).returned(), failure.name());
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.discovery.record.ignored")), failure.name());
            assertFalse(audit.recentEvents().toString().contains("j7m-nugget-discovery-v1"), failure.name());
            controller.close();
        }
    }

    @Test
    public void malformedDiscoveryRecordAuditDoesNotLeakRecordContent() {
        ControlledFileOperations files = new ControlledFileOperations();
        Path directory = Path.of("/virtual/discovery");
        files.records.put(directory.resolve("bad.j7m-discovery"), "SECRET-SENTINEL-not-a-record");
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        NuggetAdminController controller = start(discoveryConfig(audit, files, directory));

        assertEquals(1, controller.discover(new NuggetDiscovery.Query(true, 10, "bad")).returned());
        assertFalse(audit.recentEvents().toString().contains("SECRET-SENTINEL"));
        controller.close();
    }

    @Test
    public void strictDiscoveryRecordGrammarRejectsV1Violations() {
        String canonical = new String(NuggetDiscoveryRecordCodec.encode(discoveryRecord(List.of(
                NuggetCapability.DISCOVER_NUGGETS.name()))), StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical + "unknown=dmFsdWU\n", Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical + "version=dmFsdWU\n", Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical.replaceFirst("runtimeId=[^\\n]+\\n", ""), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical.replace("protocolMinimum=MQ", " protocolMinimum=MQ"), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical.replace("protocolMinimum=MQ", "protocolMinimum=KzE"), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical.replace("protocolMaximum=MQ", "protocolMaximum=OTk5OTk5OTk5"), Instant.EPOCH));
    }

    @Test
    public void discoveryCapabilitiesAreExactAndBounded() {
        List<String> maxCapabilities = java.util.stream.IntStream.range(0, NuggetDiscoveryRecordCodec.MAX_CAPABILITIES)
                .mapToObj(index -> "CAP_" + index)
                .toList();
        NuggetDiscovery.Record decoded = NuggetDiscoveryRecordCodec.decode(
                new String(NuggetDiscoveryRecordCodec.encode(discoveryRecord(maxCapabilities)), StandardCharsets.UTF_8),
                Instant.EPOCH);

        assertEquals(NuggetDiscoveryRecordCodec.MAX_CAPABILITIES, decoded.capabilities().size());
        String canonical = new String(NuggetDiscoveryRecordCodec.encode(discoveryRecord(List.of("A"))), StandardCharsets.UTF_8);
        String tooMany = java.util.stream.IntStream.range(0, NuggetDiscoveryRecordCodec.MAX_CAPABILITIES + 1)
                .mapToObj(index -> "CAP" + index)
                .collect(java.util.stream.Collectors.joining(","));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical.replaceFirst("capabilities=[^\\n]+", "capabilities=" + encodeField(tooMany)),
                Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical.replaceFirst("capabilities=[^\\n]+", "capabilities=" + encodeField("DUP,DUP")),
                Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> NuggetDiscoveryRecordCodec.decode(
                canonical.replaceFirst("capabilities=[^\\n]+", "capabilities=" + encodeField("A".repeat(65))),
                Instant.EPOCH));
    }

    @Test
    public void discoveryTemporalEligibilityAndClockRollbackAreDeterministic() {
        ControlledFileOperations files = new ControlledFileOperations();
        Path directory = Path.of("/virtual/discovery");
        MutableClock clock = new MutableClock(Instant.parse("2026-07-10T10:00:00Z"));
        NuggetAdminController controller = start(discoveryConfig(new InMemoryAuditSink(128), files, directory, clock));
        try {
            Instant now = clock.instant();
            files.records.clear();
            files.records.put(directory.resolve("stale.j7m-discovery"),
                    discoveryRecordText(discoveryRecord("stale", now.minusSeconds(20L), now.minusSeconds(10L), now.minusSeconds(1L))));

            NuggetDiscovery.Result staleOnly = controller.discover(NuggetDiscovery.Query.active());
            assertEquals(0, staleOnly.returned());
            assertEquals(0, staleOnly.totalAvailable());

            files.records.put(directory.resolve("active.j7m-discovery"),
                    discoveryRecordText(discoveryRecord("active", now.minusSeconds(1L), now, now.plusSeconds(5L))));
            NuggetDiscovery.Result mixed = controller.discover(NuggetDiscovery.Query.active());
            assertEquals(1, mixed.returned());
            assertEquals(1, mixed.totalAvailable());

            assertStaleAfterPolicy(controller, files, directory, "future-last-seen",
                    discoveryRecord("future-last-seen", now, now.plusSeconds(40L), now.plusSeconds(45L)));
            assertStaleAfterPolicy(controller, files, directory, "future-start",
                    discoveryRecord("future-start", now.plusSeconds(40L), now, now.plusSeconds(5L)));
            assertStaleAfterPolicy(controller, files, directory, "excessive-lease",
                    discoveryRecord("excessive-lease", now, now, now.plusSeconds(60L)));
            assertStaleAfterPolicy(controller, files, directory, "expiry-before-last-seen",
                    discoveryRecord("expiry-before-last-seen", now, now, now.minusSeconds(1L)));

            files.records.clear();
            files.records.put(directory.resolve("rollback.j7m-discovery"),
                    discoveryRecordText(discoveryRecord("rollback", now.minusSeconds(1L), now, now.plusSeconds(1L))));
            clock.advance(Duration.ofSeconds(2L));
            assertEquals(0, controller.discover(NuggetDiscovery.Query.active()).returned());
            clock.advance(Duration.ofSeconds(-2L));
            assertEquals(0, controller.discover(NuggetDiscovery.Query.active()).returned());
        } finally {
            controller.close();
        }
    }

    @Test
    public void expiredDiscoveryObservationStateIsBounded() {
        ControlledFileOperations files = new ControlledFileOperations();
        NuggetAdminController controller = start(discoveryConfig(new InMemoryAuditSink(32), files, Path.of("/virtual/discovery")));
        try {
            for (int index = 0; index < 2_100; index++) {
                controller.rememberExpiredDiscoveryRecordForTests("id:" + index, Instant.EPOCH.plusSeconds(index));
            }
            assertTrue(controller.expiredDiscoveryRecordCountForTests() <= 2_048);
        } finally {
            controller.close();
        }
    }

    @Test
    public void heartbeatRefreshShutdownAndLatePublicationAreDeterministic() throws Exception {
        ControlledFileOperations files = new ControlledFileOperations();
        Path directory = Path.of("/virtual/discovery");
        MutableClock clock = new MutableClock(Instant.parse("2026-07-10T10:00:00Z"));
        NuggetAdminController controller = start(discoveryConfig(new InMemoryAuditSink(128), files, directory, clock));
        Path recordPath = files.onlyDiscoveryRecord();
        NuggetDiscovery.Record first = NuggetDiscoveryRecordCodec.decode(files.records.get(recordPath), clock.instant());

        clock.advance(Duration.ofSeconds(4L));
        assertTrue(controller.publishLocalDiscoveryRecordForTests());
        NuggetDiscovery.Record refreshed = NuggetDiscoveryRecordCodec.decode(files.records.get(recordPath), clock.instant());
        assertTrue(refreshed.lastSeenAt().isAfter(first.lastSeenAt()));
        assertTrue(refreshed.expiresAt().isAfter(first.expiresAt()));

        files.discoveryPersistenceEntered = new CountDownLatch(1);
        files.releaseDiscoveryPersistence = new CountDownLatch(1);
        files.blockDiscoveryPersistence = true;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> refresh = executor.submit(controller::publishLocalDiscoveryRecordForTests);
            assertTrue(files.discoveryPersistenceEntered.await(5L, TimeUnit.SECONDS), "discovery refresh was not reached");
            Future<?> close = executor.submit(controller::close);
            // Submission alone does not order shutdown before the late persistence completion.
            close.get(5L, TimeUnit.SECONDS);
            files.releaseDiscoveryPersistence.countDown();
            assertFalse(refresh.get(5L, TimeUnit.SECONDS));
        }

        assertEquals(0, files.discoveryRecordCount());
        assertFalse(controller.publishLocalDiscoveryRecordForTests());
        assertTrue(controller.discoveryExecutorTerminatedForTests());
    }

    @Test
    public void concurrentScanAndRefreshDoNotExposePartialRecords() throws Exception {
        ControlledFileOperations files = new ControlledFileOperations();
        Path directory = Path.of("/virtual/discovery");
        MutableClock clock = new MutableClock(Instant.parse("2026-07-10T10:00:00Z"));
        NuggetAdminController controller = start(discoveryConfig(new InMemoryAuditSink(128), files, directory, clock));

        files.discoveryPersistenceEntered = new CountDownLatch(1);
        files.releaseDiscoveryPersistence = new CountDownLatch(1);
        files.blockDiscoveryPersistence = true;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> refresh = executor.submit(controller::publishLocalDiscoveryRecordForTests);
            assertTrue(files.discoveryPersistenceEntered.await(5L, TimeUnit.SECONDS), "discovery refresh was not reached");
            Future<NuggetDiscovery.Result> scan = executor.submit(() -> controller.discover(NuggetDiscovery.Query.active()));
            NuggetDiscovery.Result duringRefresh = scan.get(5L, TimeUnit.SECONDS);
            files.releaseDiscoveryPersistence.countDown();
            assertTrue(refresh.get(5L, TimeUnit.SECONDS));
            assertTrue(duringRefresh.records().stream()
                    .allMatch(record -> record.status() == NuggetDiscovery.Status.ACTIVE));
        } finally {
            controller.close();
        }
    }

    @Test
    public void discoveryTemporaryCleanupIsBoundedAndNonRecursive() {
        ControlledFileOperations files = new ControlledFileOperations();
        Path directory = Path.of("/virtual/discovery");
        NuggetAdminController controller = start(discoveryConfig(new InMemoryAuditSink(64), files, directory));
        files.records.clear();
        Instant now = Instant.parse("2026-07-10T10:00:00Z");
        files.records.put(directory.resolve("owned.j7m-discovery"),
                discoveryRecordText(discoveryRecord("owned", now, now, now.plusSeconds(5L))));
        files.records.put(directory.resolve("other.j7m-discovery"),
                discoveryRecordText(discoveryRecord("other", now, now, now.plusSeconds(5L))));
        files.records.put(directory.resolve("nested").resolve("ignored.tmp"), "nested-temp");
        for (int index = 0; index < 4; index++) {
            files.records.put(directory.resolve("aaa-unrelated-" + index + ".txt"), "unrelated");
        }
        for (int index = 0; index < 20; index++) {
            files.records.put(directory.resolve("abandoned-" + index + ".tmp"), "temporary");
        }

        NuggetDiscovery.Result result = controller.discover(new NuggetDiscovery.Query(true, 10, "cleanup"));

        assertEquals(2, result.returned());
        assertEquals(12, files.cleanupDeletes);
        assertEquals(16, files.lastCleanupMaxDeletes);
        assertEquals(8, files.records.keySet().stream()
                .filter(path -> directory.equals(path.getParent()))
                .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .count());
        assertTrue(files.records.containsKey(directory.resolve("owned.j7m-discovery")));
        assertTrue(files.records.containsKey(directory.resolve("other.j7m-discovery")));
        assertTrue(files.records.containsKey(directory.resolve("nested").resolve("ignored.tmp")));
        controller.close();
    }

    @Test
    public void configuredTargetAuditCoversAcceptedRejectedAndProtocolIncompatibleSafely() {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        NuggetAdminConfiguration.Builder rejected = NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit));
        assertThrows(IllegalArgumentException.class, () -> rejected.discoveryTarget(
                "admin",
                "https",
                "user@example.invalid?token=SECRET-SENTINEL",
                443,
                "local-secret",
                "nugget",
                "password SECRET-SENTINEL",
                1,
                1));

        ControlledFileOperations files = new ControlledFileOperations();
        AtomicLong nanos = new AtomicLong();
        NuggetAdminConfiguration configuration = NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .localDiscovery(Path.of("/virtual/discovery"), true)
                .discoveryTiming(Duration.ofSeconds(5L), Duration.ofSeconds(1L))
                .fileOperations(files)
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(
                        NuggetDiscovery.EndpointDescriptor.local("compatible"),
                        "compatible-id",
                        "safe-name",
                        1,
                        1))
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(
                        NuggetDiscovery.EndpointDescriptor.local("incompatible"),
                        "incompatible-id",
                        "unsafe password SECRET-SENTINEL",
                        2,
                        2))
                .build();
        NuggetAdminController controller = start(configuration);
        controller.discover(new NuggetDiscovery.Query(true, 10, "audit"));
        controller.close();

        String auditText = audit.recentEvents().toString();
        assertTrue(audit.recentEvents().stream()
                .anyMatch(event -> event.action().equals("nugget.discovery.configured-target.rejected")));
        assertTrue(audit.recentEvents().stream()
                .anyMatch(event -> event.action().equals("nugget.discovery.configured-target.accepted")));
        assertTrue(audit.recentEvents().stream()
                .anyMatch(event -> event.action().equals("nugget.discovery.protocol.incompatible")));
        assertFalse(auditText.contains("SECRET-SENTINEL"));
        assertFalse(auditText.contains("example.invalid"));
        assertFalse(auditText.contains("j7m-local:incompatible"));
    }

    @Test
    public void rejectedControlExecutorRestoresShutdownState() {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        AtomicInteger closeCalls = new AtomicInteger();
        NuggetAdminConfiguration configuration = NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .runtimeCloseAction(closeCalls::incrementAndGet)
                .confirmationPolicy(Duration.ofSeconds(30L), 4)
                .resourceFactory(new RejectingControlResourceFactory())
                .build();
        NuggetAdminController controller = start(configuration);
        try {
            String admission = controller.authenticate(request("control-reject-auth"), copyPin()).admissionId();
            NuggetControl.Result challenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "control-reject"));

            NuggetControl.Result rejected = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(), "control-reject"));

            assertEquals(NuggetControl.Outcome.OUTCOME_UNKNOWN, rejected.outcome());
            assertEquals(MiddlewareErrorCode.OVERLOADED, rejected.error().code());
            assertEquals(NuggetState.RUNNING, controller.status().identity().state());
            assertEquals(0, closeCalls.get());
            assertTrue(audit.recentEvents().toString().contains("control-overloaded"));
        } finally {
            controller.close();
        }
    }

    @Test
    public void auditBoundaryCancellationRestoresShutdownStateAndSubmittedFlag() throws Exception {
        ThrowingControlAuditSink audit = new ThrowingControlAuditSink(128);
        CountDownLatch closeCalled = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        NuggetAdminConfiguration configuration = NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .runtimeCloseAction(() -> {
                    closeCalls.incrementAndGet();
                    closeCalled.countDown();
                })
                .confirmationPolicy(Duration.ofSeconds(30L), 4)
                .controlExecutionPolicy(1, 2, Duration.ofSeconds(2L), Duration.ofSeconds(2L))
                .build();
        NuggetAdminController controller = start(configuration);
        try {
            String admission = controller.authenticate(request("audit-rollback-auth"), copyPin()).admissionId();
            NuggetControl.Result challenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "audit-rollback"));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> controller.nuggetControl(controlRequest(controller, admission,
                            NuggetControl.Action.SHUTDOWN_NUGGET, challenge.confirmation().challenge(), "audit-rollback")));

            assertEquals("control audit failed", failure.getMessage());
            assertEquals(NuggetState.RUNNING, controller.status().identity().state());
            assertEquals(0, closeCalls.get());

            NuggetControl.Result retryChallenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "audit-rollback-retry"));
            NuggetControl.Result accepted = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    retryChallenge.confirmation().challenge(), "audit-rollback-retry"));

            assertEquals(NuggetControl.Outcome.CONFIRMATION_REQUIRED, retryChallenge.outcome());
            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(closeCalled.await(2L, TimeUnit.SECONDS));
            assertEquals(1, closeCalls.get());
        } finally {
            controller.close();
        }
    }

    @Test
    public void auditBoundaryRollbackCannotOverwriteConcurrentAcceptedKillStopping() throws Exception {
        BlockingOperationAuditSink audit = new BlockingOperationAuditSink(128, "rollback-owner");
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger killCalls = new AtomicInteger();
        CountDownLatch killCalled = new CountDownLatch(1);
        NuggetAdminConfiguration configuration = NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .runtimeCloseAction(closeCalls::incrementAndGet)
                .killEnabled(true)
                .terminationCapability(() -> {
                    killCalls.incrementAndGet();
                    killCalled.countDown();
                })
                .confirmationPolicy(Duration.ofSeconds(30L), 4)
                .controlExecutionPolicy(1, 4, Duration.ofSeconds(2L), Duration.ofSeconds(2L))
                .build();
        NuggetAdminController controller = start(configuration);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String admission = controller.authenticate(request("rollback-owner-auth"), copyPin()).admissionId();
            NuggetControl.Result shutdownChallenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "rollback-owner"));

            Future<IllegalStateException> failingShutdown = executor.submit(() -> assertThrows(
                    IllegalStateException.class,
                    () -> controller.nuggetControl(controlRequest(
                            controller,
                            admission,
                            NuggetControl.Action.SHUTDOWN_NUGGET,
                            shutdownChallenge.confirmation().challenge(),
                            "rollback-owner"))));
            assertTrue(audit.entered.await(5L, TimeUnit.SECONDS), "shutdown audit boundary was not reached");

            NuggetControl.Result killChallenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.KILL_NUGGET, "", "accepted-kill"));
            NuggetControl.Result acceptedKill = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.KILL_NUGGET,
                    killChallenge.confirmation().challenge(),
                    "accepted-kill"));

            assertEquals(NuggetControl.Outcome.ACCEPTED, acceptedKill.outcome());
            assertEquals(NuggetState.STOPPING, controller.status().identity().state());

            audit.release.countDown();
            assertEquals("control audit failed", failingShutdown.get(5L, TimeUnit.SECONDS).getMessage());
            assertEquals(NuggetState.STOPPING, controller.status().identity().state());
            assertEquals(0, closeCalls.get());
            assertTrue(killCalled.await(5L, TimeUnit.SECONDS), "accepted kill action did not run");
            assertEquals(1, killCalls.get());
        } finally {
            controller.close();
        }
    }

    @Test
    public void concurrentConfirmationCreationRetainsConfiguredBound() throws Exception {
        NuggetAdminConfiguration configuration = NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, new InMemoryAuditSink(128)))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .runtimeCloseAction(() -> { })
                .confirmationPolicy(Duration.ofSeconds(30L), 1)
                .build();
        NuggetAdminController controller = start(configuration);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            String admission = controller.authenticate(request("retention-auth"), copyPin()).admissionId();
            CountDownLatch startGate = new CountDownLatch(1);
            Future<NuggetControl.Result> first = callers.submit(() -> {
                startGate.await();
                return controller.nuggetControl(controlRequest(
                        controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "retained-one"));
            });
            Future<NuggetControl.Result> second = callers.submit(() -> {
                startGate.await();
                return controller.nuggetControl(controlRequest(
                        controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "retained-two"));
            });
            startGate.countDown();

            NuggetControl.Result firstChallenge = first.get(2L, TimeUnit.SECONDS);
            NuggetControl.Result secondChallenge = second.get(2L, TimeUnit.SECONDS);
            int acceptedConfirmations = 0;
            if (controller.nuggetControl(controlRequest(controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    firstChallenge.confirmation().challenge(), "retained-one")).outcome() == NuggetControl.Outcome.ACCEPTED) {
                acceptedConfirmations++;
            }
            if (controller.nuggetControl(controlRequest(controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    secondChallenge.confirmation().challenge(), "retained-two")).outcome() == NuggetControl.Outcome.ACCEPTED) {
                acceptedConfirmations++;
            }

            assertEquals(1, acceptedConfirmations);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new AssertionError("concurrent confirmation creation failed", exception);
        } finally {
            callers.shutdownNow();
            controller.close();
        }
    }

    @Test
    public void acceptedControlMonitorUsesSmallerServerTimeoutOrRequestDeadline() throws Exception {
        assertControlMonitorDelay(Duration.ofSeconds(2L), Duration.ofMillis(500L), 500L);
        assertControlMonitorDelay(Duration.ofSeconds(2L), Duration.ofSeconds(2L), 2_000L);
        assertControlMonitorDelay(Duration.ofSeconds(2L), Duration.ofSeconds(10L), 2_000L);
    }

    @Test
    public void cooperativeControlTimeoutInterruptsActionAndKeepsTimedOutPrecedence() throws Exception {
        ManualControlMonitorExecutor monitor = new ManualControlMonitorExecutor();
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch actionDone = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        NuggetAdminConfiguration configuration = controlMonitorConfiguration(audit, monitor, Duration.ofSeconds(2L),
                () -> {
                    closeCalls.incrementAndGet();
                    actionEntered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    } finally {
                        actionDone.countDown();
                    }
                }, null);
        NuggetAdminController controller = start(configuration);
        try {
            String admission = controller.authenticate(request("timeout-auth"), copyPin()).admissionId();
            NuggetControl.Result challenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "timeout-cooperative"));
            NuggetControl.Result accepted = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(),
                    "timeout-cooperative"));

            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(actionEntered.await(2L, TimeUnit.SECONDS));
            monitor.runNext();

            assertTrue(interrupted.await(2L, TimeUnit.SECONDS));
            assertTrue(actionDone.await(2L, TimeUnit.SECONDS));
            assertEquals(1, closeCalls.get());
            assertEquals(NuggetState.DEGRADED, controller.status().identity().state());
            NuggetControl.Result replay = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(),
                    "timeout-cooperative"));
            assertEquals(NuggetControl.Outcome.TIMED_OUT, replay.outcome());
            assertTrue(replay.outcomeUnknown());
            assertTrue(replay.cleanupPartial());
            assertEquals(NuggetState.DEGRADED, replay.currentState());
            assertEquals(NuggetState.DEGRADED, replay.resultingState());
            assertEquals("timed-out", replay.metadata().get("result"));
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.control.async.timedOut")
                            && event.metadata().getOrDefault("operationId", "").equals("timeout-cooperative")
                            && event.metadata().getOrDefault("outcome", "").equals("TIMED_OUT")));
            assertFalse(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.control.async.completed")
                            && event.metadata().getOrDefault("operationId", "").equals("timeout-cooperative")));
        } finally {
            controller.close();
        }
    }

    @Test
    public void selfCloseControlTimeoutAccountingDoesNotInterruptCurrentAction() throws Exception {
        ManualControlMonitorExecutor monitor = new ManualControlMonitorExecutor();
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        AtomicReference<NuggetAdminController> controllerRef = new AtomicReference<>();
        CountDownLatch cleanupReturned = new CountDownLatch(1);
        CountDownLatch timeoutObserved = new CountDownLatch(1);
        CountDownLatch actionDone = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger interruptionCount = new AtomicInteger();
        NuggetAdminConfiguration configuration = controlMonitorConfiguration(audit, monitor, Duration.ofSeconds(2L),
                () -> {
                    closeCalls.incrementAndGet();
                    controllerRef.get().close();
                    cleanupReturned.countDown();
                    try {
                        timeoutObserved.await();
                    } catch (InterruptedException exception) {
                        interruptionCount.incrementAndGet();
                        Thread.currentThread().interrupt();
                    } finally {
                        actionDone.countDown();
                    }
                }, null);
        NuggetAdminController controller = start(configuration);
        controllerRef.set(controller);
        try {
            String admission = controller.authenticate(request("self-close-timeout-auth"), copyPin()).admissionId();
            NuggetControl.Result challenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "self-close-timeout"));
            NuggetControl.Result accepted = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(),
                    "self-close-timeout"));
            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(cleanupReturned.await(2L, TimeUnit.SECONDS));

            monitor.runNext();
            timeoutObserved.countDown();

            assertTrue(actionDone.await(2L, TimeUnit.SECONDS));
            assertEquals(1, closeCalls.get());
            assertEquals(0, interruptionCount.get());
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.control.async.timedOut")
                            && event.metadata().getOrDefault("operationId", "").equals("self-close-timeout")
                            && event.metadata().getOrDefault("outcome", "").equals("TIMED_OUT")));
            assertFalse(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.control.async.completed")
                            && event.metadata().getOrDefault("operationId", "").equals("self-close-timeout")));
        } finally {
            timeoutObserved.countDown();
            controller.close();
        }
    }

    @Test
    public void nonCooperativeControlTimeoutDoesNotBlockAdminCloseOrReplayExecution() throws Exception {
        ManualControlMonitorExecutor monitor = new ManualControlMonitorExecutor();
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        NuggetAdminConfiguration configuration = controlMonitorConfiguration(audit, monitor, Duration.ofSeconds(2L),
                () -> {
                    closeCalls.incrementAndGet();
                    actionEntered.countDown();
                    while (releaseAction.getCount() > 0L) {
                        Thread.onSpinWait();
                    }
                }, null);
        NuggetAdminController controller = start(configuration);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            String admission = controller.authenticate(request("noncoop-timeout-auth"), copyPin()).admissionId();
            NuggetControl.Result challenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "timeout-noncoop"));
            NuggetControl.Result accepted = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(),
                    "timeout-noncoop"));
            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(actionEntered.await(2L, TimeUnit.SECONDS));

            monitor.runNext();

            NuggetControl.Result replay = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(),
                    "timeout-noncoop"));
            assertEquals(NuggetControl.Outcome.TIMED_OUT, replay.outcome());
            assertTrue(replay.outcomeUnknown());
            assertTrue(replay.cleanupPartial());
            assertEquals(1, closeCalls.get());
            Future<?> closed = closer.submit(controller::close);
            closed.get(2L, TimeUnit.SECONDS);
            assertEquals(NuggetState.STOPPED, controller.status().identity().state());
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.control.async.timedOut")
                            && event.metadata().getOrDefault("operationId", "").equals("timeout-noncoop")));
        } finally {
            releaseAction.countDown();
            closer.shutdownNow();
            controller.close();
        }
    }

    @Test
    public void enabledKillTimeoutAndReplayInvokeInjectedCapabilityAtMostOnce() throws Exception {
        ManualControlMonitorExecutor monitor = new ManualControlMonitorExecutor();
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CountDownLatch actionDone = new CountDownLatch(1);
        AtomicInteger killCalls = new AtomicInteger();
        NuggetAdminConfiguration configuration = controlMonitorConfiguration(audit, monitor, Duration.ofSeconds(2L),
                null,
                () -> {
                    killCalls.incrementAndGet();
                    actionEntered.countDown();
                    while (releaseAction.getCount() > 0L) {
                        Thread.onSpinWait();
                    }
                    actionDone.countDown();
                });
        NuggetAdminController controller = start(configuration);
        try {
            String admission = controller.authenticate(request("kill-timeout-auth"), copyPin()).admissionId();
            NuggetControl.Result challenge = controller.nuggetControl(controlRequest(
                    controller, admission, NuggetControl.Action.KILL_NUGGET, "", "kill-timeout"));
            NuggetControl.Result accepted = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.KILL_NUGGET,
                    challenge.confirmation().challenge(),
                    "kill-timeout"));
            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(actionEntered.await(2L, TimeUnit.SECONDS));

            monitor.runNext();

            NuggetControl.Result replay = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.KILL_NUGGET,
                    challenge.confirmation().challenge(),
                    "kill-timeout"));
            assertEquals(NuggetControl.Outcome.TIMED_OUT, replay.outcome());
            assertTrue(replay.outcomeUnknown());
            assertTrue(replay.cleanupPartial());
            assertEquals(1, killCalls.get());
            releaseAction.countDown();
            assertTrue(actionDone.await(2L, TimeUnit.SECONDS));
            NuggetControl.Result lateReplay = controller.nuggetControl(controlRequest(
                    controller,
                    admission,
                    NuggetControl.Action.KILL_NUGGET,
                    challenge.confirmation().challenge(),
                    "kill-timeout"));
            assertEquals(NuggetControl.Outcome.TIMED_OUT, lateReplay.outcome());
            assertTrue(lateReplay.outcomeUnknown());
            assertTrue(lateReplay.cleanupPartial());
        } finally {
            releaseAction.countDown();
            controller.close();
        }
    }

    private static NuggetAdminConfiguration config(
            final SecurityMode mode,
            final InMemoryAuditSink audit,
            final ControlledFileOperations files,
            final NuggetAdminLifecycleHooks hooks,
            final int iterations) {
        AtomicLong nanos = new AtomicLong();
        return NuggetAdminConfiguration.builder()
                .security(security(mode, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .iterations(iterations)
                .pinFromProtectedVerifierFile(VERIFIER_PATH)
                .fileOperations(files)
                .lifecycleHooks(hooks)
                .build();
    }

    private static NuggetAdminConfiguration discoveryConfig(
            final InMemoryAuditSink audit,
            final ControlledFileOperations files,
            final Path directory) {
        return discoveryConfig(audit, files, directory,
                Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC));
    }

    private static NuggetAdminConfiguration discoveryConfig(
            final InMemoryAuditSink audit,
            final ControlledFileOperations files,
            final Path directory,
            final Clock clock) {
        AtomicLong nanos = new AtomicLong();
        return NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(clock)
                .monotonicTimeSource(nanos::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .localDiscovery(directory, true)
                .discoveryTiming(Duration.ofSeconds(5L), Duration.ofSeconds(1L))
                .fileOperations(files)
                .build();
    }

    private static NuggetAdminConfiguration configWithProgrammaticPin(
            final InMemoryAuditSink audit,
            final NuggetAdminLifecycleHooks hooks) {
        AtomicLong nanos = new AtomicLong();
        return NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .lifecycleHooks(hooks)
                .build();
    }

    private static NuggetAdminConfiguration configWithProtectedPinFile(
            final SecurityMode mode,
            final InMemoryAuditSink audit,
            final ControlledFileOperations files) {
        AtomicLong nanos = new AtomicLong();
        return NuggetAdminConfiguration.builder()
                .security(security(mode, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .pinFromProtectedFile(VERIFIER_PATH)
                .fileOperations(files)
                .build();
    }

    private static SecurityConfiguration security(final SecurityMode mode, final SecurityAuditSink audit) {
        return new SecurityConfiguration(mode, new AllowAllAuthorizationPolicy(), audit, new DefaultRedactor(),
                false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())));
    }

    private static NuggetAdminController start(final NuggetAdminConfiguration configuration) {
        return NuggetAdminController.start(new RuntimeIdentity("runtime-test", "default", Map.of()), configuration);
    }

    private static NuggetAuthenticationRequest request(final String correlationId) {
        return NuggetAuthenticationRequest.of(correlationId, NuggetAuthenticationSource.LOCAL, "test-local", true, Instant.EPOCH);
    }

    private static NuggetControl.Request controlRequest(
            final NuggetAdminController controller,
            final String admission,
            final NuggetControl.Action action,
            final String confirmation,
            final String operationId) {
        return new NuggetControl.Request(
                admission,
                SecurityContext.system("internal-control-caller"),
                operationId,
                operationId,
                action,
                action == NuggetControl.Action.KILL_NUGGET ? NuggetControl.Mode.FORCED : NuggetControl.Mode.GRACEFUL,
                controller.status().identity().nuggetId(),
                "runtime-test",
                1L,
                confirmation,
                Instant.EPOCH,
                Instant.parse("2026-07-10T10:00:10Z"),
                Map.of());
    }

    private static char[] copyPin() {
        return Arrays.copyOf(PIN, PIN.length);
    }

    private static void assertControlMonitorDelay(
            final Duration controlTimeout,
            final Duration requestDeadline,
            final long expectedDelayMillis) throws Exception {
        ManualControlMonitorExecutor monitor = new ManualControlMonitorExecutor();
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        CountDownLatch actionCalled = new CountDownLatch(1);
        NuggetAdminConfiguration configuration = controlMonitorConfiguration(
                audit,
                monitor,
                controlTimeout,
                actionCalled::countDown,
                null);
        NuggetAdminController controller = start(configuration);
        try {
            String admission = controller.authenticate(request("delay-auth-" + expectedDelayMillis), copyPin()).admissionId();
            NuggetControl.Result challenge = controller.nuggetControl(controlRequestWithDeadline(
                    controller,
                    admission,
                    NuggetControl.Action.SHUTDOWN_NUGGET,
                    "",
                    "delay-" + expectedDelayMillis,
                    requestDeadline));
            NuggetControl.Result accepted = controller.nuggetControl(controlRequestWithDeadline(
                    controller,
                    admission,
                    NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(),
                    "delay-" + expectedDelayMillis,
                    requestDeadline));

            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(actionCalled.await(2L, TimeUnit.SECONDS));
            assertEquals(expectedDelayMillis, monitor.delayMillis(0));
        } finally {
            controller.close();
        }
    }

    private static NuggetAdminConfiguration controlMonitorConfiguration(
            final InMemoryAuditSink audit,
            final ManualControlMonitorExecutor monitor,
            final Duration controlTimeout,
            final Runnable runtimeClose,
            final Runnable terminationCapability) {
        NuggetAdminConfiguration.Builder builder = NuggetAdminConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetAdminInternalCoverageTest::copyPin)
                .resourceFactory(new ControlledMonitorResourceFactory(monitor))
                .confirmationPolicy(Duration.ofSeconds(30L), 4)
                .controlExecutionPolicy(1, 2, controlTimeout, Duration.ofMillis(1L));
        if (runtimeClose != null) {
            builder.runtimeCloseAction(runtimeClose);
        }
        if (terminationCapability != null) {
            builder.killEnabled(true);
            builder.terminationCapability(terminationCapability);
        }
        return builder.build();
    }

    private static NuggetControl.Request controlRequestWithDeadline(
            final NuggetAdminController controller,
            final String admission,
            final NuggetControl.Action action,
            final String confirmation,
            final String operationId,
            final Duration deadlineFromNow) {
        Instant requestedAt = Instant.parse("2026-07-10T10:00:00Z");
        return new NuggetControl.Request(
                admission,
                SecurityContext.system("internal-control-caller"),
                operationId,
                operationId,
                action,
                action == NuggetControl.Action.KILL_NUGGET ? NuggetControl.Mode.FORCED : NuggetControl.Mode.GRACEFUL,
                controller.status().identity().nuggetId(),
                "runtime-test",
                1L,
                confirmation,
                requestedAt,
                requestedAt.plus(deadlineFromNow),
                Map.of());
    }

    private static String verifierRecord(final char[] pin, final int iterations) {
        NuggetPinVerifier verifier = NuggetPinVerifier.create(pin, iterations, new SecureRandom(), Instant.EPOCH, "test-policy");
        try {
            return verifier.encodedRecord();
        } finally {
            verifier.close();
        }
    }

    private enum FileValidationMode {
        OK,
        SYMBOLIC_LINK,
        UNEXPECTED_OWNER,
        UNSAFE_FILE_PERMISSIONS,
        UNSAFE_DIRECTORY_PERMISSIONS,
        UNSAFE_DIRECTORY_OWNER,
        UNAVAILABLE,
        NON_REGULAR
    }

    private enum PersistFailure {
        NONE,
        ATOMIC_REPLACEMENT_UNAVAILABLE,
        WRITE_FAILURE,
        FORCE_FAILURE,
        REVALIDATION_FAILURE,
        MOVE_FAILURE
    }

    private enum DiscoveryReadFailure {
        NONE,
        FILE_CHANGED,
        SYMLINK_REPLACED,
        OWNER_CHANGED,
        PERMISSIONS_CHANGED,
        IDENTITY_CHANGED,
        GROWS_ABOVE_LIMIT,
        UNAVAILABLE_STABLE_IDENTITY
    }

    private static NuggetDiscovery.Record discoveryRecord(final List<String> capabilities) {
        Instant now = Instant.parse("2026-07-10T10:00:00Z");
        return new NuggetDiscovery.Record(
                "nugget-test",
                "test",
                "runtime-test",
                NuggetDiscovery.EndpointDescriptor.local("nugget-test"),
                1,
                1,
                NuggetDiscovery.Compatibility.COMPATIBLE,
                capabilities,
                "1",
                "LOCAL",
                now,
                now,
                now.plusSeconds(5L),
                NuggetDiscovery.Source.LOCAL_RECORD,
                NuggetDiscovery.Status.ACTIVE,
                List.of());
    }

    private static NuggetDiscovery.Record discoveryRecord(
            final String nuggetId,
            final Instant startedAt,
            final Instant lastSeenAt,
            final Instant expiresAt) {
        return new NuggetDiscovery.Record(
                nuggetId,
                "test-" + nuggetId,
                "runtime-test",
                NuggetDiscovery.EndpointDescriptor.local(nuggetId),
                1,
                1,
                NuggetDiscovery.Compatibility.COMPATIBLE,
                List.of(NuggetCapability.DISCOVER_NUGGETS.name()),
                "1",
                "LOCAL",
                startedAt,
                lastSeenAt,
                expiresAt,
                NuggetDiscovery.Source.LOCAL_RECORD,
                NuggetDiscovery.Status.ACTIVE,
                List.of());
    }

    private static String discoveryRecordText(final NuggetDiscovery.Record record) {
        return new String(NuggetDiscoveryRecordCodec.encode(record), StandardCharsets.UTF_8);
    }

    private static void assertStaleAfterPolicy(
            final NuggetAdminController controller,
            final ControlledFileOperations files,
            final Path directory,
            final String id,
            final NuggetDiscovery.Record record) {
        files.records.clear();
        files.records.put(directory.resolve(id + ".j7m-discovery"), discoveryRecordText(record));
        NuggetDiscovery.Result active = controller.discover(NuggetDiscovery.Query.active());
        assertEquals(0, active.returned(), id);
        assertEquals(0, active.totalAvailable(), id);
        NuggetDiscovery.Result stale = controller.discover(new NuggetDiscovery.Query(true, 10, id));
        assertTrue(stale.records().stream()
                .anyMatch(value -> value.nuggetId().equals(id) && value.status() == NuggetDiscovery.Status.STALE), id);
    }

    private static String encodeField(final String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(final Instant initial) {
            this.now = initial;
        }

        private void advance(final Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }

    private static final class RejectingControlResourceFactory implements NuggetAdminResourceFactory {
        @Override
        public ExecutorService newProviderExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            return Executors.newSingleThreadExecutor();
        }

        @Override
        public ExecutorService newControlExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.shutdown();
            return executor;
        }

        @Override
        public ScheduledThreadPoolExecutor newDiscoveryExecutor(final String nuggetId) {
            return new ScheduledThreadPoolExecutor(1);
        }
    }

    private static final class ControlledMonitorResourceFactory implements NuggetAdminResourceFactory {
        private final ManualControlMonitorExecutor monitor;

        private ControlledMonitorResourceFactory(final ManualControlMonitorExecutor monitor) {
            this.monitor = monitor;
        }

        @Override
        public ExecutorService newProviderExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            return Executors.newSingleThreadExecutor();
        }

        @Override
        public ExecutorService newControlExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            return Executors.newSingleThreadExecutor();
        }

        @Override
        public ScheduledExecutorService newControlMonitorExecutor(final String nuggetId) {
            return this.monitor;
        }

        @Override
        public ScheduledThreadPoolExecutor newDiscoveryExecutor(final String nuggetId) {
            return new ScheduledThreadPoolExecutor(1);
        }
    }

    private static final class ManualControlMonitorExecutor extends AbstractExecutorService implements ScheduledExecutorService {
        private final List<ManualScheduledFuture> scheduled = new java.util.ArrayList<>();
        private boolean shutdown;

        @Override
        public synchronized ScheduledFuture<?> schedule(
                final Runnable command,
                final long delay,
                final TimeUnit unit) {
            if (this.shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("manual monitor is shut down");
            }
            ManualScheduledFuture future = new ManualScheduledFuture(command, unit.toMillis(delay));
            this.scheduled.add(future);
            return future;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(
                final Callable<V> callable,
                final long delay,
                final TimeUnit unit) {
            throw new UnsupportedOperationException("callable scheduling is not used by this test");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                final Runnable command,
                final long initialDelay,
                final long period,
                final TimeUnit unit) {
            throw new UnsupportedOperationException("periodic scheduling is not used by this test");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                final Runnable command,
                final long initialDelay,
                final long delay,
                final TimeUnit unit) {
            throw new UnsupportedOperationException("periodic scheduling is not used by this test");
        }

        @Override
        public synchronized void shutdown() {
            this.shutdown = true;
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            this.shutdown = true;
            for (ManualScheduledFuture future : this.scheduled) {
                future.cancel(false);
            }
            return List.of();
        }

        @Override
        public synchronized boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return this.shutdown;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(final Runnable command) {
            this.schedule(command, 0L, TimeUnit.MILLISECONDS);
        }

        private synchronized long delayMillis(final int index) {
            return this.scheduled.get(index).delayMillis;
        }

        private void runNext() {
            ManualScheduledFuture future;
            synchronized (this) {
                if (this.scheduled.isEmpty()) {
                    throw new IllegalStateException("no scheduled control monitor task");
                }
                future = this.scheduled.remove(0);
            }
            future.run();
        }
    }

    private static final class ManualScheduledFuture implements ScheduledFuture<Object> {
        private final Runnable command;
        private final long delayMillis;
        private final CountDownLatch done;
        private volatile boolean cancelled;

        private ManualScheduledFuture(final Runnable command, final long delayMillis) {
            this.command = command;
            this.delayMillis = delayMillis;
            this.done = new CountDownLatch(1);
        }

        private void run() {
            if (!this.cancelled) {
                try {
                    this.command.run();
                } finally {
                    this.done.countDown();
                }
            }
        }

        @Override
        public long getDelay(final TimeUnit unit) {
            return unit.convert(this.delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(final Delayed other) {
            return Long.compare(this.getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            this.cancelled = true;
            this.done.countDown();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public boolean isDone() {
            return this.done.getCount() == 0L;
        }

        @Override
        public Object get() throws InterruptedException {
            this.done.await();
            return null;
        }

        @Override
        public Object get(final long timeout, final TimeUnit unit) throws InterruptedException {
            this.done.await(timeout, unit);
            return null;
        }
    }

    private static final class ThrowingControlAuditSink implements SecurityAuditSink {
        private final InMemoryAuditSink delegate;
        private final AtomicInteger remainingFailures;

        private ThrowingControlAuditSink(final int capacity) {
            this.delegate = new InMemoryAuditSink(capacity);
            this.remainingFailures = new AtomicInteger(1);
        }

        @Override
        public void record(final AuditEvent event) {
            if (event != null
                    && event.action().equals("nugget.control")
                    && event.metadata().getOrDefault("outcome", "").equals("ACCEPTED")
                    && this.remainingFailures.compareAndSet(1, 0)) {
                throw new IllegalStateException("control audit failed");
            }
            this.delegate.record(event);
        }

        @Override
        public List<AuditEvent> recentEvents() {
            return this.delegate.recentEvents();
        }

        @Override
        public long eventCount() {
            return this.delegate.eventCount();
        }
    }

    private static final class BlockingOperationAuditSink implements SecurityAuditSink {
        private final InMemoryAuditSink delegate;
        private final String operationId;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger remainingFailures;

        private BlockingOperationAuditSink(final int capacity, final String operationId) {
            this.delegate = new InMemoryAuditSink(capacity);
            this.operationId = operationId;
            this.entered = new CountDownLatch(1);
            this.release = new CountDownLatch(1);
            this.remainingFailures = new AtomicInteger(1);
        }

        @Override
        public void record(final AuditEvent event) {
            if (event != null
                    && event.action().equals("nugget.control")
                    && event.metadata().getOrDefault("operationId", "").equals(this.operationId)
                    && event.metadata().getOrDefault("outcome", "").equals("ACCEPTED")
                    && this.remainingFailures.compareAndSet(1, 0)) {
                this.entered.countDown();
                try {
                    if (!this.release.await(5L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("control audit release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("control audit interrupted", exception);
                }
                throw new IllegalStateException("control audit failed");
            }
            this.delegate.record(event);
        }

        @Override
        public List<AuditEvent> recentEvents() {
            return this.delegate.recentEvents();
        }

        @Override
        public long eventCount() {
            return this.delegate.eventCount();
        }
    }

    private static final class ControlledFileOperations implements NuggetAdminFileOperations {
        private final Map<Path, String> records = new HashMap<>();
        private final CountDownLatch persistenceEntered = new CountDownLatch(1);
        private final CountDownLatch releasePersistence = new CountDownLatch(1);
        private CountDownLatch discoveryPersistenceEntered = new CountDownLatch(1);
        private CountDownLatch releaseDiscoveryPersistence = new CountDownLatch(1);
        private FileValidationMode validationMode = FileValidationMode.OK;
        private PersistFailure persistFailure = PersistFailure.NONE;
        private boolean blockPersistence;
        private boolean blockDiscoveryPersistence;
        private int reads;
        private int unboundedReads;
        private int persistCalls;
        private int discoveryPersistCalls;
        private int discoveryRemoveCalls;
        private int cleanupDeletes;
        private int lastCleanupMaxDeletes;
        private int lastBoundedReadLimit;
        private boolean temporaryFilePresent;
        private boolean partialTemporaryContentCreated;
        private boolean discoveryPersistFailure;
        private boolean discoveryRemoveFailure;
        private DiscoveryReadFailure discoveryReadFailure = DiscoveryReadFailure.NONE;

        @Override
        public void validateProtectedFile(final Path path) throws IOException {
            switch (this.validationMode) {
                case OK -> {
                }
                case SYMBOLIC_LINK -> throw new IOException("protected file rejected");
                case UNEXPECTED_OWNER -> throw new IOException("protected file owner rejected");
                case UNSAFE_FILE_PERMISSIONS -> throw new IOException("protected file permissions rejected");
                case UNSAFE_DIRECTORY_PERMISSIONS -> throw new IOException("protected directory permissions rejected");
                case UNSAFE_DIRECTORY_OWNER -> throw new IOException("protected directory owner rejected");
                case UNAVAILABLE -> throw new NuggetAdminFileSecurityUnavailableException("protected file permissions unavailable");
                case NON_REGULAR -> throw new IOException("protected file rejected");
            }
        }

        @Override
        public void validateProtectedDirectory(final Path path) throws IOException {
            this.validateProtectedFile(path);
        }

        @Override
        public void ensureProtectedDirectory(final Path path) throws IOException {
            this.validateProtectedDirectory(path);
        }

        @Override
        public NuggetAdminFileOperations.DiscoveryDirectoryEntries listDiscoveryEntries(final Path directory, final int maxEntries) {
            List<Path> values = this.records.keySet().stream()
                    .filter(path -> directory.equals(path.getParent()))
                    .sorted()
                    .limit(maxEntries)
                    .toList();
            long total = this.records.keySet().stream()
                    .filter(path -> directory.equals(path.getParent()))
                    .count();
            return new NuggetAdminFileOperations.DiscoveryDirectoryEntries(values, total > values.size());
        }

        @Override
        public byte[] readAllBytes(final Path path) {
            this.reads++;
            this.unboundedReads++;
            String value = this.records.get(path);
            if (value == null) {
                return new byte[0];
            }
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public char[] readBoundedUtf8Chars(final Path path, final int maxBytes) throws IOException {
            this.reads++;
            this.lastBoundedReadLimit = maxBytes;
            String value = this.records.get(path);
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IOException("protected PIN file rejected");
            }
            return value.toCharArray();
        }

        @Override
        public String readBoundedUtf8(final Path path, final int maxBytes) throws IOException {
            this.reads++;
            this.lastBoundedReadLimit = maxBytes;
            String value = this.records.get(path);
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IOException("protected verifier file rejected");
            }
            return value;
        }

        @Override
        public String readProtectedBoundedUtf8(final Path path, final int maxBytes) throws IOException {
            this.validateProtectedFile(path);
            switch (this.discoveryReadFailure) {
                case NONE -> {
                }
                case FILE_CHANGED -> throw new IOException("protected discovery record changed");
                case SYMLINK_REPLACED -> throw new IOException("protected discovery record rejected");
                case OWNER_CHANGED -> throw new IOException("protected discovery record owner rejected");
                case PERMISSIONS_CHANGED -> throw new IOException("protected discovery record permissions rejected");
                case IDENTITY_CHANGED -> throw new IOException("protected discovery record identity rejected");
                case GROWS_ABOVE_LIMIT -> throw new IOException("protected discovery record rejected");
                case UNAVAILABLE_STABLE_IDENTITY -> throw new NuggetAdminFileSecurityUnavailableException(
                        "protected discovery file identity unavailable");
            }
            return this.readBoundedUtf8(path, maxBytes);
        }

        @Override
        public void persistVerifierRecord(final Path path, final byte[] bytes) throws IOException {
            this.persistCalls++;
            this.temporaryFilePresent = true;
            this.partialTemporaryContentCreated = true;
            this.persistenceEntered.countDown();
            if (this.blockPersistence) {
                try {
                    assertTrue(this.releasePersistence.await(5L, TimeUnit.SECONDS), "persistence was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for persistence release", e);
                }
            }
            try {
                switch (this.persistFailure) {
                    case NONE -> this.records.put(path, new String(bytes, StandardCharsets.UTF_8));
                    case ATOMIC_REPLACEMENT_UNAVAILABLE -> throw new UnsupportedOperationException("atomic move unavailable");
                    case WRITE_FAILURE -> throw new IOException("write failed");
                    case FORCE_FAILURE -> throw new IOException("force failed");
                    case REVALIDATION_FAILURE -> throw new IOException("revalidation failed");
                    case MOVE_FAILURE -> throw new IOException("move failed");
                }
            } finally {
                this.temporaryFilePresent = false;
            }
        }

        @Override
        public void persistDiscoveryRecord(final Path directory, final String fileName, final byte[] bytes) throws IOException {
            this.ensureProtectedDirectory(directory);
            this.discoveryPersistCalls++;
            this.discoveryPersistenceEntered.countDown();
            if (this.blockDiscoveryPersistence) {
                try {
                    assertTrue(this.releaseDiscoveryPersistence.await(5L, TimeUnit.SECONDS),
                            "discovery persistence was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for discovery persistence release", e);
                }
            }
            if (this.discoveryPersistFailure) {
                throw new IllegalStateException("discovery persistence failed");
            }
            this.records.put(directory.resolve(fileName), new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void removeDiscoveryRecord(final Path path) throws IOException {
            this.discoveryRemoveCalls++;
            if (this.discoveryRemoveFailure) {
                throw new IOException("discovery removal failed");
            }
            this.records.remove(path);
        }

        @Override
        public void cleanupDiscoveryTemps(final Path directory, final int maxDeletes) {
            this.lastCleanupMaxDeletes = maxDeletes;
            List<Path> examined = this.records.keySet().stream()
                    .filter(path -> directory.equals(path.getParent()))
                    .sorted()
                    .limit(Math.max(0, maxDeletes))
                    .toList();
            List<Path> candidates = examined.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .toList();
            for (Path candidate : candidates) {
                if (this.records.remove(candidate) != null) {
                    this.cleanupDeletes++;
                }
            }
        }

        private Path onlyDiscoveryRecord() {
            List<Path> discoveryRecords = this.records.keySet().stream()
                    .filter(path -> path.getFileName().toString().endsWith(".j7m-discovery"))
                    .toList();
            assertEquals(1, discoveryRecords.size());
            return discoveryRecords.getFirst();
        }

        private int discoveryRecordCount() {
            return (int) this.records.keySet().stream()
                    .filter(path -> path.getFileName().toString().endsWith(".j7m-discovery"))
                    .count();
        }
    }

    private static final class RecordingResourceFactory implements NuggetAdminResourceFactory {
        private final AtomicInteger providerCreations = new AtomicInteger();
        private final AtomicInteger providerShutdowns = new AtomicInteger();
        private final AtomicInteger discoveryCreations = new AtomicInteger();
        private final AtomicInteger discoveryShutdowns = new AtomicInteger();
        private final AtomicInteger discoveryScheduleCalls = new AtomicInteger();
        private final AtomicInteger discoveryCancelCalls = new AtomicInteger();
        private boolean failDiscoverySchedule;
        private boolean failDiscoveryCancel;

        @Override
        public ExecutorService newProviderExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            this.providerCreations.incrementAndGet();
            return new RecordingProviderExecutor(this.providerShutdowns);
        }

        @Override
        public java.util.concurrent.ScheduledExecutorService newDiscoveryExecutor(final String nuggetId) {
            this.discoveryCreations.incrementAndGet();
            return new RecordingDiscoveryExecutor(
                    this.discoveryShutdowns,
                    this.discoveryScheduleCalls,
                    this.discoveryCancelCalls,
                    this.failDiscoverySchedule,
                    this.failDiscoveryCancel);
        }
    }

    private static final class RecordingProviderExecutor extends ThreadPoolExecutor {
        private final AtomicInteger shutdowns;

        private RecordingProviderExecutor(final AtomicInteger shutdowns) {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            this.shutdowns = shutdowns;
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.shutdowns.incrementAndGet();
            return super.shutdownNow();
        }
    }

    private static final class RecordingDiscoveryExecutor extends ScheduledThreadPoolExecutor {
        private final AtomicInteger shutdowns;
        private final AtomicInteger scheduleCalls;
        private final AtomicInteger cancelCalls;
        private final boolean failSchedule;
        private final boolean failCancel;

        private RecordingDiscoveryExecutor(
                final AtomicInteger shutdowns,
                final AtomicInteger scheduleCalls,
                final AtomicInteger cancelCalls,
                final boolean failSchedule,
                final boolean failCancel) {
            super(1);
            this.shutdowns = shutdowns;
            this.scheduleCalls = scheduleCalls;
            this.cancelCalls = cancelCalls;
            this.failSchedule = failSchedule;
            this.failCancel = failCancel;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                final Runnable command,
                final long initialDelay,
                final long delay,
                final TimeUnit unit) {
            this.scheduleCalls.incrementAndGet();
            if (this.failSchedule) {
                throw new IllegalStateException("discovery schedule failed");
            }
            if (this.failCancel) {
                return new ThrowingCancelFuture(this.cancelCalls);
            }
            return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.shutdowns.incrementAndGet();
            return super.shutdownNow();
        }
    }

    private static final class ThrowingCancelFuture implements ScheduledFuture<Object> {
        private final AtomicInteger cancelCalls;

        private ThrowingCancelFuture(final AtomicInteger cancelCalls) {
            this.cancelCalls = cancelCalls;
        }

        @Override
        public long getDelay(final TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(final Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            this.cancelCalls.incrementAndGet();
            throw new IllegalStateException("scheduled future cancel failed");
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(final long timeout, final TimeUnit unit) {
            return null;
        }
    }

    private static final class BlockingVerifyHooks implements NuggetAdminLifecycleHooks {
        private final CountDownLatch verificationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseVerification = new CountDownLatch(1);
        private final CountDownLatch closeStarted = new CountDownLatch(1);

        @Override
        public void beforePinVerification() {
            this.verificationEntered.countDown();
            try {
                assertTrue(this.releaseVerification.await(5L, TimeUnit.SECONDS), "verification was not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
