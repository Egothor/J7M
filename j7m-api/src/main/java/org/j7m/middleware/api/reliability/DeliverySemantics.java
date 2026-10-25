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

import java.io.Serializable;

/**
 * Delivery semantics selected for an invocation.
 *
 * <p>The record is immutable, serializable, and thread-safe. Generic
 * exactly-once distributed execution is represented as unsupported and must be
 * rejected by evaluators.</p>
 *
 * @param mode requested delivery mode
 * @param retrySafe whether retry is known to be safe
 * @param effectivelyOncePattern whether effectively-once pattern metadata is present
 */
public record DeliverySemantics(
        DeliveryMode mode,
        boolean retrySafe,
        boolean effectivelyOncePattern) implements Serializable {

    /**
     * Creates validated delivery semantics.
     */
    public DeliverySemantics {
        if (mode == null) {
            throw new IllegalArgumentException("delivery mode must not be null");
        }
        if (mode == DeliveryMode.AT_MOST_ONCE_BEST_EFFORT && retrySafe) {
            throw new IllegalArgumentException("at-most-once best-effort must not be marked retry-safe");
        }
    }

    /**
     * Returns at-most-once best-effort semantics.
     *
     * @return semantics
     */
    public static DeliverySemantics atMostOnceBestEffort() {
        return new DeliverySemantics(DeliveryMode.AT_MOST_ONCE_BEST_EFFORT, false, false);
    }

    /**
     * Returns at-least-once retryable semantics.
     *
     * @return semantics
     */
    public static DeliverySemantics atLeastOnceRetryable() {
        return new DeliverySemantics(DeliveryMode.AT_LEAST_ONCE_RETRYABLE, true, false);
    }

    /**
     * Returns effectively-once pattern metadata semantics.
     *
     * @return semantics
     */
    public static DeliverySemantics effectivelyOncePatternSupport() {
        return new DeliverySemantics(DeliveryMode.EFFECTIVELY_ONCE_PATTERN, true, true);
    }

    /**
     * Returns an unsupported generic exactly-once request.
     *
     * @return semantics
     */
    public static DeliverySemantics unsupportedExactlyOnce() {
        return new DeliverySemantics(DeliveryMode.UNSUPPORTED_EXACTLY_ONCE, false, false);
    }

    /**
     * Reports whether the request is a generic exactly-once claim.
     *
     * @return {@code true} when unsupported exactly-once was requested
     */
    public boolean unsupportedExactlyOnceRequested() {
        return this.mode == DeliveryMode.UNSUPPORTED_EXACTLY_ONCE;
    }
}
