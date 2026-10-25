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

package org.j7m.middleware.application.api;

import java.time.Duration;

/**
 * Finite fixed-window admission policy. A LOCAL implementation must reject a DISTRIBUTED profile.
 * @param permits admitted requests per window, 1..1000000; later bulkhead rejection does not refund them
 * @param window positive monotonic interval, 1ms..1day
 * @param scope required coordination scope; never silently downgraded
 */
public record RateLimitProfile(long permits, Duration window, Scope scope) {
    /**
     * Coordination requirement selected by the trusted host.
     */
    public enum Scope {
        /**
         * Independent local JVM budget.
         */ LOCAL,
        /**
         * Coordinated budget requiring an explicitly installed distributed backend.
         */ DISTRIBUTED
    }
    /**
     * Validates finite limits without allocating state or choosing a fallback scope.
     */
    public RateLimitProfile {
        if (permits < 1 || permits > 1000000 || window == null || window.compareTo(Duration.ofMillis(1)) < 0
                || window.compareTo(Duration.ofDays(1)) > 0 || scope == null) throw new IllegalArgumentException("Invalid rate profile");
    }
}
