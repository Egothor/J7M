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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.deployer.DefaultApplicationDeployer;
import org.j7m.middleware.deployer.api.ApplicationDeployer;
import org.j7m.middleware.deployer.api.DeactivationOptions;
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.RollbackOptions;
import org.j7m.middleware.deployer.api.UnloadOptions;
import org.j7m.middleware.deployer.api.UpgradeOptions;
import org.j7m.middleware.deployer.api.VerificationOptions;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.security.defaults.RuleBasedAuthorizationPolicy;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Final Phase 5.6 application-platform acceptance audit tests.
 */
final class Phase56ApplicationPlatformAcceptanceTest {
    private static final List<String> REQUIRED_ACCEPTANCE_ROWS = List.of(
            "JPMS module descriptors",
            "ApplicationManager live activation",
            "Unauthorized deploy denied",
            "Workload processing example",
            "Example build validation",
            "Gradle plugin baseline",
            "Deployer CLI baseline",
            "No Phase 6/non-goal work started");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void remoteDeployerOperationsAreDeniedBeforeExecutionForUnauthorizedCaller() {
        ApplicationBundle bundle = helloBundle("hello.j7m");
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("secure-repo"));
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        SecurityConfiguration security = security(audit);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("phase56-secure-deployer-server", store, security);
             DefaultMiddlewareRuntime client = runtime("phase56-secure-deployer-client", store, security)) {
            ApplicationManager manager = new ApplicationManager(server, repository);
            DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
            SecurityContexts.run(context("publisher", Set.of("application-admin")), () -> server.export(
                    "j7m.application.deployer",
                    deployer,
                    ApplicationDeployer.class,
                    new ExportOptions(false, "grpc", "default", List.of(ApplicationDeployer.class.getName()),
                            Map.of("phase", "5.6"), Duration.ofSeconds(30L), Duration.ofSeconds(10L))));

            ApplicationDeployer proxy = SecurityContexts.call(context("viewer", Set.of("application-viewer")),
                    () -> client.lookup("j7m.application.deployer", ApplicationDeployer.class, LookupOptions.defaults()));

            assertDenied(() -> proxy.verify(bundle, VerificationOptions.development()));
            assertDenied(() -> proxy.deploy(bundle, new DeployOptions("viewer", false)));

            repository.publish(bundle, new RepositoryPublishOptions("publisher", false));
            ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:hello-remote-service:1.0.0");
            assertDenied(() -> proxy.activate(coordinate, new ApplicationActivationRequest("viewer", "dev", false, Map.of())));

            ApplicationActivation activation = SecurityContexts.call(context("publisher", Set.of("application-admin")),
                    () -> manager.activate(coordinate, new ApplicationActivationRequest("publisher", "dev", false, Map.of())));
            assertDenied(() -> proxy.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false)));
            assertDenied(() -> proxy.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false)));
            assertDenied(() -> proxy.upgrade(activation.instanceIdentity().instanceId(), coordinate, UpgradeOptions.stopStart()));
            assertDenied(() -> proxy.rollback(activation.instanceIdentity().instanceId(), new RollbackOptions("")));

            assertTrue(audit.eventCount() > 0L);
            assertTrue(audit.recentEvents().stream()
                    .anyMatch(event -> event.decision().toLowerCase(java.util.Locale.ROOT).contains("deny")
                            || event.reason().toLowerCase(java.util.Locale.ROOT).contains("matching allow rule")));
            SecurityContexts.run(context("publisher", Set.of("application-admin")), () -> {
                manager.deactivate(activation.instanceIdentity().instanceId());
                manager.unload(activation.instanceIdentity().instanceId());
            });
        }
    }

    @Test
    void strictUnsignedBundleAndStartupFailureRemainSafe() throws IOException {
        ApplicationBundle unsigned = helloBundle("unsigned.j7m");
        assertFalse(new ApplicationBundleVerifier().verify(unsigned, true).accepted());

        Path badManifest = this.temporaryDirectory.resolve("bad-manifest.json");
        Files.writeString(badManifest, Files.readString(helloManifest(), StandardCharsets.UTF_8)
                .replace("org.j7m.middleware.examples.hello.HelloApplication", "java.lang.String"), StandardCharsets.UTF_8);
        ApplicationBundle bad = new J7mBundleWriter().write(badManifest, this.temporaryDirectory.resolve("bad.j7m"),
                List.of(exampleJar()), List.of(helloReadme()));
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("bad-repo"));
        repository.publish(bad, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase56-failed-start", org.j7m.middleware.security.defaults.SecurityDefaults.development())) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            assertThrows(MiddlewareException.class, () -> manager.activate(bad.coordinate(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));
            assertEquals(0, runtime.list("//apps/example/hello/HelloService",
                    List.of("org.j7m.middleware.examples.hello.HelloService"), Map.of(), LookupOptions.defaults()).size());
            assertTrue(manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).stream()
                    .anyMatch(activation -> "FAILED".equals(activation.metadata().get("lastActivationResult"))));
        }
    }

    @Test
    void phase56DocumentsAcceptanceReleaseReadinessAndExecutableExamples() throws IOException {
        String acceptance = read("docs/internal/05000 - Acceptance and Evidence/phase-5-application-platform-acceptance.md");
        for (String row : REQUIRED_ACCEPTANCE_ROWS) {
            assertTrue(acceptance.contains("| " + row + " |"), "acceptance row missing: " + row);
        }
        assertTrue(acceptance.contains("## Security Audit"));
        assertTrue(acceptance.contains("## Operational Audit"));
        assertFalse(acceptance.contains("| PASS_WITH_LIMITATION |"));
        assertTrue(acceptance.contains("runWorkloadProcessingExample"));
        assertTrue(acceptance.contains("runStreamingPipelineExample"));
        assertTrue(acceptance.contains("runRegulatedEnterpriseAppExample"));
        assertTrue(acceptance.contains("runGradlePluginWorkflowDemo"));
        assertTrue(acceptance.contains("runDeployerCliWorkflowDemo"));
        assertTrue(acceptance.contains("runDeployerCliUpgradeRollbackDemo"));
        assertFalse(acceptance.contains("remain documented placeholders"));
        assertFalse(acceptance.contains("| PARTIAL |"));
        assertFalse(acceptance.contains("| DEFERRED |"));
        assertFalse(acceptance.contains("| FAIL |"));
        assertTrue(acceptance.contains("Application resource limits visible where implemented"));
        assertTrue(acceptance.contains("Current resource usage visible where implemented"));

        String readiness = read("docs/internal/04000 - Engineering Audits/phase-5-application-platform-release-readiness.md");
        assertTrue(readiness.contains("This release is a Phase 5.2-5.6 application-platform baseline."));
        assertTrue(readiness.contains("Known limitations"));
        assertTrue(readiness.contains("Non-goal confirmation"));
        assertTrue(readiness.contains("ACCEPT"));
        assertTrue(readiness.contains("ACCEPT_PENDING_OWNER_COMMIT"));
        assertTrue(readiness.contains("Canary-baseline returns a stable unsupported result"));
        assertTrue(readiness.contains("PASS_WITH_LIMITATION=0"));
        assertTrue(readiness.contains("PARTIAL=0"));
        assertTrue(readiness.contains("DEFERRED=0"));

        String examples = read("docs/internal/01000 - Normative Specifications/examples.md");
        assertFalse(examples.contains("Documentation Placeholders"));
        assertTrue(examples.contains("runWorkloadProcessingExample"));
        assertTrue(examples.contains("runStreamingPipelineExample"));
        assertTrue(examples.contains("runRegulatedEnterpriseAppExample"));
    }

    @Test
    void phase56EvidenceTasksExposeReproducibilityAndSkipControls() throws IOException {
        String build = rootBuildLogic();
        assertTrue(build.contains("generateApplicationPlatformAcceptanceReport"));
        assertTrue(build.contains("generateApplicationPlatformLimitationBurndownReport"));
        assertTrue(build.contains("diagnoseUdpMulticastCapability"));
        assertTrue(build.contains("gitBranch"));
        assertTrue(build.contains("dirtyWorkingTree"));
        assertTrue(build.contains("technicalRecommendation"));
        assertTrue(build.contains("cleanWorkingTreeReleaseSeal"));
        assertTrue(build.contains("ACCEPT_PENDING_OWNER_COMMIT"));
        assertTrue(build.contains("acceptance report claims ACCEPT while the working tree is dirty"));
        assertTrue(build.contains("javaRuntimeVersion"));
        assertTrue(build.contains("osArchitecture"));
        assertTrue(build.contains("validationTaskEvidence"));
        assertTrue(build.contains("validationAggregateEvidence"));
        assertTrue(build.contains("operatorGuidanceCommands"));
        assertTrue(build.contains("exactCommandEvidence"));
        assertTrue(build.contains("AGGREGATE_TASK_STATE"));
        assertTrue(build.contains("GRADLE_TASK_OUTCOME"));
        assertTrue(build.contains("guidanceOnly: true"));
        assertTrue(build.contains("Gradle task outcome evidence is incomplete"));
        assertTrue(build.contains("artifact validation evidence is incomplete"));
        assertTrue(build.contains("generated Markdown does not agree with validation aggregate evidence"));
        assertTrue(build.contains("generated Markdown does not agree with validation task evidence"));
        assertTrue(build.contains("Phase 5 report does not record exact command execution evidence"));
        assertTrue(build.contains("udpMulticastCapability"));
        assertTrue(build.contains("required validation suite contains skipped test"));
        assertTrue(build.contains("taskEvidenceStatus"));
        assertTrue(build.contains("xmlSuiteEvidenceStatus"));
        assertTrue(build.contains("requiredEvidenceStatus"));
        assertTrue(build.contains("deterministic-phase-5-evidence-v1"));
        assertTrue(build.contains("deterministic-phase-5-burndown-v1"));
        assertFalse(build.contains("validationCommands.each { String command -> validationCommandResults.put(command, \"PASS\") }"));
        String phase5Report = Files.readString(projectPath("gradle/build-logic/root/50-application-platform-reports.gradle"),
                StandardCharsets.UTF_8);
        assertFalse(phase5Report.contains("timestamp: java.time.Instant.now().toString()"));
        assertFalse(phase5Report.contains("Validation command results:"));
        assertFalse(phase5Report.contains("validationCommandResults"));
        assertTrue(build.contains("JsonSlurper"));
        assertTrue(build.contains("application-platform-limitation-burndown.json"));
        String phase6Report = Files.readString(projectPath("gradle/build-logic/root/60-sovereign-acceptance-report.gradle"),
                StandardCharsets.UTF_8);
        assertFalse(phase6Report.contains("PASS_EXPECTED_BY_REQUIRED_AUDIT_SEQUENCE"));
        assertFalse(phase6Report.contains("javadocs: \"PASS\""));
        assertFalse(phase6Report.contains("examples: \"PASS\""));
        assertFalse(phase6Report.contains("demos: \"PASS\""));
        assertFalse(phase6Report.contains("securityAudit: \"PASS\""));
        assertFalse(phase6Report.contains("operationsAudit: \"PASS\""));
        assertFalse(phase6Report.contains("reliabilityAudit: \"PASS\""));
        assertTrue(phase6Report.contains("validationCommandGuidance"));
        assertTrue(phase6Report.contains("NOT_CAPTURED_BY_REPORT_GENERATOR"));
        assertTrue(phase6Report.contains("STATIC_POLICY_NO_BLOCKERS"));
        assertTrue(phase6Report.contains("These entries are report-generator evidence states, not synthesized task outcomes."));

        String operations = read("docs/internal/01000 - Normative Specifications/java-25-runtime-notes.md");
        assertTrue(operations.contains("grpc-netty-shaded"));
        assertTrue(operations.contains("Validation Does Not Fail"));
        assertTrue(operations.contains("native"));
        assertTrue(operations.contains("Unsafe"));
    }

    @Test
    void examplesAndNamedModulesDoNotExposeInternalJ7mPackages() throws IOException {
        for (Path root : List.of(projectPath("examples"), projectPath("j7m-examples/src/main/java"))) {
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    assertFalse(text.contains(".internal."), "internal package reference in " + file);
                }
            }
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(projectPath("."))) {
            for (Path descriptor : stream
                    .filter(path -> path.getFileName().toString().equals("module-info.java"))
                    .filter(path -> !path.toString().contains("/build/"))
                    .toList()) {
                String text = Files.readString(descriptor, StandardCharsets.UTF_8);
                assertFalse(text.contains("exports ") && text.contains(".internal"), "internal export in " + descriptor);
            }
        }
    }

    private static void assertDenied(final ThrowingOperation operation) {
        MiddlewareException exception = assertThrows(MiddlewareException.class, () ->
                SecurityContexts.call(context("viewer", Set.of("application-viewer")), () -> {
                    operation.run();
                    return null;
                }));
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, exception.code());
    }

    private ApplicationBundle helloBundle(final String fileName) {
        return new J7mBundleWriter().write(helloManifest(), this.temporaryDirectory.resolve(fileName),
                List.of(exampleJar()), List.of(helloReadme()));
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit) {
        RuleBasedAuthorizationPolicy policy = new RuleBasedAuthorizationPolicy(List.of(
                new RuleBasedAuthorizationPolicy.Rule(Set.of("application-admin"), Set.of(
                        PermissionAction.EXPORT,
                        PermissionAction.LOOKUP,
                        PermissionAction.INVOKE,
                        PermissionAction.UNBIND,
                        PermissionAction.READ_STATUS), ""),
                new RuleBasedAuthorizationPolicy.Rule(Set.of("application-viewer"), Set.of(PermissionAction.LOOKUP, PermissionAction.LIST), "")));
        return new SecurityConfiguration(SecurityMode.DEV, policy, audit,
                new DefaultRedactor(), false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("demo", "phase56", Map.of())));
    }

    private static SecurityContext context(final String principal, final Set<String> roles) {
        return new SecurityContext(new Principal(principal, principal, roles, Set.of()), "phase56-client",
                new TrustDomain("dev"), new Credential("demo", "phase56", Map.of()),
                "correlation-" + principal, "invocation-" + principal + "-" + Instant.now().toEpochMilli(),
                "loopback", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED, roles, Map.of(), Instant.now());
    }

    private DefaultMiddlewareRuntime runtime(final String id, final SecurityConfiguration security) {
        return runtime(id, new EmbeddedRegistryStore(), security);
    }

    private DefaultMiddlewareRuntime runtime(final String id, final EmbeddedRegistryStore store, final SecurityConfiguration security) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .security(security)
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private Path helloManifest() {
        return projectPath("examples/hello-remote-service/META-INF/j7m-application.json");
    }

    private Path helloReadme() {
        return projectPath("examples/hello-remote-service/README.md");
    }

    private Path exampleJar() {
        Path jar = projectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
        assertDoesNotThrow(() -> Files.size(jar));
        return jar;
    }

    private String read(final String relative) throws IOException {
        return Files.readString(projectPath(relative), StandardCharsets.UTF_8);
    }

    private Path projectPath(final String relative) {
        Path path = Path.of(relative);
        if (Files.exists(path)) {
            return path;
        }
        Path parent = Path.of("..").resolve(relative).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("missing project path " + relative);
    }

    private Path repositoryPath(final String relative) {
        Path current = Path.of(".").toAbsolutePath().normalize();
        if (Files.exists(current.resolve("settings.gradle"))) {
            return current.resolve(relative).normalize();
        }
        Path parent = current.getParent();
        if (parent != null && Files.exists(parent.resolve("settings.gradle"))) {
            return parent.resolve(relative).normalize();
        }
        return projectPath(relative);
    }

    private String rootBuildLogic() throws IOException {
        Path root = repositoryPath("");
        StringBuilder builder = new StringBuilder(Files.readString(root.resolve("build.gradle"), StandardCharsets.UTF_8));
        Path rootBuildLogic = root.resolve("gradle/build-logic/root");
        try (java.util.stream.Stream<Path> stream = Files.walk(rootBuildLogic)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".gradle")).sorted().toList()) {
                builder.append('\n').append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return builder.toString();
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
