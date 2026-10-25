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

package org.j7m.middleware.durablelog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public final class DurableLogArchitectureBoundaryTest {
    @Test
    public void kernelAndRuntimeDoNotRequireDurableLogAndNoInternalExportsExist() throws Exception {
        Path root = Files.exists(Path.of("..", "j7m-kernel")) ? Path.of("..") : Path.of(".");
        String kernel = Files.readString(root.resolve("j7m-kernel/src/main/java/module-info.java"));
        String runtime = Files.readString(root.resolve("j7m-runtime/src/main/java/module-info.java"));
        String durable = Files.readString(root.resolve("j7m-durable-log/src/main/java/module-info.java"));

        assertFalse(kernel.contains("org.j7m.durablelog"));
        assertFalse(runtime.contains("org.j7m.durablelog"));
        assertFalse(durable.contains("exports") && durable.contains(".internal"));
        assertFalse(durable.contains("org.j7m.transport.grpc"));
        assertFalse(durable.contains("org.j7m.transport.rmi"));
    }

    @Test
    public void diagnosticCrashHarnessIsNotInExportedProductionSurface() throws Exception {
        Path root = Files.exists(Path.of("..", "j7m-durable-log")) ? Path.of("..") : Path.of(".");
        String durable = Files.readString(root.resolve("j7m-durable-log/src/main/java/module-info.java"));

        assertFalse(durable.contains("org.j7m.middleware.durablelog.internal"));
        assertFalse(Files.exists(root.resolve("j7m-durable-log/src/main/java/org/j7m/middleware/durablelog/TestCrashHarness.java")));
    }

    @Test
    public void exportedDurableLogPackageContainsOnlyIntendedPublicFacadeTypes() throws Exception {
        Path root = Files.exists(Path.of("..", "j7m-durable-log")) ? Path.of("..") : Path.of(".");
        Path exportedPackage = root.resolve("j7m-durable-log/src/main/java/org/j7m/middleware/durablelog");
        Set<String> allowedPublicTypes = Set.of(
                "DurableLogBuilder.java",
                "DurableLogConfig.java",
                "DurableLogFactory.java",
                "DurableLogHealth.java",
                "FileDurableLog.java",
                "LogCompactionMetadata.java");

        try (Stream<Path> files = Files.list(exportedPackage)) {
            Set<String> unexpectedPublicTypes = files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !allowedPublicTypes.contains(path.getFileName().toString()))
                    .filter(DurableLogArchitectureBoundaryTest::containsPublicTopLevelType)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(unexpectedPublicTypes.isEmpty(), "unexpected public durable-log implementation types: " + unexpectedPublicTypes);
        }
    }

    @Test
    public void segmentIndexDoesNotExposeFullHistoryLocationListLookups() throws Exception {
        Path root = Files.exists(Path.of("..", "j7m-durable-log")) ? Path.of("..") : Path.of(".");
        String source = Files.readString(root.resolve("j7m-durable-log/src/main/java/org/j7m/middleware/durablelog/SegmentIndex.java"));

        assertFalse(source.contains("locationsDescending("));
        assertFalse(source.contains("locationsAtOrAfter("));
        assertFalse(source.contains("List<IndexedLocation>"));
        assertFalse(source.contains("List<RecordLocation>"));
    }

    private static boolean containsPublicTopLevelType(final Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("public final class ")
                    || source.contains("public class ")
                    || source.contains("public record ")
                    || source.contains("public interface ")
                    || source.contains("public enum ");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to read durable-log source", exception);
        }
    }
}
