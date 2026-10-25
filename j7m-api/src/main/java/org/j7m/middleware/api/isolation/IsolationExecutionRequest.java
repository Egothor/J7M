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
 * Request to run or evaluate isolated work.
 *
 * <p>The request is immutable and thread-safe. A local process provider uses
 * {@link #processSpec()}; an in-process policy provider may use it only for
 * policy evaluation and status metadata. The stored timeout is the effective
 * deadline, calculated as the shorter of the caller timeout and
 * {@link ResourceBudget#maxRuntime()}. Implementations must not expose raw
 * command arguments, environment values, or output data in public
 * diagnostics.</p>
 *
 * @param subject subject being isolated
 * @param mode requested isolation mode
 * @param processSpec process command and environment
 * @param policy policy set
 * @param budget resource budget
 * @param timeout optional request timeout
 * @param correlationId optional safe correlation identifier
 */
public record IsolationExecutionRequest(
        IsolationSubject subject,
        IsolationMode mode,
        IsolationProcessSpec processSpec,
        IsolationPolicy policy,
        ResourceBudget budget,
        Duration timeout,
        String correlationId) {
    /**
     * Creates a validated execution request.
     */
    public IsolationExecutionRequest {
        if (subject == null || mode == null) {
            throw new IllegalArgumentException("subject and mode are required");
        }
        policy = policy == null ? IsolationPolicy.defaults() : policy;
        budget = budget == null ? ResourceBudget.defaults() : budget;
        timeout = timeout == null ? budget.maxRuntime() : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (budget.maxRuntime().compareTo(timeout) < 0) {
            timeout = budget.maxRuntime();
        }
        correlationId = correlationId == null ? "" : correlationId;
    }
}
