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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.jvm.tasks.Jar;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.ApplicationManifestParser;
import org.j7m.middleware.application.repository.ApplicationManifestValidator;
import org.j7m.middleware.application.repository.DefaultBundleSigner;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.JpmsPackagingValidator;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SignaturePolicy;

/**
 * Gradle plugin for local J7M application developer workflows.
 *
 * <p>The plugin registers package, sign, verify, deploy, plan, run, status,
 * stop, and unload tasks. All tasks are local-runtime scoped and use the same
 * J7M bundle, signing, verification, and repository APIs as the runtime tests.
 * No task contacts remote fleets, external repositories, vendor identity
 * providers, external certificate authorities, KMS, SIEM, or GRC systems.</p>
 *
 * <p>The plugin is stateless. Task outputs are deterministic for unchanged
 * inputs except for local repository audit timestamps.</p>
 */
public final class J7mApplicationPlugin implements Plugin<Project> {
    /**
     * Creates the plugin.
     */
    public J7mApplicationPlugin() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void apply(final Project project) {
        J7mApplicationExtension extension = project.getExtensions()
                .create("j7m", J7mApplicationExtension.class);
        extension.getManifest().convention(project.getLayout()
                .getProjectDirectory()
                .file("META-INF/j7m-application.json"));
        extension.getRepositoryDir().convention(project.getLayout()
                .getBuildDirectory()
                .dir("j7m-repository"));
        extension.getBundleFile().convention(project.getLayout()
                .getBuildDirectory()
                .file("libs/" + project.getName() + ".j7m"));

        this.register(project, "j7mPackage", "Packages a J7M application bundle.",
                task -> this.packageBundle(project, extension));
        this.register(project, "j7mSign", "Signs a J7M application bundle.",
                task -> this.signBundle(extension));
        this.register(project, "j7mVerify", "Verifies a J7M application bundle.",
                task -> this.verifyBundle(extension));
        this.register(project, "j7mDeployLocal", "Publishes a J7M application bundle to a local repository.",
                task -> this.deployLocal(extension));
        this.register(project, "j7mPlanLocal", "Creates a dry-run local activation plan.",
                task -> this.planLocal(extension));
        this.register(project, "j7mRunLocal", "Runs a local application smoke workflow.",
                task -> this.runLocal(extension));
        this.register(project, "j7mStatusLocal", "Prints safe local application status.",
                task -> this.statusLocal(extension));
        this.register(project, "j7mStopLocal", "Stops a local application workflow.",
                task -> this.stopLocal(extension));
        this.register(project, "j7mUnloadLocal", "Unloads a local application workflow.",
                task -> this.unloadLocal(extension));

        project.getTasks().named("j7mSign").configure(task -> task.dependsOn("j7mPackage"));
        project.getTasks().named("j7mPackage").configure(task -> task.dependsOn(project.getTasks().withType(Jar.class)));
        project.getTasks().named("j7mVerify").configure(task -> task.dependsOn("j7mSign"));
        project.getTasks().named("j7mDeployLocal").configure(task -> task.dependsOn("j7mVerify"));
        project.getTasks().named("j7mPlanLocal").configure(task -> task.dependsOn("j7mDeployLocal"));
        project.getTasks().named("j7mRunLocal").configure(task -> task.dependsOn("j7mPlanLocal"));
        project.getTasks().named("j7mStatusLocal").configure(task -> task.dependsOn("j7mRunLocal"));
        project.getTasks().named("j7mStopLocal").configure(task -> task.dependsOn("j7mStatusLocal"));
        project.getTasks().named("j7mUnloadLocal").configure(task -> task.dependsOn("j7mStopLocal"));
    }

    private void register(
            final Project project,
            final String name,
            final String description,
            final org.gradle.api.Action<Task> action) {
        if (project.getTasks().findByName(name) != null) {
            return;
        }
        project.getTasks().register(name, task -> {
            task.setGroup("j7m");
            task.setDescription(description);
            task.doLast(action);
        });
    }

