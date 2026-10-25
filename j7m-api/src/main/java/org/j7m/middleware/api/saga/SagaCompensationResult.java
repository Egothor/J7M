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
import java.util.Map;

/**
 * Safe compensation result.
 *
 * <p>The result describes the outcome of an application-provided compensation
 * action. It does not claim arbitrary external side effects were undone.
 *
 * @param success whether compensation succeeded
 * @param retryable whether a failed compensation may be retried
 * @param reasonCode stable machine-readable result code
 * @param metadata redacted result metadata
 */
public record SagaCompensationResult(boolean success, boolean retryable, String reasonCode, Map<String, String> metadata)
        implements Serializable {
    /**
     * Creates a validated result.
     */
    public SagaCompensationResult {
        reasonCode = SagaValues.code(reasonCode == null ? (success ? "SUCCESS" : "FAILED") : reasonCode);
        metadata = SagaValues.safeMap(metadata);
    }

    /**
     * Returns a successful compensation result.
     *
     * @return immutable success result
     */
    public static SagaCompensationResult succeeded() {
        return new SagaCompensationResult(true, false, "SUCCESS", Map.of());
    }

    /**
     * Returns a failed compensation result.
     *
     * @param reasonCode stable failure reason code
     * @param retryable whether retry policy may retry the failure
     * @return immutable failure result
     */
    public static SagaCompensationResult failed(final String reasonCode, final boolean retryable) {
        return new SagaCompensationResult(false, retryable, reasonCode, Map.of());
    }
}
