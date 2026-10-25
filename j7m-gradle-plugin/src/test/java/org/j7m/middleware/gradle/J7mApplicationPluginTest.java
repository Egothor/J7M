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

package org.j7m.middleware.gradle;

import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Gradle plugin local application workflow.
 */
final class J7mApplicationPluginTest {
    private static final String MODULAR_HELLO_API_JAR_PROPERTY = "j7m.test.modularHelloApiJar";
    private static final String MODULAR_HELLO_IMPL_JAR_PROPERTY = "j7m.test.modularHelloImplJar";
    private static final String MODULAR_HELLO_API_JAR =
            "examples/modular-deployable-application/api/build/libs/com.example.j7m.modular.hello.api.jar";
    private static final String MODULAR_HELLO_IMPL_JAR =
            "examples/modular-deployable-application/impl/build/libs/com.example.j7m.modular.hello.impl.jar";

    @TempDir
    private Path temporaryDirectory;

    /**
     * Verifies that applying the plugin creates the typed extension and all
     * required lifecycle tasks.
     *
     * @throws Exception on fixture setup failure
     */
    @Test
    void pluginRegistersExtensionAndTasks() throws Exception {
        Project project = this.project("hello-plugin");
        J7mApplicationExtension extension = project.getExtensions().getByType(J7mApplicationExtension.class);

        assertNotNull(extension);
        assertNotNull(project.getTasks().findByName("j7mPackage"));
        assertNotNull(project.getTasks().findByName("j7mSign"));
        assertNotNull(project.getTasks().findByName("j7mVerify"));
        assertNotNull(project.getTasks().findByName("j7mDeployLocal"));
        assertNotNull(project.getTasks().findByName("j7mPlanLocal"));
        assertNotNull(project.getTasks().findByName("j7mRunLocal"));
        assertNotNull(project.getTasks().findByName("j7mStatusLocal"));
        assertNotNull(project.getTasks().findByName("j7mStopLocal"));
        assertNotNull(project.getTasks().findByName("j7mUnloadLocal"));
    }

    /**
     * Verifies a classpath application package/sign/verify/deploy/run/status
     * stop/unload workflow.
     *
     * @throws Exception on fixture setup failure
     */
    @Test
    void pluginExecutesClasspathWorkflow() throws Exception {
        Project project = this.project("classpath-plugin");
        J7mApplicationExtension extension = project.getExtensions().getByType(J7mApplicationExtension.class);

        this.execute(project, "j7mPackage");
        this.execute(project, "j7mSign");
        this.execute(project, "j7mVerify");
        this.execute(project, "j7mDeployLocal");
        this.execute(project, "j7mPlanLocal");
        this.execute(project, "j7mRunLocal");
        this.execute(project, "j7mStatusLocal");
        this.execute(project, "j7mStopLocal");
        this.execute(project, "j7mUnloadLocal");

        assertTrue(Files.isRegularFile(extension.getBundleFile().get().getAsFile().toPath()));
        String state = Files.readString(extension.getRepositoryDir().get().getAsFile().toPath()
                .resolve(".local-runtime-state.json"), StandardCharsets.UTF_8);
        assertTrue(state.contains("UNAVAILABLE"));
        assertTrue(state.contains("ApplicationManager"));
        assertFalse(state.contains("private"));
    }

    /**
     * Verifies that strict regulated plugin verification succeeds when SBOM,
     * provenance, and signature metadata are present.
     *
     * @throws Exception on fixture setup failure
     */
    @Test
    void pluginExecutesStrictRegulatedWorkflow() throws Exception {
        Project project = this.project("regulated-plugin");
        Path metadata = project.getProjectDir().toPath().resolve("META-INF");
        Files.writeString(metadata.resolve("sbom.spdx.json"), "{\"spdxVersion\":\"SPDX-2.3\"}\n", StandardCharsets.UTF_8);
        Files.writeString(metadata.resolve("j7m-provenance.json"), "{\"builder\":\"plugin-test\"}\n", StandardCharsets.UTF_8);
        J7mApplicationExtension extension = project.getExtensions().getByType(J7mApplicationExtension.class);
        extension.getVerificationProfile().set("strict-regulated");

        this.execute(project, "j7mPackage");
        this.execute(project, "j7mSign");
        this.execute(project, "j7mVerify");
        this.execute(project, "j7mDeployLocal");

        assertTrue(Files.isRegularFile(extension.getBundleFile().get().getAsFile().toPath()));
    }

