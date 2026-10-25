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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Safe saga recovery report.
 *
 * <p>The report summarizes durable-log replay. Diagnostics are redacted and
 * identify recovery stages, missing executable definitions, and malformed
 * events without exposing payloads or credentials.
 *
 * @param eventsReplayed durable events replayed
 * @param definitionsRecovered definitions or definition metadata recovered
 * @param executionsRecovered executions recovered from durable state
 * @param deadLettersRecovered dead-letter records recovered
 * @param failed whether recovery failed
 * @param diagnostics redacted recovery diagnostics
 */
public record SagaRecoveryReport(
        long eventsReplayed,
        long definitionsRecovered,
        long executionsRecovered,
        long deadLettersRecovered,
        boolean failed,
        List<Map<String, String>> diagnostics) implements Serializable {
    /**
     * Creates an immutable recovery report.
     */
    public SagaRecoveryReport {
        if (eventsReplayed < 0L || definitionsRecovered < 0L || executionsRecovered < 0L || deadLettersRecovered < 0L) {
            throw new IllegalArgumentException("recovery counters must be non-negative");
        }
        if (diagnostics == null || diagnostics.isEmpty()) {
            diagnostics = List.of();
        } else {
            List<Map<String, String>> safeDiagnostics = new ArrayList<>(diagnostics.size());
            for (Map<String, String> diagnostic : diagnostics) {
                safeDiagnostics.add(SagaValues.safeMap(diagnostic));
            }
            diagnostics = List.copyOf(safeDiagnostics);
        }
    }
}
