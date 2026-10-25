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

package org.j7m.middleware.application.replication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RepositoryReplicationArchitectureBoundaryTest {
    @Test
    void applicationPlatformHasNoDemoMainOrExternalObjectStorageDependency() throws Exception {
        Path root = projectRoot();
        Path main = root.resolve("j7m-application-platform/src/main/java");
        try (java.util.stream.Stream<Path> stream = Files.walk(main)) {
            for (Path source : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                assertFalse(text.contains("public static void main"), source.toString());
                assertFalse(source.getFileName().toString().contains("Demo"), source.toString());
            }
        }
        String build = rootBuildLogic(root);
        for (String line : build.lines().toList()) {
            String trimmed = line.trim();
            boolean productionDependency = trimmed.matches("^(api|implementation|compileOnly|runtimeOnly)\\s+.*");
            if (productionDependency) {
                assertFalse(trimmed.contains("software.amazon.awssdk"), trimmed);
                assertFalse(trimmed.contains("google-cloud-storage"), trimmed);
            }
        }
        assertTrue(build.contains("validateRepositoryReplication"));
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        return Files.isDirectory(current.resolve("j7m-application-platform")) ? current : current.getParent();
    }

    private static String rootBuildLogic(final Path root) throws Exception {
        StringBuilder builder = new StringBuilder(Files.readString(root.resolve("build.gradle"), StandardCharsets.UTF_8));
        Path rootBuildLogic = root.resolve("gradle/build-logic/root");
        try (java.util.stream.Stream<Path> stream = Files.walk(rootBuildLogic)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".gradle")).sorted().toList()) {
                builder.append('\n').append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return builder.toString();
    }
}
