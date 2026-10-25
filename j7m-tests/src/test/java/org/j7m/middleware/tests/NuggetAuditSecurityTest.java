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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.operations.api.NuggetAdminContract;
import org.j7m.middleware.operations.api.NuggetAdminOperationKind;
import org.j7m.middleware.operations.api.NuggetAdminRequestContext;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.junit.jupiter.api.Test;

/**
 * Audit and redaction tests for nugget admission.
 */
public final class NuggetAuditSecurityTest {
    @Test
    public void auditTopLevelFieldsAreBoundedAndRedacted() {
        String secret = "audit-top-level-secret-pin-sentinel";
        String oversized = "safe-token-".repeat(80);
        AuditEvent event = new AuditEvent(
                Instant.EPOCH,
                oversized,
                "principal-" + secret,
                oversized,
                "/tmp/" + secret,
                "allow",
                "policy-" + secret,
                oversized,
                "invocation-" + secret,
                "https://user:" + secret + "@example.invalid",
                "reason-" + secret,
                "headless",
                "default",
                Map.of("safe", "value", "pin", secret));
        String text = event.toString();

        assertFalse(text.contains(secret));
        assertFalse(text.contains(oversized));
        assertEquals("redacted", event.principal());
        assertTrue(event.resource().isEmpty() || event.resource().equals("redacted"));
        assertEquals("redacted", event.policyId());
        assertTrue(event.metadata().containsKey("safe"));
        assertFalse(event.metadata().containsKey("pin"));
    }

    @Test
    public void auditMachineCodeFieldsRetainStructuredSensitiveWordCodes() {
        AuditEvent rotated = auditEvent("credential-rotated", "outbound-payload-limit", Map.of());
        AuditEvent stale = auditEvent("scheduler.invoke", "stale-fencing-token", Map.of());

        assertEquals("credential-rotated", rotated.action());
        assertEquals("outbound-payload-limit", rotated.reason());
        assertEquals("stale-fencing-token", stale.reason());
    }

    @Test
    public void auditMachineCodeFieldsRejectMalformedAndUnsafeContent() {
        String oversized = "safe-code-".repeat(80);

        assertEquals("", auditEvent("credential rotated", "allowed", Map.of()).action());
        assertEquals("", auditEvent("credential\u001Brotated", "allowed", Map.of()).action());
        assertEquals("", auditEvent("credential\u202Erotated", "allowed", Map.of()).action());
        assertEquals("", auditEvent("bad/path", "allowed", Map.of()).action());
        assertEquals("", auditEvent(oversized, "allowed", Map.of()).action());
        assertEquals("", auditEvent("credential-rotated", "secret token value", Map.of()).reason());
        assertEquals("redacted", auditEvent("credential", "allowed", Map.of()).action());
        assertEquals("redacted", auditEvent("credential-rotated", "payload", Map.of()).reason());
        assertEquals("redacted", auditEvent("credential-rotated", "secret", Map.of()).reason());
        assertEquals("redacted", auditEvent("credential-rotated", "token", Map.of()).reason());
        assertEquals("redacted", auditEvent("java.lang.IllegalStateException", "allowed", Map.of()).action());
        assertEquals("redacted", auditEvent("credential-rotated", "stacktrace", Map.of()).reason());
        assertEquals("redacted", auditEvent("credential-rotated", "C:secret", Map.of()).reason());
        assertEquals("", auditEvent("\uD800", "allowed", Map.of()).action());
    }

