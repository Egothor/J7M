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
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.DeliverySemantics;
import org.j7m.middleware.api.reliability.IdempotencyKey;

/**
 * Propagated saga transaction context; this is not an ACID transaction.
 *
 * <p>The context is immutable and thread-safe. It carries correlation,
 * idempotency, dedupe, delivery, deadline, cancellation, participant, and
 * redacted metadata across saga steps. It is local propagation metadata, not a
 * lock, XA resource, or two-phase-commit participant. Implementations must
 * clear any thread-local or execution-local view after a step completes so
 * context does not leak between executions.</p>
 *
 * <p>Public status should normally expose {@link TransactionContextSummary}
 * rather than this full context. Metadata is redacted on construction using the
 * saga redaction policy; callers must not place command payload bytes or
 * credential material in keys that need to remain visible.</p>
 *
 * @param contextId stable non-ACID context identifier
 * @param scope logical scope represented by this context
 * @param correlation correlation and causal metadata
 * @param idempotencyKey optional idempotency key for duplicate start or command handling
 * @param dedupeKey optional dedupe key for application-level duplicate detection
 * @param deliverySemantics delivery semantics expected by the application action
 * @param deadline effective deadline, or {@link Instant#MAX} when absent
 * @param cancellationRequested whether cancellation has been requested for this context
 * @param participant safe participant identity
 * @param metadata redacted user metadata
 */
public record TransactionContext(
        TransactionContextId contextId,
        TransactionScope scope,
        TransactionCorrelation correlation,
        IdempotencyKey idempotencyKey,
        DedupeKey dedupeKey,
        DeliverySemantics deliverySemantics,
        Instant deadline,
        boolean cancellationRequested,
        TransactionParticipant participant,
        Map<String, String> metadata) implements Serializable {
    /**
     * Creates a validated immutable context.
     *
     * <p>Missing identifiers, scope, correlation, delivery semantics, deadline,
     * and participant values are replaced with safe defaults. Metadata is
     * defensively copied and redacted. Equality is value based and suitable for
     * deterministic context propagation tests.</p>
     */
    public TransactionContext {
        contextId = contextId == null ? new TransactionContextId(java.util.UUID.randomUUID().toString()) : contextId;
        scope = scope == null ? TransactionScope.SAGA : scope;
        correlation = correlation == null ? new TransactionCorrelation(null, null, null, null) : correlation;
        deliverySemantics = deliverySemantics == null ? DeliverySemantics.atLeastOnceRetryable() : deliverySemantics;
        deadline = deadline == null ? Instant.MAX : deadline;
        participant = participant == null ? new TransactionParticipant("anonymous", Map.of()) : participant;
        metadata = SagaValues.safeMap(metadata);
    }

    /**
     * Returns a child context for a step.
     *
     * <p>The child receives a new context id and child causal operation while
     * preserving correlation, idempotency, dedupe, deadline, cancellation,
     * participant, and redacted metadata. The method does not mutate this
     * context and does not install thread-local state.</p>
     *
     * @param childScope child scope to assign
     * @return immutable child context preserving parent correlation and deadline data
     */
    public TransactionContext child(final TransactionScope childScope) {
        return new TransactionContext(new TransactionContextId(java.util.UUID.randomUUID().toString()),
                childScope, this.correlation.child(), this.idempotencyKey, this.dedupeKey,
                this.deliverySemantics, this.deadline, this.cancellationRequested, this.participant, this.metadata);
    }
}
