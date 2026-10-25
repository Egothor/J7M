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
 * Status-safe transaction context summary.
 *
 * <p>The summary is not an ACID transaction handle. It is the preferred public
 * status representation for transaction context because it exposes correlation,
 * deadline, cancellation, participant, and redacted metadata needed for saga
 * audit/status views without exposing command payloads or credential-like
 * metadata. The record is immutable, thread-safe, and value based.</p>
 *
 * @param contextId transaction context identifier
 * @param scope transaction scope carried by the context
 * @param correlation correlation and causal relationship metadata
 * @param deadline effective deadline, or {@link Instant#MAX} when absent
 * @param cancellationRequested whether cancellation was requested
 * @param participant safe participant identity
 * @param metadata redacted metadata
 */
public record TransactionContextSummary(
        TransactionContextId contextId,
        TransactionScope scope,
        TransactionCorrelation correlation,
        Instant deadline,
        boolean cancellationRequested,
        TransactionParticipant participant,
        Map<String, String> metadata) implements Serializable {
    /**
     * Creates a validated status-safe transaction context summary.
     *
     * <p>Missing values are replaced with safe defaults. Metadata is defensively
     * copied and redacted, so the summary can be returned from service status,
     * evidence, recovery reports, and demo output.</p>
     *
     * @param contextId transaction context identifier
     * @param scope transaction scope
     * @param correlation correlation metadata
     * @param deadline deadline for the context
     * @param cancellationRequested cancellation flag
     * @param participant safe participant identity
     * @param metadata redacted metadata
     */
    public TransactionContextSummary {
        contextId = contextId == null ? new TransactionContextId(java.util.UUID.randomUUID().toString()) : contextId;
        scope = scope == null ? TransactionScope.SAGA : scope;
        correlation = correlation == null ? new TransactionCorrelation(null, null, null, null) : correlation;
        deadline = deadline == null ? Instant.MAX : deadline;
        participant = participant == null ? new TransactionParticipant("anonymous", Map.of()) : participant;
        metadata = SagaValues.safeMap(metadata);
    }

    /**
     * Builds a redacted summary from a full execution context.
     *
     * <p>The returned value intentionally drops idempotency and dedupe objects
     * that are not needed for public status. It preserves non-sensitive
     * correlation, participant, deadline, and cancellation metadata.</p>
     *
     * @param context full internal transaction context
     * @return safe public summary
     */
    public static TransactionContextSummary from(final TransactionContext context) {
        if (context == null) {
            return new TransactionContextSummary(null, null, null, null, false, null, Map.of());
        }
        return new TransactionContextSummary(context.contextId(), context.scope(), context.correlation(),
                context.deadline(), context.cancellationRequested(), context.participant(), context.metadata());
    }
}