    @Test
    public void auditMetadataRemainsImmutableBoundedAndRedacted() {
        String sentinel = "audit-metadata-secret-sentinel";
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("safe", "visible");
        metadata.put("token", sentinel);
        metadata.put("path", "/tmp/" + sentinel);
        metadata.put("exception", "IllegalStateException:" + sentinel);
        for (int index = 0; index < 40; index++) {
            metadata.put("safe" + index, "value" + index);
        }

        AuditEvent event = auditEvent("credential-rotated", "outbound-payload-limit", metadata);
        metadata.put("late", "mutation");

        assertEquals("visible", event.metadata().get("safe"));
        assertFalse(event.metadata().containsKey("token"));
        assertFalse(event.metadata().containsKey("path"));
        assertFalse(event.metadata().containsKey("exception"));
        assertFalse(event.metadata().containsKey("late"));
        assertTrue(event.metadata().size() <= 32);
        assertTrue(event.metadata().containsKey("detailsOmitted"));
        assertThrows(UnsupportedOperationException.class, () -> event.metadata().put("new", "value"));
        assertFalse(event.toString().contains(sentinel));
    }

    @Test
    public void inMemoryAuditSinkRetainedCapacityHasHardMaximum() {
        InMemoryAuditSink audit = new InMemoryAuditSink(100_000);
        for (int index = 0; index < 5_000; index++) {
            audit.record(new AuditEvent(
                    Instant.EPOCH,
                    "runtime",
                    "principal",
                    "action." + index,
                    "resource",
                    "allow",
                    "policy",
                    "corr-" + index,
                    "",
                    "",
                    "",
                    "headless",
                    "default",
                    Map.of()));
        }
        List<AuditEvent> retained = audit.recentEvents();

        assertEquals(5_000L, audit.eventCount());
        assertEquals(4_096, retained.size());
        assertEquals("action.904", retained.getFirst().action());
    }

    @Test
    public void auditMatrixCoversPublicOperationKindsWithoutUnsafeMetadata() {
        InMemoryAuditSink audit = new InMemoryAuditSink(256);
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(audit)
                .pinFromProgrammaticProvider("protected", () -> "matrix-pin".toCharArray())
                .build());
        String admission = controller.authenticate(NuggetAuthenticationRequest.of(
                "matrix-auth",
                NuggetAuthenticationSource.LOCAL,
                "matrix-local",
                true,
                Instant.EPOCH), "matrix-pin".toCharArray()).admissionId();

        for (NuggetAdminOperationKind kind : NuggetAdminOperationKind.values()) {
            exerciseOperationKind(controller, kind, admission);
            assertTrue(audit.recentEvents().stream().anyMatch(event -> operationAuditMatch(kind, event)),
                    kind.name());
        }

