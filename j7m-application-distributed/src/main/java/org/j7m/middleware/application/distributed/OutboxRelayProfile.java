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
import org.j7m.middleware.distributed.ReplicatedQueue;

/**
 * Finite host relay policy over the existing JDBC outbox protocol. DB reads are bounded periodic
 * work batches, not native queue claim polling. Relay connections are independently owned; the
 * publication's caller Connection is never borrowed across transactions.
 * @param publication exact authorized logical SQL profile
 * @param destination borrowed authenticated queue facade
 * @param queue authorized destination FIFO queue
 * @param maximumBatch aggregate heads per run, 1..256
 * @param interval delay after an actual completed run, 1ms..1minute
 * @param operationBudget original source/send budget, 1ms..25seconds
 */
public record OutboxRelayProfile(OutboxProfile publication, ReplicatedQueue destination, String queue,
        int maximumBatch, Duration interval, Duration operationBudget) {
    /**
     * Validates bounded settings without opening a Connection or worker.
     */
    public OutboxRelayProfile {
        Objects.requireNonNull(publication); Objects.requireNonNull(destination); DeliveryIdentities.name(queue);
        if (!destination.scopeId().equals(publication.tenant())) throw new IllegalArgumentException("Outbox destination tenant mismatch");
        if (maximumBatch < 1 || maximumBatch > 256 || interval == null || interval.compareTo(Duration.ofMillis(1)) < 0 || interval.compareTo(Duration.ofMinutes(1)) > 0
                || operationBudget == null || operationBudget.compareTo(Duration.ofMillis(1)) < 0 || operationBudget.compareTo(Duration.ofSeconds(25)) > 0)
            throw new IllegalArgumentException("Invalid outbox relay bounds");
    }
    /**
     * Omits endpoints, tenant and destination names from diagnostics.
     * @return safe fixed profile identity
     */
    @Override public String toString() { return "OutboxRelayProfile[host-owned]"; }
}
