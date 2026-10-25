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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.j7m.middleware.api.isolation.FileSystemAccess;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationExecutionState;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.ResourceEnforcementLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InProcessIsolationPolicyTest {
    @Test
    void inProcessPolicyProviderDocumentsPolicyBoundary(@TempDir final Path directory) {
        try (InProcessIsolationProvider provider = IsolationTestSupport.inProcessProvider(directory)) {
            assertTrue(provider.evaluateFileSystem(IsolationTestSupport.subject(), IsolationTestSupport.allowingPolicy(directory).fileSystemPolicy(),
                    FileSystemAccess.WRITE, directory.resolve("out.txt")).allowed());
            assertFalse(provider.evaluateNetworkEgress(IsolationTestSupport.subject(),
                    IsolationTestSupport.allowingPolicy(directory).networkEgressPolicy(), URI.create("https://203.0.113.10:443")).allowed());
            assertTrue(provider.evaluateEnvironment(IsolationTestSupport.subject(),
                    IsolationTestSupport.allowingPolicy(directory).environmentPolicy(), "TOKEN", IsolationTestSupport.SECRET)
                    .metadata().containsValue("<redacted>"));
            final IsolationExecutionRequest request = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.IN_PROCESS_POLICY, null, IsolationTestSupport.allowingPolicy(directory),
                    null, Duration.ofSeconds(1), "corr");
            assertEquals(IsolationExecutionState.COMPLETED, provider.execute(request).state());
            assertEquals(ResourceEnforcementLevel.POLICY_ONLY, request.budget().threadBudget().level());
        }
    }
}
