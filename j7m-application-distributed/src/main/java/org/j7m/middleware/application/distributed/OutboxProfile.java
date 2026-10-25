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
import org.j7m.middleware.integrations.*;

/**
 * One host-owned logical SQL outbox and exact A11 connection capability. SQL scope includes tenant
 * and logical publisher identity/version, so sharing physical tables cannot suppress another domain.
 * Retained identity/order tombstones are not evicted; payload pruning is separate from delivery.
 * @param namespace annotation-selected logical outbox name authorized by this host profile
 * @param tenant stable authorized tenant
 * @param publisherId stable logical producer contract ID, independent of bundle deployment
 * @param publisherVersion positive logical producer version
 * @param jdbcPolicy exact participating A11 profile name
 * @param jdbcRegistration exact borrowed registration installed in the JDBC policy
 * @param codec trusted side-effect-free data codec with explicit schema allowlist
 * @param intents borrowed D12 first-writer frame/trace retention backend
 * @param intentRetention finite intent retention1ms..1day; EXPIRED never silently reexecutes
 * @param payloadRetention earliest sent-payload prune time, 1ms..365days
 * @param telemetry optional borrowed telemetry integration
 */
public record OutboxProfile(String namespace, String tenant, String publisherId, int publisherVersion,
        String jdbcPolicy, ManagedJdbcTransactionPolicy.Registration jdbcRegistration, ManagedPayloadCodec codec,
        DeduplicatedRpc intents, Duration intentRetention, Duration payloadRetention, Observability telemetry) {
    /**
     * Validates bounds without opening a connection or modifying schema.
     */
    public OutboxProfile {
        DeliveryIdentities.scope(tenant);
        for (String value : java.util.List.of(namespace, publisherId, jdbcPolicy)) DeliveryIdentities.name(value);
        Objects.requireNonNull(jdbcRegistration); Objects.requireNonNull(codec); Objects.requireNonNull(intents);
        if (publisherVersion < 1 || intentRetention == null || intentRetention.compareTo(Duration.ofMillis(1)) < 0 || intentRetention.compareTo(Duration.ofDays(1)) > 0
                || payloadRetention == null || payloadRetention.compareTo(Duration.ofMillis(1)) < 0 || payloadRetention.compareTo(Duration.ofDays(365)) > 0)
            throw new IllegalArgumentException("Invalid outbox retention/version");
    }
    String scope() { return "outbox-" + DeliveryIdentities.digest(namespace, tenant, publisherId, Integer.toString(publisherVersion)); }
    JdbcDelivery delivery() { return new JdbcDelivery(scope(), telemetry); }
    /**
     * Omits endpoint, tenant and factory details from diagnostics.
     * @return safe fixed profile label
     */
    @Override public String toString() { return "OutboxProfile[H2_TCP_2_3_232]"; }
}
