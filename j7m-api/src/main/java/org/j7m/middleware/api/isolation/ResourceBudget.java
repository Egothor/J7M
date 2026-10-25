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

package org.j7m.middleware.api.isolation;

import java.time.Duration;

/**
 * Bounded resource budget for an isolation request.
 *
 * <p>The budget is immutable and thread-safe. Pure-Java providers enforce
 * concurrency, runtime, output, environment, and command-line limits. The
 * Java-only baseline has no internal work queue, so queue/backlog values are
 * policy metadata unless a future provider reports stronger support. CPU,
 * memory, and working-directory-size values are advisory or policy hooks unless
 * an implementation reports stronger support.</p>
 *
 * @param maxConcurrentExecutions maximum concurrent executions in provider
 * @param maxRuntime maximum runtime before timeout
 * @param maxStdoutBytes maximum captured stdout bytes
 * @param maxStderrBytes maximum captured stderr bytes
 * @param maxEnvironmentVariables maximum exposed environment variables
 * @param maxEnvironmentBytes maximum aggregate environment bytes
 * @param maxCommandLineBytes maximum command-line bytes
 * @param maxQueueBacklog maximum pending backlog policy value
 * @param cpuBudget advisory CPU budget
 * @param memoryBudget advisory memory budget
 * @param threadBudget thread budget
 * @param queueBudget queue/backlog budget classification
 */
public record ResourceBudget(
        int maxConcurrentExecutions,
        Duration maxRuntime,
        int maxStdoutBytes,
        int maxStderrBytes,
        int maxEnvironmentVariables,
        int maxEnvironmentBytes,
        int maxCommandLineBytes,
        int maxQueueBacklog,
        CpuBudget cpuBudget,
        MemoryBudget memoryBudget,
        ThreadBudget threadBudget,
        QueueBudget queueBudget) {
    /**
     * Creates a validated budget.
     */
    public ResourceBudget {
        if (maxConcurrentExecutions <= 0 || maxStdoutBytes < 0 || maxStderrBytes < 0
                || maxEnvironmentVariables < 0 || maxEnvironmentBytes < 0 || maxCommandLineBytes < 0
                || maxQueueBacklog < 0) {
            throw new IllegalArgumentException("resource budget limits are invalid");
        }
        maxRuntime = maxRuntime == null ? Duration.ofSeconds(30L) : maxRuntime;
        if (maxRuntime.isNegative() || maxRuntime.isZero()) {
            throw new IllegalArgumentException("max runtime must be positive");
        }
        cpuBudget = cpuBudget == null ? new CpuBudget(0L, ResourceEnforcementLevel.ADVISORY) : cpuBudget;
        memoryBudget = memoryBudget == null ? new MemoryBudget(0L, ResourceEnforcementLevel.ADVISORY) : memoryBudget;
        threadBudget = threadBudget == null ? new ThreadBudget(0, ResourceEnforcementLevel.POLICY_ONLY) : threadBudget;
        queueBudget = queueBudget == null ? new QueueBudget(maxQueueBacklog, ResourceEnforcementLevel.POLICY_ONLY) : queueBudget;
    }

    /**
     * Returns a conservative default budget for local test and demo execution.
     *
     * @return default budget
     */
    public static ResourceBudget defaults() {
        return new ResourceBudget(4, Duration.ofSeconds(10L), 8192, 8192, 32, 8192, 8192, 64,
                new CpuBudget(0L, ResourceEnforcementLevel.ADVISORY),
                new MemoryBudget(0L, ResourceEnforcementLevel.ADVISORY),
                new ThreadBudget(64, ResourceEnforcementLevel.POLICY_ONLY),
                new QueueBudget(64, ResourceEnforcementLevel.POLICY_ONLY));
    }
}
