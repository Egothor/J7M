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

package org.j7m.middleware.operations.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.j7m.middleware.api.RemoteError;

/**
 * Safe, immutable result of a nugget PIN admission attempt.
 *
 * <p>The result never contains clear PINs, verifier bytes, salts, hashes, KDF
 * output, reusable authentication envelopes, or secret source values. A
 * successful result is admission only and does not carry authorization. The
 * admission id, when present, is a runtime-scoped session handle with bounded
 * retention and expiration. Callers may reuse it for read-only operations until
 * the service rejects it or it expires; possession of the id is never a bearer
 * authorization grant.</p>
 *
 * @param outcome outcome category
 * @param failureReason safe failure reason
 * @param nuggetId nugget id
 * @param correlationId audit correlation id
 * @param admissionId bounded expiring admission session id for this runtime
 *        process
 * @param retryAfter delay hint for throttled callers
 * @param timestamp result timestamp
 * @param error normalized safe error, or {@code null}
 * @param metadata redacted safe metadata
 */
public record NuggetAuthenticationResult(
        NuggetAuthenticationOutcome outcome,
        NuggetAuthenticationFailureReason failureReason,
        String nuggetId,
        String correlationId,
        String admissionId,
        Duration retryAfter,
        Instant timestamp,
        RemoteError error,
        Map<String, String> metadata) {
    /**
     * Creates a defensive immutable result.
     */
    public NuggetAuthenticationResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        failureReason = failureReason == null ? NuggetAuthenticationFailureReason.NONE : failureReason;
        nuggetId = AdminContractSupport.optionalToken(nuggetId);
        correlationId = AdminContractSupport.optionalToken(correlationId);
        admissionId = AdminContractSupport.optionalToken(admissionId);
        retryAfter = retryAfter == null || retryAfter.isNegative()
                ? Duration.ZERO
                : AdminContractSupport.boundedDuration(
                        retryAfter,
                        Duration.ZERO,
                        NuggetAdminContract.MAX_DURATION);
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        metadata = AdminContractSupport.orderedStringMap(metadata, NuggetAdminContract.MAX_METADATA_ENTRIES);
    }

    /**
     * Returns whether the request was accepted for admission.
     *
     * @return accepted flag
     */
    public boolean accepted() {
        return this.outcome == NuggetAuthenticationOutcome.ACCEPTED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NuggetAuthenticationResult[outcome=" + this.outcome
                + ", failureReason=" + this.failureReason
                + ", nuggetId=" + this.nuggetId
                + ", correlationId=" + this.correlationId
                + ", admissionId=<redacted>"
                + ", retryAfter=" + this.retryAfter
                + ", timestamp=" + this.timestamp
                + ", error=" + this.error
                + ", metadata=" + this.metadata + "]";
    }
}
