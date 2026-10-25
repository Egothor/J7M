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

final class SagaNoDistributedTransactionClaimTest {
    @Test
    void docsAndApiDoNotClaimDistributedAcidOrExactlyOnce() throws Exception {
        Path root = SagaTestSupport.repositoryRoot();
        String docs = Files.exists(root.resolve("docs/internal/01000 - Normative Specifications/saga.md"))
                ? Files.readString(root.resolve("docs/internal/01000 - Normative Specifications/saga.md"))
                : "";
        String api = String.join("\n", Files.walk(root.resolve("j7m-api/src/main/java/org/j7m/middleware/api/saga"))
                .filter(Files::isRegularFile)
                .map(SagaNoDistributedTransactionClaimTest::read)
                .toList());

        String normalizedApi = api.toLowerCase(java.util.Locale.ROOT);
        assertFalse(normalizedApi.contains("provides xa"));
        assertFalse(normalizedApi.contains("implements xa"));
        assertFalse(normalizedApi.contains("two-phase commit coordinator"));
        assertFalse(normalizedApi.contains("guarantees generic exactly-once"));
        assertTrue(docs.isBlank() || docs.contains("not an ACID transaction"));
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
