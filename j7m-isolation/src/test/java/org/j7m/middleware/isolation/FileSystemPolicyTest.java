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

import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.api.isolation.FileSystemAccess;
import org.j7m.middleware.api.isolation.FileSystemPolicy;
import org.j7m.middleware.api.isolation.FileSystemRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSystemPolicyTest {
    @Test
    void denyAllowNormalizeAndTraversal(@TempDir final Path directory) {
        try (InProcessIsolationProvider provider = IsolationTestSupport.inProcessProvider(directory)) {
            final FileSystemPolicy policy = new FileSystemPolicy(true, List.of(
                    new FileSystemRule(FileSystemAccess.READ, directory.resolve("allowed"), true, true),
                    new FileSystemRule(FileSystemAccess.WRITE, directory.resolve("allowed"), true, true),
                    new FileSystemRule(FileSystemAccess.WRITE, directory.resolve("allowed/deny"), false, true)));
            assertFalse(provider.evaluateFileSystem(IsolationTestSupport.subject(), FileSystemPolicy.denyAll(),
                    FileSystemAccess.READ, directory).allowed());
            assertTrue(provider.evaluateFileSystem(IsolationTestSupport.subject(), policy,
                    FileSystemAccess.READ, directory.resolve("allowed/../allowed/file.txt")).allowed());
            assertFalse(provider.evaluateFileSystem(IsolationTestSupport.subject(), policy,
                    FileSystemAccess.WRITE, directory.resolve("allowed/deny/file.txt")).allowed());
            assertFalse(provider.evaluateFileSystem(IsolationTestSupport.subject(), policy,
                    FileSystemAccess.READ, directory.resolve("allowed/../../outside.txt")).allowed());
        }
    }
}