    /**
     * Verifies that plugin JPMS packaging includes real module JARs and that
     * the produced bundle activates through the production application manager.
     *
     * @throws Exception on fixture setup, packaging, activation, or invocation
     * failure
     */
    @Test
    void pluginJpmsBundleActivatesThroughRealApplicationManager() throws Exception {
        Project project = this.project("jpms-plugin");
        Path projectDirectory = project.getProjectDir().toPath();
        Path modules = projectDirectory.resolve("modules");
        Files.createDirectories(modules);
        copyModularFixtureJar(modularHelloApiJar(), modules.resolve("com.example.j7m.modular.hello.api.jar"));
        copyModularFixtureJar(modularHelloImplJar(), modules.resolve("com.example.j7m.modular.hello.impl.jar"));
        Files.copy(projectPath("examples/modular-deployable-application/META-INF/j7m-application.json"),
                projectDirectory.resolve("META-INF/j7m-application.json"), StandardCopyOption.REPLACE_EXISTING);

        J7mApplicationExtension extension = project.getExtensions().getByType(J7mApplicationExtension.class);
        this.execute(project, "j7mPackage");
        this.execute(project, "j7mSign");
        this.execute(project, "j7mVerify");
        this.execute(project, "j7mDeployLocal");

        Path bundle = extension.getBundleFile().get().getAsFile().toPath();
        try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            assertNotNull(zip.getEntry("modules/com.example.j7m.modular.hello.api.jar"));
            assertNotNull(zip.getEntry("modules/com.example.j7m.modular.hello.impl.jar"));
        }

