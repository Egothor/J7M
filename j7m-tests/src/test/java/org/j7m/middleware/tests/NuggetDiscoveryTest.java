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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.runtime.NuggetAdminRuntime;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7.3 headless nugget discovery coverage.
 */
public final class NuggetDiscoveryTest {
    private static final RuntimeIdentity RUNTIME_ID = new RuntimeIdentity("runtime-discovery", "default", Map.of());
    private static final char[] PIN = "correct-pin".toCharArray();
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Test
    public void localPublicationScansExpiresAndRemovesSecureRecord(@TempDir final Path directory) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        NuggetAdminService service = service(directory, clock, audit);
        try {
            NuggetDiscovery.Result active = service.discover(NuggetDiscovery.Query.active());

            assertEquals(1, active.returned());
            NuggetDiscovery.Record record = active.records().getFirst();
            assertEquals(NuggetDiscovery.Source.LOCAL_RECORD, record.source());
            assertEquals(NuggetDiscovery.Status.ACTIVE, record.status());
            assertEquals("LOCAL", record.hostSummary());
            assertEquals(RUNTIME_ID.runtimeId(), record.runtimeId());
            assertFalse(record.toString().contains("correct-pin"));
            assertFalse(record.endpoint().toString().contains(directory.toString()));
            assertEquals(1, Files.list(directory).filter(path -> path.getFileName().toString().endsWith(".j7m-discovery")).count());

            clock.advance(Duration.ofSeconds(6L));
            NuggetDiscovery.Result stale = service.discover(new NuggetDiscovery.Query(true, 10, "stale"));
            assertEquals(NuggetDiscovery.Status.STALE, stale.records().getFirst().status());
            NuggetDiscovery.Result activeAfterExpiry = service.discover(NuggetDiscovery.Query.active());
            assertEquals(0, activeAfterExpiry.returned());
            assertEquals(0, activeAfterExpiry.totalAvailable());
            assertEquals(NuggetDiscovery.ResolutionStatus.STALE,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_ID, record.nuggetId(), "resolve")).status());
            clock.advance(Duration.ofSeconds(-4L));
            assertEquals(0, service.discover(NuggetDiscovery.Query.active()).returned());
        } finally {
            service.close();
        }
        assertEquals(0, Files.list(directory).filter(path -> path.getFileName().toString().endsWith(".j7m-discovery")).count());
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().startsWith("nugget.discovery")));
        assertFalse(audit.recentEvents().toString().contains("correct-pin"));
    }

    @Test
    public void disabledAdminDoesNotPublish(@TempDir final Path directory) {
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .enabled(false)
                .security(security(new InMemoryAuditSink(32), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneId.of("UTC")))
                .localDiscovery(directory, true)
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        try {
            assertEquals(0, service.discover(NuggetDiscovery.Query.active()).returned());
        } finally {
            service.close();
        }
    }

    @Test
    public void publicDiscoveryResultsCapRecordsWarningsAndEndpointLocalId() {
        List<NuggetDiscovery.Record> records = new java.util.ArrayList<>();
        for (int index = 0; index < NuggetDiscovery.MAX_RESULTS + 25; index++) {
            records.add(new NuggetDiscovery.Record(
                    "nugget-" + index,
                    "node-" + index,
                    "runtime-" + index,
                    NuggetDiscovery.EndpointDescriptor.local("SECRET-SENTINEL-" + index),
                    1,
                    1,
                    NuggetDiscovery.Compatibility.COMPATIBLE,
                    List.of("admin"),
                    "",
                    "LOCAL",
                    NOW,
                    NOW,
                    NOW.plus(Duration.ofSeconds(5L)),
                    NuggetDiscovery.Source.LOCAL_RECORD,
                    NuggetDiscovery.Status.ACTIVE,
                    List.of("warning\n" + index)));
        }
        List<String> warnings = new java.util.ArrayList<>();
        for (int index = 0; index < NuggetDiscovery.MAX_WARNINGS + 10; index++) {
            warnings.add("warning-control-\n-" + index + "-" + "x".repeat(200));
        }

        NuggetDiscovery.Result result = new NuggetDiscovery.Result(records, records.size(), records.size(), NOW, false, warnings);
        NuggetDiscovery.Resolution resolution = new NuggetDiscovery.Resolution(
                NuggetDiscovery.ResolutionStatus.RESOLVED,
                records,
                "resolved",
                null,
                NOW,
                warnings);

        assertEquals(NuggetDiscovery.MAX_RESULTS, result.records().size());
        assertTrue(result.partial());
        assertEquals(NuggetDiscovery.MAX_WARNINGS, result.warnings().size());
        assertTrue(result.warnings().stream().allMatch(value -> value.length() <= NuggetDiscovery.MAX_WARNING_LENGTH));
        assertFalse(result.toString().contains("SECRET-SENTINEL"));
        assertTrue(result.records().stream().allMatch(record -> record.endpoint().localId().isBlank()));
        assertEquals(NuggetDiscovery.MAX_RESULTS, resolution.records().size());
        assertEquals(NuggetDiscovery.MAX_WARNINGS, resolution.warnings().size());
    }

    @Test
    public void publicDiscoveryRecordSanitizesCapabilitiesAndWarningsWithBoundedScan() {
        List<String> capabilities = new java.util.ArrayList<>();
        for (int index = 0; index < 80; index++) {
            capabilities.add("capability-" + index);
        }
        capabilities.add("must-not-be-inspected");
        List<String> warnings = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            warnings.add("warning-" + index + "\n".repeat(512));
        }
        warnings.add("must-not-be-inspected");

        NuggetDiscovery.Record record = new NuggetDiscovery.Record(
                "nugget-bounded",
                "bounded",
                "runtime-bounded",
                NuggetDiscovery.EndpointDescriptor.local("bounded-local"),
                1,
                1,
                NuggetDiscovery.Compatibility.COMPATIBLE,
                capabilities,
                "",
                "LOCAL",
                NOW,
                NOW,
                NOW.plus(Duration.ofSeconds(5L)),
                NuggetDiscovery.Source.LOCAL_RECORD,
                NuggetDiscovery.Status.ACTIVE,
                warnings);

        assertEquals(32, record.capabilities().size());
        assertEquals(NuggetDiscovery.MAX_WARNINGS, record.warnings().size());
        assertFalse(record.capabilities().contains("must-not-be-inspected"));
        assertFalse(record.warnings().contains("must-not-be-inspected"));
        assertTrue(record.warnings().stream().allMatch(value -> value.length() <= NuggetDiscovery.MAX_WARNING_LENGTH));
    }

    @Test
    public void malformedOversizedAndSymlinkRecordsAreIgnored(@TempDir final Path directory) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        NuggetAdminService service = service(directory, clock, new InMemoryAuditSink(64));
        try {
            Files.writeString(directory.resolve("bad.j7m-discovery"), "not-a-record", StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("large.j7m-discovery"), "x".repeat(5000), StandardCharsets.UTF_8);
            Path symlink = directory.resolve("link.j7m-discovery");
            try {
                Files.createSymbolicLink(symlink, directory.resolve("bad.j7m-discovery"));
            } catch (UnsupportedOperationException | IOException ignored) {
                Files.writeString(symlink, "not-a-record", StandardCharsets.UTF_8);
            }

            NuggetDiscovery.Result result = service.discover(new NuggetDiscovery.Query(true, 10, "malformed"));

            assertEquals(1, result.records().stream()
                    .filter(record -> record.source() == NuggetDiscovery.Source.LOCAL_RECORD
                            && record.status() == NuggetDiscovery.Status.ACTIVE)
                    .count());
            assertFalse(result.toString().contains("not-a-record"));
        } finally {
            service.close();
        }
    }

    @Test
    public void localDiscoveryScanWorkIsBoundedBeforeFiltering(@TempDir final Path directory) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        NuggetAdminService service = service(directory, clock, new InMemoryAuditSink(128));
        try {
            for (int index = 0; index < 300; index++) {
                Files.writeString(directory.resolve("unrelated-" + index + ".tmp"), "ignored", StandardCharsets.UTF_8);
            }

            NuggetDiscovery.Result result = service.discover(new NuggetDiscovery.Query(true, 10, "scan-bound"));

            assertTrue(result.partial());
            assertTrue(result.warnings().contains("scan-incomplete"));
        } finally {
            service.close();
        }
    }

    @Test
    public void configuredTargetsAreBoundedSafeAndNeverPerformNetworkCalls() {
        NuggetDiscovery.EndpointDescriptor endpoint =
                new NuggetDiscovery.EndpointDescriptor("local", "j7m-local", "", -1, "configured-a");
        NuggetAdminService service = configuredService(
                new NuggetDiscovery.ConfiguredTarget(endpoint, "", "configured"),
                new NuggetDiscovery.ConfiguredTarget(endpoint, "", "configured"));
        try {
            NuggetDiscovery.Result result = service.discover(NuggetDiscovery.Query.active());

            assertEquals(1, result.returned());
            assertEquals(NuggetDiscovery.Status.UNRESOLVED, result.records().getFirst().status());
            assertEquals(NuggetDiscovery.ResolutionStatus.UNRESOLVED,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.ENDPOINT_ID,
                            "j7m-local:configured-a", "endpoint")).status());
            assertThrows(IllegalArgumentException.class,
                    () -> new NuggetDiscovery.EndpointDescriptor("admin", "https", "user@example.invalid?token=SECRET-SENTINEL", 443, ""));
        } finally {
            service.close();
        }
    }

    @Test
    public void configuredTargetsEvaluateProtocolCompatibilityWithoutNetworkCalls() {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        NuggetDiscovery.EndpointDescriptor compatibleEndpoint =
                new NuggetDiscovery.EndpointDescriptor("local", "j7m-local", "", -1, "compatible");
        NuggetDiscovery.EndpointDescriptor belowEndpoint =
                new NuggetDiscovery.EndpointDescriptor("local", "j7m-local", "", -1, "below");
        NuggetDiscovery.EndpointDescriptor aboveEndpoint =
                new NuggetDiscovery.EndpointDescriptor("local", "j7m-local", "", -1, "above");
        NuggetAdminService service = configuredServiceWithAudit(audit,
                new NuggetDiscovery.ConfiguredTarget(compatibleEndpoint, "compatible-id", "same", 1, 1),
                new NuggetDiscovery.ConfiguredTarget(belowEndpoint, "below-id", "same", 2, 3),
                new NuggetDiscovery.ConfiguredTarget(aboveEndpoint, "above-id", "above", 2, 2));
        try {
            NuggetDiscovery.Result all = service.discover(new NuggetDiscovery.Query(true, 10, "protocol"));

            assertTrue(all.records().stream()
                    .anyMatch(record -> record.nuggetId().equals("compatible-id")
                            && record.compatibility() == NuggetDiscovery.Compatibility.COMPATIBLE));
            assertTrue(all.records().stream()
                    .anyMatch(record -> record.nuggetId().equals("below-id")
                            && record.compatibility() == NuggetDiscovery.Compatibility.INCOMPATIBLE));
            assertFalse(service.discover(NuggetDiscovery.Query.active()).records().stream()
                    .anyMatch(record -> record.nuggetId().equals("below-id")));
            assertEquals(NuggetDiscovery.ResolutionStatus.INCOMPATIBLE,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_ID, "below-id", "below")).status());
            assertEquals(NuggetDiscovery.ResolutionStatus.INCOMPATIBLE,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_NAME, "above", "above")).status());
            assertEquals(NuggetDiscovery.ResolutionStatus.RESOLVED,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_NAME, "same", "same")).status());
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.action().equals("nugget.discovery.protocol.incompatible")));
            assertFalse(audit.recentEvents().toString().contains("j7m-local:below"));
        } finally {
            service.close();
        }
        assertThrows(IllegalArgumentException.class,
                () -> new NuggetDiscovery.ConfiguredTarget(compatibleEndpoint, "bad", "bad", 4, 2));
    }

    @Test
    public void configuredTargetNamesUseSecretAwareMinimalDisclosure() {
        InMemoryAuditSink audit = new InMemoryAuditSink(256);
        List<String> rejectedNames = List.of(
                "aaaaaaaaaaaaaaaaaaaaaaaa",
                "AAAAAAAAAAAAAAAAAAAAAAAA",
                "Aa0Bb1Cc2Dd3Ee4Ff5Gg6H",
                "0123456789abcdef01234567",
                "QUJDREVGR0hJSktMTU5PUFFS",
                "abcdEFGH0123_-abcdEFGH01",
                "550e8400-e29b-41d4-a716-446655440000",
                "/tmp/SECRET-SENTINEL",
                "https://SECRET-SENTINEL.example",
                "password SECRET-SENTINEL",
                "safe\nname",
                "safe\rname",
                "safe\tname",
                "safe\u0000name",
                "safe\u001Bname",
                "safe\u007Fname",
                "safe\u0085name",
                "\u0001safename",
                "safename\u0002",
                "safe\u0003\u0004name");
        NuggetAdminService service = configuredServiceWithAudit(audit,
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("safe-abc"), "", "ABC"),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("safe-local"), "", "local-dev"),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("safe-billing"), "", "billing-01"),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("safe-prague"), "", "Prague Node"),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("lower"), "", rejectedNames.get(0)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("upper"), "", rejectedNames.get(1)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("mixed"), "", rejectedNames.get(2)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("hex"), "", rejectedNames.get(3)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("base64"), "", rejectedNames.get(4)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("base64url"), "", rejectedNames.get(5)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("uuid"), "", rejectedNames.get(6)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("path"), "", rejectedNames.get(7)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("url"), "", rejectedNames.get(8)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("secret"), "", rejectedNames.get(9)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("lf"), "", rejectedNames.get(10)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("cr"), "", rejectedNames.get(11)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("tab"), "", rejectedNames.get(12)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("nul"), "", rejectedNames.get(13)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("esc"), "", rejectedNames.get(14)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("del"), "", rejectedNames.get(15)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("c1"), "", rejectedNames.get(16)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("leading-control"), "", rejectedNames.get(17)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("trailing-control"), "", rejectedNames.get(18)),
                new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("multiple-control"), "", rejectedNames.get(19)));
        try {
            NuggetDiscovery.Result result = service.discover(new NuggetDiscovery.Query(true, 40, "names"));

            assertTrue(result.records().stream().anyMatch(record -> record.nuggetName().equals("ABC")));
            assertTrue(result.records().stream().anyMatch(record -> record.nuggetName().equals("local-dev")));
            assertTrue(result.records().stream().anyMatch(record -> record.nuggetName().equals("billing-01")));
            assertTrue(result.records().stream().anyMatch(record -> record.nuggetName().equals("Prague Node")));
            for (String rejectedName : rejectedNames) {
                assertFalse(result.toString().contains(rejectedName), "unsafe name reached discovery result");
                assertFalse(audit.recentEvents().toString().contains(rejectedName), "unsafe name reached audit");
            }
            assertFalse(result.toString().contains("SECRET-SENTINEL"));
            assertFalse(result.records().stream().anyMatch(record -> record.nuggetName().equals("safename")));
            assertFalse(result.records().stream().flatMap(record -> record.warnings().stream()).anyMatch(value -> value.contains("safename")));
            assertEquals(rejectedNames.size(), result.records().stream().filter(record -> record.nuggetName().isBlank()).count());
            assertTrue(result.records().stream()
                    .filter(record -> record.nuggetName().isBlank())
                    .allMatch(record -> record.status() == NuggetDiscovery.Status.UNRESOLVED));
        } finally {
            service.close();
        }
    }

    @Test
    public void localPublishedNameWithControlCharacterIsOmitted(@TempDir final Path directory) {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(audit, new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneId.of("UTC")))
                .monotonicTimeSource(new AtomicLong()::get)
                .nuggetName("safe\nname")
                .pinFromProgrammaticProvider("protected", NuggetDiscoveryTest::copyPin)
                .localDiscovery(directory, true)
                .discoveryTiming(Duration.ofSeconds(5L), Duration.ofSeconds(1L))
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        try {
            NuggetDiscovery.Result result = service.discover(NuggetDiscovery.Query.active());

            assertEquals(1, result.returned());
            assertEquals("", result.records().getFirst().nuggetName());
            assertFalse(result.toString().contains("safe\nname"));
            assertFalse(result.toString().contains("safename"));
            assertFalse(audit.recentEvents().toString().contains("safe\nname"));
            assertFalse(audit.recentEvents().toString().contains("safename"));
            assertTrue(result.records().getFirst().warnings().isEmpty());
        } finally {
            service.close();
        }
    }

    @Test
    public void mergeResolutionConflictsAndDuplicateNamesAreDeterministic(@TempDir final Path directory) {
        MutableClock clock = new MutableClock(NOW);
        NuggetDiscovery.EndpointDescriptor localEndpoint = NuggetDiscovery.EndpointDescriptor.local("configured-local");
        NuggetDiscovery.EndpointDescriptor sharedEndpoint = NuggetDiscovery.EndpointDescriptor.local("shared");
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(128), new AllowAllAuthorizationPolicy()))
                .clock(clock)
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetDiscoveryTest::copyPin)
                .localDiscovery(directory, true)
                .discoveryTiming(Duration.ofSeconds(5L), Duration.ofSeconds(1L))
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(localEndpoint, "", "ignored"))
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(sharedEndpoint, "nugget-a", "duplicate"))
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(sharedEndpoint, "nugget-b", "duplicate"))
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("name-1"), "nugget-name-1", "same-name"))
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(NuggetDiscovery.EndpointDescriptor.local("name-2"), "nugget-name-2", "same-name"))
                .build();
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, configuration);
        try {
            NuggetDiscovery.Result result = service.discover(new NuggetDiscovery.Query(true, 20, "merge"));

            assertEquals(1L, result.records().stream()
                    .filter(record -> record.source() == NuggetDiscovery.Source.LOCAL_RECORD)
                    .count());
            assertTrue(result.records().stream().anyMatch(record -> record.status() == NuggetDiscovery.Status.CONFLICTED));
            assertEquals(NuggetDiscovery.ResolutionStatus.AMBIGUOUS,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_NAME, "same-name", "name")).status());
            assertEquals(NuggetDiscovery.ResolutionStatus.RESOLVED,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_ID, "nugget-name-1", "id")).status());
            assertEquals(NuggetDiscovery.ResolutionStatus.NOT_FOUND,
                    service.resolve(new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_ID, "missing", "missing")).status());
            assertEquals(
                    result.records(),
                    service.discover(new NuggetDiscovery.Query(true, 20, "merge")).records());
        } finally {
            service.close();
        }
    }

    @Test
    public void concurrentScanAndShutdownRemainBounded(@TempDir final Path directory) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        NuggetAdminService service = service(directory, clock, new InMemoryAuditSink(128));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<NuggetDiscovery.Result> scan = executor.submit(() -> service.discover(new NuggetDiscovery.Query(true, 10, "scan")));
            Future<?> close = executor.submit(() -> {
                service.close();
                service.close();
                return null;
            });

            scan.get(5L, TimeUnit.SECONDS);
            close.get(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    public void allTargetOutputIsBoundedAndDeterministic() {
        NuggetAdminRuntimeConfiguration.Builder builder = NuggetAdminRuntimeConfiguration.builder()
                .security(security(new InMemoryAuditSink(64), new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneId.of("UTC")))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetDiscoveryTest::copyPin);
        for (int index = 0; index < 20; index++) {
            builder.discoveryTarget(new NuggetDiscovery.ConfiguredTarget(
                    NuggetDiscovery.EndpointDescriptor.local("target-" + index),
                    "nugget-" + index,
                    "target-" + index));
        }
        NuggetAdminService service = NuggetAdminRuntime.start(RUNTIME_ID, builder.build());
        try {
            NuggetDiscovery.Result first = service.discover(new NuggetDiscovery.Query(true, 5, "bounded"));
            NuggetDiscovery.Result second = service.discover(new NuggetDiscovery.Query(true, 5, "bounded"));

            assertEquals(5, first.returned());
            assertEquals(20, first.totalAvailable());
            assertEquals(first.records(), second.records());
        } finally {
            service.close();
        }
    }

    private static NuggetAdminService configuredService(final NuggetDiscovery.ConfiguredTarget... targets) {
        return configuredServiceWithAudit(new InMemoryAuditSink(64), targets);
    }

    private static NuggetAdminService configuredServiceWithAudit(
            final InMemoryAuditSink audit,
            final NuggetDiscovery.ConfiguredTarget... targets) {
        NuggetAdminRuntimeConfiguration.Builder builder = NuggetAdminRuntimeConfiguration.builder()
                .security(security(audit, new AllowAllAuthorizationPolicy()))
                .clock(Clock.fixed(NOW, ZoneId.of("UTC")))
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetDiscoveryTest::copyPin);
        for (NuggetDiscovery.ConfiguredTarget target : targets) {
            builder.discoveryTarget(target);
        }
        return NuggetAdminRuntime.start(RUNTIME_ID, builder.build());
    }

    private static NuggetAdminService service(
            final Path directory,
            final Clock clock,
            final InMemoryAuditSink audit) {
        NuggetAdminRuntimeConfiguration configuration = NuggetAdminRuntimeConfiguration.builder()
                .security(security(audit, new AllowAllAuthorizationPolicy()))
                .clock(clock)
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", NuggetDiscoveryTest::copyPin)
                .localDiscovery(directory, true)
                .discoveryTiming(Duration.ofSeconds(5L), Duration.ofSeconds(1L))
                .build();
        return NuggetAdminRuntime.start(RUNTIME_ID, configuration);
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit, final AuthorizationPolicy policy) {
        return new SecurityConfiguration(SecurityMode.DEV, policy, audit, new DefaultRedactor(),
                false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())));
    }

    private static char[] copyPin() {
        return java.util.Arrays.copyOf(PIN, PIN.length);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(final Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        private void advance(final Duration duration) {
            this.now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now.get();
        }
    }

    private static final class DenyPolicy implements AuthorizationPolicy {
        @Override
        public String id() {
            return "deny";
        }

        @Override
        public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
            return AuthorizationDecision.deny(this.id(), "denied");
        }
    }
}
