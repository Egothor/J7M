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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.DefaultBundleSigner;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.PublicKeyTrustStore;
import org.j7m.middleware.application.repository.SignaturePolicy;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.examples.hello.HelloService;
import org.j7m.middleware.tests.fixtures.Gate3CountingService;
import org.j7m.middleware.tests.fixtures.Gate3CountingServiceImpl;
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
 * Corrective Gate 3 tests for built-in signing, local repository security,
 * resource policy enforcement, and ObjectRef non-bearer behavior.
 */
final class Phase53SecurityRepositoryHardeningTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void builtInSigningStrictAndRegulatedVerificationAreEnforced() throws IOException {
        ApplicationBundle unsigned = helloBundle("unsigned.j7m");
        ApplicationBundleVerifier verifier = new ApplicationBundleVerifier();
        assertTrue(verifier.verify(unsigned, SignaturePolicy.DEVELOPMENT).accepted());
        assertFalse(verifier.verify(unsigned, SignaturePolicy.STRICT_PRODUCTION).accepted());

        ApplicationBundle signed = new DefaultBundleSigner().sign(helloBundle("signed.j7m"));
        assertTrue(verifier.verify(signed, SignaturePolicy.STRICT_PRODUCTION).accepted());
        assertFalse(verifier.verify(signed, SignaturePolicy.STRICT_REGULATED).accepted(), "hello bundle has no SBOM");
        assertFalse(new ApplicationBundleVerifier(new PublicKeyTrustStore(Map.of()))
                .verify(signed, SignaturePolicy.STRICT_PRODUCTION).accepted());

