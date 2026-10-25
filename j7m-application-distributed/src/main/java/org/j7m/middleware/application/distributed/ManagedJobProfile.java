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
 * Explicit trusted native job source and bounded worker/renewal policy.
 * The backend is borrowed. Serial execution means at most one current native lease for the logical
 * definition; expired uncooperative Java may still run but cannot commit protected native effects.
 * @param backend authenticated execution source with pinned live-claim sessions
 * @param codec immutable data-only codec/schema allowlist
 * @param namespace stable host-owned tenant/resource namespace
 * @param lease native claim lease, at least2seconds and at most1day
 * @param renewInterval bounded renewal interval, at most one third of the lease
 * @param operationBudget original native RPC budget, at most one third of the lease
 * @param handlerBudget finite owned invocation budget, never automatically extended by renewals
 * @param reconnectDelay bounded failed-transport retry delay, not idle source polling
 * @param serial whether native serialGroup must prevent overlapping authoritative claims
 */
public record ManagedJobProfile(ManagedExecutionBackend backend,ManagedPayloadCodec codec,String namespace,
        Duration lease,Duration renewInterval,Duration operationBudget,Duration handlerBudget,Duration reconnectDelay,boolean serial) {
    /**
     * Validates host configuration without connecting or constructing application code.
     * @throws NullPointerException for missing collaborators or durations
     * @throws IllegalArgumentException for invalid finite capacities or timing
     */
    public ManagedJobProfile {
        Objects.requireNonNull(backend);Objects.requireNonNull(codec);
        bounded(lease,Duration.ofSeconds(2),Duration.ofDays(1));bounded(renewInterval,Duration.ofMillis(10),lease.dividedBy(3));
        bounded(operationBudget,Duration.ofMillis(1),lease.dividedBy(3));bounded(handlerBudget,Duration.ofMillis(1),Duration.ofDays(1));
        bounded(reconnectDelay,Duration.ofMillis(10),Duration.ofMinutes(1));
        if(renewInterval.plus(operationBudget).plusSeconds(1).compareTo(lease)>=0||namespace==null||!namespace.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("Invalid job namespace");
    }
    private static void bounded(Duration value,Duration minimum,Duration maximum){Objects.requireNonNull(value);if(value.compareTo(minimum)<0||value.compareTo(maximum)>0)throw new IllegalArgumentException("Invalid job duration");}
    /**
     * Omits endpoint, namespace and payload data.
     * @return fixed safe diagnostic
     */
    @Override public String toString(){return "ManagedJobProfile[host-owned]";}
}
