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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.ApplicationManifestParser;
import org.j7m.middleware.application.repository.ApplicationManifestValidator;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.examples.regulated.AuditVerificationResult;
import org.j7m.middleware.examples.regulated.ComplianceSummary;
import org.j7m.middleware.examples.regulated.EvidenceReport;
import org.j7m.middleware.examples.regulated.ExampleCryptoPosture;
import org.j7m.middleware.examples.regulated.RegulatedEnterpriseService;
import org.j7m.middleware.examples.regulated.RegulatedExampleVerifier;
import org.j7m.middleware.examples.streaming.BatchResult;
import org.j7m.middleware.examples.streaming.StreamExchangeResult;
import org.j7m.middleware.examples.streaming.StreamMetrics;
import org.j7m.middleware.examples.streaming.StreamOptions;
import org.j7m.middleware.examples.streaming.StreamSession;
import org.j7m.middleware.examples.streaming.StreamingPipelineService;
import org.j7m.middleware.examples.workload.Checkpoint;
import org.j7m.middleware.examples.workload.LeaseResult;
import org.j7m.middleware.examples.workload.TaskHandle;
import org.j7m.middleware.examples.workload.WorkloadProcessingService;
import org.j7m.middleware.examples.workload.WorkloadTaskProgress;
import org.j7m.middleware.examples.workload.WorkloadTaskStatus;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Corrective Gate 1 tests proving required Phase 5.5 examples are executable
 * deployable applications rather than placeholders.
 */
