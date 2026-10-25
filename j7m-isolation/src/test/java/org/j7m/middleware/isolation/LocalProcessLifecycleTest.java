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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.isolation.IsolationException;
import org.j7m.middleware.api.isolation.IsolationExecutionHandle;
import org.j7m.middleware.api.isolation.IsolationExecutionResult;
import org.j7m.middleware.api.isolation.IsolationExecutionState;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationProcessSpec;
import org.j7m.middleware.api.isolation.ResourceBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalProcessLifecycleTest {
    @Test
    void launchCompleteFailTimeoutAndCancel(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            assertEquals(IsolationExecutionState.COMPLETED,
                    provider.execute(IsolationTestSupport.request(directory, IsolationTestSupport.javaCommand("success"), Duration.ofSeconds(5))).state());
            assertEquals(IsolationExecutionState.FAILED,
                    provider.execute(IsolationTestSupport.request(directory, IsolationTestSupport.javaCommand("fail"), Duration.ofSeconds(5))).state());
            assertEquals(IsolationExecutionState.TIMED_OUT,
                    provider.execute(IsolationTestSupport.request(directory, IsolationTestSupport.javaCommand("sleep"), Duration.ofMillis(200))).state());
            assertEquals(1L, provider.metrics().timedOutExecutions());
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.timed_out".equals(event.action())));
            final IsolationExecutionHandle handle = provider.start(IsolationTestSupport.request(directory,
                    IsolationTestSupport.javaCommand("sleep"), Duration.ofSeconds(5)));
            final IsolationExecutionResult cancelled = handle.cancel();
            assertTrue(cancelled.state() == IsolationExecutionState.CANCELLED || cancelled.state() == IsolationExecutionState.KILLED);
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "cleanup.completed".equals(event.action())));
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.failed".equals(event.action())));
        }
    }

    @Test
    void effectiveRuntimeUsesShorterRequestTimeout(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final ResourceBudget budget = new ResourceBudget(4, Duration.ofSeconds(5), 128, 128,
                    32, 8192, 8192, 64, null, null, null, null);
            final IsolationExecutionRequest request = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("sleep"), directory, java.util.Map.of()),
                    IsolationTestSupport.allowingPolicy(directory), budget, Duration.ofMillis(200), "corr");
            final IsolationExecutionResult result = provider.execute(request);
            assertEquals(IsolationExecutionState.TIMED_OUT, result.state());
            assertTrue(result.usage().runtime().compareTo(Duration.ofSeconds(3)) < 0);
            assertEquals(1L, provider.metrics().timedOutExecutions());
        }
    }

    @Test
    void effectiveRuntimeUsesShorterBudget(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final ResourceBudget budget = new ResourceBudget(4, Duration.ofMillis(200), 128, 128,
                    32, 8192, 8192, 64, null, null, null, null);
            final IsolationExecutionRequest request = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("sleep"), directory, java.util.Map.of()),
                    IsolationTestSupport.allowingPolicy(directory), budget, Duration.ofSeconds(5), "corr");
            assertEquals(Duration.ofMillis(200), request.timeout());
            final IsolationExecutionResult result = provider.execute(request);
            assertEquals(IsolationExecutionState.TIMED_OUT, result.state());
            assertTrue(result.usage().runtime().compareTo(Duration.ofSeconds(3)) < 0);
            assertEquals(1L, provider.metrics().timedOutExecutions());
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.timed_out".equals(event.action())
                    && "DEADLINE_EXCEEDED".equals(event.reasonCode())));
        }
    }

    @Test
    void forcedProcessKillIsAuditedWhenTimeoutRequiresIt(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final ResourceBudget budget = new ResourceBudget(4, Duration.ofMillis(200), 128, 128,
                    32, 8192, 8192, 64, null, null, null, null);
            final IsolationExecutionResult result = provider.execute(new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("slow-shutdown"), directory, java.util.Map.of()),
                    IsolationTestSupport.allowingPolicy(directory), budget, Duration.ofSeconds(5), "corr"));
            assertEquals(IsolationExecutionState.TIMED_OUT, result.state());
            assertEquals(1L, provider.metrics().killedExecutions());
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.killed".equals(event.action())
                    && "PROCESS_KILLED".equals(event.reasonCode())));
            assertTrue(provider.auditEvents().stream().anyMatch(event -> "cleanup.completed".equals(event.action())));
            assertEquals(0, provider.metrics().activeExecutions());
        }
    }

    @Test
    void providerCloseCancelsActiveProcessRejectsNewWorkAndIsIdempotent(@TempDir final Path directory) {
        final LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory);
        final IsolationExecutionHandle handle = provider.start(IsolationTestSupport.request(directory,
                IsolationTestSupport.javaCommand("sleep"), Duration.ofSeconds(5)));

        provider.close();
        final IsolationExecutionResult result = handle.await(Duration.ofMillis(200));

        assertEquals(IsolationExecutionState.CANCELLED, result.state());
        assertEquals(0, provider.metrics().activeExecutions());
        assertTrue(provider.auditEvents().stream().anyMatch(event -> "process.provider_close_cancelled".equals(event.action())));
        final IsolationException exception = assertThrows(IsolationException.class, () -> provider.start(
                IsolationTestSupport.request(directory, IsolationTestSupport.javaCommand("success"), Duration.ofSeconds(5))));
        assertEquals(MiddlewareErrorCode.UNAVAILABLE, exception.code());
        provider.close();
    }

    @Test
    void hardProcessCapacityRejectsBeforeLaunchingSecondProcess(@TempDir final Path directory) {
        final LocalProcessIsolationProvider provider = new LocalProcessIsolationProvider(
                new org.j7m.middleware.api.isolation.IsolationProviderId("local-cap-test"),
                IsolationTestSupport.config(directory), 1, 2);
        try {
            final ResourceBudget budget = new ResourceBudget(4, Duration.ofSeconds(5), 128, 128,
                    32, 8192, 8192, 64, null, null, null, null);
            final IsolationExecutionRequest request = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("sleep"), directory, java.util.Map.of()),
                    IsolationTestSupport.allowingPolicy(directory), budget, Duration.ofSeconds(5), "corr");
            final IsolationExecutionHandle first = provider.start(request);
            final IsolationException exception = assertThrows(IsolationException.class, () -> provider.start(request));
            assertEquals(MiddlewareErrorCode.OVERLOADED, exception.code());
            assertEquals(1L, provider.auditEvents().stream().filter(event -> "process.started".equals(event.action())).count());
            first.cancel();
        } finally {
            provider.close();
        }
    }
}