        String text = audit.recentEvents().toString();
        assertFalse(text.contains("matrix-pin"));
        assertFalse(text.contains("confirmation-secret"));
        assertFalse(text.contains("provider exception"));
        assertTrue(audit.recentEvents().stream().allMatch(event -> event.metadata().size() <= 32));
    }

    @Test
    public void secretSentinelDoesNotLeakThroughPublicSurfaces() {
        String sentinel = "SENTINEL-PIN-7-1";
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminStartupTest.baseConfig(audit)
                .pinFromProgrammaticProvider("protected", sentinel::toCharArray)
                .build();
        NuggetAdminService controller = NuggetAdminStartupTest.start(configuration);

        var accepted = controller.authenticate(NuggetAuthenticationRequest.of("sentinel-ok",
                NuggetAuthenticationSource.LOCAL, "local", true, java.time.Instant.EPOCH), sentinel.toCharArray());
        var rejected = controller.authenticate(NuggetAuthenticationRequest.of("sentinel-bad",
                NuggetAuthenticationSource.LOCAL, "local", true, java.time.Instant.EPOCH), "wrong".toCharArray());

        String publicText = controller.status().toString()
                + accepted
                + rejected
                + audit.recentEvents()
                + configuration;

        assertFalse(publicText.contains(sentinel));
        assertFalse(publicText.toLowerCase(java.util.Locale.ROOT).contains("pbkdf2withhmacsha256|"));
        assertTrue(accepted.metadata().containsKey("authorization"));
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("nugget.pin.verifier.initialized")));
    }

    private static void exerciseOperationKind(
            final NuggetAdminService controller,
            final NuggetAdminOperationKind kind,
            final String admission) {
        Instant now = Instant.parse("2026-07-10T10:00:00Z");
        switch (kind) {
            case CAPABILITIES -> controller.adminCapabilities(context(kind, now));
            case DISCOVERY -> controller.discover(new NuggetDiscovery.Query(true, 4, "matrix-discovery"));
            case AUTHENTICATION -> controller.authenticate(NuggetAuthenticationRequest.of(
                    "matrix-auth-reject",
                    NuggetAuthenticationSource.LOCAL,
                    "matrix-local",
                    true,
                    now), "wrong".toCharArray());
            case INTROSPECTION -> controller.environment(new NuggetIntrospection.Request(
                    admission,
                    SecurityContext.system("matrix-caller"),
                    "matrix-introspection",
                    now,
                    Map.of("operation", "matrix")));
            case APPLICATION_LIFECYCLE -> controller.applicationLifecycle(new NuggetApplicationLifecycle.Request(
                    admission,
                    SecurityContext.system("matrix-caller"),
                    "matrix-lifecycle",
                    NuggetApplicationLifecycle.Action.START,
                    ApplicationCoordinate.parse("org.j7m.tests:matrix:1.0.0"),
                    null,
                    false,
                    now,
                    now.plusSeconds(5L),
                    Map.of("operation", "matrix")));
            case NUGGET_CONTROL -> controller.nuggetControl(new NuggetControl.Request(
                    admission,
                    SecurityContext.system("matrix-caller"),
                    "matrix-control",
                    "matrix-control",
                    NuggetControl.Action.KILL_NUGGET,
                    NuggetControl.Mode.GRACEFUL,
                    controller.status().identity().nuggetId(),
                    controller.status().identity().runtimeIdentity().runtimeId(),
                    0L,
                    "confirmation-secret",
                    now,
                    now.plusSeconds(5L),
                    Map.of("operation", "matrix")));
        }
    }

    private static boolean operationAuditMatch(final NuggetAdminOperationKind kind, final AuditEvent event) {
        return switch (kind) {
            case CAPABILITIES -> event.action().startsWith("nugget.capabilities.");
            case DISCOVERY -> event.action().startsWith("nugget.discovery.");
            case AUTHENTICATION -> event.action().startsWith("nugget.auth.");
            case INTROSPECTION -> event.action().startsWith("nugget.introspection.");
            case APPLICATION_LIFECYCLE -> event.action().equals("nugget.application.lifecycle");
            case NUGGET_CONTROL -> event.action().equals("nugget.control") || event.action().startsWith("nugget.control.");
        };
    }

    private static NuggetAdminRequestContext context(final NuggetAdminOperationKind kind, final Instant now) {
        return new NuggetAdminRequestContext(
                NuggetAdminContract.PROTOCOL_VERSION,
                "matrix-" + kind.name(),
                "matrix-" + kind.name(),
                null,
                kind,
                now,
                Duration.ofSeconds(5L),
                now.plusSeconds(5L),
                Map.of("operation", "matrix"));
    }

    @Test
    public void defaultRedactorCoversPinDerivedAndAdmissionMaterial() {
        DefaultRedactor redactor = new DefaultRedactor();
        Map<String, String> redacted = redactor.redactMetadata(Map.of(
                "pin", "clear",
                "pinVerifierBytes", "derived",
                "pinHash", "hash",
                "kdfOutput", "kdf",
                "credentialCache", "cache",
                "authHeader", "header",
                "confirmationToken", "token",
                "safe", "visible"));

        assertTrue(redacted.values().stream().filter("<redacted>"::equals).count() >= 7L);
        assertTrue(redacted.containsValue("visible"));
    }

    private static AuditEvent auditEvent(final String action, final String reason, final Map<String, String> metadata) {
        return new AuditEvent(
                Instant.EPOCH,
                "runtime",
                "principal",
                action,
                "resource",
                "allow",
                "policy",
                "correlation",
                "invocation",
                "",
                reason,
                "headless",
                "default",
                metadata);
    }
}
