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

package org.j7m.middleware.application.distributed;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable trusted ownership configuration; annotations cannot choose endpoints or lease duration.
 * Renewal and effects share one acquired session. The native resource remains the final authority
 * for expiry, fence and partition epoch. Backend handles are borrowed, never closed by a call.
 * @param backend opaque fixed-group or epoch-pinned partition source
 * @param namespace stable host namespace, independent of artifact incarnation and network address
 * @param lease positive native lease duration,2seconds..1day
 * @param renewEvery renewal delay, at least1ms and at most one quarter of the lease
 * @param operationBudget per-operation cap,1ms..25seconds and at most one quarter of the lease
 * @param cleanupBudget separate bounded cancellation budget,1ms..25seconds; never used for business effects
 * @param maximumEffects distinct logical protected writes per invocation,1..1024
 */
public record ManagedOwnershipProfile(ManagedOwnershipBackend backend, String namespace, Duration lease,
        Duration renewEvery, Duration operationBudget, Duration cleanupBudget, int maximumEffects) {
    /**
     * Uses the default bound of64 retained protected-effect identities for one invocation.
     * @param backend borrowed native source
     * @param namespace stable host namespace
     * @param lease finite lease
     * @param renewEvery renewal delay
     * @param operationBudget original-budget-clamped native operation cap
     * @param cleanupBudget cleanup-only cap
     */
    public ManagedOwnershipProfile(ManagedOwnershipBackend backend, String namespace, Duration lease,
            Duration renewEvery, Duration operationBudget, Duration cleanupBudget) {
        this(backend, namespace, lease, renewEvery, operationBudget, cleanupBudget, 64);
    }
    /**
     * Checks finite timing and namespace constraints without contacting a backend.
     * @param backend trusted borrowed backend
     * @param namespace stable bounded host namespace
     * @param lease native expiry duration
     * @param renewEvery bounded renewal delay
     * @param operationBudget original-budget-clamped operation cap
     * @param cleanupBudget bounded cleanup-only budget
     * @param maximumEffects distinct retained effects,1..1024
     * @throws IllegalArgumentException for invalid namespace or timing
     * @throws NullPointerException for null backend
     */
    public ManagedOwnershipProfile {
        Objects.requireNonNull(backend);
        bounded(lease, Duration.ofSeconds(2), Duration.ofDays(1));
        bounded(renewEvery, Duration.ofMillis(1), lease.dividedBy(4));
        bounded(operationBudget, Duration.ofMillis(1), lease.dividedBy(4).compareTo(Duration.ofSeconds(25)) < 0 ? lease.dividedBy(4) : Duration.ofSeconds(25));
        bounded(cleanupBudget, Duration.ofMillis(1), Duration.ofSeconds(25));
        if (namespace == null || !namespace.matches("[A-Za-z0-9_.-]{1,128}") || maximumEffects < 1 || maximumEffects > 1024) {
            throw new IllegalArgumentException("Invalid ownership namespace or effect bound");
        }
    }
    private static void bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) throw new IllegalArgumentException("Invalid ownership timing");
    }
    /**
     * Omits endpoint and namespace details from incidental diagnostics.
     * @return fixed redacted label
     */
    @Override public String toString() { return "ManagedOwnershipProfile[host-owned]"; }
}