        ApplicationBundle regulated = new DefaultBundleSigner().sign(regulatedBundle("regulated.j7m"));
        assertTrue(verifier.verify(regulated, SignaturePolicy.STRICT_REGULATED).accepted());
        assertFalse(verifier.verify(copyWithoutEntry(regulated, this.temporaryDirectory.resolve("missing-sbom.j7m"),
                J7mBundleReader.SBOM_ENTRY), SignaturePolicy.STRICT_REGULATED).accepted());
        assertFalse(verifier.verify(copyWithoutEntry(regulated, this.temporaryDirectory.resolve("missing-provenance.j7m"),
                J7mBundleReader.PROVENANCE_ENTRY), SignaturePolicy.STRICT_REGULATED).accepted());
        assertFalse(verifier.verify(tamper(regulated, this.temporaryDirectory.resolve("tampered.j7m")),
                SignaturePolicy.STRICT_PRODUCTION).accepted());
    }

    @Test
    void localRepositoryEnforcesStrictVerificationAndTamperEvidentAudit() throws IOException {
        ApplicationBundle regulated = new DefaultBundleSigner().sign(regulatedBundle("regulated-publish.j7m"));
        Path root = this.temporaryDirectory.resolve("regulated-repo");
        LocalApplicationRepository repository = new LocalApplicationRepository(root);
        assertTrue(repository.publish(regulated, new RepositoryPublishOptions("publisher", true, "strict-regulated")).success());
        ApplicationCoordinate coordinate = regulated.coordinate();
        assertEquals(coordinate, repository.fetch(coordinate).coordinate());
        assertTrue(repository.verify(coordinate).accepted());
        assertTrue(repository.promote(coordinate, "prod").success());
        assertTrue(repository.demote(coordinate, "prod").success());
        assertTrue(repository.verifyAuditChain());
        assertEquals("true", repository.status().metadata().get("auditChainValid"));
        assertEquals("built-in-ed25519", repository.status().metadata().get("signatureVerification"));

        Path audit = root.resolve("audit").resolve("repository-events.jsonl");
        List<String> lines = Files.readAllLines(audit, StandardCharsets.UTF_8);
        Files.write(audit, lines.stream()
                .map(line -> line.replace("promote", "tampered-promote"))
                .toList(), StandardCharsets.UTF_8);
        assertFalse(repository.verifyAuditChain());
        assertFalse(repository.status().metadata().toString().contains("PRIVATE"));
    }

    @Test
    void activationRequiresCurrentVerificationRecordAndResourcePolicyAllowsAndDenies() throws IOException {
        ApplicationBundle signed = new DefaultBundleSigner().sign(helloBundle("verified.j7m"));
        Path repoRoot = this.temporaryDirectory.resolve("activation-repo");
        LocalApplicationRepository repository = new LocalApplicationRepository(repoRoot);
        repository.publish(signed, new RepositoryPublishOptions("publisher", true, "strict-production"));
        ApplicationCoordinate coordinate = signed.coordinate();
        try (DefaultMiddlewareRuntime runtime = runtime("gate3-activation", org.j7m.middleware.security.defaults.SecurityDefaults.development())) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(coordinate,
                    new ApplicationActivationRequest("operator", "prod", true, Map.of("verificationProfile", "strict-production")));
            assertEquals("1.0", activation.metadata().get("configuredCpu"));
            assertEquals("268435456", activation.metadata().get("configuredMemory"));
            HelloService hello = runtime.lookup("//apps/example/hello/HelloService", HelloService.class, LookupOptions.defaults());
            assertTrue(hello.hello("resource").contains("hello resource"));
            ApplicationActivation afterCall = manager.list(ApplicationQuery.all()).getFirst();
            assertEquals("1", afterCall.metadata().get("completedInvocations"));
            manager.deactivate(activation.instanceIdentity().instanceId());
            manager.unload(activation.instanceIdentity().instanceId());
        }

        Path verification = bundlePath(repoRoot, coordinate).getParent().resolve("verification.json");
        Files.delete(verification);
        try (DefaultMiddlewareRuntime runtime = runtime("gate3-unverified", org.j7m.middleware.security.defaults.SecurityDefaults.development())) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            MiddlewareException exception = assertThrows(MiddlewareException.class, () -> manager.activate(coordinate,
                    new ApplicationActivationRequest("operator", "prod", true, Map.of("verificationProfile", "strict-production"))));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, exception.code());
        }

        LocalApplicationRepository deniedRepository = new LocalApplicationRepository(this.temporaryDirectory.resolve("resource-deny-repo"));
        deniedRepository.publish(new DefaultBundleSigner().sign(helloBundle("resource-denied.j7m")),
                new RepositoryPublishOptions("publisher", true, "strict-production"));
        try (DefaultMiddlewareRuntime runtime = runtime("gate3-resource-denied", org.j7m.middleware.security.defaults.SecurityDefaults.development())) {
            ApplicationManager manager = new ApplicationManager(runtime, deniedRepository);
            MiddlewareException exception = assertThrows(MiddlewareException.class, () -> manager.activate(coordinate,
                    new ApplicationActivationRequest("operator", "prod", true, Map.of(
                            "verificationProfile", "strict-production",
                            "resource.maxCpu", "0.5"))));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, exception.code());
        }
    }

    @Test
    void activationDeniedWhenRepositoryArtifactChangesAfterVerification() throws IOException {
        ApplicationBundle signed = new DefaultBundleSigner().sign(helloBundle("tamper-source.j7m"));
        Path repoRoot = this.temporaryDirectory.resolve("tamper-repo");
        LocalApplicationRepository repository = new LocalApplicationRepository(repoRoot);
        repository.publish(signed, new RepositoryPublishOptions("publisher", true, "strict-production"));
        Path stored = bundlePath(repoRoot, signed.coordinate());
        Files.copy(tamper(signed, this.temporaryDirectory.resolve("stored-tampered.j7m")).path(), stored,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (DefaultMiddlewareRuntime runtime = runtime("gate3-tamper-denied", org.j7m.middleware.security.defaults.SecurityDefaults.development())) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            MiddlewareException exception = assertThrows(MiddlewareException.class, () -> manager.activate(signed.coordinate(),
                    new ApplicationActivationRequest("operator", "prod", true, Map.of("verificationProfile", "strict-production"))));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, exception.code());
        }
    }

    @Test
    void applicationInboundPayloadLimitRejectsInvocationAndUpdatesCounters() throws IOException {
        Path limitedManifest = this.temporaryDirectory.resolve("limited-manifest.json");
        Files.writeString(limitedManifest, Files.readString(helloManifest(), StandardCharsets.UTF_8)
                .replace("\"maxInboundPayload\": \"4MiB\"", "\"maxInboundPayload\": \"4\""),
                StandardCharsets.UTF_8);
        ApplicationBundle bundle = new DefaultBundleSigner().sign(new J7mBundleWriter().write(limitedManifest,
                this.temporaryDirectory.resolve("limited.j7m"), List.of(exampleJar()), List.of(helloReadme())));
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("payload-repo"));
        repository.publish(bundle, new RepositoryPublishOptions("publisher", true, "strict-production"));
        try (DefaultMiddlewareRuntime runtime = runtime("gate3-payload-limit", org.j7m.middleware.security.defaults.SecurityDefaults.development())) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(bundle.coordinate(),
                    new ApplicationActivationRequest("operator", "prod", true, Map.of("verificationProfile", "strict-production")));
            HelloService hello = runtime.lookup("//apps/example/hello/HelloService", HelloService.class, LookupOptions.defaults());
            MiddlewareException exception = assertThrows(MiddlewareException.class, () -> hello.hello("too-large"));
            assertEquals(MiddlewareErrorCode.OVERLOADED, exception.code());
            ApplicationActivation status = manager.list(ApplicationQuery.all()).getFirst();
            assertEquals("1", status.metadata().get("rejectedPayloadInvocations"));
            assertEquals("max-inbound-payload", status.metadata().get("lastResourceRejectionReason"));
            manager.deactivate(activation.instanceIdentity().instanceId());
            manager.unload(activation.instanceIdentity().instanceId());
        }
    }

    @Test
    void objectRefPossessionDoesNotBypassServerAuthorization() {
        InMemoryAuditSink audit = new InMemoryAuditSink(128);
        SecurityConfiguration security = security(audit);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        Gate3CountingServiceImpl target = new Gate3CountingServiceImpl();
        try (DefaultMiddlewareRuntime runtime = runtime("gate3-objectref-server", store, security)) {
            SecurityContexts.run(context("admin", Set.of("admin")), () -> runtime.export(
                    "gate3.secure.CountingService",
                    target,
                    Gate3CountingService.class,
                    new ExportOptions(false, "grpc", "default", List.of(Gate3CountingService.class.getName()),
                            Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(10L))));
            ObjectRef ref = SecurityContexts.call(context("admin", Set.of("admin")),
                    () -> runtime.list("gate3.secure.CountingService", List.of(Gate3CountingService.class.getName()),
                            Map.of(), LookupOptions.defaults()).getFirst());
            Gate3CountingService authorized = runtime.proxyFor(ref, Gate3CountingService.class);
            String allowed = SecurityContexts.call(context("caller", Set.of("invoker")),
                    () -> authorized.secret("allowed-value"));
            assertEquals("ok:allowed-value", allowed);
            assertEquals(1, target.invocationCount());

            Gate3CountingService unauthorized = runtime.proxyFor(ref, Gate3CountingService.class);
            MiddlewareException denied = assertThrows(MiddlewareException.class, () ->
                    SecurityContexts.call(context("viewer", Set.of("viewer")),
                            () -> unauthorized.secret("sensitive-secret-value")));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, denied.code());
            assertFalse(denied.getMessage().contains("sensitive-secret-value"));
            assertEquals(1, target.invocationCount(), "target method executed after denial");
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("invoke")
                    && event.decision().equals("deny")));
            assertNull(SecurityContexts.current());
        }
    }

    private ApplicationBundle helloBundle(final String fileName) {
        return new J7mBundleWriter().write(helloManifest(), this.temporaryDirectory.resolve(fileName),
                List.of(exampleJar()), List.of(helloReadme()));
    }

    private ApplicationBundle regulatedBundle(final String fileName) {
        return new J7mBundleWriter().write(regulatedManifest(), this.temporaryDirectory.resolve(fileName),
                List.of(exampleJar()), List.of(regulatedReadme()));
    }

    private ApplicationBundle copyWithoutEntry(final ApplicationBundle source, final Path target, final String excludedEntry) throws IOException {
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

    private ApplicationBundle tamper(final ApplicationBundle source, final Path target) throws IOException {
        try (ZipFile zip = new ZipFile(source.path().toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0L);
                output.putNextEntry(copy);
                if ("docs/README.md".equals(entry.getName())) {
                    output.write("tampered".getBytes(StandardCharsets.UTF_8));
                } else {
                    output.write(zip.getInputStream(entry).readAllBytes());
                }
                output.closeEntry();
            }
        }
        return new ApplicationBundle(target, source.coordinate(), new J7mBundleReader().sha256(target));
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit) {
        RuleBasedAuthorizationPolicy policy = new RuleBasedAuthorizationPolicy(List.of(
                new RuleBasedAuthorizationPolicy.Rule(Set.of("admin"), Set.of(PermissionAction.EXPORT, PermissionAction.LIST), ""),
                new RuleBasedAuthorizationPolicy.Rule(Set.of("invoker"), Set.of(PermissionAction.INVOKE), "")));
        return new SecurityConfiguration(SecurityMode.DEV, policy, audit,
                new DefaultRedactor(), false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("demo", "gate3", Map.of())));
    }

    private static SecurityContext context(final String principal, final Set<String> roles) {
        return new SecurityContext(new Principal(principal, principal, roles, Set.of()), "gate3-client",
                new TrustDomain("dev"), new Credential("demo", "gate3", Map.of()),
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

    private Path bundlePath(final Path root, final ApplicationCoordinate coordinate) {
        return root.resolve("artifacts")
                .resolve(coordinate.id().group().replace('.', '/'))
                .resolve(coordinate.id().name())
                .resolve(coordinate.version().value())
                .resolve(coordinate.id().name() + "-" + coordinate.version().value() + ".j7m");
    }

    private Path helloManifest() {
        return projectPath("examples/hello-remote-service/META-INF/j7m-application.json");
    }

    private Path helloReadme() {
        return projectPath("examples/hello-remote-service/README.md");
    }

    private Path regulatedManifest() {
        return projectPath("examples/regulated-enterprise-app/META-INF/j7m-application.json");
    }

    private Path regulatedReadme() {
        return projectPath("examples/regulated-enterprise-app/README.md");
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