    private void packageBundle(final Project project, final J7mApplicationExtension extension) {
        Path manifest = extension.getManifest().get().getAsFile().toPath();
        Path bundle = extension.getBundleFile().get().getAsFile().toPath();
        try {
            String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
            ApplicationManifest parsed = new ApplicationManifestParser().parse(manifestText);
            ApplicationVerificationResult result = new ApplicationManifestValidator()
                    .validate(parsed, this.policy(extension).signatureRequired());
            if (!result.accepted()) {
                throw new GradleException("J7M manifest rejected");
            }
            List<Path> libraries = this.libraryPaths(project, extension, parsed);
            List<Path> modules = this.modulePaths(project, extension, parsed);
            List<Path> docs = this.documentationPaths(project, extension);
            List<Path> configurationFiles = this.fileCollectionPaths(extension.getConfigurationFiles());
            new J7mBundleWriter().write(manifest, bundle, libraries, modules, docs, configurationFiles);
            project.getLogger().lifecycle("j7mPackage wrote {}", bundle.getFileName());
        } catch (Exception exception) {
            throw new GradleException("j7mPackage failed: " + safe(exception), exception);
        }
    }

    private void signBundle(final J7mApplicationExtension extension) {
        ApplicationBundle bundle = this.bundle(extension);
        new DefaultBundleSigner().sign(bundle);
        System.out.println("j7mSign signed " + bundle.path().getFileName());
    }

    private void verifyBundle(final J7mApplicationExtension extension) {
        ApplicationVerificationResult result = new ApplicationBundleVerifier().verify(this.bundle(extension), this.policy(extension));
        if (!result.accepted()) {
            throw new GradleException("J7M verification rejected bundle");
        }
        System.out.println("j7mVerify accepted profile=" + extension.getVerificationProfile().get());
    }

    private void deployLocal(final J7mApplicationExtension extension) {
        LocalApplicationRepository repository = this.repository(extension);
        ApplicationBundle bundle = this.bundle(extension);
        repository.publish(bundle, new RepositoryPublishOptions("gradle-plugin", this.policy(extension).signatureRequired(),
                extension.getVerificationProfile().get()));
        System.out.println("j7mDeployLocal published " + bundle.coordinate());
    }

    private void planLocal(final J7mApplicationExtension extension) {
        ApplicationBundle bundle = this.bundle(extension);
        this.writeUnavailableState(extension, bundle.coordinate());
        System.out.println("j7mPlanLocal unavailable coordinate=" + bundle.coordinate());
    }

    private void runLocal(final J7mApplicationExtension extension) {
        ApplicationBundle bundle = this.bundle(extension);
        this.writeUnavailableState(extension, bundle.coordinate());
        System.out.println("j7mRunLocal unavailable coordinate=" + bundle.coordinate());
    }

    private void statusLocal(final J7mApplicationExtension extension) {
        Path state = this.stateFile(extension);
        try {
            String text = Files.exists(state) ? Files.readString(state, StandardCharsets.UTF_8) : "{\"state\":\"NONE\"}";
            System.out.println("j7mStatusLocal " + text.trim());
        } catch (Exception exception) {
            throw new GradleException("j7mStatusLocal failed: " + safe(exception), exception);
        }
    }

    private void stopLocal(final J7mApplicationExtension extension) {
        ApplicationBundle bundle = this.bundle(extension);
        this.writeUnavailableState(extension, bundle.coordinate());
        System.out.println("j7mStopLocal unavailable");
    }

    private void unloadLocal(final J7mApplicationExtension extension) {
        ApplicationBundle bundle = this.bundle(extension);
        this.writeUnavailableState(extension, bundle.coordinate());
        System.out.println("j7mUnloadLocal unavailable");
    }

