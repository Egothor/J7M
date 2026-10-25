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
import java.time.Duration;
import org.j7m.middleware.api.isolation.IsolationExecutionResult;
import org.j7m.middleware.api.isolation.IsolationProviderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IsolationAuditStatusMetricsTest {
    @Test
    void auditMetricsAndHealthAreSafe(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final IsolationExecutionResult result = provider.execute(IsolationTestSupport.request(directory,
                    IsolationTestSupport.javaCommand("secret"), Duration.ofSeconds(5)));
            assertTrue(provider.metrics().completedExecutions() + provider.metrics().failedExecutions() >= 1L);
            assertTrue(provider.health().available());
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.started".equals(event.action())));
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.completed".equals(event.action())));
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "output.redacted".equals(event.action())));
            assertFalse(provider.auditEvents().toString().contains("secret-value-987"));
            assertFalse(result.toString().contains("secret-value-987"));
        }
    }

    @Test
    void compositeProviderAggregatesDelegateMetrics(@TempDir final Path directory) {
        try (DefaultIsolationProvider provider = new DefaultIsolationProvider(new IsolationProviderId("composite-metrics"),
                IsolationConfig.defaults(directory))) {
            provider.execute(IsolationTestSupport.request(directory, IsolationTestSupport.javaCommand("success"), Duration.ofSeconds(5)));
            assertTrue(provider.metrics().completedExecutions() >= 1L);
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.started".equals(event.action())));
        }
    }
}