final class Phase55RequiredExamplesClosureTest {
    private static final List<String> REQUIRED_EXAMPLES = List.of(
            "deployable-application",
            "modular-deployable-application",
            "hello-remote-service",
            "callbacks-and-events",
            "secure-banking-workflow",
            "ha-hp-cluster",
            "workload-processing",
            "streaming-pipeline",
            "regulated-enterprise-app");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void workloadExamplePackagesVerifiesActivatesInvokesStopsAndUnloads() {
        ApplicationBundle bundle = packageExample("workload-processing");
        assertTrue(new ApplicationBundleVerifier().verify(bundle, false).accepted());
        LocalApplicationRepository repository = publish(bundle, "workload-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase55-workload-example")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(ApplicationCoordinate.parse("org.j7m.examples:workload-processing:1.0.0"),
                    new ApplicationActivationRequest("tester", "dev", false, Map.of()));
            WorkloadProcessingService service = runtime.lookup("//apps/example/workload/ProcessingService",
                    WorkloadProcessingService.class, LookupOptions.defaults());
            TaskHandle task = service.submitTask("payload");
            WorkloadTaskProgress progress = service.progress(task);
            Checkpoint checkpoint = service.checkpoint(task);
            LeaseResult lease = service.acquireLease(task, "worker");
            assertFalse(service.completeWithFence(task, lease.fencingToken() - 1L));
            assertTrue(service.completeWithFence(task, lease.fencingToken()));
            TaskHandle cancelledHandle = service.submitTask("cancel");
            assertTrue(service.cancel(cancelledHandle));
            WorkloadTaskStatus cancelled = service.status(cancelledHandle);
            assertTrue(progress.percent() > 0.0D);
            assertEquals(task.taskId(), checkpoint.taskId());
            assertEquals("CANCELLED", cancelled.state());
            assertTrue(service.adminStatus().contains("tasks="));
            manager.deactivate(activation.instanceIdentity().instanceId());
            assertEquals(0, runtime.list("//apps/example/workload/ProcessingService",
                    List.of(WorkloadProcessingService.class.getName()), Map.of(), LookupOptions.defaults()).size());
            manager.unload(activation.instanceIdentity().instanceId());
            assertEquals(0, manager.list(ApplicationQuery.all()).size());
        }
    }

    @Test
    void streamingExamplePackagesVerifiesActivatesInvokesStopsAndUnloads() {
        ApplicationBundle bundle = packageExample("streaming-pipeline");
        assertTrue(new ApplicationBundleVerifier().verify(bundle, false).accepted());
        LocalApplicationRepository repository = publish(bundle, "streaming-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase55-streaming-example")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(ApplicationCoordinate.parse("org.j7m.examples:streaming-pipeline:1.0.0"),
                    new ApplicationActivationRequest("tester", "dev", false, Map.of()));
            StreamingPipelineService service = runtime.lookup("//apps/example/streaming/PipelineService",
                    StreamingPipelineService.class, LookupOptions.defaults());
            StreamSession session = service.openSession(new StreamOptions(8, 2));
            BatchResult accepted = service.submitBatch(session, List.of("a", "b"));
            BatchResult backpressure = service.submitBatch(session, List.of("c"));
            BatchResult oversized = service.submitBatch(session, List.of("too-large-for-limit"));
            List<String> serverItems = service.readBatch(session, 2);
            StreamExchangeResult exchange = service.exchange(session, List.of("d"), 1);
            assertTrue(service.cancel(session));
            StreamMetrics metrics = service.metrics(session);
            assertEquals(2, accepted.accepted());
            assertTrue(backpressure.backpressure());
            assertEquals(1, oversized.rejected());
            assertEquals(2, serverItems.size());
            assertEquals(1, exchange.serverItems().size());
            assertTrue(metrics.cancelled());
            assertTrue(service.adminStatus().contains("sessions="));
            manager.deactivate(activation.instanceIdentity().instanceId());
            assertEquals(0, runtime.list("//apps/example/streaming/PipelineService",
                    List.of(StreamingPipelineService.class.getName()), Map.of(), LookupOptions.defaults()).size());
            manager.unload(activation.instanceIdentity().instanceId());
            assertEquals(0, manager.list(ApplicationQuery.all()).size());
        }
    }

    @Test
    void regulatedExamplePackagesVerifiesRejectsMissingMetadataActivatesInvokesStopsAndUnloads() throws IOException {
        ApplicationBundle bundle = packageExample("regulated-enterprise-app");
        assertTrue(new ApplicationBundleVerifier().verify(bundle, false).accepted());
        RegulatedExampleVerifier verifier = new RegulatedExampleVerifier();
        assertTrue(verifier.verify(bundle).accepted());
        assertFalse(verifier.verify(copyWithoutEntry(bundle, this.temporaryDirectory.resolve("missing-sbom.j7m"),
                RegulatedExampleVerifier.SBOM_ENTRY)).accepted());
        assertFalse(verifier.verify(copyWithoutEntry(bundle, this.temporaryDirectory.resolve("missing-provenance.j7m"),
                J7mBundleReader.PROVENANCE_ENTRY)).accepted());
        LocalApplicationRepository repository = publish(bundle, "regulated-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase55-regulated-example")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(ApplicationCoordinate.parse("org.j7m.examples:regulated-enterprise-app:1.0.0"),
                    new ApplicationActivationRequest("tester", "dev", false, Map.of()));
            RegulatedEnterpriseService service = runtime.lookup("//apps/example/regulated/EnterpriseService",
                    RegulatedEnterpriseService.class, LookupOptions.defaults());
            ComplianceSummary summary = service.complianceSummary();
            EvidenceReport report = service.exportEvidence();
            ExampleCryptoPosture posture = service.cryptoPosture();
            String result = SecurityContexts.call(context("auditor"), () -> service.processControlledRequest("secret-request"));
            AuditVerificationResult audit = service.verifyAuditTrail();
            assertFalse(summary.certificationClaim());
            assertFalse(report.controls().isEmpty());
            assertTrue(posture.strictMode());
            assertFalse(posture.keyMaterialExposed());
            assertTrue(result.contains("auditor"));
            assertTrue(audit.valid());
            manager.deactivate(activation.instanceIdentity().instanceId());
            assertEquals(0, runtime.list("//apps/example/regulated/EnterpriseService",
                    List.of(RegulatedEnterpriseService.class.getName()), Map.of(), LookupOptions.defaults()).size());
            manager.unload(activation.instanceIdentity().instanceId());
            assertEquals(0, manager.list(ApplicationQuery.all()).size());
        }
    }

    @Test
    void requiredExamplesHaveNoPlaceholderMarkersAndHaveParseableManifests() throws IOException {
        for (String example : REQUIRED_EXAMPLES) {
            Path root = projectPath("examples/" + example);
            Path readme = root.resolve("README.md");
            assertTrue(Files.exists(readme), "missing README for " + example);
            String text = Files.readString(readme, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
            assertFalse(text.contains("documentation placeholder"), example);
            assertFalse(text.contains("deferred"), example);
            assertFalse(text.contains("future end-to-end"), example);
            assertFalse(text.contains("future packaged"), example);
            Path manifest = root.resolve("META-INF/j7m-application.json");
            if (Files.exists(manifest)) {
                String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
                assertTrue(new ApplicationManifestValidator().validate(new ApplicationManifestParser().parse(manifestText), false).accepted(),
                        "invalid manifest for " + example);
            }
        }
    }

    private ApplicationBundle packageExample(final String exampleName) {
        return new J7mBundleWriter().write(
                projectPath("examples/" + exampleName + "/META-INF/j7m-application.json"),
                this.temporaryDirectory.resolve(exampleName + ".j7m"),
                List.of(exampleJar()),
                List.of(projectPath("examples/" + exampleName + "/README.md")));
    }

    private LocalApplicationRepository publish(final ApplicationBundle bundle, final String repositoryName) {
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve(repositoryName));
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        return repository;
    }

    private DefaultMiddlewareRuntime runtime(final String runtimeId) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(runtimeId)
                .registry(new EmbeddedRegistryClient(new EmbeddedRegistryStore(), java.time.Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private ApplicationBundle copyWithoutEntry(
            final ApplicationBundle source,
            final Path target,
            final String excludedEntry) throws IOException {
        try (ZipFile zip = new ZipFile(source.path().toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (excludedEntry.equals(entry.getName())) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0L);
                output.putNextEntry(copy);
                output.write(zip.getInputStream(entry).readAllBytes());
                output.closeEntry();
            }
        }
        return new ApplicationBundle(target, source.coordinate(), new J7mBundleReader().sha256(target));
    }

    private SecurityContext context(final String principal) {
        return new SecurityContext(new Principal(principal, principal, Set.of("admin"), Set.of()), "test-runtime",
                new TrustDomain("dev"), new Credential("test", "1", Map.of()), "test-correlation",
                "test-invocation", "local", true, TransportSecurityLevel.TEST_AUTHENTICATED,
                Set.of("admin"), Map.of(), java.time.Instant.now());
    }

    private Path exampleJar() {
        Path jar = projectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
        assertDoesNotThrow(() -> Files.size(jar));
        return jar;
    }

    private Path projectPath(final String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = Path.of("..").resolve(relative).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("missing project path " + relative);
    }
}
