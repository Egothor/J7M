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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.j7m.middleware.admin.RuntimeAdmin;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.ApplicationManifestParser;
import org.j7m.middleware.application.repository.ApplicationManifestValidator;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.examples.banking.ApprovalResult;
import org.j7m.middleware.examples.banking.BankingApprovalService;
import org.j7m.middleware.examples.banking.Money;
import org.j7m.middleware.examples.banking.PaymentId;
import org.j7m.middleware.examples.cluster.ClusteredGreetingService;
import org.j7m.middleware.examples.cluster.ClusteredGreetingServiceImpl;
import org.j7m.middleware.examples.events.EventService;
import org.j7m.middleware.examples.events.EventServiceImpl;
import org.j7m.middleware.examples.events.RecordingEventCallback;
import org.j7m.middleware.examples.hello.HelloService;
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
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.security.defaults.RuleBasedAuthorizationPolicy;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5.5 developer experience and examples foundation tests.
 */
final class Phase55DeveloperExperienceTest {
    private static final List<String> README_SECTIONS = List.of(
            "Purpose",
            "Architecture flow",
            "Prerequisites",
            "Build",
            "Test",
            "Package",
            "Verify bundle",
            "Deploy to local repository",
            "Start / activate",
            "Invoke / verify behavior",
            "Status / observability",
            "Stop / unload",
            "Upgrade / rollback, where applicable",
            "Expected output",
            "Security notes",
            "Cleanup",
            "Known limitations");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void templatesHaveRequiredFilesAndParseableManifests() throws IOException {
        Map<String, Boolean> templates = Map.of(
                "simple-service", false,
                "secure-service", true,
                "workload-app", false,
                "streaming-app", false,
                "deployable-app", true);
        for (Map.Entry<String, Boolean> entry : templates.entrySet()) {
            Path root = projectPath("examples/templates/" + entry.getKey());
            assertTrue(Files.exists(root.resolve("build.gradle")));
            assertTrue(Files.exists(root.resolve("META-INF/j7m-application.json")));
            assertTrue(Files.exists(root.resolve("README.md")));
            if (entry.getValue()) {
                assertTrue(Files.exists(root.resolve("src/main/java/module-info.java")), "missing module-info in " + entry.getKey());
            }
            String manifest = Files.readString(root.resolve("META-INF/j7m-application.json"), StandardCharsets.UTF_8);
            assertTrue(new ApplicationManifestValidator().validate(new ApplicationManifestParser().parse(manifest), false).accepted(),
                    "invalid manifest for " + entry.getKey());
            assertReadmeSections(root.resolve("README.md"));
        }
    }

