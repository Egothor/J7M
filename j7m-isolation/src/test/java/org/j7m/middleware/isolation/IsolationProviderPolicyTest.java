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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.isolation.FileSystemAccess;
import org.j7m.middleware.api.isolation.FileSystemPolicy;
import org.j7m.middleware.api.isolation.IsolationException;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationProviderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IsolationProviderPolicyTest {
    @Test
    void providerSelectionAndPolicyDecisionsWork(@TempDir final Path directory) {
        try (DefaultIsolationProvider provider = new DefaultIsolationProvider(new IsolationProviderId("default"), IsolationTestSupport.config(directory))) {
            assertTrue(provider.supportedModes().contains(IsolationMode.IN_PROCESS_POLICY));
            assertTrue(provider.supportedModes().contains(IsolationMode.LOCAL_PROCESS));
            assertTrue(provider.evaluateFileSystem(IsolationTestSupport.subject(), IsolationTestSupport.allowingPolicy(directory).fileSystemPolicy(),
                    FileSystemAccess.READ, directory.resolve("a.txt")).allowed());
            assertFalse(provider.evaluateFileSystem(IsolationTestSupport.subject(), FileSystemPolicy.denyAll(),
                    FileSystemAccess.READ, directory.resolve("a.txt")).allowed());
            assertFalse(provider.evaluateNetworkEgress(IsolationTestSupport.subject(),
                    IsolationTestSupport.allowingPolicy(directory).networkEgressPolicy(), URI.create("https://example.com:443")).allowed());
        }
    }

    @Test
    void closedProviderRejectsWork(@TempDir final Path directory) {
        final InProcessIsolationProvider provider = IsolationTestSupport.inProcessProvider(directory);
        provider.close();
        final IsolationException exception = assertThrows(IsolationException.class, () -> provider.start(
                new org.j7m.middleware.api.isolation.IsolationExecutionRequest(IsolationTestSupport.subject(), IsolationMode.IN_PROCESS_POLICY,
                        null, IsolationTestSupport.allowingPolicy(directory), null, java.time.Duration.ofSeconds(1), "")));
        assertEquals(MiddlewareErrorCode.UNAVAILABLE, exception.code());
    }
}
