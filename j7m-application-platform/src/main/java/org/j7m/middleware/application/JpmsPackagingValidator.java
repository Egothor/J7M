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

package org.j7m.middleware.application.repository;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.j7m.middleware.application.api.ApplicationManifest;

/**
 * Shared strict JPMS application packaging validator.
 *
 * <p>The validator is stateless and thread-safe. It validates only manifest
 * declared module artifacts beneath an explicit project root. It normalizes
 * relative paths, rejects absolute paths, traversal, outside-root resolution,
 * duplicate manifest paths, duplicate module names, missing artifacts,
 * automatic or ambiguous module descriptors, descriptor-name mismatches, and
 * service interface or implementation class entries that are absent from all
 * declared module JARs. It never loads application classes and never searches
 * by basename.</p>
 */
public final class JpmsPackagingValidator {
    private final ApplicationPlatformLimits limits;

    /**
     * Creates a validator using default application-platform limits.
     */
    public JpmsPackagingValidator() {
        this(ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a validator with explicit limits.
     *
     * @param limits packaging bounds
     */
    public JpmsPackagingValidator(final ApplicationPlatformLimits limits) {
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
    }

    /**
     * Validates manifest-declared JPMS module artifacts and returns their exact
     * normalized paths.
     *
     * @param projectRoot project root used to resolve manifest relative paths
     * @param manifest parsed application manifest
     * @param explicitModulePaths module paths supplied outside the manifest;
     * when non-empty they must exactly match declared manifest paths
     * @return immutable normalized module artifact paths in manifest order
     * @throws IllegalArgumentException if any module path, descriptor, class
     * entry, duplicate, size, missing, or undeclared-path rule is violated
     */
    public List<Path> validateDeclaredModules(
            final Path projectRoot,
            final ApplicationManifest manifest,
            final List<Path> explicitModulePaths) {
        if (projectRoot == null || manifest == null) {
            throw new IllegalArgumentException("project root and manifest are required");
        }
        if (!"JPMS".equals(manifest.moduleMode())) {
            return List.of();
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Path> modules = new ArrayList<>(manifest.modules().size());
        Set<Path> declaredPaths = new HashSet<>();
        Set<String> moduleNames = new HashSet<>();
        Map<String, Path> classEntries = new HashMap<>();
        for (ApplicationManifest.ModuleDeclaration declaration : manifest.modules()) {
            Path modulePath = this.resolveDeclaredPath(root, declaration);
            if (!declaredPaths.add(modulePath)) {
                throw new IllegalArgumentException("duplicate JPMS module artifact path");
            }
            if (!moduleNames.add(declaration.name())) {
                throw new IllegalArgumentException("duplicate JPMS module name");
            }
            this.validateModuleArtifact(modulePath, declaration);
            this.recordClassEntries(modulePath, classEntries);
            modules.add(modulePath);
        }
        this.validateNoUndeclaredExplicitModules(root, explicitModulePaths, declaredPaths);
        this.validateDeclaredServiceEntries(manifest, classEntries);
        return List.copyOf(modules);
    }

    private Path resolveDeclaredPath(final Path root, final ApplicationManifest.ModuleDeclaration declaration) {
        if (declaration == null || declaration.name().isBlank() || declaration.path().isBlank()) {
            throw new IllegalArgumentException("declared JPMS module is required");
        }
        Path declaredPath = Path.of(declaration.path());
        if (declaredPath.isAbsolute()) {
            throw new IllegalArgumentException("declared JPMS module path must be relative");
        }
        Path normalized = root.resolve(declaredPath).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("declared JPMS module path escapes project root");
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("declared JPMS module is missing");
        }
        return normalized;
    }

    private void validateModuleArtifact(final Path modulePath, final ApplicationManifest.ModuleDeclaration declaration) {
        try {
            if (Files.size(modulePath) > this.limits.maxBundleBytes()) {
                throw new IllegalArgumentException("declared JPMS module exceeds bundle size limit");
            }
            try (JarFile jar = new JarFile(modulePath.toFile(), true)) {
                if (jar.getEntry("module-info.class") == null) {
                    throw new IllegalArgumentException("declared JPMS module has no descriptor");
                }
            }
            ModuleFinder finder = ModuleFinder.of(modulePath);
            Set<ModuleReference> references = finder.findAll();
            if (references.size() != 1) {
                throw new IllegalArgumentException("declared JPMS module descriptor is ambiguous");
            }
            ModuleDescriptor descriptor = references.iterator().next().descriptor();
            if (descriptor.isAutomatic()) {
                throw new IllegalArgumentException("declared JPMS module must not be automatic");
            }
            if (!declaration.name().equals(descriptor.name())) {
                throw new IllegalArgumentException("declared JPMS module name does not match descriptor");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("declared JPMS module descriptor is invalid", exception);
        }
    }

    private void recordClassEntries(final Path modulePath, final Map<String, Path> classEntries) {
        try (JarFile jar = new JarFile(modulePath.toFile(), true)) {
            jar.stream().forEach(entry -> {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")
                        && !"module-info.class".equals(entry.getName())) {
                    Path previous = classEntries.putIfAbsent(entry.getName(), modulePath);
                    if (previous != null && !previous.equals(modulePath)) {
                        throw new IllegalArgumentException("duplicate JPMS class entry across declared modules");
                    }
                }
            });
        } catch (IOException exception) {
            throw new IllegalArgumentException("declared JPMS module class entries are invalid", exception);
        }
    }

    private void validateNoUndeclaredExplicitModules(
            final Path root,
            final List<Path> explicitModulePaths,
            final Set<Path> declaredPaths) {
        List<Path> safeExplicitPaths = explicitModulePaths == null ? List.of() : List.copyOf(explicitModulePaths);
        for (Path explicitPath : safeExplicitPaths) {
            Path normalized = explicitPath.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || !declaredPaths.contains(normalized)) {
                throw new IllegalArgumentException("undeclared JPMS module artifact is not allowed");
            }
        }
    }

    private void validateDeclaredServiceEntries(
            final ApplicationManifest manifest,
            final Map<String, Path> classEntries) {
        for (ApplicationManifest.ServiceDeclaration service : manifest.services()) {
            this.requireClassEntry(classEntries, service.interfaceName());
            this.requireClassEntry(classEntries, service.implementationClass());
        }
    }

    private void requireClassEntry(final Map<String, Path> classEntries, final String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("declared JPMS service class is required");
        }
        String entryName = className.replace('.', '/') + ".class";
        if (!classEntries.containsKey(entryName)) {
            throw new IllegalArgumentException("declared JPMS service class entry is missing");
        }
    }
}
