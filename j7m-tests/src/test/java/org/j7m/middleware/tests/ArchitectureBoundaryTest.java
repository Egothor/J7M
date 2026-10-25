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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.TransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Tests dependency boundaries that are central to the Phase 1 architecture.
 */
public final class ArchitectureBoundaryTest {
    @Test
    public void coreAndRuntimeDoNotImportConcreteTransports() {
        assertFalse(importsForbiddenTransport("j7m-kernel"));
        assertFalse(importsForbiddenTransport("j7m-runtime"));
    }

    @Test
    public void coreAndRuntimeDoNotDeclareConcreteTransportOrSchedulerDependencies() {
        String build = readBuildFile();
        assertFalse(projectBlock(build, "j7m-kernel").contains("project(\":j7m-transport-grpc\")"));
        assertFalse(projectBlock(build, "j7m-kernel").contains("project(\":j7m-transport-rmi\")"));
        assertFalse(projectBlock(build, "j7m-runtime").contains("project(\":j7m-transport-grpc\")"));
        assertFalse(projectBlock(build, "j7m-runtime").contains("project(\":j7m-transport-rmi\")"));
    }

    @Test
    public void grpcAndDefaultCodecDoNotUseJavaNativeSerialization() {
        assertFalse(sourceContains("j7m-transport-grpc", "ObjectOutputStream"));
        assertFalse(sourceContains("j7m-transport-grpc", "ObjectInputStream"));
        assertFalse(sourceContains("j7m-kernel", "ObjectOutputStream"));
        assertFalse(sourceContains("j7m-kernel", "ObjectInputStream"));
        assertFalse(sourceContains("j7m-kernel", "java.io.Serializable"));
    }

    @Test
    public void concreteTransportsDoNotUseInProcessShortcut() {
        assertFalse(sourceContains("j7m-transport-grpc", "AbstractInProcessTransportProvider"));
        assertFalse(sourceContains("j7m-transport-rmi", "AbstractInProcessTransportProvider"));
        assertFalse(sourceContains("j7m-transport-grpc", "InProcessTransportNetwork"));
        assertFalse(sourceContains("j7m-transport-rmi", "InProcessTransportNetwork"));
    }

    @Test
    public void accidentalTransportImplementationsAreNotPublicExportedApi() {
        String apiInProcessNetwork = readSource("j7m-api", "org/j7m/middleware/transport/api/InProcessTransportNetwork.java");
        String apiInProcessProvider = readSource("j7m-api", "org/j7m/middleware/transport/api/AbstractInProcessTransportProvider.java");
        String apiDescriptor = readSource("j7m-api", "module-info.java");
        String grpcDescriptor = readSource("j7m-transport-grpc", "module-info.java");
        String rmiDescriptor = readSource("j7m-transport-rmi", "module-info.java");

        assertFalse(apiInProcessNetwork.contains("public final class InProcessTransportNetwork"));
        assertFalse(apiInProcessProvider.contains("public abstract class AbstractInProcessTransportProvider"));
        assertFalse(sourceExists("j7m-transport-rmi", "org/j7m/middleware/transport/rmi/RmiRemoteObjectService.java"));
        assertTrue(grpcDescriptor.contains("provides org.j7m.middleware.transport.api.TransportProvider"));
        assertTrue(rmiDescriptor.contains("exports org.j7m.middleware.transport.rmi;"));
        assertFalse(rmiDescriptor.contains("exports org.j7m.middleware.transport.rmi.internal"));
        assertFalse(rmiDescriptor.contains("opens org.j7m.middleware.transport.rmi.internal"));
        assertTrue(rmiDescriptor.contains("provides org.j7m.middleware.transport.api.TransportProvider"));
        assertTrue(apiDescriptor.contains("exports org.j7m.middleware.transport.api;"));
    }

    @Test
    public void transportProvidersAreDiscoverableThroughServiceLoader() {
        List<String> providerNames = new ArrayList<>();
        ServiceLoader<TransportProvider> loader = ServiceLoader.load(TransportProvider.class);
        for (TransportProvider provider : loader) {
            providerNames.add(provider.getClass().getName());
            provider.close();
        }
        providerNames.sort(Comparator.naturalOrder());

        assertTrue(providerNames.contains("org.j7m.middleware.transport.grpc.GrpcTransportProvider"));
        assertTrue(providerNames.contains("org.j7m.middleware.transport.rmi.RmiTransportProvider"));
    }

    @Test
    public void runtimeBuilderFailsFastWhenServiceLoaderFindsNoProviders() {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(new ClassLoader(null) {
        });
        try {
            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> new MiddlewareRuntimeBuilder().runtimeId("architecture-no-provider").build());
            assertEquals(MiddlewareErrorCode.TRANSPORT_ERROR, failure.code());
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static boolean importsForbiddenTransport(final String moduleName) {
        Path root = Path.of(moduleName, "src", "main", "java");
        if (!Files.exists(root)) {
            root = Path.of("..", moduleName, "src", "main", "java").normalize();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    String source = Files.readString(path);
                    return source.contains("org.j7m.middleware.transport.grpc")
                            || source.contains("org.j7m.middleware.transport.rmi")
                            || source.contains("io.grpc")
                            || source.contains("java.rmi");
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean sourceContains(final String moduleName, final String needle) {
        Path root = Path.of(moduleName, "src", "main", "java");
        if (!Files.exists(root)) {
            root = Path.of("..", moduleName, "src", "main", "java").normalize();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    return Files.readString(path).contains(needle);
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String readBuildFile() {
        Path buildFile = Path.of("build.gradle");
        if (!Files.exists(buildFile)) {
            buildFile = Path.of("..", "build.gradle").normalize();
        }
        try {
            return Files.readString(buildFile);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String readSource(final String moduleName, final String relativePath) {
        Path source = Path.of(moduleName, "src", "main", "java", relativePath);
        if (!Files.exists(source)) {
            source = Path.of("..", moduleName, "src", "main", "java", relativePath).normalize();
        }
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean sourceExists(final String moduleName, final String relativePath) {
        Path source = Path.of(moduleName, "src", "main", "java", relativePath);
        if (!Files.exists(source)) {
            source = Path.of("..", moduleName, "src", "main", "java", relativePath).normalize();
        }
        return Files.exists(source);
    }

    private static String projectBlock(final String buildFile, final String projectName) {
        String marker = "project(" + '"' + ":" + projectName + '"' + ")";
        int markerIndex = buildFile.indexOf(marker);
        if (markerIndex < 0) {
            throw new AssertionError("missing Gradle project block for " + projectName);
        }
        int openBrace = buildFile.indexOf('{', markerIndex + marker.length());
        if (openBrace < 0) {
            throw new AssertionError("missing Gradle project block body for " + projectName);
        }
        int start = openBrace + 1;
        int depth = 1;
        for (int index = start; index < buildFile.length(); index++) {
            char current = buildFile.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return buildFile.substring(start, index);
                }
            }
        }
        throw new AssertionError("unterminated Gradle project block for " + projectName);
    }
}
