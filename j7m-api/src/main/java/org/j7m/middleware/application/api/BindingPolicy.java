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
import java.util.Objects;

/**
 * Immutable host-owned limits and selection for one live binding; annotation policy IDs reference
 * instances of this value. All deadlines also obey a shorter caller-supplied remaining budget.
 * @param selection target selection strategy
 * @param unavailable behavior when no eligible target exists
 * @param waitLimit maximum availability wait
 * @param pollInterval bounded discovery polling interval
 * @param discoveryTimeout maximum one discovery operation budget
 * @param invocationTimeout default maximum one business invocation budget
 * @param maxCandidates bounded candidate and generation-history capacity,1..256
 */
public record BindingPolicy(Selection selection, Unavailable unavailable, Duration waitLimit,
        Duration pollInterval, Duration discoveryTimeout, Duration invocationTimeout, int maxCandidates) {
    /**
     * Supported deterministic selection policies.
     */
    public enum Selection {
        /**
         * Preserve an eligible selected identity.
         */
        STICKY,
        /**
         * Prefer a local runtime candidate, then deterministic identity order.
         */
        PREFER_LOCAL,
        /**
         * Select the lexicographically first eligible concrete identity.
         */
        LOWEST_ID
    }
    /**
     * Explicit unavailable behavior; optional results never manufacture a null business proxy.
     */
    public enum Unavailable {
        /**
         * Fail without waiting for another discovery observation.
         */
        FAIL_FAST,
        /**
         * Wait only within the caller's remaining deadline and waitLimit.
         */
        WAIT,
        /**
         * Expose absence through an explicit Optional provider.
         */
        OPTIONAL
    }
    /**
     * Validates finite limits.
     * @param selection deterministic selection
     * @param unavailable missing-target behavior
     * @param waitLimit positive bound at most30seconds
     * @param pollInterval 10milliseconds..30seconds
     * @param discoveryTimeout positive bound at most30seconds
     * @param invocationTimeout positive bound at most30seconds
     * @param maxCandidates 1..256
     * @throws IllegalArgumentException for invalid bounds
     */
    public BindingPolicy {
        Objects.requireNonNull(selection); Objects.requireNonNull(unavailable);
        for (Duration duration : new Duration[]{waitLimit, discoveryTimeout, invocationTimeout}) {
            if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Invalid binding deadline limit");
        }
        if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(10)) < 0 || pollInterval.compareTo(Duration.ofSeconds(30)) > 0
                || maxCandidates < 1 || maxCandidates > 256) throw new IllegalArgumentException("Invalid binding polling capacity");
    }
}
