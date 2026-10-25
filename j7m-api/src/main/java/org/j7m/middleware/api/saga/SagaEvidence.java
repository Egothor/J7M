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
import java.time.Instant;
import java.util.Map;

/**
 * Safe saga evidence/audit event.
 *
 * <p>Evidence records are intended for operator status, audit, and acceptance
 * reports. Metadata is redacted on construction and must not contain raw
 * command payloads or credentials.
 *
 * @param timestamp event timestamp from the saga clock
 * @param action stable action name
 * @param executionId execution associated with the event, or {@code null} when not applicable
 * @param stepId step associated with the event, or {@code null} when not applicable
 * @param transactionContextId safe transaction context identifier
 * @param decision stable decision or result classification
 * @param reasonCode stable machine-readable reason code
 * @param metadata redacted evidence metadata
 */
public record SagaEvidence(
        Instant timestamp,
        String action,
        SagaExecutionId executionId,
        SagaStepId stepId,
        String transactionContextId,
        String decision,
        String reasonCode,
        Map<String, String> metadata) implements Serializable {
    /**
     * Creates a validated immutable evidence entry.
     */
    public SagaEvidence {
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        action = SagaValues.code(action == null ? "SAGA_AUDIT_RECORDED" : action);
        transactionContextId = transactionContextId == null ? "" : transactionContextId;
        decision = SagaValues.code(decision == null ? "recorded" : decision).toLowerCase(java.util.Locale.ROOT);
        reasonCode = SagaValues.code(reasonCode == null ? action : reasonCode);
        metadata = SagaValues.safeMap(metadata);
    }
}
