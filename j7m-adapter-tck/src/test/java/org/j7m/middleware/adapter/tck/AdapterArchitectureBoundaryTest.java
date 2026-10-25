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

package org.j7m.middleware.adapter.tck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

final class AdapterArchitectureBoundaryTest {
    private static final Path ROOT = repositoryRoot();

    @Test
    void adapterModulesAvoidVendorSdkDependenciesAndRuntimeLeakage() throws IOException {
        final String adapterSources = readMainSources("j7m-adapter-spi") + readMainSources("j7m-adapter-tck");

        for (String token : List.of(
                "org.apache.kafka",
                "com.rabbitmq",
                "org.apache.pulsar",
                "io.etcd",
                "org.apache.zookeeper",
                "io.kubernetes",
                "io.fabric8",
                "org.postgresql",
                "org.elasticsearch",
                "software.amazon.awssdk",
                "com.google.cloud",
                "com.azure",
                "com.github.dockerjava",
                "testcontainers")) {
            assertFalse(adapterSources.contains(token), "forbidden vendor token in adapter main source: " + token);
        }
    }

    @Test
    void fakeAdaptersAreTestFixturesOnly() throws IOException {
        try (Stream<Path> mainSources = Files.walk(ROOT.resolve("j7m-adapter-tck/src/main/java"))) {
            assertTrue(mainSources
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .noneMatch(name -> name.contains("Fake")));
        }
        assertTrue(Files.isRegularFile(ROOT.resolve("j7m-adapter-tck/src/test/java/org/j7m/middleware/adapter/tck/FakeAdapters.java")));
    }

    @Test
    void adapterModuleDescriptorsDoNotExportInternalPackages() throws IOException {
        final String spiDescriptor = Files.readString(ROOT.resolve("j7m-adapter-spi/src/main/java/module-info.java"), StandardCharsets.UTF_8);
        final String tckDescriptor = Files.readString(ROOT.resolve("j7m-adapter-tck/src/main/java/module-info.java"), StandardCharsets.UTF_8);

        assertFalse(spiDescriptor.contains(".internal"));
        assertFalse(tckDescriptor.contains(".internal"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isDirectory(current.resolve("j7m-adapter-tck"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String readMainSources(final String module) throws IOException {
        final StringBuilder builder = new StringBuilder();
        final Path root = ROOT.resolve(module).resolve("src").resolve("main").resolve("java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                builder.append(Files.readString(source, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT));
                builder.append('\n');
            }
        }
        return builder.toString();
    }
}
