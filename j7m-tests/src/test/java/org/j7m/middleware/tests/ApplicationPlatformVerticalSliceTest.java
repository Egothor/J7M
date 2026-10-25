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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.ApplicationManifestParser;
import org.j7m.middleware.application.repository.ApplicationManifestValidator;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.deployer.DefaultApplicationDeployer;
import org.j7m.middleware.deployer.api.DeactivationOptions;
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.PackageRequest;
import org.j7m.middleware.deployer.api.UnloadOptions;
import org.j7m.middleware.deployer.api.VerificationOptions;
import org.j7m.middleware.examples.hello.HelloService;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5.2 first vertical-slice tests.
 */
final class ApplicationPlatformVerticalSliceTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void coordinateParsingAndManifestValidationWork() throws IOException {
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:hello-remote-service:1.0.0");
        assertEquals(new ApplicationId("org.j7m.examples", "hello-remote-service"), coordinate.id());

        String manifest = Files.readString(manifestPath(), StandardCharsets.UTF_8);
        ApplicationVerificationResult valid = new ApplicationManifestValidator()
                .validate(new ApplicationManifestParser().parse(manifest), false);
        assertTrue(valid.accepted(), valid.messages().toString());

        String unsupported = manifest.replace("j7m.application/v1", "j7m.application/v999");
        ApplicationVerificationResult invalid = new ApplicationManifestValidator()
                .validate(new ApplicationManifestParser().parse(unsupported), false);
        assertFalse(invalid.accepted());
    }

    @Test
    void bundleVerificationRepositoryAndImmutabilityWork() throws IOException {
        ApplicationBundle bundle = writeBundle(this.temporaryDirectory.resolve("hello.j7m"));
        ApplicationBundleVerifier verifier = new ApplicationBundleVerifier();
        assertTrue(verifier.verify(bundle, false).accepted());
        assertFalse(verifier.verify(bundle, true).accepted());

        ApplicationBundle tampered = tamper(bundle, this.temporaryDirectory.resolve("tampered.j7m"));
        assertFalse(verifier.verify(tampered, false).accepted());

        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("repo"));
        assertTrue(repository.publish(bundle, new RepositoryPublishOptions("test", false)).success());
        assertEquals(1, repository.listVersions(ApplicationId.parse("org.j7m.examples:hello-remote-service")).size());
        assertThrows(MiddlewareException.class, () -> repository.publish(bundle, new RepositoryPublishOptions("test", false)));
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:hello-remote-service:1.0.0");
        assertEquals(coordinate, repository.fetch(coordinate).coordinate());
        assertTrue(repository.promote(coordinate, "dev").success());
        assertTrue(repository.demote(coordinate, "dev").success());
        assertFalse(repository.auditEvents().isEmpty());
    }

    @Test
    void deployerActivatesExportsStopsAndUnloadsApplication() {
        ApplicationBundle bundle = writeBundle(this.temporaryDirectory.resolve("hello.j7m"));
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("repo"));
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                .runtimeId("application-platform-test")
                .registry(new org.j7m.middleware.registry.embedded.EmbeddedRegistryClient(store, java.time.Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build()) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
            assertTrue(deployer.verify(bundle, VerificationOptions.development()).accepted());
            assertTrue(deployer.deploy(bundle, new DeployOptions("test", false)).success());
            ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:hello-remote-service:1.0.0");
            assertEquals(ApplicationRunState.PLANNED, deployer.planActivation(coordinate, null).state());

            ApplicationActivation activation = deployer.activate(coordinate,
                    new ApplicationActivationRequest("operator", "dev", false, Map.of("feature", "enabled")));
            assertEquals(ApplicationRunState.RUNNING, activation.state());
            assertFalse(activation.metadata().get("classLoader").isBlank());
            assertEquals(1, deployer.listActiveApplications(ApplicationQuery.all()).size());

            HelloService hello = runtime.lookup("//apps/example/hello/HelloService", HelloService.class, LookupOptions.defaults());
            String result = hello.hello("integration");
            assertTrue(result.contains("hello integration"));
            assertNotEquals("", result.substring(result.indexOf("correlation=") + "correlation=".length()));
            assertEquals("", J7mContext.currentInvocation().correlationId(), "invocation context leaked after dispatch");

            assertTrue(deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false)).success());
            assertEquals(0, runtime.list("//apps/example/hello/HelloService", List.of(HelloService.class.getName()), Map.of(), LookupOptions.defaults()).size());
            assertTrue(deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false)).success());
            assertEquals(0, deployer.listActiveApplications(ApplicationQuery.all()).size());
        }
    }

    @Test
    void failedActivationDoesNotPublishHealthyRegistryRecord() throws IOException {
        Path manifest = this.temporaryDirectory.resolve("bad-manifest.json");
        Files.writeString(manifest, Files.readString(manifestPath(), StandardCharsets.UTF_8)
                .replace("org.j7m.middleware.examples.hello.HelloApplication", "java.lang.String"), StandardCharsets.UTF_8);
        ApplicationBundle bundle = new J7mBundleWriter().write(manifest, this.temporaryDirectory.resolve("bad.j7m"), List.of(exampleJar()), List.of(readmePath()));
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("bad-repo"));
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                .runtimeId("application-platform-failure-test")
                .registry(new org.j7m.middleware.registry.embedded.EmbeddedRegistryClient(new EmbeddedRegistryStore(), java.time.Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build()) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            assertThrows(MiddlewareException.class, () -> manager.activate(bundle.coordinate(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));
            assertEquals(0, runtime.list("//apps/example/hello/HelloService", List.of(HelloService.class.getName()), Map.of(), LookupOptions.defaults()).size());
        }
    }

    private ApplicationBundle writeBundle(final Path path) {
        return new J7mBundleWriter().write(manifestPath(), path, List.of(exampleJar()), List.of(readmePath()));
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

    private Path manifestPath() {
        return projectPath("examples/deployable-application/META-INF/j7m-application.json");
    }

    private Path readmePath() {
        return projectPath("examples/deployable-application/README.md");
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
