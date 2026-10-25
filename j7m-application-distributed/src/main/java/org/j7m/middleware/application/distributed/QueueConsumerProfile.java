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
import java.util.*;
import org.j7m.middleware.distributed.*;

/**
 * Explicit authorized queue worker configuration. An annotation cannot select credentials,
 * endpoint classes or a new authority. Members must be unique among simultaneous host workers
 * and stable on restart to resume their exact pending receipt. Native FIFO permits one live
 * delivery per queue; parallelism is across queues. Borrowed endpoints/codecs remain host-owned.
 * @param source authenticated routed queue supporting native push
 * @param deduplication D12 guard backend; required for EXTERNAL_GUARD
 * @param codec explicitly schema-allowlisted thread-safe payload codec
 * @param tenant stable authorized source tenant
 * @param namespace stable logical handler namespace, independent of deployment artifact
 * @param group finite host consumer group
 * @param member stable host member identity, not an application-supplied claim
 * @param queues finite destination allowlist, at most256
 * @param visibility positive receipt lease, 1second..1day and greater than3operationBudgets
 * @param operationBudget finite source command budget, 1millisecond..25seconds
 * @param handlerBudget original application budget, 1millisecond..1day
 * @param retention D12 guard retention, 1millisecond..1day
 * @param strategy explicit durable effect protocol
 */
public record QueueConsumerProfile(ReplicatedQueue source, DeduplicatedRpc deduplication, ManagedPayloadCodec codec,
        String tenant, String namespace, String group, String member, Set<String> queues, Duration visibility,
        Duration operationBudget, Duration handlerBudget, Duration retention, Strategy strategy) {
    /**
     * Chooses the actual effect protocol; it is trusted host configuration, not metadata authority.
     */
    public enum Strategy {
        /**
         * D12 claims before body; unresolved external effects never automatically reenter it.
         */ EXTERNAL_GUARD,
        /**
         * Requires actual A13 inbox and A11 JDBC policy; DB marker decides legitimate redelivery.
         */ INBOX
    }
    /**
     * Validates and copies bounds without accessing the backend.
     */
    public QueueConsumerProfile {
        Objects.requireNonNull(source); Objects.requireNonNull(codec); Objects.requireNonNull(strategy);
        if (strategy == Strategy.EXTERNAL_GUARD) Objects.requireNonNull(deduplication);
        DeliveryIdentities.scope(tenant);
        if (!source.scopeId().equals(tenant)) throw new IllegalArgumentException("Queue worker tenant differs from authorized facade");
        for (String value : List.of(namespace, group, member)) DeliveryIdentities.name(value);
        queues = Set.copyOf(queues); queues.forEach(DeliveryIdentities::name);
        bounded(visibility, Duration.ofSeconds(1), Duration.ofDays(1));
        bounded(operationBudget, Duration.ofMillis(1), Duration.ofSeconds(25));
        bounded(handlerBudget, Duration.ofMillis(1), Duration.ofDays(1));
        bounded(retention, Duration.ofMillis(1), Duration.ofDays(1));
        if (queues.isEmpty() || queues.size() > 256 || operationBudget.multipliedBy(3).compareTo(visibility) >= 0) throw new IllegalArgumentException("Invalid queue worker limits");
    }
    private static void bounded(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalArgumentException("Invalid queue worker duration");
    }
    /**
     * Omits endpoint and tenant credentials from diagnostics.
     * @return fixed host-owned profile label
     */
    @Override public String toString() { return "QueueConsumerProfile[host-owned]"; }
}
