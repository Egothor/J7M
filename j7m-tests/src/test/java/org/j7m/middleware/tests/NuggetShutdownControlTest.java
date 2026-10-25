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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.runtime.NuggetAdminRuntime;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
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
 * Focused Phase 7.7 tests for nugget drain, shutdown, and kill controls.
 */
public final class NuggetShutdownControlTest {
    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
    private static final char[] PIN = new char[] {'7', '7', '0', '0', '0', '1'};
    private static final RuntimeIdentity RUNTIME_IDENTITY = new RuntimeIdentity("phase77-runtime", "default", Map.of());

    @Test
    public void defaultControlMethodReturnsUnavailableForOlderImplementers() {
        DefaultControlOnlyService service = new DefaultControlOnlyService();

        NuggetControl.Result result = service.nuggetControl(new NuggetControl.Request(
                "admission",
                SecurityContext.system("phase77-caller"),
                "operation-default",
                "phase77-default",
                NuggetControl.Action.DRAIN_NUGGET,
                NuggetControl.Mode.GRACEFUL,
                "nugget-a",
                "runtime-a",
                1L,
                "",
                NOW,
                NOW.plusSeconds(10L),
                Map.of()));

        assertEquals(NuggetControl.Outcome.UNAVAILABLE, result.outcome());
        assertFalse(result.accepted());
        assertFalse(result.authorized());
        assertEquals(MiddlewareErrorCode.UNAVAILABLE, result.error().code());
        assertEquals(0, service.preexistingMethodCalls.get());
    }

    @Test
    public void drainTransitionsReadinessAndRejectsLaterApplicationMutations() {
        RecordingPolicy policy = new RecordingPolicy(false);
        try (NuggetAdminService service = start(new InMemoryAuditSink(64), policy, false, false, null, null)) {
            String admission = admit(service);
            NuggetControl.Result result = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.DRAIN_NUGGET, "", "phase77-drain"));

