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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.InaccessibleObjectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Corrective Gate 2 JPMS descriptor closure and module-boundary tests.
 */
final class Phase52JpmsDescriptorClosureTest {
    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Map<String, String> PRODUCTION_MODULES = productionModules();

    @Test
    void allProductionJavaModulesHaveMatchingDescriptors() throws IOException {
        for (Map.Entry<String, String> entry : PRODUCTION_MODULES.entrySet()) {
            Path sourceDescriptor = projectPath(entry.getKey() + "/src/main/java/module-info.java");
            Path binaryDescriptor = projectPath(entry.getKey() + "/build/classes/java/main/module-info.class");
            assertTrue(Files.exists(sourceDescriptor), "missing source descriptor for " + entry.getKey());
            assertTrue(Files.exists(binaryDescriptor), "missing compiled descriptor for " + entry.getKey());
            ModuleDescriptor descriptor = readDescriptor(binaryDescriptor);
            assertEquals(entry.getValue(), descriptor.name(), "wrong module name for " + entry.getKey());
        }
    }

    @Test
    void descriptorsDoNotExportInternalPackagesAndProductionModulesHaveNoSplitPackages() throws IOException {
        Map<String, String> packageOwners = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : PRODUCTION_MODULES.entrySet()) {
            ModuleDescriptor descriptor = readDescriptor(projectPath(entry.getKey() + "/build/classes/java/main/module-info.class"));
            assertTrue(descriptor.exports().stream().noneMatch(exported -> exported.source().contains(".internal")),
                    "internal package exported by " + entry.getKey());
            Path root = projectPath(entry.getKey() + "/src/main/java");
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("module-info.java")) {
                        continue;
                    }
                    String packageName = packageName(file);
                    String previous = packageOwners.putIfAbsent(packageName, entry.getKey());
                    assertTrue(previous == null || previous.equals(entry.getKey()),
                            "split package " + packageName + " in " + previous + " and " + entry.getKey());
                }
            }
        }
    }

    @Test
    void coreAndRuntimeDescriptorsDoNotReadConcreteTransports() throws IOException {
        ModuleDescriptor core = readDescriptor(projectPath("j7m-kernel/build/classes/java/main/module-info.class"));
        ModuleDescriptor runtime = readDescriptor(projectPath("j7m-runtime/build/classes/java/main/module-info.class"));
        for (String forbidden : List.of(
                "org.j7m.transport.grpc",
                "org.j7m.transport.rmi",
                "io.grpc",
                "java.rmi")) {
            assertFalse(reads(core, forbidden), "j7m-kernel reads " + forbidden);
            assertFalse(reads(runtime, forbidden), "j7m-runtime reads " + forbidden);
        }
    }

    @Test
    void spiProviderDeclarationsArePresent() throws IOException {
        ModuleDescriptor grpc = readDescriptor(projectPath("j7m-transport-grpc/build/classes/java/main/module-info.class"));
        ModuleDescriptor rmi = readDescriptor(projectPath("j7m-transport-rmi/build/classes/java/main/module-info.class"));
        ModuleDescriptor codec = readDescriptor(projectPath("j7m-kernel/build/classes/java/main/module-info.class"));
        assertProvides(grpc, "org.j7m.middleware.transport.api.TransportProvider",
                "org.j7m.middleware.transport.grpc.GrpcTransportProvider");
        assertProvides(rmi, "org.j7m.middleware.transport.api.TransportProvider",
                "org.j7m.middleware.transport.rmi.RmiTransportProvider");
        assertProvides(codec, "org.j7m.middleware.codec.api.ValueCodec",
                "org.j7m.middleware.codec.defaultcodec.DefaultValueCodec");
    }

    @Test
    void serviceLoaderDiscoversConfiguredProvidersInModuleLayer() throws ReflectiveOperationException {
        ModuleFinder finder = ModuleFinder.of(PRODUCTION_MODULES.keySet().stream()
                .map(project -> projectPath(project + "/build/classes/java/main"))
                .toArray(Path[]::new));
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(
                        finder,
                        ModuleFinder.of(),
                        Set.of(
                                "org.j7m.transport.rmi",
                                "org.j7m.kernel"));
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());

        Set<String> transportProviders = providerNames(
                layer,
                "org.j7m.api",
                "org.j7m.middleware.transport.api.TransportProvider");
        assertTrue(transportProviders.contains("org.j7m.middleware.transport.rmi.RmiTransportProvider"));

        Set<String> codecProviders = providerNames(
                layer,
                "org.j7m.api",
                "org.j7m.middleware.codec.api.ValueCodec");
        assertTrue(codecProviders.contains("org.j7m.middleware.codec.defaultcodec.DefaultValueCodec"));
    }

    @Test
    void moduleLayerDeniesReflectiveAccessToUnexportedCoreInternalPackage() throws ReflectiveOperationException {
        ModuleFinder finder = ModuleFinder.of(PRODUCTION_MODULES.keySet().stream()
                .map(project -> projectPath(project + "/build/classes/java/main"))
                .toArray(Path[]::new));
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), Set.of("org.j7m.kernel"));
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        ClassLoader loader = layer.findLoader("org.j7m.kernel");
        Class<?> sentinel = loader.loadClass("org.j7m.middleware.core.internal.JpmsInternalSentinel");
        Constructor<?> constructor = sentinel.getDeclaredConstructor();
        assertThrows(IllegalAccessException.class, () -> constructor.newInstance());
        assertThrows(InaccessibleObjectException.class, () -> constructor.setAccessible(true));
    }

    @Test
    void gradlePluginIsTheOnlyNonDescriptorJavaModuleAndIsClassifiedSeparately() {
        assertFalse(PRODUCTION_MODULES.containsKey("j7m-gradle-plugin"));
        assertTrue(Files.isDirectory(projectPath("j7m-gradle-plugin/src/main/java")));
        assertFalse(Files.exists(projectPath("j7m-gradle-plugin/src/main/java/module-info.java")));
    }

    private static boolean reads(final ModuleDescriptor descriptor, final String moduleName) {
        return descriptor.requires().stream().anyMatch(require -> require.name().equals(moduleName));
    }

    private static void assertProvides(
            final ModuleDescriptor descriptor,
            final String service,
            final String provider) {
        assertTrue(descriptor.provides().stream()
                        .anyMatch(provides -> provides.service().equals(service) && provides.providers().contains(provider)),
                descriptor.name() + " missing provider " + provider + " for " + service);
    }

    private static Set<String> providerNames(
            final ModuleLayer layer,
            final String serviceModuleName,
            final String serviceClassName) throws ClassNotFoundException {
        ClassLoader loader = layer.findLoader(serviceModuleName);
        Class<?> serviceType = loader.loadClass(serviceClassName);
        Set<String> names = new HashSet<>();
        for (Object provider : ServiceLoader.load(layer, serviceType)) {
            names.add(provider.getClass().getName());
        }
        return Set.copyOf(names);
    }

    private static String packageName(final Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int start = text.indexOf("package ");
        assertTrue(start >= 0, "missing package declaration in " + file);
        int end = text.indexOf(';', start);
        assertTrue(end > start, "malformed package declaration in " + file);
        return text.substring(start + "package ".length(), end).trim();
    }

    private static ModuleDescriptor readDescriptor(final Path moduleInfoClass) throws IOException {
        try (InputStream input = Files.newInputStream(moduleInfoClass)) {
            return ModuleDescriptor.read(input);
        }
    }

    private static Path projectPath(final String relative) {
        return PROJECT_ROOT.resolve(relative).normalize();
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate project root from " + Path.of("").toAbsolutePath());
    }

    private static Map<String, String> productionModules() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("j7m-api", "org.j7m.api");
        modules.put("j7m-kernel", "org.j7m.kernel");
        modules.put("j7m-runtime", "org.j7m.runtime");
        modules.put("j7m-application-platform", "org.j7m.application.platform");
        modules.put("j7m-transport-grpc", "org.j7m.transport.grpc");
        modules.put("j7m-transport-rmi", "org.j7m.transport.rmi");
        modules.put("j7m-deployer-cli", "org.j7m.deployer.cli");
        modules.put("j7m-testkit", "org.j7m.testkit");
        modules.put("j7m-examples", "org.j7m.examples");
        return Map.copyOf(modules);
    }
}
