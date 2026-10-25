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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
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
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.RollbackOptions;
import org.j7m.middleware.deployer.api.UpgradeOptions;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5.2 JPMS modularization and Phase 5.4 upgrade/rollback tests.
 */
final class Phase52JpmsAndUpgradeTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void selectedModulesHaveDescriptorsAndNoInternalExports() throws IOException {
        Map<String, String> descriptors = Map.ofEntries(
                Map.entry("j7m-api", "org.j7m.api"),
                Map.entry("j7m-kernel", "org.j7m.kernel"),
                Map.entry("j7m-runtime", "org.j7m.runtime"),
                Map.entry("j7m-application-platform", "org.j7m.application.platform"),
                Map.entry("j7m-examples", "org.j7m.examples"),
                Map.entry("j7m-transport-grpc", "org.j7m.transport.grpc"),
                Map.entry("j7m-transport-rmi", "org.j7m.transport.rmi"));
        for (Map.Entry<String, String> entry : descriptors.entrySet()) {
            Path sourceDescriptor = projectPath(entry.getKey() + "/src/main/java/module-info.java");
            Path binaryDescriptor = projectPath(entry.getKey() + "/build/classes/java/main/module-info.class");
            assertTrue(Files.exists(sourceDescriptor), "missing descriptor " + sourceDescriptor);
            assertTrue(Files.exists(binaryDescriptor), "missing compiled descriptor " + binaryDescriptor);
            ModuleDescriptor parsed;
            try (java.io.InputStream input = Files.newInputStream(binaryDescriptor)) {
                parsed = ModuleDescriptor.read(input);
            }
            assertEquals(entry.getValue(), parsed.name());
            assertTrue(parsed.exports().stream().noneMatch(exported -> exported.source().contains(".internal.")));
        }
    }

    @Test
    void selectedNamedModulesDoNotHaveSplitPackages() throws IOException {
        List<Path> roots = List.of(
                projectPath("j7m-api/src/main/java"),
                projectPath("j7m-kernel/src/main/java"),
                projectPath("j7m-runtime/src/main/java"),
                projectPath("j7m-application-platform/src/main/java"),
                projectPath("j7m-examples/src/main/java"),
                projectPath("j7m-transport-grpc/src/main/java"),
                projectPath("j7m-transport-rmi/src/main/java"));
        Map<String, Path> owners = new HashMap<>();
        for (Path root : roots) {
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("module-info.java")) {
                        continue;
                    }
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    int index = text.indexOf("package ");
                    if (index < 0) {
                        continue;
                    }
                    int end = text.indexOf(';', index);
                    String packageName = text.substring(index + "package ".length(), end).trim();
                    Path previous = owners.putIfAbsent(packageName, root);
                    assertTrue(previous == null || previous.equals(root), "split package " + packageName);
                }
            }
        }
    }

    @Test
    void architectureBoundariesRemainCleanInBuildFile() throws IOException {
        String kernelBuild = Files.readString(repositoryPath("j7m-kernel/build.gradle"), StandardCharsets.UTF_8);
        String runtimeBuild = Files.readString(repositoryPath("j7m-runtime/build.gradle"), StandardCharsets.UTF_8);
        String apiModule = Files.readString(repositoryPath("j7m-api/src/main/java/module-info.java"), StandardCharsets.UTF_8);
        assertFalse(kernelBuild.contains("j7m-transport-grpc"));
        assertFalse(kernelBuild.contains("j7m-transport-rmi"));
        assertFalse(runtimeBuild.contains("j7m-transport-grpc"));
        assertFalse(runtimeBuild.contains("j7m-transport-rmi"));
        assertFalse(apiModule.contains("org.j7m.application.platform"));
    }

    @Test
    void jpmsManifestValidationAndBundleVerificationWork() throws IOException {
        String manifestText = Files.readString(modularManifest("1.0.0"), StandardCharsets.UTF_8);
        ApplicationVerificationResult accepted = new ApplicationManifestValidator()
                .validate(new ApplicationManifestParser().parse(manifestText), false);
        assertTrue(accepted.accepted(), accepted.messages().toString());

        String missingBootstrapModule = manifestText.replace("\"bootstrapModule\": \"com.example.j7m.modular.hello.impl\",", "");
        ApplicationVerificationResult missing = new ApplicationManifestValidator()
                .validate(new ApplicationManifestParser().parse(missingBootstrapModule), false);
        assertFalse(missing.accepted());

        String traversal = manifestText.replace("modules/com.example.j7m.modular.hello.impl.jar", "../impl.jar");
        ApplicationVerificationResult unsafe = new ApplicationManifestValidator()
                .validate(new ApplicationManifestParser().parse(traversal), false);
        assertFalse(unsafe.accepted());

        ApplicationBundle bundle = modularBundle("1.0.0");
        assertTrue(new ApplicationBundleVerifier().verify(bundle, false).accepted());
    }

    @Test
    void moduleLayerActivationLookupStopAndUnloadWork() throws ReflectiveOperationException {
        ApplicationBundle bundle = modularBundle("1.0.0");
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("repo"));
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase52-jpms")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            assertEquals(ApplicationRunState.RUNNING, activation.state());
            assertEquals("JPMS", activation.metadata().get("moduleMode"));
            assertFalse(activation.metadata().get("moduleLayer").isBlank());
            assertTrue(activation.metadata().get("modules").contains("com.example.j7m.modular.hello.impl"));

            Class<?> api = manager.serviceInterface(activation.instanceIdentity().instanceId(),
                    "com.example.j7m.modular.hello.api.ModularHelloService");
            Object proxy = runtime.lookup("//apps/example/modular/hello/ModularHelloService", api, LookupOptions.defaults());
            assertEquals("modular hello test", api.getMethod("hello", String.class).invoke(proxy, "test"));
            assertTrue(manager.deactivate(activation.instanceIdentity().instanceId()).success());
            assertEquals(0, runtime.list("//apps/example/modular/hello/ModularHelloService", List.of(api.getName()), Map.of(), LookupOptions.defaults()).size());
            assertTrue(manager.unload(activation.instanceIdentity().instanceId()).success());
            assertEquals(0, manager.list(ApplicationQuery.all()).size());
        }
    }

    @Test
    void stopStartUpgradeAndRollbackWork() throws ReflectiveOperationException {
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("upgrade-repo"));
        ApplicationBundle v1 = modularBundle("1.0.0");
        ApplicationBundle v2 = modularBundle("1.1.0");
        repository.publish(v1, new RepositoryPublishOptions("test", false));
        repository.publish(v2, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase52-upgrade")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
            ApplicationActivation v1Activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            assertTrue(deployer.upgrade(v1Activation.instanceIdentity().instanceId(),
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), UpgradeOptions.stopStart()).success());
            ApplicationActivation running = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).getLast();
            assertEquals("1.1.0", running.instanceIdentity().application().coordinate().version().value());
            Class<?> api = manager.serviceInterface(running.instanceIdentity().instanceId(),
                    "com.example.j7m.modular.hello.api.ModularHelloService");
            Object proxy = runtime.lookup("//apps/example/modular/hello/ModularHelloService", api, LookupOptions.defaults());
            assertEquals("modular hello upgraded", api.getMethod("hello", String.class).invoke(proxy, "upgraded"));
            assertTrue(deployer.rollback(running.instanceIdentity().instanceId(), new RollbackOptions("")).success());
            ApplicationActivation restored = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).getLast();
            assertEquals("1.0.0", restored.instanceIdentity().application().coordinate().version().value());
            assertFalse(manager.upgradeHistory().isEmpty());
            assertTrue(manager.auditEvents().stream().anyMatch(event -> event.startsWith("application.upgrade")));
            assertTrue(manager.auditEvents().stream().anyMatch(event -> event.startsWith("application.rollback")));
        }
    }

    @Test
    void failedUpgradeRestoresPreviousAndUnknownStrategyIsRejected() throws IOException {
        LocalApplicationRepository repository = new LocalApplicationRepository(this.temporaryDirectory.resolve("failed-upgrade-repo"));
        ApplicationBundle v1 = modularBundle("1.0.0");
        ApplicationBundle bad = badModularBundle();
        repository.publish(v1, new RepositoryPublishOptions("test", false));
        repository.publish(bad, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase52-failed-upgrade")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
            ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            MiddlewareException unsupported = assertThrows(MiddlewareException.class, () -> deployer.upgrade(activation.instanceIdentity().instanceId(),
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), new UpgradeOptions("unknown-strategy")));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, unsupported.code());
            assertFalse(deployer.upgrade(activation.instanceIdentity().instanceId(),
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:9.9.9"), UpgradeOptions.stopStart()).success());
            ApplicationActivation restored = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).getLast();
            assertEquals("1.0.0", restored.instanceIdentity().application().coordinate().version().value());
            assertEquals(1, runtime.list("//apps/example/modular/hello/ModularHelloService",
                    List.of("com.example.j7m.modular.hello.api.ModularHelloService"), Map.of(), LookupOptions.defaults()).size());
        }
    }

    private ApplicationBundle modularBundle(final String version) {
        return new J7mBundleWriter().write(modularManifest(version),
                this.temporaryDirectory.resolve("modular-" + version + ".j7m"),
                List.of(), List.of(modularApiJar(), modularImplJar()), List.of(modularReadme()));
    }

    private ApplicationBundle badModularBundle() throws IOException {
        String text = Files.readString(modularManifest("1.1.0"), StandardCharsets.UTF_8)
                .replace("com.example.j7m.modular.hello.impl.ModularHelloApplication", "java.lang.String")
                .replace("\"version\": \"1.1.0\"", "\"version\": \"9.9.9\"");
        Path manifest = this.temporaryDirectory.resolve("bad-jpms-manifest.json");
        Files.writeString(manifest, text, StandardCharsets.UTF_8);
        return new J7mBundleWriter().write(manifest,
                this.temporaryDirectory.resolve("modular-bad.j7m"),
                List.of(), List.of(modularApiJar(), modularImplJar()), List.of(modularReadme()));
    }

    private DefaultMiddlewareRuntime runtime(final String id) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(new EmbeddedRegistryStore(), java.time.Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private Path modularManifest(final String version) {
        if ("1.1.0".equals(version)) {
            return projectPath("examples/modular-deployable-application/META-INF/j7m-application-1.1.0.json");
        }
        return projectPath("examples/modular-deployable-application/META-INF/j7m-application.json");
    }

    private Path modularApiJar() {
        return projectPath("examples/modular-deployable-application/api/build/libs/com.example.j7m.modular.hello.api.jar");
    }

    private Path modularImplJar() {
        return projectPath("examples/modular-deployable-application/impl/build/libs/com.example.j7m.modular.hello.impl.jar");
    }

    private Path modularReadme() {
        return projectPath("examples/modular-deployable-application/README.md");
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
}