            assertEquals(NuggetControl.Outcome.PARTIAL, result.outcome());
            assertTrue(result.accepted());
            assertTrue(result.partial());
            assertEquals(NuggetState.DRAINING, service.status().identity().state());
            NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness = service.readiness(
                    new NuggetIntrospection.Request(admission, SecurityContext.system("phase77-caller"),
                            "phase77-readiness", NOW, Map.of()));
            assertEquals(NuggetIntrospection.ReadinessState.NOT_READY, readiness.value().state());
            assertTrue(result.warnings().contains("full-work-drain-deferred"));
        }
    }

    @Test
    public void staleShutdownConfirmationIsRejectedAfterDrainKeepsDrainingState() {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        RecordingPolicy policy = new RecordingPolicy(false);
        AtomicInteger closeCount = new AtomicInteger();
        try (NuggetAdminService service = start(audit, policy, false, false, closeCount::incrementAndGet, null)) {
            String admission = admit(service);
            NuggetControl.Result challenge = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "phase77-old-shutdown"));

            NuggetControl.Result drain = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.DRAIN_NUGGET, "", "phase77-drain-before-old-shutdown"));
            NuggetControl.Result rejected = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(), "phase77-old-shutdown"));

            assertEquals(NuggetControl.Outcome.CONFIRMATION_REQUIRED, challenge.outcome());
            assertEquals(NuggetControl.Outcome.PARTIAL, drain.outcome());
            assertEquals(NuggetControl.Outcome.DENIED, rejected.outcome());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, rejected.error().code());
            assertEquals(0, closeCount.get());
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.reason().equals("confirmation-rejected")));
            assertEquals(NuggetState.DRAINING, service.status().identity().state());
        }
    }

    @Test
    public void shutdownRequiresSingleUseConfirmationAndRunsRuntimeCloseCallbackOnce() throws InterruptedException {
        RecordingPolicy policy = new RecordingPolicy(false);
        CountDownLatch closeCalled = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        try (NuggetAdminService service = start(new InMemoryAuditSink(128), policy, false, false,
                () -> {
                    closeCount.incrementAndGet();
                    closeCalled.countDown();
                }, null)) {
            String admission = admit(service);
            NuggetControl.Request first = request(service, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "",
                    "phase77-shutdown");

            NuggetControl.Result challenge = service.nuggetControl(first);

            assertEquals(NuggetControl.Outcome.CONFIRMATION_REQUIRED, challenge.outcome());
            assertNotNull(challenge.confirmation());
            assertFalse(challenge.toString().contains(challenge.confirmation().challenge()));
            NuggetControl.Result accepted = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(), "phase77-shutdown"));
            NuggetControl.Result replay = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(), "phase77-shutdown"));

            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertEquals(NuggetControl.Outcome.IN_PROGRESS, replay.outcome());
            assertTrue(closeCalled.await(2L, TimeUnit.SECONDS));
            assertEquals(1, closeCount.get());
            assertEquals(List.of(PermissionAction.ADMIN, PermissionAction.ADMIN, PermissionAction.ADMIN), policy.actions);
        }
    }

    @Test
    public void shutdownCallbackStartsAfterAuditAndStoppingRejectsNewMutations() throws InterruptedException {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        RecordingPolicy policy = new RecordingPolicy(false);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger callbackSawAcceptedAudit = new AtomicInteger();
        try (NuggetAdminService service = start(audit, policy, false, false,
                () -> {
                    if (audit.recentEvents().toString().contains("accepted")) {
                        callbackSawAcceptedAudit.incrementAndGet();
                    }
                    closeEntered.countDown();
                    try {
                        releaseClose.await(2L, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }, null)) {
            String admission = admit(service);
            NuggetControl.Result challenge = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "phase77-stopping"));

            NuggetControl.Result accepted = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(), "phase77-stopping"));

            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(closeEntered.await(2L, TimeUnit.SECONDS));
            assertEquals(1, callbackSawAcceptedAudit.get());
            assertEquals(NuggetState.STOPPING, service.status().identity().state());
            NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness = service.readiness(
                    new NuggetIntrospection.Request(admission, SecurityContext.system("phase77-caller"),
                            "phase77-stopping-readiness", NOW, Map.of()));
            assertEquals(NuggetIntrospection.ReadinessState.NOT_READY, readiness.value().state());

            NuggetControl.Result drainAfterShutdown = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.DRAIN_NUGGET, "", "phase77-drain-after-stop"));
            assertEquals(NuggetControl.Outcome.IN_PROGRESS, drainAfterShutdown.outcome());
            assertFalse(drainAfterShutdown.accepted());
            assertEquals(NuggetState.STOPPING, service.status().identity().state());

            NuggetApplicationLifecycle.Result lifecycle = service.applicationLifecycle(new NuggetApplicationLifecycle.Request(
                    admission,
                    SecurityContext.system("phase77-caller"),
                    "phase77-lifecycle-while-stopping",
                    NuggetApplicationLifecycle.Action.START,
                    ApplicationCoordinate.parse("org.j7m.tests:stopping:1.0.0"),
                    null,
                    false,
                    NOW,
                    NOW.plusSeconds(10L),
                    Map.of()));
            assertEquals(NuggetApplicationLifecycle.Outcome.REJECTED, lifecycle.outcome());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, lifecycle.error().code());
            assertEquals("nugget-shutting-down", lifecycle.metadata().get("reason"));
        } finally {
            releaseClose.countDown();
        }
    }

    @Test
    public void shutdownSelfCloseDoesNotInterruptCurrentControlAction() throws InterruptedException {
        RecordingPolicy policy = new RecordingPolicy(false);
        AtomicReference<NuggetAdminService> serviceRef = new AtomicReference<>();
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicInteger interruptedBeforeClose = new AtomicInteger();
        AtomicInteger interruptedAfterClose = new AtomicInteger();
        NuggetAdminService service = start(new InMemoryAuditSink(128), policy, false, false,
                () -> {
                    if (Thread.currentThread().isInterrupted()) {
                        interruptedBeforeClose.incrementAndGet();
                    }
                    serviceRef.get().close();
                    if (Thread.currentThread().isInterrupted()) {
                        interruptedAfterClose.incrementAndGet();
                    }
                    closeReturned.countDown();
                }, null);
        serviceRef.set(service);
        try (service) {
            String admission = admit(service);
            NuggetControl.Result challenge = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "phase77-self-close"));

            NuggetControl.Result accepted = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET,
                    challenge.confirmation().challenge(), "phase77-self-close"));

            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertTrue(closeReturned.await(2L, TimeUnit.SECONDS));
            assertEquals(0, interruptedBeforeClose.get());
            assertEquals(0, interruptedAfterClose.get());
            assertEquals(NuggetState.STOPPED, service.status().identity().state());
        }
    }

    @Test
    public void wrongOperationConfirmationIsRejectedAndChallengeIsCliSafe() {
        RecordingPolicy policy = new RecordingPolicy(false);
        try (NuggetAdminService service = start(new InMemoryAuditSink(128), policy, false, true,
                () -> { }, () -> { }, new LeadingDashSecureRandom())) {
            String admission = admit(service);
            NuggetControl.Result challenge = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "phase77-wrong-op"));

            assertEquals(NuggetControl.Outcome.CONFIRMATION_REQUIRED, challenge.outcome());
            assertNotNull(challenge.confirmation());
            assertTrue(challenge.confirmation().challenge().startsWith("c-"));
            assertFalse(challenge.confirmation().challenge().startsWith("--"));

            NuggetControl.Result rejected = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.KILL_NUGGET,
                    challenge.confirmation().challenge(), "phase77-wrong-op"));

            assertEquals(NuggetControl.Outcome.DENIED, rejected.outcome());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, rejected.error().code());
        }
    }

    @Test
    public void killDisabledAndMissingCapabilityRejectBeforeTermination() {
        RecordingPolicy policy = new RecordingPolicy(false);
        AtomicInteger terminations = new AtomicInteger();
        try (NuggetAdminService disabled = start(new InMemoryAuditSink(64), policy, false, false, null,
                terminations::incrementAndGet)) {
            NuggetControl.Result result = disabled.nuggetControl(request(
                    disabled, admit(disabled), NuggetControl.Action.KILL_NUGGET, "valid-looking", "phase77-kill-disabled"));

            assertEquals(NuggetControl.Outcome.DISABLED, result.outcome());
            assertEquals(0, terminations.get());
        }

        try (NuggetAdminService missingCapability = start(new InMemoryAuditSink(64), policy, false, true, null, null)) {
            NuggetControl.Result result = missingCapability.nuggetControl(request(
                    missingCapability, admit(missingCapability), NuggetControl.Action.KILL_NUGGET, "", "phase77-kill-no-cap"));

            assertEquals(NuggetControl.Outcome.UNAVAILABLE, result.outcome());
            assertEquals(0, terminations.get());
        }
    }

    @Test
    public void enabledKillUsesInjectedCapabilityOnceAfterConfirmation() throws InterruptedException {
        RecordingPolicy policy = new RecordingPolicy(false);
        CountDownLatch terminated = new CountDownLatch(1);
        AtomicInteger terminations = new AtomicInteger();
        try (NuggetAdminService service = start(new InMemoryAuditSink(128), policy, false, true, null,
                () -> {
                    terminations.incrementAndGet();
                    terminated.countDown();
                })) {
            String admission = admit(service);
            NuggetControl.Result challenge = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.KILL_NUGGET, "", "phase77-kill"));

            NuggetControl.Result accepted = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.KILL_NUGGET,
                    challenge.confirmation().challenge(), "phase77-kill"));
            NuggetControl.Result replay = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.KILL_NUGGET,
                    challenge.confirmation().challenge(), "phase77-kill"));

            assertEquals(NuggetControl.Outcome.ACCEPTED, accepted.outcome());
            assertEquals(NuggetControl.Outcome.IN_PROGRESS, replay.outcome());
            assertTrue(terminated.await(2L, TimeUnit.SECONDS));
            assertEquals(1, terminations.get());
            assertEquals(List.of(PermissionAction.KILL, PermissionAction.KILL, PermissionAction.KILL), policy.actions);
        }
    }

    @Test
    public void readOnlyAndAuthorizationDenialRejectBeforeControlExecution() {
        AtomicInteger closeCount = new AtomicInteger();
        RecordingPolicy allow = new RecordingPolicy(false);
        try (NuggetAdminService readOnly = start(new InMemoryAuditSink(64), allow, true, false,
                closeCount::incrementAndGet, null)) {
            NuggetControl.Result result = readOnly.nuggetControl(request(
                    readOnly, admit(readOnly), NuggetControl.Action.DRAIN_NUGGET, "", "phase77-readonly"));

            assertEquals(NuggetControl.Outcome.DENIED, result.outcome());
            assertEquals(0, closeCount.get());
        }

        RecordingPolicy deny = new RecordingPolicy(true);
        try (NuggetAdminService service = start(new InMemoryAuditSink(64), deny, false, false,
                closeCount::incrementAndGet, null)) {
            NuggetControl.Result result = service.nuggetControl(request(
                    service, admit(service), NuggetControl.Action.SHUTDOWN_NUGGET, "", "phase77-denied"));

            assertEquals(NuggetControl.Outcome.DENIED, result.outcome());
            assertEquals(0, closeCount.get());
        }
    }

    @Test
    public void confirmationAndAuditDoNotExposeSecrets() {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        RecordingPolicy policy = new RecordingPolicy(false);
        try (NuggetAdminService service = start(audit, policy, false, false, () -> { }, null)) {
            String admission = admit(service);
            NuggetControl.Result challenge = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "", "phase77-redaction"));
            String challengeValue = challenge.confirmation().challenge();
            NuggetControl.Result rejected = service.nuggetControl(request(
                    service, admission, NuggetControl.Action.SHUTDOWN_NUGGET, "wrong-confirmation", "phase77-redaction"));

            assertEquals(NuggetControl.Outcome.DENIED, rejected.outcome());
            String auditText = audit.recentEvents().toString();
            assertFalse(auditText.contains(new String(PIN)));
            assertFalse(auditText.contains(admission));
            assertFalse(auditText.contains(challengeValue));
            assertFalse(challenge.toString().contains(challengeValue));
        }
    }

    private static NuggetAdminService start(
            final InMemoryAuditSink audit,
            final RecordingPolicy policy,
            final boolean readOnly,
            final boolean killEnabled,
            final Runnable runtimeClose,
            final Runnable terminationCapability) {
        return start(audit, policy, readOnly, killEnabled, runtimeClose, terminationCapability, null);
    }

    private static NuggetAdminService start(
            final InMemoryAuditSink audit,
            final RecordingPolicy policy,
            final boolean readOnly,
            final boolean killEnabled,
            final Runnable runtimeClose,
            final Runnable terminationCapability,
            final SecureRandom secureRandom) {
        NuggetAdminRuntimeConfiguration.Builder builder = NuggetAdminRuntimeConfiguration.builder()
                .security(security(audit, policy))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .nuggetName("phase77-test-nugget")
                .pinFromProgrammaticProvider("phase77-test-provider", NuggetShutdownControlTest::copyPin)
                .readOnly(readOnly)
                .killEnabled(killEnabled)
                .confirmationPolicy(Duration.ofSeconds(30L), 16)
                .controlExecutionPolicy(1, 2, Duration.ofSeconds(2L), Duration.ofSeconds(2L));
        if (secureRandom != null) {
            builder.secureRandom(secureRandom);
        }
        if (runtimeClose != null) {
            builder.runtimeCloseAction(runtimeClose);
        }
        if (terminationCapability != null) {
            builder.terminationCapability(terminationCapability);
        }
        return NuggetAdminRuntime.start(RUNTIME_IDENTITY, builder.build());
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
                new MutableCredentialProvider(new Credential("test", "phase77", Map.of())));
    }

    private static String admit(final NuggetAdminService service) {
        return service.authenticate(NuggetAuthenticationRequest.of(
                "phase77-auth",
                NuggetAuthenticationSource.LOCAL,
                "phase77-local",
                true,
                NOW), copyPin()).admissionId();
    }

    private static NuggetControl.Request request(
            final NuggetAdminService service,
            final String admission,
            final NuggetControl.Action action,
            final String confirmation,
            final String operationId) {
        return new NuggetControl.Request(
                admission,
                SecurityContext.system("phase77-caller"),
                operationId,
                operationId,
                action,
                action == NuggetControl.Action.KILL_NUGGET ? NuggetControl.Mode.FORCED : NuggetControl.Mode.GRACEFUL,
                service.status().identity().nuggetId(),
                RUNTIME_IDENTITY.runtimeId(),
                1L,
                confirmation,
                NOW,
                NOW.plusSeconds(10L),
                Map.of("confirmation", "SECRET-SENTINEL"));
    }

    private static char[] copyPin() {
        char[] copy = new char[PIN.length];
        System.arraycopy(PIN, 0, copy, 0, PIN.length);
        return copy;
    }

    private static final class RecordingPolicy implements AuthorizationPolicy {
        private final boolean deny;
        private final List<PermissionAction> actions = new ArrayList<>();

        private RecordingPolicy(final boolean deny) {
            this.deny = deny;
        }

        @Override
        public String id() {
            return "phase77-recording-policy";
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

    private static final class LeadingDashSecureRandom extends SecureRandom {
        @Override
        public void nextBytes(final byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) 0xFB;
            }
        }
    }

    private static final class DefaultControlOnlyService implements NuggetAdminService {
        private final AtomicInteger preexistingMethodCalls = new AtomicInteger();

        @Override
        public org.j7m.middleware.operations.api.NuggetStatus status() {
            this.preexistingMethodCalls.incrementAndGet();
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public org.j7m.middleware.operations.api.NuggetAuthenticationResult authenticate(
                final NuggetAuthenticationRequest request,
                final char[] pin) {
            this.preexistingMethodCalls.incrementAndGet();
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(
                final NuggetIntrospection.Request request) {
            this.preexistingMethodCalls.incrementAndGet();
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(
                final NuggetIntrospection.Request request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> disk(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.DiskQuery query) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(final NuggetIntrospection.Request request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(
                final NuggetIntrospection.Request request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(final NuggetIntrospection.Request request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.TransportSummary>> transports(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RegistrySummary> registry(final NuggetIntrospection.Request request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RepositorySummary> repository(
                final NuggetIntrospection.Request request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
                final NuggetIntrospection.Request request,
                final String applicationInstanceId) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
                final NuggetIntrospection.Request request,
                final String objectId) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(final NuggetIntrospection.Request request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> recentErrors(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.RecentErrorQuery query) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public org.j7m.middleware.operations.api.NuggetDiscovery.Result discover(
                final org.j7m.middleware.operations.api.NuggetDiscovery.Query query) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public org.j7m.middleware.operations.api.NuggetDiscovery.Resolution resolve(
                final org.j7m.middleware.operations.api.NuggetDiscovery.Selector selector) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public org.j7m.middleware.operations.api.NuggetDiscovery.StatusSummary discoveryStatus() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void close() {
        }
    }
}