        LocalApplicationRepository repository = new LocalApplicationRepository(
                extension.getRepositoryDir().get().getAsFile().toPath());
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0");
        try (DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                .runtimeId("plugin-jpms-runtime")
                .registry(new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC()))
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build()) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(coordinate,
                    new ApplicationActivationRequest("plugin-test", "dev", false, Map.of()));
            assertEquals(ApplicationRunState.RUNNING, activation.state());
            Class<?> serviceInterface = manager.serviceInterface(activation.instanceIdentity().instanceId(),
                    "com.example.j7m.modular.hello.api.ModularHelloService");
            assertTrue(serviceInterface.getModule().isNamed());
            assertFalse(serviceInterface.getClassLoader() == J7mApplicationPluginTest.class.getClassLoader());
            Object proxy = runtime.lookup("//apps/example/modular/hello/ModularHelloService",
                    serviceInterface, LookupOptions.defaults());
            Object result = serviceInterface.getMethod("hello", String.class).invoke(proxy, "plugin");
            assertEquals("modular hello plugin", result);
            assertThrows(MiddlewareException.class, () -> manager.serviceInterface(activation.instanceIdentity().instanceId(),
                    "java.lang.Runnable"));
            manager.deactivate(activation.instanceIdentity().instanceId());
            manager.unload(activation.instanceIdentity().instanceId());
            assertTrue(manager.list(null).isEmpty());
        }
    }

    @Test
    void pluginRejectsDeclaredJpmsArtifactWithoutModuleDescriptor() throws Exception {
        Project project = this.project("jpms-automatic-negative");
        Path projectDirectory = project.getProjectDir().toPath();
        Path modules = projectDirectory.resolve("modules");
        Files.createDirectories(modules);
        Path automatic = modules.resolve("com.example.j7m.modular.hello.api.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(automatic))) {
            output.putNextEntry(new JarEntry("example.txt"));
            output.write("automatic".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        copyModularFixtureJar(modularHelloImplJar(), modules.resolve("com.example.j7m.modular.hello.impl.jar"));
        Files.copy(projectPath("examples/modular-deployable-application/META-INF/j7m-application.json"),
                projectDirectory.resolve("META-INF/j7m-application.json"), StandardCopyOption.REPLACE_EXISTING);

        assertThrows(GradleException.class, () -> this.execute(project, "j7mPackage"));
    }

    @Test
    void pluginRejectsDeclaredJpmsArtifactWithMismatchedModuleIdentity() throws Exception {
        Project project = this.project("jpms-identity-negative");
        Path projectDirectory = project.getProjectDir().toPath();
        Path modules = projectDirectory.resolve("modules");
        Files.createDirectories(modules);
        copyModularFixtureJar(modularHelloApiJar(), modules.resolve("com.example.j7m.modular.hello.api.jar"));
        copyModularFixtureJar(modularHelloImplJar(), modules.resolve("com.example.j7m.modular.hello.impl.jar"));
        String manifest = Files.readString(projectPath("examples/modular-deployable-application/META-INF/j7m-application.json"),
                StandardCharsets.UTF_8)
                .replace("\"name\": \"com.example.j7m.modular.hello.api\"",
                        "\"name\": \"com.example.j7m.modular.hello.decoy\"");
        Files.writeString(projectDirectory.resolve("META-INF/j7m-application.json"), manifest, StandardCharsets.UTF_8);

        assertThrows(GradleException.class, () -> this.execute(project, "j7mPackage"));
    }

    @Test
    void pluginRejectsSameBasenameWhenDeclaredJpmsPathIsMissing() throws Exception {
        Project project = this.project("jpms-basename-negative");
        Path projectDirectory = project.getProjectDir().toPath();
        Path elsewhere = projectDirectory.resolve("elsewhere");
        Files.createDirectories(elsewhere);
        copyModularFixtureJar(modularHelloApiJar(), elsewhere.resolve("com.example.j7m.modular.hello.api.jar"));
        Files.copy(projectPath("examples/modular-deployable-application/META-INF/j7m-application.json"),
                projectDirectory.resolve("META-INF/j7m-application.json"), StandardCopyOption.REPLACE_EXISTING);

        assertThrows(GradleException.class, () -> this.execute(project, "j7mPackage"));
    }

    private Project project(final String name) throws Exception {
        Path directory = this.temporaryDirectory.resolve(name);
        Files.createDirectories(directory.resolve("META-INF"));
        Files.writeString(directory.resolve("META-INF/j7m-application.json"), manifest(name), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("README.md"), "# " + name + "\n", StandardCharsets.UTF_8);
        Project project = ProjectBuilder.builder()
                .withName(name)
                .withProjectDir(directory.toFile())
                .build();
        project.getPluginManager().apply(J7mApplicationPlugin.class);
        J7mApplicationExtension extension = project.getExtensions().getByType(J7mApplicationExtension.class);
        extension.getSmokeTestService().set("//apps/example/hello/HelloService");
        return project;
    }

    private void execute(final Project project, final String name) {
        Task task = project.getTasks().getByName(name);
        task.getActions().forEach(action -> action.execute(task));
    }

    private static String manifest(final String name) {
        return """
                {
                  "schemaVersion": "j7m.application/v1",
                  "application": {
                    "group": "org.j7m.plugin",
                    "name": "%s",
                    "version": "1.0.0",
                    "displayName": "%s",
                    "description": "plugin test"
                  },
                  "runtime": {
                    "minJ7mVersion": "0.5.6",
                    "javaVersion": "25",
                    "moduleMode": "CLASSPATH",
                    "bootstrapClass": "org.example.PluginApplication"
                  },
                  "services": [
                    {
                      "serviceName": "//apps/plugin/%s",
                      "interfaceName": "org.example.PluginService",
                      "implementationClass": "org.example.PluginServiceImpl",
                      "transport": "grpc",
                      "globallyExported": true
                    }
                  ]
                }
                """.formatted(name, name, name);
    }

    private static Path modularHelloApiJar() throws Exception {
        return modularFixtureJar(MODULAR_HELLO_API_JAR_PROPERTY, MODULAR_HELLO_API_JAR);
    }

    private static Path modularHelloImplJar() throws Exception {
        return modularFixtureJar(MODULAR_HELLO_IMPL_JAR_PROPERTY, MODULAR_HELLO_IMPL_JAR);
    }

    private static Path modularFixtureJar(final String propertyName, final String fallbackRelativePath) throws Exception {
        String configuredPath = System.getProperty(propertyName);
        Path jar;
        if (configuredPath == null || configuredPath.isBlank()) {
            jar = projectPath(fallbackRelativePath);
        } else {
            jar = Path.of(configuredPath);
        }
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("J7M plugin test fixture " + propertyName
                    + " must reference a regular module JAR: " + jar);
        }
        verifyModuleDescriptor(jar);
        return jar;
    }

    private static void copyModularFixtureJar(final Path source, final Path target) throws Exception {
        verifyModuleDescriptor(source);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void verifyModuleDescriptor(final Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            if (zip.getEntry("module-info.class") == null) {
                throw new IllegalStateException("J7M plugin test fixture JAR lacks module-info.class: " + jar);
            }
        }
    }

    private static Path projectPath(final String relative) {
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
