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
import org.j7m.middleware.api.reliability.IdempotencyKey;

/**
 * Options used when starting a saga.
 *
 * <p>Options are persisted with the execution and recovered with the original
 * policy values. They are immutable and safe for durable event encoding.
 *
 * @param idempotencyKey optional key used to detect duplicate saga starts
 * @param retryPolicy default retry policy for steps without an override
 * @param timeoutPolicy default timeout policy for the saga execution
 * @param cancellationPolicy cancellation behavior for this execution
 */
public record SagaOptions(
        IdempotencyKey idempotencyKey,
        SagaRetryPolicy retryPolicy,
        SagaTimeoutPolicy timeoutPolicy,
        SagaCancellationPolicy cancellationPolicy) implements Serializable {
    /**
     * Creates validated options.
     */
    public SagaOptions {
        retryPolicy = retryPolicy == null ? SagaRetryPolicy.none() : retryPolicy;
        timeoutPolicy = timeoutPolicy == null ? SagaTimeoutPolicy.none() : timeoutPolicy;
        cancellationPolicy = cancellationPolicy == null ? SagaCancellationPolicy.compensate() : cancellationPolicy;
    }

    /**
     * Returns default saga start options.
     *
     * @return immutable default options with no idempotency key, no retry, no timeout, and compensation on cancel
     */
    public static SagaOptions defaults() {
        return new SagaOptions(null, SagaRetryPolicy.none(), SagaTimeoutPolicy.none(), SagaCancellationPolicy.compensate());
    }
}