    private LocalApplicationRepository repository(final J7mApplicationExtension extension) {
        return new LocalApplicationRepository(extension.getRepositoryDir().get().getAsFile().toPath());
    }

    private ApplicationBundle bundle(final J7mApplicationExtension extension) {
        Path path = extension.getBundleFile().get().getAsFile().toPath();
        if (!Files.isRegularFile(path)) {
            throw new GradleException("bundle does not exist");
        }
        ApplicationCoordinate coordinate = new ApplicationManifestParser()
                .parse(this.readManifest(extension))
                .identity()
                .coordinate();
        return new ApplicationBundle(path, coordinate, new org.j7m.middleware.application.repository.J7mBundleReader().sha256(path));
    }

    private String readManifest(final J7mApplicationExtension extension) {
        try {
            return Files.readString(extension.getManifest().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new GradleException("failed to read manifest: " + safe(exception), exception);
        }
    }

    private SignaturePolicy policy(final J7mApplicationExtension extension) {
        return ApplicationBundleVerifier.policy(extension.getVerificationProfile().get(), false);
    }

    private List<Path> libraryPaths(
            final Project project,
            final J7mApplicationExtension extension,
            final ApplicationManifest manifest) {
        List<Path> libraries = new ArrayList<>(this.fileCollectionPaths(extension.getLibraries()));
        if (!"JPMS".equals(manifest.moduleMode())) {
            project.getTasks().withType(Jar.class).forEach(task -> {
                Path path = task.getArchiveFile().get().getAsFile().toPath();
                if (Files.isRegularFile(path) && !libraries.contains(path)) {
                    libraries.add(path);
                }
            });
        }
        return List.copyOf(libraries);
    }

    private List<Path> modulePaths(
            final Project project,
            final J7mApplicationExtension extension,
            final ApplicationManifest manifest) {
        List<Path> modules = new ArrayList<>(this.fileCollectionPaths(extension.getModules()));
        Path projectDirectory = project.getLayout().getProjectDirectory().getAsFile().toPath();
        List<Path> declared = new JpmsPackagingValidator().validateDeclaredModules(projectDirectory, manifest, modules);
        for (Path modulePath : declared) {
            if (!modules.contains(modulePath)) {
                modules.add(modulePath);
            }
        }
        return List.copyOf(modules);
    }

    private List<Path> documentationPaths(final Project project, final J7mApplicationExtension extension) {
        List<Path> docs = new ArrayList<>(this.fileCollectionPaths(extension.getDocumentation()));
        Path readme = project.getLayout().getProjectDirectory().file("README.md").getAsFile().toPath();
        if (Files.isRegularFile(readme) && !docs.contains(readme)) {
            docs.add(readme);
        }
        return List.copyOf(docs);
    }

    private List<Path> fileCollectionPaths(final org.gradle.api.file.FileCollection files) {
        List<Path> paths = new ArrayList<>();
        for (File file : files) {
            Path path = file.toPath();
            if (Files.isRegularFile(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    private void writeUnavailableState(
            final J7mApplicationExtension extension,
            final ApplicationCoordinate coordinate) {
        try {
            Path file = this.stateFile(extension);
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    "{\"state\":\"UNAVAILABLE\",\"coordinate\":\"" + coordinate
                            + "\",\"reason\":\"local lifecycle requires an active ApplicationManager session\","
                            + "\"secrets\":\"redacted\"}\n",
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new GradleException("failed to write local J7M state: " + safe(exception), exception);
        }
    }

    private Path stateFile(final J7mApplicationExtension extension) {
        return extension.getRepositoryDir().get().getAsFile().toPath().resolve(".local-runtime-state.json");
    }

    private static String safe(final Exception exception) {
        if (exception instanceof GradleException) {
            return "gradle task failed";
        }
        if (exception instanceof java.io.IOException) {
            return "file operation failed";
        }
        if (exception instanceof RuntimeException) {
            return "application packaging operation failed";
        }
        return "operation failed";
    }
}
