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

package org.j7m.middleware.api.reliability;

/**
 * Stable status for reliability policy decisions.
 *
 * <p>The enum is immutable and thread-safe.</p>
 */
public enum ReliabilityStatus {
    /**
     * The operation is accepted as first-seen or policy-compliant.
     */
    ACCEPTED,
    /**
     * The operation is retry-safe.
     */
    RETRY_SAFE,
    /**
     * The operation is not retry-safe.
     */
    NOT_RETRY_SAFE,
    /**
     * A duplicate was observed and accepted with a marker.
     */
    DUPLICATE_MARKED,
    /**
     * A duplicate was rejected.
     */
    DUPLICATE_REJECTED,
    /**
     * A previous result is available for the duplicate key.
     */
    PREVIOUS_RESULT_AVAILABLE,
    /**
     * A fencing token was stale.
     */
    STALE_FENCING_TOKEN,
    /**
     * A fencing token authority or scope did not match.
     */
    FENCING_SCOPE_MISMATCH,
    /**
     * Generic exactly-once execution is unsupported.
     */
    UNSUPPORTED_EXACTLY_ONCE,
    /**
     * A configured payload or result limit was exceeded.
     */
    LIMIT_EXCEEDED,
    /**
     * Backpressure rejected or delayed the operation.
     */
    BACKPRESSURE_APPLIED,
    /**
     * The invocation was cancelled.
     */
    CANCELLED,
    /**
     * The invocation deadline was exceeded.
     */
    DEADLINE_EXCEEDED
}
