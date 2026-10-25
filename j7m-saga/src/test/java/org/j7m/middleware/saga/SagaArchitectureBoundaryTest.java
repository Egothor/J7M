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

package org.j7m.middleware.saga;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SagaArchitectureBoundaryTest {
    @Test
    void productionModuleHasNoDemoMainOrForbiddenRuntimeDependencies() throws Exception {
        Path root = SagaTestSupport.repositoryRoot();
        String moduleInfo = Files.readString(root.resolve("j7m-saga/src/main/java/module-info.java"));
        String build = rootBuildLogic(root);

        assertTrue(moduleInfo.contains("module org.j7m.saga"));
        assertTrue(moduleInfo.contains("requires transitive org.j7m.api"));
        assertTrue(moduleInfo.contains("requires org.j7m.durablelog"));
        assertTrue(moduleInfo.contains("requires org.j7m.messaging"));
        assertFalse(moduleInfo.contains("org.j7m.runtime"));
        assertFalse(moduleInfo.contains("org.j7m.transport"));
        assertFalse(moduleInfo.contains("org.j7m.demo"));
        assertFalse(Files.walk(root.resolve("j7m-saga/src/main/java"))
                .filter(Files::isRegularFile)
                .anyMatch(path -> path.getFileName().toString().contains("Demo") || safeRead(path).contains("public static void main")));
        assertTrue(build.contains("validateSaga"));
        assertTrue(build.contains("[\"tempo\", \"ral\"].join(\"\")"));
        assertTrue(build.contains("[\"nara\", \"yana\"].join(\"\")"));
        assertTrue(build.contains("[\"jakarta\", \"transaction\"].join(\".\")"));
    }

    private static String safeRead(final Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String rootBuildLogic(final Path root) throws Exception {
        StringBuilder builder = new StringBuilder(Files.readString(root.resolve("build.gradle")));
        Path rootBuildLogic = root.resolve("gradle/build-logic/root");
        try (java.util.stream.Stream<Path> stream = Files.walk(rootBuildLogic)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".gradle")).sorted().toList()) {
                builder.append('\n').append(Files.readString(file));
            }
        }
        return builder.toString();
    }
}
