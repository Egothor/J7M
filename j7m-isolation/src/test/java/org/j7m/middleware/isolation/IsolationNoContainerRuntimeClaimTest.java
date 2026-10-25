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

package org.j7m.middleware.isolation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class IsolationNoContainerRuntimeClaimTest {
    @Test
    void docsAndBuildAvoidContainerRuntimeClaimsAndDependencies() throws Exception {
        final String docs = Files.exists(Path.of("../docs/internal/06000 - Security Reviews/isolation-provider.md"))
                ? Files.readString(Path.of("../docs/internal/06000 - Security Reviews/isolation-provider.md")).toLowerCase(java.util.Locale.ROOT)
                : "";
        final String rootBuild = Files.readString(Path.of("../build.gradle")).toLowerCase(java.util.Locale.ROOT);
        for (String line : rootBuild.lines().toList()) {
            String trimmed = line.trim();
            boolean productionDependency = trimmed.matches("^(api|implementation|compileonly|runtimeonly)\\s+.*");
            if (productionDependency) {
                assertFalse(trimmed.contains("com.github.dockerjava"), line);
                assertFalse(trimmed.contains("io.kubernetes"), line);
                assertFalse(trimmed.contains("org.testcontainers"), line);
            }
        }
        if (!docs.isEmpty()) {
            assertTrue(docs.contains("not docker"));
            assertTrue(docs.contains("not kubernetes"));
            assertTrue(docs.contains("not a full container runtime"));
            assertTrue(docs.contains("advisory"));
        }
    }
}
