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

package org.j7m.middleware.api.saga;

import java.io.Serializable;
import java.util.List;

/**
 * Safe saga service status.
 *
 * <p>Status is immutable and redacted. It exposes operational summaries,
 * bounded dead-letter/evidence views, and the latest safe recovery report.
 *
 * @param metrics service metrics snapshot
 * @param executions safe per-execution summaries
 * @param deadLetters bounded safe dead-letter summaries
 * @param evidence bounded safe evidence records
 * @param recoveryReport latest recovery report
 * @param lastError redacted latest error text
 */
public record SagaStatus(
        SagaMetricsSnapshot metrics,
        List<SagaExecution> executions,
        List<SagaDeadLetterRecord> deadLetters,
        List<SagaEvidence> evidence,
        SagaRecoveryReport recoveryReport,
        String lastError) implements Serializable {
    /**
     * Creates an immutable status snapshot.
     */
    public SagaStatus {
        metrics = metrics == null ? new SagaMetricsSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L) : metrics;
        executions = executions == null ? List.of() : List.copyOf(executions);
        deadLetters = deadLetters == null ? List.of() : List.copyOf(deadLetters);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        recoveryReport = recoveryReport == null ? new SagaRecoveryReport(0L, 0L, 0L, 0L, false, List.of()) : recoveryReport;
        lastError = lastError == null ? "" : lastError;
    }
}
