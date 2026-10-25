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

/**
 * Provider metrics snapshot.
 *
 * @param activeExecutions current active executions
 * @param completedExecutions completed execution count
 * @param failedExecutions failed execution count
 * @param cancelledExecutions cancelled execution count
 * @param timedOutExecutions timed-out execution count
 * @param killedExecutions killed execution count
 * @param policyAllowed policy allow count
 * @param policyDenied policy denial count
 * @param resourceRejected resource rejection count
 * @param redactionEvents output/environment redaction count
 */
public record IsolationMetricsSnapshot(
        int activeExecutions,
        long completedExecutions,
        long failedExecutions,
        long cancelledExecutions,
        long timedOutExecutions,
        long killedExecutions,
        long policyAllowed,
        long policyDenied,
        long resourceRejected,
        long redactionEvents) {
    /**
     * Creates a validated metrics snapshot.
     */
    public IsolationMetricsSnapshot {
        if (activeExecutions < 0 || completedExecutions < 0L || failedExecutions < 0L || cancelledExecutions < 0L
                || timedOutExecutions < 0L || killedExecutions < 0L || policyAllowed < 0L || policyDenied < 0L
                || resourceRejected < 0L || redactionEvents < 0L) {
            throw new IllegalArgumentException("metrics values must be non-negative");
        }
    }
}
