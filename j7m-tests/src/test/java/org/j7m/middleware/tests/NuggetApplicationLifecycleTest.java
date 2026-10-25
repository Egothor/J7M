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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ActivationIdentity;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.deployer.api.ActivationOptions;
import org.j7m.middleware.deployer.api.ApplicationDeployer;
import org.j7m.middleware.deployer.api.DeactivationOptions;
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.PackageRequest;
import org.j7m.middleware.deployer.api.RollbackOptions;
import org.j7m.middleware.deployer.api.UnloadOptions;
import org.j7m.middleware.deployer.api.UpgradeOptions;
import org.j7m.middleware.deployer.api.VerificationOptions;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetStatus;
import org.j7m.middleware.runtime.NuggetAdminRuntime;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.junit.jupiter.api.Test;

/**
 * Focused Phase 7.6 tests for nugget application lifecycle operations.
 */
public final class NuggetApplicationLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-07-16T10:00:00Z");
    private static final char[] PIN = new char[] {'7', '6', '0', '0', '0', '1'};
    private static final ApplicationCoordinate COORDINATE =
            ApplicationCoordinate.parse("org.j7m.tests:lifecycle:1.0.0");
    private static final ApplicationInstanceId INSTANCE_ID = new ApplicationInstanceId("instance-phase76");
    private static final List<String> CANONICAL_RETURNED_FAILURE_METADATA_KEYS = List.of(
            "action",
            "previousState",
            "currentState",
            "resultingState",
            "reason",
            "result",
            "detailsOmitted");

    @Test
    public void defaultLifecycleMethodReturnsUnavailableForOlderImplementers() {
        DefaultLifecycleOnlyService service = new DefaultLifecycleOnlyService();

        NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest("default-admission", false));

        assertEquals(NuggetApplicationLifecycle.Outcome.UNAVAILABLE, result.outcome());
        assertFalse(result.accepted());
        assertFalse(result.authorized());
        assertFalse(result.inProgress());
        assertTrue(result.completed());
        assertFalse(result.outcomeUnknown());
        assertEquals(MiddlewareErrorCode.UNAVAILABLE, result.error().code());
        assertEquals("phase76-start", result.correlationId());
        assertEquals(COORDINATE, result.coordinate());
        assertEquals(0, service.preexistingMethodCalls.get());
    }

    @Test
    public void unavailableProviderReturnsTruthfulLifecycleResult() {
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        RecordingPolicy policy = new RecordingPolicy(false);
        try (NuggetAdminService service = start(audit, policy, null, false, true)) {
            String admission = admit(service);

            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admission, false));

            assertEquals(NuggetApplicationLifecycle.Outcome.UNAVAILABLE, result.outcome());
            assertFalse(result.accepted());
            assertEquals(0, policy.actions.size());
            assertTrue(audit.recentEvents().toString().contains("provider-unavailable"));
            assertNoSensitiveLifecycleAudit(audit, admission);
        }
    }

    @Test
    public void lifecycleGatesRejectReadOnlyAndDisabledStartBeforeMutation() {
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        RecordingPolicy policy = new RecordingPolicy(false);
        RecordingDeployer readOnlyDeployer = new RecordingDeployer();
        try (NuggetAdminService service = start(audit, policy, readOnlyDeployer, true, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admit(service), false));

            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
            assertEquals(0, readOnlyDeployer.totalMutations());
            assertEquals(0, policy.actions.size());
        }

        RecordingDeployer startDisabledDeployer = new RecordingDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, startDisabledDeployer, false, false)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admit(service), false));

            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
            assertEquals(0, startDisabledDeployer.totalMutations());
        }
    }

    @Test
    public void expiredDeadlineRejectsBeforeAuthorizationAndProviderWork() {
        RecordingPolicy policy = new RecordingPolicy(false);
        RecordingDeployer deployer = new RecordingDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
            String admission = admit(service);
            NuggetApplicationLifecycle.Request expired = new NuggetApplicationLifecycle.Request(
                    admission,
                    SecurityContext.system("phase76-caller"),
                    "phase76-expired",
                    NuggetApplicationLifecycle.Action.START,
                    COORDINATE,
                    null,
                    false,
                    NOW,
                    NOW,
                    Map.of());

            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(expired);

            assertEquals(NuggetApplicationLifecycle.Outcome.DEADLINE_EXCEEDED, result.outcome());
            assertEquals(0, policy.actions.size());
            assertEquals(0, deployer.totalMutations());
        }
    }

    @Test
    public void dryRunStartPlansWithoutActivation() {
        RecordingPolicy policy = new RecordingPolicy(false);
        RecordingDeployer deployer = new RecordingDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admit(service), true));

            assertEquals(NuggetApplicationLifecycle.Outcome.DRY_RUN, result.outcome());
            assertTrue(result.accepted());
            assertEquals(1, deployer.planCalls);
            assertEquals(0, deployer.activateCalls);
            assertEquals(PermissionAction.LOAD, policy.actions.getFirst());
        }
    }

    @Test
    public void authorizationActionsAndInstanceLifecycleDelegateThroughProvider() {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        RecordingPolicy policy = new RecordingPolicy(false);
        RecordingDeployer deployer = new RecordingDeployer();
        try (NuggetAdminService service = start(audit, policy, deployer, false, true)) {
            String admission = admit(service);

            NuggetApplicationLifecycle.Result start = service.applicationLifecycle(startRequest(admission, false));
            NuggetApplicationLifecycle.Result drain = service.applicationLifecycle(instanceRequest(
                    admission, NuggetApplicationLifecycle.Action.DRAIN, "phase76-drain"));
            NuggetApplicationLifecycle.Result stop = service.applicationLifecycle(instanceRequest(
                    admission, NuggetApplicationLifecycle.Action.STOP, "phase76-stop"));
            NuggetApplicationLifecycle.Result unload = service.applicationLifecycle(instanceRequest(
                    admission, NuggetApplicationLifecycle.Action.UNLOAD, "phase76-unload"));

            assertEquals(NuggetApplicationLifecycle.Outcome.COMPLETED, start.outcome());
            assertEquals(NuggetApplicationLifecycle.Outcome.COMPLETED, drain.outcome());
            assertEquals(ApplicationRunState.RUNNING, drain.previousState());
            assertEquals(ApplicationRunState.DRAINING, drain.currentState());
            assertEquals(NuggetApplicationLifecycle.Outcome.COMPLETED, stop.outcome());
            assertEquals(ApplicationRunState.STOPPED, stop.currentState());
            assertEquals(NuggetApplicationLifecycle.Outcome.COMPLETED, unload.outcome());
            assertEquals(ApplicationRunState.UNLOADED, unload.currentState());
            assertEquals(List.of(PermissionAction.LOAD, PermissionAction.ADMIN, PermissionAction.ADMIN,
                    PermissionAction.UNLOAD), policy.actions);
            assertEquals(1, deployer.activateCalls);
            assertEquals(1, deployer.drainCalls);
            assertEquals(1, deployer.stopCalls);
            assertEquals(1, deployer.unloadCalls);
            assertNoSensitiveLifecycleAudit(audit, admission);
        }
    }

    @Test
    public void authorizationDenialDoesNotInvokeProvider() {
        RecordingPolicy policy = new RecordingPolicy(true);
        RecordingDeployer deployer = new RecordingDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admit(service), false));

            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
            assertFalse(result.authorized());
            assertEquals(0, deployer.totalMutations());
            assertEquals(List.of(PermissionAction.LOAD), policy.actions);
        }
    }

    @Test
    public void missingInstancePreservesProviderNotFoundCode() {
        RecordingPolicy policy = new RecordingPolicy(false);
        MissingInstanceDeployer deployer = new MissingInstanceDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(
                    instanceRequest(admit(service), NuggetApplicationLifecycle.Action.DRAIN, "phase76-missing"));

            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
            assertEquals(MiddlewareErrorCode.NOT_FOUND, result.error().code());
            assertEquals("not-found", result.error().details().get("reason"));
            assertFalse(result.outcomeText().contains("java."));
        }
    }

    @Test
    public void invalidUnloadStatePreservesProviderInvalidArgumentCode() {
        RecordingPolicy policy = new RecordingPolicy(false);
        for (ApplicationRunState state : List.of(ApplicationRunState.RUNNING, ApplicationRunState.DRAINING)) {
            RejectingUnloadDeployer deployer = new RejectingUnloadDeployer(state);
            try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
                NuggetApplicationLifecycle.Result result = service.applicationLifecycle(
                        instanceRequest(admit(service), NuggetApplicationLifecycle.Action.UNLOAD,
                                "phase76-invalid-unload-" + state.name()));

                assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, result.error().code());
                assertEquals("invalid-lifecycle-state", result.error().details().get("reason"));
                assertEquals(state, result.previousState());
                assertFalse(result.outcomeText().contains("Exception"));
            }
        }
    }

    @Test
    public void activationVerificationFailurePreservesProviderCodeAndDetails() {
        RecordingPolicy policy = new RecordingPolicy(false);
        ActivationVerificationFailureDeployer deployer = new ActivationVerificationFailureDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admit(service), false));

            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, result.error().code());
            assertEquals("verification-failed", result.error().details().get("reason"));
            assertEquals("strict", result.error().details().get("profile"));
            assertFalse(result.outcomeText().contains("Exception"));
        }
    }

    @Test
    public void maliciousLifecycleProviderFailureUsesPublicProjection() {
        RecordingPolicy policy = new RecordingPolicy(false);
        MaliciousFailureDeployer deployer = new MaliciousFailureDeployer();
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        try (NuggetAdminService service = start(audit, policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admit(service), false));

            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, result.error().code());
            assertEquals("application lifecycle operation failed", result.error().message());
            assertEquals("verification-failed", result.error().details().get("reason"));
            assertEquals("strict", result.error().details().get("profile"));
            assertEquals("true", result.error().details().get("detailsOmitted"));
            assertEquals("verification-failed", result.metadata().get("reason"));

            String publicText = result.toString() + result.error().details() + result.metadata() + result.warnings();
            assertNoLifecycleSentinels(publicText);
            assertFalse(result.error().details().containsKey("metadata"));

            AuditEvent event = audit.recentEvents().stream()
                    .filter(candidate -> "nugget.application.lifecycle".equals(candidate.action()))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            assertEquals("verification-failed", event.reason());
            assertEquals("verification-failed", event.metadata().get("reason"));
            assertNoLifecycleSentinels(event.toString());
        }
    }

    @Test
    public void returnedDeploymentFailureUsesPublicProjectionForInstanceLifecycle() {
        RecordingPolicy policy = new RecordingPolicy(false);
        for (NuggetApplicationLifecycle.Action action : List.of(
                NuggetApplicationLifecycle.Action.DRAIN,
                NuggetApplicationLifecycle.Action.STOP,
                NuggetApplicationLifecycle.Action.UNLOAD)) {
            InMemoryAuditSink audit = new InMemoryAuditSink(64);
            ReturnedFailureDeployer deployer = new ReturnedFailureDeployer();
            try (NuggetAdminService service = start(audit, policy, deployer, false, true)) {
                NuggetApplicationLifecycle.Result result = service.applicationLifecycle(
                        instanceRequest(admit(service), action, "phase76-returned-failure-" + action.name()));

                assertEquals(NuggetApplicationLifecycle.Outcome.FAILED, result.outcome());
                assertEquals(ApplicationRunState.FAILED, result.currentState());
                assertEquals(ApplicationRunState.FAILED, result.resultingState());
                assertFalse(result.accepted());
                assertTrue(result.completed());
                assertTrue(result.outcomeUnknown());
                assertEquals("application lifecycle operation was not completed", result.outcomeText());
                assertEquals(MiddlewareErrorCode.UNAVAILABLE, result.error().code());
                assertEquals("application lifecycle operation was not completed", result.error().message());
                assertEquals("deployer-result-failed", result.error().details().get("reason"));
                assertEquals(action.name(), result.error().details().get("action"));
                assertEquals("FAILED", result.error().details().get("currentState"));
                assertEquals("FAILED", result.error().details().get("resultingState"));
                assertEquals("true", result.error().details().get("detailsOmitted"));
                assertEquals(CANONICAL_RETURNED_FAILURE_METADATA_KEYS,
                        new ArrayList<>(result.error().details().keySet()));
                assertEquals("deployer-result-failed", result.metadata().get("reason"));
                assertEquals("FAILED", result.metadata().get("currentState"));
                assertEquals(CANONICAL_RETURNED_FAILURE_METADATA_KEYS, new ArrayList<>(result.metadata().keySet()));
                assertThrows(UnsupportedOperationException.class, () -> result.metadata().put("mutation", "rejected"));
                assertTrue(result.warnings().isEmpty());
                assertFalse(result.error().details().containsKey("message"));

                ApplicationDeploymentResult publicDeploymentResult = result.deploymentResult();
                assertFalse(publicDeploymentResult.success());
                assertEquals(ApplicationRunState.FAILED, publicDeploymentResult.state());
                assertEquals("application lifecycle operation was not completed", publicDeploymentResult.message());
                assertEquals(List.of("deployer-result-failed"), publicDeploymentResult.details());

                String publicText = result.toString() + result.outcomeText() + publicDeploymentResult
                        + result.error().message() + result.error().details() + result.metadata() + result.warnings();
                assertNoLifecycleSentinels(publicText);

                AuditEvent event = audit.recentEvents().stream()
                        .filter(candidate -> "nugget.application.lifecycle".equals(candidate.action()))
                        .reduce((first, second) -> second)
                        .orElseThrow();
                assertEquals("deployer-result-failed", event.reason());
                assertEquals("FAILED", event.metadata().get("currentState"));
                List<String> eventProjectionKeys = new ArrayList<>();
                for (String key : event.metadata().keySet()) {
                    if (CANONICAL_RETURNED_FAILURE_METADATA_KEYS.contains(key)) {
                        eventProjectionKeys.add(key);
                    }
                }
                assertEquals(CANONICAL_RETURNED_FAILURE_METADATA_KEYS, eventProjectionKeys);
                assertNoLifecycleSentinels(event.toString());
            }
        }
    }

    @Test
    public void returnedDeploymentFailureDoesNotInspectBlankProviderMessageForOmissionFlag() {
        RecordingPolicy policy = new RecordingPolicy(false);
        BlankReturnedFailureDeployer deployer = new BlankReturnedFailureDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(
                    instanceRequest(admit(service), NuggetApplicationLifecycle.Action.STOP,
                            "phase76-returned-blank-failure"));

            assertEquals(NuggetApplicationLifecycle.Outcome.FAILED, result.outcome());
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, result.error().code());
            assertEquals("deployer-result-failed", result.error().details().get("reason"));
            assertEquals("true", result.error().details().get("detailsOmitted"));
            assertEquals("true", result.metadata().get("detailsOmitted"));
            assertEquals("application lifecycle operation was not completed", result.outcomeText());
            assertEquals("application lifecycle operation was not completed", result.deploymentResult().message());
            assertEquals(List.of("deployer-result-failed"), result.deploymentResult().details());
            assertFalse(result.toString().contains(" ".repeat(128)));
        }
    }

    @Test
    public void allowlistedLifecycleProviderValuesCannotCarrySecrets() {
        RecordingPolicy policy = new RecordingPolicy(false);
        UnsafeAllowlistedValueDeployer deployer = new UnsafeAllowlistedValueDeployer();
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        try (NuggetAdminService service = start(audit, policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(startRequest(admit(service), false));

            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, result.outcome());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, result.error().code());
            assertEquals("permission-or-verification-denied", result.error().details().get("reason"));
            assertEquals("true", result.error().details().get("detailsOmitted"));
            assertFalse(result.error().details().containsKey("profile"));
            assertFalse(result.error().details().containsKey("phase"));
            assertFalse(result.error().details().containsKey("state"));
            assertFalse(result.error().details().containsKey("policyCategory"));
            assertFalse(result.error().details().containsKey("verificationCategory"));
            assertFalse(result.error().details().containsKey("action"));
            assertFalse(result.error().details().containsKey("retryable"));

            String publicText = result.toString() + result.error().details() + result.metadata() + result.warnings();
            assertNoLifecycleSentinels(publicText);
            assertFalse(publicText.contains("secret-token"));
            assertFalse(publicText.contains("IllegalStateException"));
            assertFalse(publicText.contains("PRIVATE-KEY"));
            assertFalse(publicText.contains("password"));

            AuditEvent event = audit.recentEvents().stream()
                    .filter(candidate -> "nugget.application.lifecycle".equals(candidate.action()))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            assertNoLifecycleSentinels(event.toString());
            assertFalse(event.toString().contains("secret-token"));
            assertFalse(event.toString().contains("IllegalStateException"));
        }
    }

    @Test
    public void lifecycleExecutorCancelsDeadlineAndRejectsOverload() throws Exception {
        RecordingPolicy policy = new RecordingPolicy(false);
        BlockingPlanDeployer deployer = new BlockingPlanDeployer(false);
        try (NuggetAdminService service = start(
                new InMemoryAuditSink(64), policy, deployer, false, true,
                1, 0, Duration.ofMillis(100L), Duration.ofMillis(100L))) {
            String admission = admit(service);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<NuggetApplicationLifecycle.Result> first = executor.submit(
                        () -> service.applicationLifecycle(startRequest(admission, false)));
                assertTrue(deployer.entered.await(5L, TimeUnit.SECONDS), "first lifecycle task did not enter provider");

                NuggetApplicationLifecycle.Result overloaded = service.applicationLifecycle(startRequest(admission, false));

                assertEquals(NuggetApplicationLifecycle.Outcome.UNAVAILABLE, overloaded.outcome());
                assertEquals(MiddlewareErrorCode.OVERLOADED, overloaded.error().code());
                NuggetApplicationLifecycle.Result timedOut = first.get(5L, TimeUnit.SECONDS);
                assertEquals(NuggetApplicationLifecycle.Outcome.DEADLINE_EXCEEDED, timedOut.outcome());
                assertTrue(timedOut.outcomeUnknown());
                assertTrue(deployer.interruptions.get() > 0);
                deployer.release.countDown();
            }
        }
    }

    @Test
    public void closeInterruptsOwnedLifecycleWork() throws Exception {
        RecordingPolicy policy = new RecordingPolicy(false);
        BlockingPlanDeployer deployer = new BlockingPlanDeployer(true);
        NuggetAdminService service = start(new InMemoryAuditSink(64), policy, deployer, false, true,
                1, 0, Duration.ofSeconds(5L), Duration.ofMillis(500L));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String admission = admit(service);
            Future<NuggetApplicationLifecycle.Result> running = executor.submit(
                    () -> service.applicationLifecycle(startRequest(admission, false)));
            assertTrue(deployer.entered.await(5L, TimeUnit.SECONDS), "lifecycle task did not enter provider");

            service.close();

            assertTrue(deployer.interrupted.await(5L, TimeUnit.SECONDS), "close did not interrupt lifecycle work");
            running.get(5L, TimeUnit.SECONDS);
        } finally {
            service.close();
        }
    }

    @Test
    public void instanceLifecycleUsesExactLookupWithoutFullActiveApplicationScan() {
        RecordingPolicy policy = new RecordingPolicy(false);
        ExactLookupDeployer deployer = new ExactLookupDeployer();
        try (NuggetAdminService service = start(new InMemoryAuditSink(32), policy, deployer, false, true)) {
            NuggetApplicationLifecycle.Result result = service.applicationLifecycle(
                    instanceRequest(admit(service), NuggetApplicationLifecycle.Action.DRAIN, "phase76-exact-drain"));

            assertEquals(NuggetApplicationLifecycle.Outcome.COMPLETED, result.outcome());
            assertEquals(ApplicationRunState.RUNNING, result.previousState());
            assertEquals(ApplicationRunState.DRAINING, result.currentState());
            assertEquals(1, deployer.exactLookupCalls.get());
            assertEquals(0, deployer.listCalls.get());
        }
    }

    private static NuggetAdminService start(
            final InMemoryAuditSink audit,
            final RecordingPolicy policy,
            final ApplicationDeployer deployer,
            final boolean readOnly,
            final boolean startApplicationEnabled) {
        return start(audit, policy, deployer, readOnly, startApplicationEnabled,
                2, 8, Duration.ofSeconds(2L), Duration.ofSeconds(2L));
    }

    private static NuggetAdminService start(
            final InMemoryAuditSink audit,
            final RecordingPolicy policy,
            final ApplicationDeployer deployer,
            final boolean readOnly,
            final boolean startApplicationEnabled,
            final int concurrency,
            final int queueCapacity,
            final Duration timeout,
            final Duration shutdownWait) {
        NuggetAdminRuntimeConfiguration.Builder builder = NuggetAdminRuntimeConfiguration.builder()
                .security(security(audit, policy))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .nuggetName("phase76-test-nugget")
                .pinFromProgrammaticProvider("phase76-test-provider", NuggetApplicationLifecycleTest::copyPin)
                .readOnly(readOnly)
                .startApplicationEnabled(startApplicationEnabled)
                .providerExecutionPolicy(concurrency, queueCapacity, timeout, shutdownWait);
        if (deployer != null) {
            builder.applicationDeployer(deployer);
        }
        return NuggetAdminRuntime.start(new RuntimeIdentity("phase76-runtime", "default", Map.of()), builder.build());
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit, final AuthorizationPolicy policy) {
        return new SecurityConfiguration(
                SecurityMode.DEV,
                policy,
                audit,
                new DefaultRedactor(),
                false,
                true,
                1024 * 1024,
                1024,
                Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "phase76", Map.of())));
    }

    private static String admit(final NuggetAdminService service) {
        return service.authenticate(NuggetAuthenticationRequest.of(
                "phase76-auth",
                NuggetAuthenticationSource.LOCAL,
                "phase76-local",
                true,
                NOW), copyPin()).admissionId();
    }

    private static NuggetApplicationLifecycle.Request startRequest(final String admission, final boolean dryRun) {
        return new NuggetApplicationLifecycle.Request(
                admission,
                SecurityContext.system("phase76-caller"),
                dryRun ? "phase76-start-dry-run" : "phase76-start",
                NuggetApplicationLifecycle.Action.START,
                COORDINATE,
                null,
                dryRun,
                NOW,
                NOW.plusSeconds(10L),
                Map.of("pin", "SECRET-SENTINEL", "verificationProfile", "development"));
    }

    private static NuggetApplicationLifecycle.Request instanceRequest(
            final String admission,
            final NuggetApplicationLifecycle.Action action,
            final String correlationId) {
        return new NuggetApplicationLifecycle.Request(
                admission,
                SecurityContext.system("phase76-caller"),
                correlationId,
                action,
                null,
                INSTANCE_ID,
                false,
                NOW,
                NOW.plusSeconds(10L),
                Map.of());
    }

    private static char[] copyPin() {
        char[] copy = new char[PIN.length];
        System.arraycopy(PIN, 0, copy, 0, PIN.length);
        return copy;
    }

    private static void assertNoSensitiveLifecycleAudit(final InMemoryAuditSink audit, final String admission) {
        String auditText = audit.recentEvents().toString();
        assertFalse(auditText.contains(new String(PIN)));
        assertFalse(auditText.contains(admission));
        assertFalse(auditText.contains("SECRET-SENTINEL"));
    }

    private static void assertNoLifecycleSentinels(final String text) {
        assertFalse(text.contains("opaque-secret-sentinel"));
        assertFalse(text.contains("password-sentinel"));
        assertFalse(text.contains("pin-sentinel"));
        assertFalse(text.contains("admission-sentinel"));
        assertFalse(text.contains("credential-sentinel"));
        assertFalse(text.contains("/secret/repository/key.pem"));
        assertFalse(text.contains("manifest-sentinel"));
        assertFalse(text.contains("certificate-sentinel"));
        assertFalse(text.contains("signature-sentinel"));
        assertFalse(text.contains("private-key-sentinel"));
        assertFalse(text.contains("stack-sentinel"));
        assertFalse(text.contains("exception-sentinel"));
        assertFalse(text.contains("cause-sentinel"));
        assertFalse(text.contains("payload-sentinel"));
        assertFalse(text.contains("configuration-sentinel"));
        assertFalse(text.contains("provider raw message sentinel"));
        assertFalse(text.contains("returned raw detail sentinel"));
        assertFalse(text.contains("IllegalStateException"));
        assertFalse(text.contains("PRIVATE-KEY"));
    }

    private static final class RecordingPolicy implements AuthorizationPolicy {
        private final boolean deny;
        private final List<PermissionAction> actions = new ArrayList<>();

        private RecordingPolicy(final boolean deny) {
            this.deny = deny;
        }

        @Override
        public String id() {
            return "phase76-recording-policy";
        }

        @Override
        public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
            this.actions.add(request.action());
            if (this.deny) {
                return AuthorizationDecision.deny(id(), "denied-for-test");
            }
            return AuthorizationDecision.allow(id());
        }
    }

    private static class RecordingDeployer implements ApplicationDeployer {
        private ApplicationActivation current = activation(ApplicationRunState.RUNNING);
        private int planCalls;
        private int activateCalls;
        private int drainCalls;
        private int stopCalls;
        private int unloadCalls;

        @Override
        public ApplicationBundle packageApplication(final PackageRequest request) {
            throw new UnsupportedOperationException("package is not used by this test");
        }

        @Override
        public ApplicationVerificationResult verify(final ApplicationBundle bundle, final VerificationOptions options) {
            throw new UnsupportedOperationException("verify is not used by this test");
        }

        @Override
        public ApplicationDeploymentResult deploy(final ApplicationBundle bundle, final DeployOptions options) {
            throw new UnsupportedOperationException("deploy is not used by this test");
        }

        @Override
        public ApplicationDeploymentPlan planActivation(final ApplicationCoordinate coordinate, final ActivationOptions options) {
            this.planCalls++;
            return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.PLANNED, List.of("verify", "start"));
        }

        @Override
        public ApplicationActivation activate(
                final ApplicationCoordinate coordinate,
                final ApplicationActivationRequest request) {
            this.activateCalls++;
            this.current = activation(ApplicationRunState.RUNNING);
            return this.current;
        }

        @Override
        public ApplicationDeploymentResult drain(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
            this.drainCalls++;
            this.current = activation(ApplicationRunState.DRAINING);
            return new ApplicationDeploymentResult(true, ApplicationRunState.DRAINING, "drained", List.of());
        }

        @Override
        public ApplicationDeploymentResult deactivate(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
            this.stopCalls++;
            this.current = activation(ApplicationRunState.STOPPED);
            return new ApplicationDeploymentResult(true, ApplicationRunState.STOPPED, "stopped", List.of());
        }

        @Override
        public ApplicationDeploymentResult unload(final ApplicationInstanceId instanceId, final UnloadOptions options) {
            this.unloadCalls++;
            this.current = activation(ApplicationRunState.UNLOADED);
            return new ApplicationDeploymentResult(true, ApplicationRunState.UNLOADED, "unloaded", List.of());
        }

        @Override
        public ApplicationDeploymentResult upgrade(
                final ApplicationInstanceId instanceId,
                final ApplicationCoordinate targetVersion,
                final UpgradeOptions options) {
            throw new UnsupportedOperationException("upgrade is not used by this test");
        }

        @Override
        public ApplicationDeploymentResult rollback(final ApplicationInstanceId instanceId, final RollbackOptions options) {
            throw new UnsupportedOperationException("rollback is not used by this test");
        }

        @Override
        public ApplicationActivation activeApplication(final ApplicationInstanceId instanceId) {
            return INSTANCE_ID.equals(instanceId) ? this.current : null;
        }

        @Override
        public List<ApplicationActivation> listActiveApplications(final ApplicationQuery query) {
            return List.of(this.current);
        }

        private int totalMutations() {
            return this.activateCalls + this.drainCalls + this.stopCalls + this.unloadCalls;
        }

        protected static ApplicationActivation activation(final ApplicationRunState state) {
            ApplicationIdentity application = new ApplicationIdentity(COORDINATE, "Lifecycle", "default");
            ApplicationInstanceIdentity instance = new ApplicationInstanceIdentity(
                    INSTANCE_ID,
                    application,
                    new RuntimeIdentity("phase76-runtime", "default", Map.of()),
                    "CLASSPATH");
            return new ApplicationActivation(
                    new ActivationIdentity("activation-phase76"),
                    instance,
                    state,
                    List.of(),
                    Map.of());
        }
    }

    private static final class BlockingPlanDeployer extends RecordingDeployer {
        private final boolean finishOnInterrupt;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final AtomicInteger interruptions = new AtomicInteger();

        private BlockingPlanDeployer(final boolean finishOnInterrupt) {
            this.finishOnInterrupt = finishOnInterrupt;
        }

        @Override
        public ApplicationDeploymentPlan planActivation(final ApplicationCoordinate coordinate, final ActivationOptions options) {
            this.entered.countDown();
            while (true) {
                try {
                    this.release.await();
                    return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.PLANNED, List.of("released"));
                } catch (InterruptedException exception) {
                    this.interruptions.incrementAndGet();
                    this.interrupted.countDown();
                    if (this.finishOnInterrupt) {
                        Thread.currentThread().interrupt();
                        return new ApplicationDeploymentPlan(coordinate, ApplicationRunState.REJECTED, List.of("interrupted"));
                    }
                }
            }
        }
    }

    private static final class ExactLookupDeployer extends RecordingDeployer {
        private final AtomicInteger exactLookupCalls = new AtomicInteger();
        private final AtomicInteger listCalls = new AtomicInteger();

        @Override
        public ApplicationActivation activeApplication(final ApplicationInstanceId instanceId) {
            this.exactLookupCalls.incrementAndGet();
            return super.activeApplication(instanceId);
        }

        @Override
        public List<ApplicationActivation> listActiveApplications(final ApplicationQuery query) {
            this.listCalls.incrementAndGet();
            return super.listActiveApplications(query);
        }
    }

    private static final class MissingInstanceDeployer extends RecordingDeployer {
        @Override
        public ApplicationActivation activeApplication(final ApplicationInstanceId instanceId) {
            return null;
        }

        @Override
        public ApplicationDeploymentResult drain(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application instance not found");
        }
    }

    private static final class RejectingUnloadDeployer extends RecordingDeployer {
        private final ApplicationRunState state;

        private RejectingUnloadDeployer(final ApplicationRunState state) {
            this.state = state;
        }

        @Override
        public ApplicationActivation activeApplication(final ApplicationInstanceId instanceId) {
            return INSTANCE_ID.equals(instanceId) ? activation(this.state) : null;
        }

        @Override
        public ApplicationDeploymentResult unload(final ApplicationInstanceId instanceId, final UnloadOptions options) {
            throw MiddlewareException.of(
                    MiddlewareErrorCode.INVALID_ARGUMENT,
                    "application instance must be stopped or failed before unload");
        }
    }

    private static final class ActivationVerificationFailureDeployer extends RecordingDeployer {
        @Override
        public ApplicationActivation activate(
                final ApplicationCoordinate coordinate,
                final ApplicationActivationRequest request) {
            throw new MiddlewareException(new RemoteError(
                    MiddlewareErrorCode.PERMISSION_DENIED,
                    "application verification failed",
                    Map.of("reason", "verification-failed", "profile", "strict"),
                    "phase76-activation",
                    "phase76-runtime",
                    false,
                    false));
        }
    }

    private static final class MaliciousFailureDeployer extends RecordingDeployer {
        @Override
        public ApplicationActivation activate(
                final ApplicationCoordinate coordinate,
                final ApplicationActivationRequest request) {
            Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put("reason", "verification-failed");
            details.put("profile", "strict");
            details.put("token", "opaque-secret-sentinel");
            details.put("password", "password-sentinel");
            details.put("pin", "pin-sentinel");
            details.put("admissionId", "admission-sentinel");
            details.put("credential", "credential-sentinel");
            details.put("path", "/secret/repository/key.pem");
            details.put("repositoryPath", "/secret/repository/key.pem");
            details.put("manifest", "manifest-sentinel");
            details.put("certificate", "certificate-sentinel");
            details.put("signature", "signature-sentinel");
            details.put("privateKey", "private-key-sentinel");
            details.put("stackTrace", "stack-sentinel");
            details.put("exception", "exception-sentinel");
            details.put("cause", "cause-sentinel");
            details.put("payload", "payload-sentinel");
            details.put("configuration", "configuration-sentinel");
            details.put("unknown", "opaque-secret-sentinel");
            details.put("phase", "verify\u001b[31m");
            throw new MiddlewareException(new RemoteError(
                    MiddlewareErrorCode.PERMISSION_DENIED,
                    "provider raw message sentinel /secret/repository/key.pem",
                    details,
                    "phase76-malicious",
                    "phase76-runtime",
                    false,
                    false));
        }
    }

    private static final class UnsafeAllowlistedValueDeployer extends RecordingDeployer {
        @Override
        public ApplicationActivation activate(
                final ApplicationCoordinate coordinate,
                final ApplicationActivationRequest request) {
            Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put("reason", "Bearer abc-token");
            details.put("profile", "secret-token");
            details.put("phase", "/secret/repository/key.pem");
            details.put("state", "java.lang.IllegalStateException");
            details.put("policyCategory", "stackTrace:line");
            details.put("verificationCategory", "-----BEGIN-PRIVATE-KEY-----");
            details.put("action", "https://user:password@host");
            details.put("retryable", "token");
            throw new MiddlewareException(new RemoteError(
                    MiddlewareErrorCode.PERMISSION_DENIED,
                    "provider raw message sentinel /secret/repository/key.pem",
                    details,
                    "phase76-unsafe-allowlisted",
                    "phase76-runtime",
                    false,
                    false));
        }
    }

    private static final class ReturnedFailureDeployer extends RecordingDeployer {
        @Override
        public ApplicationDeploymentResult drain(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
            return returnedFailure();
        }

        @Override
        public ApplicationDeploymentResult deactivate(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
            return returnedFailure();
        }

        @Override
        public ApplicationDeploymentResult unload(final ApplicationInstanceId instanceId, final UnloadOptions options) {
            return returnedFailure();
        }

        private static ApplicationDeploymentResult returnedFailure() {
            return new ApplicationDeploymentResult(
                    false,
                    ApplicationRunState.FAILED,
                    "java.lang.IllegalStateException: provider raw message sentinel /secret/repository/key.pem",
                    List.of(
                            "returned raw detail sentinel",
                            "opaque-secret-sentinel",
                            "-----BEGIN-PRIVATE-KEY-----",
                            "configuration-sentinel"));
        }
    }

    private static final class BlankReturnedFailureDeployer extends RecordingDeployer {
        @Override
        public ApplicationDeploymentResult deactivate(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
            return new ApplicationDeploymentResult(
                    false,
                    ApplicationRunState.FAILED,
                    " ".repeat(16_384),
                    List.of());
        }
    }

    private static final class DefaultLifecycleOnlyService implements NuggetAdminService {
        private final AtomicInteger preexistingMethodCalls = new AtomicInteger();

        @Override
        public NuggetStatus status() {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetAuthenticationResult authenticate(
                final NuggetAuthenticationRequest request,
                final char[] pin) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>>
                disk(
                        final NuggetIntrospection.Request request,
                        final NuggetIntrospection.DiskQuery query) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.TransportSummary>>
                transports(
                        final NuggetIntrospection.Request request,
                        final NuggetIntrospection.Query query) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RegistrySummary> registry(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RepositorySummary> repository(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>>
                applications(
                        final NuggetIntrospection.Request request,
                        final NuggetIntrospection.Query query) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
                final NuggetIntrospection.Request request,
                final String applicationInstanceId) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>>
                objects(
                        final NuggetIntrospection.Request request,
                        final NuggetIntrospection.Query query) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
                final NuggetIntrospection.Request request,
                final String objectId) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(
                final NuggetIntrospection.Request request) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>>
                recentErrors(
                        final NuggetIntrospection.Request request,
                        final NuggetIntrospection.RecentErrorQuery query) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetDiscovery.Result discover(final NuggetDiscovery.Query query) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetDiscovery.Resolution resolve(final NuggetDiscovery.Selector selector) {
            throw this.unexpectedCall();
        }

        @Override
        public NuggetDiscovery.StatusSummary discoveryStatus() {
            throw this.unexpectedCall();
        }

        @Override
        public void close() {
        }

        private UnsupportedOperationException unexpectedCall() {
            this.preexistingMethodCalls.incrementAndGet();
            return new UnsupportedOperationException("pre-existing service method must not be used by default lifecycle");
        }
    }
}
