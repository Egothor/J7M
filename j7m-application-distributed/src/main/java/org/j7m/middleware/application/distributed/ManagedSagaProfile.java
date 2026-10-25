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
import org.j7m.middleware.distributed.*;

/**
 * Trusted bounded saga capabilities; annotations never select endpoints or an administrative key.
 * Clients are borrowed. Definition input/result framing uses the supplied data-only schema codec.
 * @param backend ordinary scope-authorized durable execution client
 * @param administration optional separately server-authorized compensation/reconciliation capability
 * @param codec trusted immutable data codec with a bounded native-safe frame size
 * @param namespace stable host-owned resource namespace
 * @param lease fixed native ownership lease, 2seconds..1day
 * @param pollInterval unavailable connection/admission retry delay, 10milliseconds..1minute
 * @param operationBudget maximum native operation budget, 1millisecond..25seconds
 * @param handlerBudget per-callback maximum, less than lease minus clock/operation margin
 * @param maximumCompensationAttempts native bounded retry count, 1..1000
 * @param compensationBackoff native exponential retry base, 1millisecond..1hour
 */
public record ManagedSagaProfile(ManagedExecutionBackend backend, ManagedExecutionBackend administration,
        ManagedPayloadCodec codec, String namespace, Duration lease, Duration pollInterval,
        Duration operationBudget, Duration handlerBudget, int maximumCompensationAttempts, Duration compensationBackoff) {
    /**
     * Validates finite capabilities and timing without I/O or starting application work.
     * @throws NullPointerException for required capabilities or durations
     * @throws IllegalArgumentException for invalid bounds
     */
    public ManagedSagaProfile {
        Objects.requireNonNull(backend); Objects.requireNonNull(codec);
        bounded(lease,Duration.ofSeconds(2),Duration.ofDays(1)); bounded(pollInterval,Duration.ofMillis(10),Duration.ofMinutes(1));
        bounded(operationBudget,Duration.ofMillis(1),Duration.ofSeconds(25)); bounded(handlerBudget,Duration.ofMillis(1),Duration.ofDays(1));
        bounded(compensationBackoff,Duration.ofMillis(1),Duration.ofHours(1));
        if (namespace == null || !namespace.matches("[A-Za-z0-9_.-]{1,128}") || maximumCompensationAttempts<1 || maximumCompensationAttempts>1000
                || handlerBudget.plus(operationBudget.multipliedBy(2)).plusSeconds(1).compareTo(lease)>=0) {
            throw new IllegalArgumentException("Invalid saga profile");
        }
    }
    ExecutionEvents events() { return backend.discovery(); }
    DurableSagas sagas() { return new DurableSagas(backend.client()); }
    DurableSagas administrativeSagas() { return administration==null?null:new DurableSagas(administration.client()); }
    private static void bounded(Duration value,Duration minimum,Duration maximum) {
        Objects.requireNonNull(value); if(value.compareTo(minimum)<0 || value.compareTo(maximum)>0) throw new IllegalArgumentException("Invalid saga duration");
    }
    /**
     * Omits resource namespace and capability identities.
     * @return fixed safe label
     */
    @Override public String toString() { return "ManagedSagaProfile[host-owned]"; }
}
