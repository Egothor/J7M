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

/**
 * Safe saga metrics snapshot.
 *
 * <p>Metrics are point-in-time counters maintained by the saga service. They
 * are safe for status output and contain no command payloads.
 *
 * @param activeSagas currently active executions
 * @param completedSagas completed terminal executions
 * @param failedSagas failed terminal executions
 * @param cancelledSagas cancelled terminal executions
 * @param timedOutSagas timed-out terminal executions
 * @param runningSteps currently running steps
 * @param compensations compensation attempts recorded
 * @param retries retry attempts recorded
 * @param deadLetters durable dead-letter records created
 * @param outboxPending pending durable outbox commands
 * @param recoveries recovery runs completed
 * @param rejectedOperations rejected operations such as backpressure violations
 */
public record SagaMetricsSnapshot(
        long activeSagas,
        long completedSagas,
        long failedSagas,
        long cancelledSagas,
        long timedOutSagas,
        long runningSteps,
        long compensations,
        long retries,
        long deadLetters,
        long outboxPending,
        long recoveries,
        long rejectedOperations) implements Serializable {
    /**
     * Creates a validated metrics snapshot.
     */
    public SagaMetricsSnapshot {
        if (activeSagas < 0L || completedSagas < 0L || failedSagas < 0L || cancelledSagas < 0L
                || timedOutSagas < 0L || runningSteps < 0L || compensations < 0L || retries < 0L
                || deadLetters < 0L || outboxPending < 0L || recoveries < 0L || rejectedOperations < 0L) {
            throw new IllegalArgumentException("saga metrics must be non-negative");
        }
    }
}