    @Test
    void exampleReadmesHaveOperationalSections() throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(projectPath("examples"))) {
            for (Path example : stream.filter(Files::isDirectory).filter(path -> !path.getFileName().toString().equals("build")).toList()) {
                if (example.getFileName().toString().equals("templates")) {
                    continue;
                }
                assertTrue(Files.exists(example.resolve("README.md")), "missing README for " + example);
                assertReadmeSections(example.resolve("README.md"));
            }
        }
    }

    @Test
    void helloRemoteServicePackagesActivatesInvokesAndUnloads() {
        ApplicationBundle bundle = new J7mBundleWriter().write(
                projectPath("examples/hello-remote-service/META-INF/j7m-application.json"),
                this.temporaryDirectory.resolve("hello-remote-service.j7m"),
                List.of(exampleJar()),
                List.of(projectPath("examples/hello-remote-service/README.md")));
        assertTrue(new ApplicationBundleVerifier().verify(bundle, false).accepted());
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("hello-repo"));
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase55-hello", new EmbeddedRegistryStore(), "grpc")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(ApplicationCoordinate.parse("org.j7m.examples:hello-remote-service:1.0.0"),
                    new ApplicationActivationRequest("tester", "dev", false, Map.of()));
            HelloService service = runtime.lookup("//apps/example/hello/HelloService", HelloService.class, LookupOptions.defaults());
            assertTrue(service.hello("developer").contains("hello developer"));
            assertTrue(manager.deactivate(activation.instanceIdentity().instanceId()).success());
            assertEquals(0, runtime.list("//apps/example/hello/HelloService", List.of(HelloService.class.getName()), Map.of(), LookupOptions.defaults()).size());
            assertTrue(manager.unload(activation.instanceIdentity().instanceId()).success());
            assertEquals(0, manager.list(ApplicationQuery.all()).size());
        }
    }

    @Test
    void callbackReferencePassingWorksOverGrpcAndRmi() {
        assertCallbackReferencePassing("grpc");
        assertCallbackReferencePassing("rmi");
    }

    @Test
    void secureBankingWorkflowEnforcesRoleRedactsAndClearsContext() {
        ApplicationBundle bundle = new J7mBundleWriter().write(
                projectPath("examples/secure-banking-workflow/META-INF/j7m-application.json"),
                this.temporaryDirectory.resolve("secure-banking-workflow.j7m"),
                List.of(exampleJar()),
                List.of(projectPath("examples/secure-banking-workflow/README.md")));
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        SecurityConfiguration security = security(audit);
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("banking-repo"));
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase55-banking", new EmbeddedRegistryStore(), "grpc", security)) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = SecurityContexts.call(context("publisher", "dev", Set.of("banking-approver")),
                    () -> manager.activate(ApplicationCoordinate.parse("org.j7m.examples:secure-banking-workflow:1.0.0"),
                            new ApplicationActivationRequest("publisher", "dev", false, Map.of())));
            BankingApprovalService service = SecurityContexts.call(context("alice", "dev", Set.of("banking-approver")),
                    () -> runtime.lookup("//apps/example/banking/ApprovalService",
                            BankingApprovalService.class, LookupOptions.defaults()));
            ApprovalResult result = SecurityContexts.call(context("alice", "dev", Set.of("banking-approver")),
                    () -> service.approvePayment(new PaymentId("pay-1"), new Money("USD", 12_34L)));
            assertTrue(result.approved());
            assertEquals("alice", result.approver());
            assertFalse(result.correlationId().isBlank());
            MiddlewareException denied = assertThrows(MiddlewareException.class,
                    () -> SecurityContexts.call(context("mallory", "dev", Set.of("viewer")),
                            () -> runtime.lookup("//apps/example/banking/ApprovalService",
                                    BankingApprovalService.class, LookupOptions.defaults())
                                    .approvePayment(new PaymentId("pay-2"), new Money("USD", 99_99L))));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, denied.code());
            String applicationAudit = SecurityContexts.call(context("alice", "dev", Set.of("banking-approver")), service::auditLog).toString();
            assertTrue(applicationAudit.contains("<redacted>"));
            assertFalse(applicationAudit.contains("1234"));
            assertFalse(applicationAudit.contains("9999"));
            assertEquals("", J7mContext.currentInvocation().correlationId());
            SecurityContexts.run(context("alice", "dev", Set.of("banking-approver")), () -> {
                manager.deactivate(activation.instanceIdentity().instanceId());
                manager.unload(activation.instanceIdentity().instanceId());
            });
        }
    }

    @Test
    void haHpClusterExampleFailsOverAndReportsStatus() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime runtimeA = runtime("phase55-cluster-a", store, "grpc");
             DefaultMiddlewareRuntime runtimeB = runtime("phase55-cluster-b", store, "grpc");
             DefaultMiddlewareRuntime client = runtime("phase55-cluster-client", store, "grpc")) {
            ObjectRegistration<ClusteredGreetingService> first = runtimeA.export("example.cluster.greeting",
                    new ClusteredGreetingServiceImpl("cluster-a"), ClusteredGreetingService.class,
                    export("grpc", Map.of("role", "primary", "load", "0.0")));
            runtimeB.export("example.cluster.greeting",
                    new ClusteredGreetingServiceImpl("cluster-b"), ClusteredGreetingService.class,
                    export("grpc", Map.of("role", "primary", "load", "0.1")));
            ClusteredGreetingService service = client.lookup("example.cluster.greeting", ClusteredGreetingService.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));
            String initial = service.instanceId();
            first.close();
            String afterFailover = service.instanceId();
            assertNotEquals(initial, afterFailover);
            assertTrue(service.greet("developer").contains(afterFailover));
            assertTrue(new RuntimeAdmin(client).formatStatus().contains("hp"));
        }
    }

    private void assertCallbackReferencePassing(final String transport) {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        EventServiceImpl target = new EventServiceImpl();
        try (DefaultMiddlewareRuntime server = runtime("phase55-events-server-" + transport, store, transport);
             DefaultMiddlewareRuntime client = runtime("phase55-events-client-" + transport, store, transport)) {
            server.export("example.events", target, EventService.class, export(transport, Map.of("example", "callbacks")));
            EventService service = client.lookup("example.events", EventService.class, LookupOptions.defaults());
            RecordingEventCallback callback = new RecordingEventCallback();
            assertEquals("event-service:received:" + transport + "-event",
                    service.registerAndPing(callback, transport + "-event"));
            assertEquals(List.of(transport + "-event"), callback.values());
            assertTrue(client.status().instances().stream().anyMatch(status -> status.ref().metadata().containsKey("callback.auto")));
            assertFalse(target.invocationLog().isEmpty());
        }
    }

    private static void assertReadmeSections(final Path readme) throws IOException {
        String text = Files.readString(readme, StandardCharsets.UTF_8);
        for (String section : README_SECTIONS) {
            assertTrue(text.contains("## " + section), "README " + readme + " missing section " + section);
        }
    }

    private DefaultMiddlewareRuntime runtime(final String id, final EmbeddedRegistryStore store, final String transport) {
        return runtime(id, store, transport, org.j7m.middleware.security.defaults.SecurityDefaults.development());
    }

    private DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryStore store,
            final String transport,
            final SecurityConfiguration security) {
        MiddlewareRuntimeBuilder builder = new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .security(security);
        if ("rmi".equals(transport)) {
            builder.addTransport(new RmiTransportProvider());
        } else {
            builder.addTransport(new GrpcTransportProvider());
        }
        builder.disableNuggetAdmin();
        return builder.build();
    }

    private static ExportOptions export(final String transport, final Map<String, String> metadata) {
        return new ExportOptions(false, transport, "default", List.of(), metadata, Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit) {
        RuleBasedAuthorizationPolicy policy = new RuleBasedAuthorizationPolicy(List.of(
                new RuleBasedAuthorizationPolicy.Rule(Set.of("banking-approver"), Set.of(
                        PermissionAction.EXPORT,
                        PermissionAction.LOOKUP,
                        PermissionAction.LIST,
                        PermissionAction.INVOKE,
                        PermissionAction.UNBIND,
                        PermissionAction.READ_STATUS), "")));
        return new SecurityConfiguration(SecurityMode.DEV, policy, audit,
                new DefaultRedactor(), false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("demo", "phase55", Map.of())));
    }

    private static SecurityContext context(final String principal, final String trustDomain, final Set<String> roles) {
        return new SecurityContext(new Principal(principal, principal, roles, Set.of()), "phase55-client",
                new TrustDomain(trustDomain), new Credential("demo", "phase55", Map.of()),
                "correlation-" + principal, "invocation-" + principal, "loopback", true,
                TransportSecurityLevel.AUTHENTICATED_ENCRYPTED, roles, Map.of(), Instant.now());
    }

    private Path exampleJar() {
        Path jar = projectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
        assertDoesNotThrow(() -> Files.size(jar));
        return jar;
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
}
