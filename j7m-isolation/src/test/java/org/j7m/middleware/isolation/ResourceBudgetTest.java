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
import org.j7m.middleware.api.isolation.FileSystemPolicy;
import org.j7m.middleware.api.isolation.IsolationException;
import org.j7m.middleware.api.isolation.IsolationExecutionResult;
import org.j7m.middleware.api.isolation.IsolationExecutionState;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationPolicy;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationProcessSpec;
import org.j7m.middleware.api.isolation.ResourceBudget;
import org.j7m.middleware.api.isolation.ResourceEnforcementLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResourceBudgetTest {
    @Test
    void commandLineAndConcurrencyLimitsUseExactErrorCode(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final ResourceBudget smallCommandBudget = new ResourceBudget(4, Duration.ofSeconds(5), 128, 128,
                    32, 512, 4, 64, null, null, null, null);
            final IsolationExecutionRequest request = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("success"), directory, java.util.Map.of()),
                    IsolationTestSupport.allowingPolicy(directory), smallCommandBudget, Duration.ofSeconds(5), "corr");
            final IsolationException exception = assertThrows(IsolationException.class, () -> provider.start(request));
            assertEquals(MiddlewareErrorCode.OVERLOADED, exception.code());
            assertEquals(0, provider.metrics().activeExecutions());
            assertValidExecution(provider, directory);

            final ResourceBudget oneActive = new ResourceBudget(1, Duration.ofSeconds(5), 128, 128,
                    32, 512, 8192, 64, null, null, null, null);
            final IsolationExecutionRequest sleep = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("sleep"), directory, java.util.Map.of()),
                    IsolationTestSupport.allowingPolicy(directory), oneActive, Duration.ofSeconds(5), "corr");
            final org.j7m.middleware.api.isolation.IsolationExecutionHandle first = provider.start(sleep);
            final IsolationException overloaded = assertThrows(IsolationException.class, () -> provider.start(sleep));
            assertEquals(MiddlewareErrorCode.OVERLOADED, overloaded.code());
            first.cancel();
        }
    }

    @Test
    void environmentLimitsUseRequestBudgetAndExactErrorCode(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final ResourceBudget oneVariable = new ResourceBudget(4, Duration.ofSeconds(5), 128, 128,
                    1, 512, 8192, 64, null, null, null, null);
            final IsolationExecutionRequest countRequest = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("success"), directory,
                            java.util.Map.of("A", "1", "B", "2")),
                    IsolationTestSupport.allowingPolicy(directory), oneVariable, Duration.ofSeconds(5), "corr");
            final IsolationException countException = assertThrows(IsolationException.class, () -> provider.start(countRequest));
            assertEquals(MiddlewareErrorCode.OVERLOADED, countException.code());
            assertEquals(0, provider.metrics().activeExecutions());
            assertValidExecution(provider, directory);

            final ResourceBudget tinyEnvironment = new ResourceBudget(4, Duration.ofSeconds(5), 128, 128,
                    4, 4, 8192, 64, null, null, null, null);
            final IsolationExecutionRequest sizeRequest = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("success"), directory,
                            java.util.Map.of("SAFE", "abcdef")),
                    IsolationTestSupport.allowingPolicy(directory), tinyEnvironment, Duration.ofSeconds(5), "corr");
            final IsolationException sizeException = assertThrows(IsolationException.class, () -> provider.start(sizeRequest));
            assertEquals(MiddlewareErrorCode.OVERLOADED, sizeException.code());
            assertEquals(0, provider.metrics().activeExecutions());
            assertValidExecution(provider, directory);
        }
    }

    @Test
    void preLaunchFailuresReleaseExecutionSlot(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final ResourceBudget oneActive = new ResourceBudget(1, Duration.ofSeconds(5), 128, 128,
                    32, 512, 8192, 64, null, null, null, null);
            final IsolationPolicy deniedWorkingDirectory = new IsolationPolicy(FileSystemPolicy.denyAll(),
                    IsolationTestSupport.allowingPolicy(directory).networkEgressPolicy(),
                    IsolationTestSupport.allowingPolicy(directory).environmentPolicy(),
                    IsolationTestSupport.allowingPolicy(directory).stdoutPolicy(),
                    IsolationTestSupport.allowingPolicy(directory).stderrPolicy());
            final IsolationExecutionRequest deniedRequest = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(IsolationTestSupport.javaCommand("success"), directory, java.util.Map.of()),
                    deniedWorkingDirectory, oneActive, Duration.ofSeconds(5), "corr");
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,
                    assertThrows(IsolationException.class, () -> provider.start(deniedRequest)).code());
            assertEquals(0, provider.metrics().activeExecutions());
            assertValidExecution(provider, directory);

            final IsolationExecutionRequest missingExecutable = new IsolationExecutionRequest(IsolationTestSupport.subject(),
                    IsolationMode.LOCAL_PROCESS,
                    new IsolationProcessSpec(java.util.List.of(directory.resolve("missing-executable").toString()), directory, java.util.Map.of()),
                    IsolationTestSupport.allowingPolicy(directory), oneActive, Duration.ofSeconds(5), "corr");
            assertEquals(MiddlewareErrorCode.INTERNAL,
                    assertThrows(IsolationException.class, () -> provider.start(missingExecutable)).code());
            assertEquals(0, provider.metrics().activeExecutions());
            assertValidExecution(provider, directory);
        }
    }

    @Test
    void advisoryAndPolicyOnlyBudgetsAreVisible() {
        final ResourceBudget budget = ResourceBudget.defaults();
        assertEquals(ResourceEnforcementLevel.ADVISORY, budget.cpuBudget().level());
        assertEquals(ResourceEnforcementLevel.ADVISORY, budget.memoryBudget().level());
        assertEquals(ResourceEnforcementLevel.POLICY_ONLY, budget.queueBudget().level());
        assertTrue(budget.threadBudget().level() == ResourceEnforcementLevel.POLICY_ONLY
                || budget.threadBudget().level() == ResourceEnforcementLevel.ENFORCED);
    }

    private static void assertValidExecution(final LocalProcessIsolationProvider provider, final Path directory) {
        final IsolationExecutionResult result = provider.execute(IsolationTestSupport.request(directory,
                IsolationTestSupport.javaCommand("success"), Duration.ofSeconds(5)));
        assertEquals(IsolationExecutionState.COMPLETED, result.state());
        assertEquals(0, provider.metrics().activeExecutions());
    }
}
