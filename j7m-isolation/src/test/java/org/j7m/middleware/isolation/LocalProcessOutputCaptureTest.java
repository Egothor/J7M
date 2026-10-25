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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.isolation.IsolationExecutionResult;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationPolicy;
import org.j7m.middleware.api.isolation.IsolationProcessSpec;
import org.j7m.middleware.api.isolation.ResourceBudget;
import org.j7m.middleware.api.isolation.StderrPolicy;
import org.j7m.middleware.api.isolation.StdoutPolicy;
import org.j7m.middleware.api.isolation.StreamCapturePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalProcessOutputCaptureTest {
    @Test
    void capturesSeparateStreamsAndRedactsSecrets(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final IsolationExecutionResult result = provider.execute(IsolationTestSupport.request(directory,
                    IsolationTestSupport.javaCommand("secret"), Duration.ofSeconds(5)));
            assertTrue(result.output().stdout().text().contains("<redacted>"));
            assertTrue(result.output().stderr().text().contains("<redacted>"));
            assertFalse(result.toString().contains("secret-value-987"));
        }
    }

    @Test
    void truncatesOversizedOutput(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final IsolationExecutionResult result = provider.execute(IsolationTestSupport.request(directory,
                    IsolationTestSupport.javaCommand("large"), Duration.ofSeconds(5)));
            assertTrue(result.output().stdoutTruncated());
        }
    }

    @Test
    void effectiveStdoutLimitUsesMinimumOfPolicyAndBudget(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final IsolationExecutionResult policyLimited = provider.execute(request(directory, "large", 8, 8, 128, 128));
            assertTrue(policyLimited.output().stdoutTruncated());
            assertTrue(policyLimited.output().stdout().text().length() <= 8);

            final IsolationExecutionResult budgetLimited = provider.execute(request(directory, "large", 64, 64, 7, 128));
            assertTrue(budgetLimited.output().stdoutTruncated());
            assertEquals(7, budgetLimited.output().stdout().text().length());
        }
    }

    @Test
    void effectiveStderrLimitUsesMinimumOfPolicyAndBudget(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final IsolationExecutionResult policyLimited = provider.execute(request(directory, "large-stderr", 8, 8, 128, 128));
            assertTrue(policyLimited.output().stderrTruncated());
            assertTrue(policyLimited.output().stderr().text().length() <= 8);

            final IsolationExecutionResult budgetLimited = provider.execute(request(directory, "large-stderr", 64, 64, 128, 9));
            assertTrue(budgetLimited.output().stderrTruncated());
            assertEquals(9, budgetLimited.output().stderr().text().length());
        }
    }

    @Test
    void redactionStillAppliesWhenBudgetAllowsSecretOutput(@TempDir final Path directory) {
        try (LocalProcessIsolationProvider provider = IsolationTestSupport.localProvider(directory)) {
            final IsolationExecutionResult result = provider.execute(request(directory, "secret", 128, 128, 128, 128));
            assertTrue(result.output().stdout().text().contains("<redacted>"));
            assertTrue(result.output().stderr().text().contains("<redacted>"));
            assertFalse(result.output().stdout().text().contains("secret-value-987"));
            assertFalse(result.output().stderr().text().contains("secret-value-987"));
            assertFalse(provider.auditEvents().toString().contains("secret-value-987"));
        }
    }

    private static IsolationExecutionRequest request(
            final Path directory,
            final String mode,
            final int policyStdoutBytes,
            final int policyStderrBytes,
            final int budgetStdoutBytes,
            final int budgetStderrBytes) {
        final IsolationPolicy base = IsolationTestSupport.allowingPolicy(directory);
        final IsolationPolicy policy = new IsolationPolicy(base.fileSystemPolicy(), base.networkEgressPolicy(), base.environmentPolicy(),
                new StdoutPolicy(new StreamCapturePolicy(policyStdoutBytes, true, List.of("secret-value-987"))),
                new StderrPolicy(new StreamCapturePolicy(policyStderrBytes, true, List.of("secret-value-987"))));
        final ResourceBudget budget = new ResourceBudget(4, Duration.ofSeconds(5), budgetStdoutBytes, budgetStderrBytes,
                32, 8192, 8192, 64, null, null, null, null);
        return new IsolationExecutionRequest(IsolationTestSupport.subject(), IsolationMode.LOCAL_PROCESS,
                new IsolationProcessSpec(IsolationTestSupport.javaCommand(mode), directory, Map.of("SAFE", "ok")),
                policy, budget, Duration.ofSeconds(5), "corr");
    }
}
