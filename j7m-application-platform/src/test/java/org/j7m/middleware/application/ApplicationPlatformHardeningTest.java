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

package org.j7m.middleware.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.PreparedObjectRegistration;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.application.api.ActivationIdentity;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.api.ApplicationVersion;
import org.j7m.middleware.application.api.ApplicationRepositoryStatus;
import org.j7m.middleware.application.api.J7mApplication;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.api.ApplicationRepository;
import org.j7m.middleware.application.repository.ApplicationManifestParser;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.repository.DefaultBundleSigner;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SigningKeyPair;
import org.j7m.middleware.deployer.ApplicationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ApplicationPlatformHardeningTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void strictManifestParserRejectsDuplicateKeys() {
        ApplicationManifestParser parser = new ApplicationManifestParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {
                  "schemaVersion": "j7m.application/v1",
                  "schemaVersion": "j7m.application/v1"
                }
                """));
    }

    @Test
    void strictManifestParserRejectsWrongShapedPresentFields() {
        ApplicationManifestParser parser = new ApplicationManifestParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(validManifestJson("shape-modules")
                .replace("\"services\": [", "\"modules\": [\"not-an-object\"],\n                  \"services\": [")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(withServicesValue("shape-services", "{}")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(validManifestJson("shape-boolean")
                .replace("\"confidential\": false", "\"confidential\": \"false\"")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(validManifestJson("shape-integer")
                .replace("\"maxConcurrentInvocations\": 8", "\"maxConcurrentInvocations\": \"eight\"")));
    }

    @Test
    void readerRejectsUnsafeEntryWithoutEchoingHostileName() throws IOException {
        Path bundle = this.temporaryDirectory.resolve("unsafe.j7m");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("../SECRET-PATH-token.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        J7mBundleReader reader = new J7mBundleReader();
        ApplicationBundle handle = new ApplicationBundle(bundle, ApplicationCoordinate.parse("org.j7m.test:unsafe:1.0.0"), "");

        MiddlewareException exception = assertThrows(MiddlewareException.class, () -> reader.readManifest(handle));
        assertFalse(exception.getMessage().contains("SECRET-PATH-token"));
    }

    @Test
    void writerRejectsDuplicateNormalizedBundlePaths() throws IOException {
        Path manifest = this.writeManifest("duplicate-writer");
        Path first = this.temporaryDirectory.resolve("first").resolve("duplicate.jar");
        Path second = this.temporaryDirectory.resolve("second").resolve("duplicate.jar");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "one", StandardCharsets.UTF_8);
        Files.writeString(second, "two", StandardCharsets.UTF_8);

        J7mBundleWriter writer = new J7mBundleWriter();

        assertThrows(MiddlewareException.class, () -> writer.write(
                manifest,
                this.temporaryDirectory.resolve("duplicate.j7m"),
                List.of(first, second),
                List.of()));
    }

    @Test
    void repositoryStatusIsRedactedAndAuditAccessIsBounded() throws IOException {
        Path manifest = this.writeManifest("repository-bounds");
        Path output = this.temporaryDirectory.resolve("repository-bounds.j7m");
        ApplicationBundle unsigned = new J7mBundleWriter().write(manifest, output, List.of(), List.of());
        ApplicationBundle signed = new DefaultBundleSigner().sign(unsigned);
        LocalApplicationRepository repository = new LocalApplicationRepository(
                this.temporaryDirectory.resolve("repo"),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        repository.publish(signed, new RepositoryPublishOptions("tester", false));
        repository.verify(signed.coordinate());

        assertFalse(repository.status().root().toString().contains(this.temporaryDirectory.toString()));
        assertTrue(repository.auditEvents(1).size() <= 1);
    }

    @Test
    void repositoryArtifactSummaryBoundsIrrelevantDirectories() throws IOException {
        LocalApplicationRepository repository = new LocalApplicationRepository(
                this.temporaryDirectory.resolve("summary-dir-bound"),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                limitsWithEntryCount(3));
        Path artifacts = repository.repositoryRoot().resolve("artifacts");
        for (int index = 0; index < 4; index++) {
            Files.createDirectories(artifacts.resolve("noise-" + index));
        }

        ApplicationRepositoryStatus status = repository.status();

        assertEquals("degraded", status.metadata().get("auditChainStatus"));
    }

    @Test
    void repositoryVersionListingBoundsIrrelevantEntries() throws IOException {
        LocalApplicationRepository repository = new LocalApplicationRepository(
                this.temporaryDirectory.resolve("version-list-bound"),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                limitsWithEntryCount(3));
        Path directory = repository.repositoryRoot().resolve("artifacts").resolve("org/j7m/test").resolve("versions");
        Files.createDirectories(directory);
        for (int index = 0; index < 4; index++) {
            Files.writeString(directory.resolve("noise-" + index + ".txt"), "noise", StandardCharsets.UTF_8);
        }

        assertThrows(MiddlewareException.class,
                () -> repository.listVersions(new ApplicationId("org.j7m.test", "versions")));
    }

    @Test
    void repositoryPromotionSummaryBoundsIrrelevantEntries() throws IOException {
        LocalApplicationRepository repository = new LocalApplicationRepository(
                this.temporaryDirectory.resolve("promotion-summary-bound"),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                limitsWithEntryCount(3));
        Path promotions = repository.repositoryRoot().resolve("promotions");
        for (int index = 0; index < 4; index++) {
            Files.writeString(promotions.resolve("noise-" + index + ".txt"), "noise", StandardCharsets.UTF_8);
        }

        ApplicationRepositoryStatus status = repository.status();

        assertEquals("degraded", status.metadata().get("auditChainStatus"));
    }

    @Test
    void repositoryRecoveryBoundsIrrelevantArtifactDirectories() throws IOException {
        Path root = this.temporaryDirectory.resolve("recovery-bound");
        Path artifacts = root.resolve("artifacts");
        Files.createDirectories(artifacts);
        for (int index = 0; index < 4; index++) {
            Files.createDirectories(artifacts.resolve("noise-" + index));
        }

        assertThrows(MiddlewareException.class, () -> new LocalApplicationRepository(
                root,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                limitsWithEntryCount(3)));
    }

    @Test
    void repositoryAuditAppendUsesCheckpointInsteadOfScanningFullLog() throws IOException {
        Path root = this.temporaryDirectory.resolve("checkpoint-repo");
        Path auditDirectory = root.resolve("audit");
        Files.createDirectories(auditDirectory);
        Path audit = auditDirectory.resolve("repository-events.jsonl");
        Files.writeString(audit, "not-json\n", StandardCharsets.UTF_8);
        String zeroHash = "0".repeat(64);
        Files.writeString(auditDirectory.resolve("repository-events.checkpoint.json"), """
                {
                  "schemaVersion": "j7m.repository.audit-checkpoint/v1",
                  "fileSize": "%d",
                  "lastHash": "%s",
                  "degraded": "false"
                }
                """.formatted(Files.size(audit), zeroHash), StandardCharsets.UTF_8);

        LocalApplicationRepository repository = new LocalApplicationRepository(
                root,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        repository.demote(ApplicationCoordinate.parse("org.j7m.test:checkpoint:1.0.0"), "dev");

        String auditText = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(auditText.contains("\"action\":\"demote\""));
        String checkpoint = Files.readString(auditDirectory.resolve("repository-events.checkpoint.json"),
                StandardCharsets.UTF_8);
        assertTrue(checkpoint.contains("\"fileSize\":\"" + Files.size(audit) + "\""));
    }

    @Test
    void repositoryAuditPageSupportsStableContinuationAndEndFlag() throws IOException {
        Path manifest = this.writeManifest("repository-page");
        Path output = this.temporaryDirectory.resolve("repository-page.j7m");
        ApplicationBundle unsigned = new J7mBundleWriter().write(manifest, output, List.of(), List.of());
        ApplicationBundle signed = new DefaultBundleSigner().sign(unsigned);
        LocalApplicationRepository repository = new LocalApplicationRepository(
                this.temporaryDirectory.resolve("page-repo"),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        repository.publish(signed, new RepositoryPublishOptions("tester", false));
        repository.verify(signed.coordinate());

        LocalApplicationRepository.AuditPage first = repository.auditEventsPage(1);
        assertEquals(1, first.events().size());
        assertFalse(first.end());
        assertTrue(first.nextCursor() > 0L);

        LocalApplicationRepository.AuditPage second = repository.auditEventsPage(first.nextCursor(), 1);
        assertEquals(1, second.events().size());
        assertTrue(second.end());
        assertTrue(second.nextCursor() > first.nextCursor());
    }

    @Test
    void applicationManagerDrainIsIndependentAndIdempotent() throws Exception {
        ApplicationManager manager = new ApplicationManager(new TestRuntime(), new TestRepository());
        ApplicationInstanceId instanceId = new ApplicationInstanceId("app-drain-test");
        CountingApplication application = new CountingApplication();
        seedManagedActivation(manager, instanceId, application);

        ApplicationDeploymentResult drained = manager.drain(instanceId);
        assertTrue(drained.success());
        assertEquals(ApplicationRunState.DRAINING, drained.state());
        assertEquals(1, application.drainCount.get());
        assertEquals(0, application.stopCount.get());
        ApplicationActivation snapshot = manager.list(null).getFirst();
        assertEquals(ApplicationRunState.DRAINING, snapshot.state());
        assertEquals("DRAINING", snapshot.metadata().get("lastActivationResult"));

        ApplicationDeploymentResult secondDrain = manager.drain(instanceId);
        assertTrue(secondDrain.success());
        assertEquals(ApplicationRunState.DRAINING, secondDrain.state());
        assertEquals(1, application.drainCount.get());

        ApplicationDeploymentResult stopped = manager.deactivate(instanceId);
        assertTrue(stopped.success());
        assertEquals(ApplicationRunState.STOPPED, stopped.state());
        assertEquals(1, application.drainCount.get());
        assertEquals(1, application.stopCount.get());
    }

    @Test
    void applicationManagerUnloadAllowsOnlyStoppedAndFailedStates() throws Exception {
        List<ApplicationRunState> invalidStates = List.of(
                ApplicationRunState.PACKAGED,
                ApplicationRunState.VERIFIED,
                ApplicationRunState.DEPLOYED,
                ApplicationRunState.PLANNED,
                ApplicationRunState.LOADING,
                ApplicationRunState.LOADED,
                ApplicationRunState.STARTING,
                ApplicationRunState.RUNNING,
                ApplicationRunState.UPGRADING,
                ApplicationRunState.ROLLING_BACK,
                ApplicationRunState.DRAINING,
                ApplicationRunState.STOPPING,
                ApplicationRunState.UNLOADING,
                ApplicationRunState.REJECTED);
        for (ApplicationRunState state : invalidStates) {
            ApplicationManager manager = new ApplicationManager(new TestRuntime(), new TestRepository());
            ApplicationInstanceId instanceId = new ApplicationInstanceId("app-unload-invalid-" + state.name());
            seedManagedActivation(manager, instanceId, new CountingApplication(), state);

            MiddlewareException exception = assertThrows(MiddlewareException.class, () -> manager.unload(instanceId));

            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, exception.code());
        }

        ApplicationManager stoppedManager = new ApplicationManager(new TestRuntime(), new TestRepository());
        ApplicationInstanceId stoppedId = new ApplicationInstanceId("app-unload-stopped");
        seedManagedActivation(stoppedManager, stoppedId, new CountingApplication(), ApplicationRunState.STOPPED);
        assertEquals(ApplicationRunState.UNLOADED, stoppedManager.unload(stoppedId).state());

        ApplicationManager failedManager = new ApplicationManager(new TestRuntime(), new TestRepository());
        ApplicationInstanceId failedId = new ApplicationInstanceId("app-unload-failed");
        seedManagedActivation(failedManager, failedId, new CountingApplication(), ApplicationRunState.FAILED);
        assertEquals(ApplicationRunState.UNLOADED, failedManager.unload(failedId).state());
    }

    @Test
    void applicationManagerSerializesConflictingSameInstanceLifecycleOperations() throws Exception {
        ApplicationManager manager = new ApplicationManager(new TestRuntime(), new TestRepository());
        ApplicationInstanceId instanceId = new ApplicationInstanceId("app-serialized-lifecycle");
        BlockingDrainApplication application = new BlockingDrainApplication();
        seedManagedActivation(manager, instanceId, application);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationDeploymentResult> drain = executor.submit(() -> manager.drain(instanceId));
            assertTrue(application.drainEntered.await(5L, TimeUnit.SECONDS));

            Future<ApplicationDeploymentResult> stop = executor.submit(() -> manager.deactivate(instanceId));
            application.releaseDrain.countDown();

            assertEquals(ApplicationRunState.DRAINING, drain.get(5L, TimeUnit.SECONDS).state());
            assertEquals(ApplicationRunState.STOPPED, stop.get(5L, TimeUnit.SECONDS).state());
        }
        assertFalse(application.stopBeforeDrainReleased.get());
        assertEquals(1, application.drainCount.get());
        assertEquals(1, application.stopCount.get());
    }

    @Test
    void repositoryAuditPagingRejectsOversizedAndMalformedLinesWithoutUnsafeContent() throws IOException {
        Path root = this.temporaryDirectory.resolve("audit-corrupt-repo");
        LocalApplicationRepository repository = new LocalApplicationRepository(root);
        Path audit = root.resolve("audit").resolve("repository-events.jsonl");
        int maxLineBytes = ApplicationPlatformLimits.defaults().maxAuditLineBytes();

        Files.writeString(audit, "x".repeat(maxLineBytes + 1), StandardCharsets.UTF_8);
        assertThrows(MiddlewareException.class, () -> repository.auditEventsPage(0L, 1));
        assertFalse(repository.verifyAuditChain());

        Files.write(audit, new byte[] {(byte) 0xc3, (byte) 0x28, (byte) '\n'});
        assertThrows(MiddlewareException.class, () -> repository.auditEventsPage(0L, 1));
        assertFalse(repository.verifyAuditChain());

        Files.writeString(audit, "x".repeat(maxLineBytes + 1) + "\n{\"safe\":true}\n", StandardCharsets.UTF_8);
        MiddlewareException exception = assertThrows(MiddlewareException.class, () -> repository.auditEventsPage(0L, 1));
        assertFalse(exception.getMessage().contains("x".repeat(128)));
    }

    @Test
    void repositoryAuditPagingHandlesCrlfAndEofLineLimits() throws IOException {
        Path root = this.temporaryDirectory.resolve("audit-crlf-repo");
        LocalApplicationRepository repository = new LocalApplicationRepository(root);
        repository.demote(ApplicationCoordinate.parse("org.j7m.test:crlf:1.0.0"), "dev");
        repository.demote(ApplicationCoordinate.parse("org.j7m.test:crlf:1.0.0"), "qa");
        Path audit = root.resolve("audit").resolve("repository-events.jsonl");
        String crlf = Files.readString(audit, StandardCharsets.UTF_8).replace("\n", "\r\n");
        Files.writeString(audit, crlf, StandardCharsets.UTF_8);

        LocalApplicationRepository.AuditPage first = repository.auditEventsPage(0L, 1);
        assertEquals(1, first.events().size());
        assertFalse(first.events().getFirst().endsWith("\r"));
        LocalApplicationRepository.AuditPage second = repository.auditEventsPage(first.nextCursor(), 1);
        assertEquals(1, second.events().size());
        assertTrue(second.end());

        int maxLineBytes = ApplicationPlatformLimits.defaults().maxAuditLineBytes();
        Files.writeString(audit, "a".repeat(maxLineBytes), StandardCharsets.UTF_8);
        LocalApplicationRepository.AuditPage exact = repository.auditEventsPage(0L, 1);
        assertEquals("a".repeat(maxLineBytes), exact.events().getFirst());
        assertTrue(exact.end());

        Files.writeString(audit, "a".repeat(maxLineBytes + 1), StandardCharsets.UTF_8);
        assertThrows(MiddlewareException.class, () -> repository.auditEventsPage(0L, 1));
    }

    @Test
    void repositoryAuditPagingHonorsInterruptWithoutClearingStatus() throws IOException {
        Path root = this.temporaryDirectory.resolve("audit-interrupt-repo");
        LocalApplicationRepository repository = new LocalApplicationRepository(root);
        repository.demote(ApplicationCoordinate.parse("org.j7m.test:interrupt:1.0.0"), "dev");

        Thread.currentThread().interrupt();
        try {
            MiddlewareException exception = assertThrows(MiddlewareException.class,
                    () -> repository.auditEventsPage(0L, 1));

            assertEquals(org.j7m.middleware.api.MiddlewareErrorCode.CANCELLED, exception.code());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void repositoryAuditAppendRejectsOversizedGeneratedRecord() throws Exception {
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-append-repo"));
        Method audit = LocalApplicationRepository.class.getDeclaredMethod(
                "audit", String.class, ApplicationCoordinate.class, String.class);
        audit.setAccessible(true);
        InvocationTargetException exception = assertThrows(InvocationTargetException.class, () -> audit.invoke(
                repository,
                "publish",
                ApplicationCoordinate.parse("org.j7m.test:oversized-audit:1.0.0"),
                "x".repeat(ApplicationPlatformLimits.defaults().maxAuditLineBytes() + 1)));
        assertTrue(exception.getCause() instanceof MiddlewareException);
    }

    @Test
    void payloadAccountingRejectsUnsupportedObjectsWithoutCallingToString() throws Exception {
        Method estimate = ApplicationManager.class.getDeclaredMethod("estimatePayloadBytes", Object[].class, int.class);
        estimate.setAccessible(true);
        AtomicInteger calls = new AtomicInteger();
        Object hostile = new Object() {
            @Override
            public String toString() {
                calls.incrementAndGet();
                throw new AssertionError("toString must not be called");
            }
        };

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> estimate.invoke(null, new Object[] {new Object[] {hostile}}, 64));

        assertTrue(exception.getCause() instanceof MiddlewareException);
        assertEquals(0, calls.get());
    }

    @Test
    void payloadAccountingCountsSupportedRecordComponentsWithoutCallingAccessors() throws Exception {
        Method estimate = ApplicationManager.class.getDeclaredMethod("estimatePayloadBytes", Object[].class, int.class);
        estimate.setAccessible(true);

        int bytes = (Integer) estimate.invoke(null, new Object[] {new Object[] {new CountedRecord(3, 7L)}}, 128);

        assertEquals(28, bytes);
    }

    @Test
    void payloadAccountingRejectsUnsupportedRecordComponentsWithoutCallingAccessors() throws Exception {
        Method estimate = ApplicationManager.class.getDeclaredMethod("estimatePayloadBytes", Object[].class, int.class);
        estimate.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> estimate.invoke(null, new Object[] {new Object[] {new UnsupportedRecord(new StringBuilder("SECRET"))}}, 128));

        assertTrue(exception.getCause() instanceof MiddlewareException);
    }

    @Test
    void payloadAccountingCountsStringRecordComponentsStructurallyWithoutCallingAccessors() throws Exception {
        Method estimate = ApplicationManager.class.getDeclaredMethod("estimatePayloadBytes", Object[].class, int.class);
        estimate.setAccessible(true);

        int bytes = (Integer) estimate.invoke(null, new Object[] {new Object[] {new StringRecord("x".repeat(128))}}, 32);

        assertTrue(bytes > 32);
    }

    @Test
    void payloadAccountingRejectsRepeatedNonCyclicContainerIdentity() throws Exception {
        Method estimate = ApplicationManager.class.getDeclaredMethod("estimatePayloadBytes", Object[].class, int.class);
        estimate.setAccessible(true);
        List<String> shared = List.of("x");

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> estimate.invoke(null, new Object[] {new Object[] {shared, shared}}, 128));

        assertTrue(exception.getCause() instanceof MiddlewareException);
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, ((MiddlewareException) exception.getCause()).code());
    }

    @Test
    void payloadAccountingRejectsCyclicContainerIdentity() throws Exception {
        Method estimate = ApplicationManager.class.getDeclaredMethod("estimatePayloadBytes", Object[].class, int.class);
        estimate.setAccessible(true);
        java.util.ArrayList<Object> cyclic = new java.util.ArrayList<>();
        cyclic.add(cyclic);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> estimate.invoke(null, new Object[] {new Object[] {cyclic}}, 128));

        assertTrue(exception.getCause() instanceof MiddlewareException);
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, ((MiddlewareException) exception.getCause()).code());
    }

    @Test
    void jsonRejectsLoneSurrogatesAndAcceptsValidPairs() {
        ApplicationManifestParser parser = new ApplicationManifestParser();
        assertEquals("Pair 😀", parser.parse(validManifestJson("json-pair")
                .replace("Hardening Test", "Pair \\uD83D\\uDE00")).identity().displayName());
        assertEquals("Raw 😀", parser.parse(validManifestJson("json-raw-pair")
                .replace("Hardening Test", "Raw 😀")).identity().displayName());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(validManifestJson("json-high")
                .replace("Hardening Test", "\\uD83D")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(validManifestJson("json-low")
                .replace("Hardening Test", "\\uDE00")));
        String loneHigh = new String(new char[] {'"', Character.highSurrogate(0x1f600), '"'});
        assertThrows(IllegalArgumentException.class, () -> parser.parse(withDisplayName("json-raw-high", loneHigh)));
    }

    @Test
    void readerRejectsZeroCompressedNonEmptyEntryBeforeInflation() throws IOException {
        Path bundle = this.temporaryDirectory.resolve("zero-compressed.j7m");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("META-INF/j7m-application.json"));
            zip.write(validManifestJson("zero-compressed").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        forceCentralDirectoryCompressedSize(bundle, "META-INF/j7m-application.json", 0);
        J7mBundleReader reader = new J7mBundleReader();
        ApplicationBundle handle = new ApplicationBundle(bundle,
                ApplicationCoordinate.parse("org.j7m.test:zero-compressed:1.0.0"), "");

        assertThrows(MiddlewareException.class, () -> reader.readManifest(handle));
    }

    @Test
    void verifierFindingsDoNotIncludeArbitraryExceptionText() throws IOException {
        Path bundle = this.temporaryDirectory.resolve("verifier-hostile.j7m");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("../SECRET-SENTINEL-token.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ApplicationBundle handle = new ApplicationBundle(bundle,
                ApplicationCoordinate.parse("org.j7m.test:hostile:1.0.0"), "");

        String findings = new org.j7m.middleware.application.repository.ApplicationBundleVerifier()
                .verify(handle, false)
                .messages()
                .toString();

        assertFalse(findings.contains("SECRET-SENTINEL"));
        assertTrue(findings.contains("verification error:"));
    }

    @Test
    void signingKeyPairToStringIsRedacted() {
        String text = SigningKeyPair.localDevelopment().toString();

        assertTrue(text.contains("<redacted>"));
        assertFalse(text.contains("Private"));
        assertFalse(text.contains("Public"));
        assertFalse(text.contains("Ed25519"));
        assertFalse(text.contains("ed25519"));
        assertFalse(text.contains("@"));
    }

    @Test
    void applicationManagerSafeDiagnosticsDoNotEchoExceptionText() throws Exception {
        Method safeMessage = ApplicationManager.class.getDeclaredMethod("safeMessage", Throwable.class);
        safeMessage.setAccessible(true);
        String text = (String) safeMessage.invoke(null,
                new IOException("/tmp/SECRET-SENTINEL/private-key/provider SunEC failure"));

        assertEquals("application I/O operation failed", text);
        assertFalse(text.contains("SECRET-SENTINEL"));
        assertFalse(text.contains("/tmp"));
        assertFalse(text.contains("SunEC"));
        assertFalse(text.contains("private-key"));
    }

    private Path writeManifest(final String name) throws IOException {
        Path manifest = this.temporaryDirectory.resolve(name).resolve("META-INF").resolve("j7m-application.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, validManifestJson(name), StandardCharsets.UTF_8);
        return manifest;
    }

    private static void seedManagedActivation(
            final ApplicationManager manager,
            final ApplicationInstanceId instanceId,
            final J7mApplication application) throws Exception {
        seedManagedActivation(manager, instanceId, application, ApplicationRunState.RUNNING);
    }

    private static void seedManagedActivation(
            final ApplicationManager manager,
            final ApplicationInstanceId instanceId,
            final J7mApplication application,
            final ApplicationRunState state) throws Exception {
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.test:drain:1.0.0");
        ApplicationIdentity applicationIdentity = new ApplicationIdentity(coordinate, "Drain Test", "dev");
        ApplicationInstanceIdentity instanceIdentity = new ApplicationInstanceIdentity(
                instanceId,
                applicationIdentity,
                new RuntimeIdentity("runtime-drain-test", "default", Map.of()),
                "CLASSPATH");
        Class<?> activationType = Class.forName("org.j7m.middleware.deployer.ApplicationManager$ManagedActivation");
        Constructor<?> constructor = activationType.getDeclaredConstructor(ActivationIdentity.class, ApplicationInstanceIdentity.class);
        constructor.setAccessible(true);
        Object activation = constructor.newInstance(new ActivationIdentity("activation-drain-test"), instanceIdentity);
        setField(activationType, activation, "application", application);
        setField(activationType, activation, "state", state);
        setField(activationType, activation, "acceptingInvocations", Boolean.TRUE);
        Field activations = ApplicationManager.class.getDeclaredField("activations");
        activations.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, Object> values = (ConcurrentMap<String, Object>) activations.get(manager);
        values.put(instanceId.value(), activation);
    }

    private static void setField(
            final Class<?> type,
            final Object target,
            final String name,
            final Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String validManifestJson(final String name) {
        return """
                {
                  "schemaVersion": "j7m.application/v1",
                  "application": {
                    "group": "org.j7m.test",
                    "name": "%s",
                    "version": "1.0.0",
                    "displayName": "Hardening Test",
                    "description": "Hardening test"
                  },
                  "runtime": {
                    "minJ7mVersion": "0.7.0",
                    "javaVersion": "25",
                    "moduleMode": "CLASSPATH",
                    "bootstrapClass": "org.j7m.test.Application"
                  },
                  "services": [
                    {
                      "serviceName": "//apps/test/%s/Service",
                      "interfaceName": "org.j7m.test.Service",
                      "implementationClass": "org.j7m.test.ServiceImpl",
                      "transport": "local",
                      "globallyExported": false,
                      "metadata": {
                        "domain": "test"
                      }
                    }
                  ],
                  "security": {
                    "trustDomain": "dev",
                    "confidential": false
                  },
                  "resources": {
                    "cpu": "1",
                    "memory": "128MiB",
                    "maxConcurrentInvocations": 8,
                    "maxInboundPayload": "1MiB"
                  }
                }
                """.formatted(name, name);
    }

    private static String withServicesValue(final String name, final String servicesValue) {
        String json = validManifestJson(name);
        int start = json.indexOf("\"services\"");
        int end = json.indexOf("\"security\"", start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("test manifest layout changed");
        }
        int lineStart = json.lastIndexOf('\n', start) + 1;
        return json.substring(0, lineStart)
                + "  \"services\": " + servicesValue + ",\n"
                + json.substring(end);
    }

    private static String withDisplayName(final String name, final String displayNameJsonValue) {
        return validManifestJson(name).replace("\"displayName\": \"Hardening Test\"", "\"displayName\": " + displayNameJsonValue);
    }

    private static void forceCentralDirectoryCompressedSize(
            final Path bundle,
            final String entryName,
            final int compressedSize) throws IOException {
        byte[] bytes = Files.readAllBytes(bundle);
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < bytes.length - 46; index++) {
            if (bytes[index] == 0x50 && bytes[index + 1] == 0x4b && bytes[index + 2] == 0x01 && bytes[index + 3] == 0x02) {
                int nameLength = Short.toUnsignedInt(ByteBuffer.wrap(bytes, index + 28, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
                if (nameLength == name.length && matches(bytes, index + 46, name)) {
                    ByteBuffer.wrap(bytes, index + 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(compressedSize);
                    Files.write(bundle, bytes);
                    return;
                }
            }
        }
        throw new IOException("central directory entry not found");
    }

    private static boolean matches(final byte[] bytes, final int offset, final byte[] expected) {
        if (offset + expected.length > bytes.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static ApplicationPlatformLimits limitsWithEntryCount(final int maxEntryCount) {
        ApplicationPlatformLimits defaults = ApplicationPlatformLimits.defaults();
        return new ApplicationPlatformLimits(
                defaults.maxBundleBytes(),
                defaults.maxCompressedBytes(),
                defaults.maxEntryExpandedBytes(),
                defaults.maxAggregateExpandedBytes(),
                defaults.maxMetadataBytes(),
                defaults.maxManifestBytes(),
                defaults.maxChecksumBytes(),
                defaults.maxSignatureBytes(),
                maxEntryCount,
                defaults.maxEntryNameBytes(),
                defaults.maxEntryPathDepth(),
                defaults.maxCompressionRatio(),
                defaults.ioBufferBytes(),
                defaults.maxAuditEventsPerPage(),
                defaults.maxAuditLineBytes(),
                defaults.maxReplicationChunks());
    }

    private static final class CountingApplication implements J7mApplication {
        private final AtomicInteger drainCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public void initialize(final ApplicationContext context) {
        }

        @Override
        public void start(final ApplicationContext context) {
        }

        @Override
        public void drain(final ApplicationContext context) {
            this.drainCount.incrementAndGet();
        }

        @Override
        public void stop(final ApplicationContext context) {
            this.stopCount.incrementAndGet();
        }
    }

    private static final class BlockingDrainApplication implements J7mApplication {
        private final CountDownLatch drainEntered = new CountDownLatch(1);
        private final CountDownLatch releaseDrain = new CountDownLatch(1);
        private final AtomicBoolean drainReleased = new AtomicBoolean();
        private final AtomicBoolean stopBeforeDrainReleased = new AtomicBoolean();
        private final AtomicInteger drainCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public void initialize(final ApplicationContext context) {
        }

        @Override
        public void start(final ApplicationContext context) {
        }

        @Override
        public void drain(final ApplicationContext context) {
            this.drainCount.incrementAndGet();
            this.drainEntered.countDown();
            try {
                boolean released = this.releaseDrain.await(5L, TimeUnit.SECONDS);
                this.drainReleased.set(released);
                if (!released) {
                    throw new IllegalStateException("test drain hook was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test drain hook was interrupted", exception);
            }
        }

        @Override
        public void stop(final ApplicationContext context) {
            if (!this.drainReleased.get()) {
                this.stopBeforeDrainReleased.set(true);
            }
            this.stopCount.incrementAndGet();
        }
    }

    private static final class TestRuntime implements MiddlewareRuntime {
        private final RuntimeIdentity identity = new RuntimeIdentity("runtime-drain-test", "default", Map.of());

        @Override
        public RuntimeIdentity identity() {
            return this.identity;
        }

        @Override
        public <T> ObjectRegistration<T> export(
                final String serviceName,
                final T instance,
                final Class<T> primaryInterface,
                final ExportOptions options) {
            throw new UnsupportedOperationException("export is not used by this test");
        }

        @Override
        public <T> PreparedObjectRegistration<T> prepareExport(
                final String serviceName,
                final T instance,
                final Class<T> primaryInterface,
                final ExportOptions options) {
            throw new UnsupportedOperationException("prepareExport is not used by this test");
        }

        @Override
        public List<ObjectRegistration<?>> commitPreparedExports(
                final List<? extends PreparedObjectRegistration<?>> preparedExports) {
            throw new UnsupportedOperationException("commitPreparedExports is not used by this test");
        }

        @Override
        public <T> T lookup(final String serviceName, final Class<T> api, final LookupOptions options) {
            throw new UnsupportedOperationException("lookup is not used by this test");
        }

        @Override
        public List<ObjectRef> list(
                final String serviceName,
                final List<String> interfaces,
                final Map<String, String> requiredParams,
                final LookupOptions options) {
            return List.of();
        }

        @Override
        public RuntimeStatus status() {
            return new RuntimeStatus(this.identity, List.of(), List.of(), List.of(), null, 0L, 0L, List.of(), Map.of());
        }

        @Override
        public void close() {
        }
    }

    private static final class TestRepository implements ApplicationRepository {
        @Override
        public ApplicationDeploymentResult publish(final ApplicationBundle bundle, final RepositoryPublishOptions options) {
            throw new UnsupportedOperationException("publish is not used by this test");
        }

        @Override
        public ApplicationBundle fetch(final ApplicationCoordinate coordinate) {
            throw new UnsupportedOperationException("fetch is not used by this test");
        }

        @Override
        public List<ApplicationVersion> listVersions(final ApplicationId applicationId) {
            return List.of();
        }

        @Override
        public ApplicationVerificationResult verify(final ApplicationCoordinate coordinate) {
            return ApplicationVerificationResult.accepted(coordinate, false, List.of());
        }

        @Override
        public ApplicationDeploymentResult promote(final ApplicationCoordinate coordinate, final String environment) {
            throw new UnsupportedOperationException("promote is not used by this test");
        }

        @Override
        public ApplicationDeploymentResult demote(final ApplicationCoordinate coordinate, final String environment) {
            throw new UnsupportedOperationException("demote is not used by this test");
        }

        @Override
        public ApplicationRepositoryStatus status() {
            return new ApplicationRepositoryStatus(Path.of("<test>"), 0, 0, Map.of());
        }
    }

    private record CountedRecord(int value, long total) {
        @Override
        public int value() {
            throw new AssertionError("record accessor must not be called");
        }

        @Override
        public long total() {
            throw new AssertionError("record accessor must not be called");
        }
    }

    private record StringRecord(String value) {
        @Override
        public String value() {
            throw new AssertionError("record accessor must not be called");
        }
    }

    private record UnsupportedRecord(StringBuilder value) {
        @Override
        public StringBuilder value() {
            throw new AssertionError("record accessor must not be called");
        }
    }
}
