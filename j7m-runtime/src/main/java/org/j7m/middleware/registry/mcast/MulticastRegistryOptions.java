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

package org.j7m.middleware.registry.mcast;

import java.net.NetworkInterface;
import java.time.Duration;

/**
 * Immutable multicast registry options.
 *
 * @param registryId local registry id
 * @param contextId registry context/application scope
 * @param group multicast group address
 * @param port multicast port
 * @param networkInterface optional network interface
 * @param queryDeadline query deadline
 * @param gatherAfterFirstResponse duration to gather list responses after first
 *        match
 * @param questionTtl question cache TTL
 * @param maxResults maximum results per query
 * @param maxPeers maximum peers per query
 * @param ttl multicast hop limit
 * @param receiveBufferBytes requested receive buffer size
 * @param maxMessageBytes maximum accepted UDP payload size
 * @param bus multicast adapter
 */
public record MulticastRegistryOptions(
        String registryId,
        String contextId,
        String group,
        int port,
        NetworkInterface networkInterface,
        Duration queryDeadline,
        Duration gatherAfterFirstResponse,
        Duration questionTtl,
        int maxResults,
        int maxPeers,
        int ttl,
        int receiveBufferBytes,
        int maxMessageBytes,
        MulticastBus bus) {
    /**
     * Creates multicast options.
     */
    public MulticastRegistryOptions {
        registryId = registryId == null || registryId.isBlank() ? "registry-" + java.util.UUID.randomUUID() : registryId;
        contextId = contextId == null || contextId.isBlank() ? "default" : contextId;
        group = group == null || group.isBlank() ? "239.7.0.1" : group;
        port = port <= 0 ? 47070 : port;
        queryDeadline = queryDeadline == null ? Duration.ofMillis(500L) : queryDeadline;
        gatherAfterFirstResponse = gatherAfterFirstResponse == null ? Duration.ofMillis(25L) : gatherAfterFirstResponse;
        questionTtl = questionTtl == null ? Duration.ofSeconds(2L) : questionTtl;
        maxResults = maxResults <= 0 ? 64 : maxResults;
        maxPeers = maxPeers <= 0 ? 32 : maxPeers;
        ttl = ttl <= 0 ? 1 : ttl;
        receiveBufferBytes = receiveBufferBytes <= 0 ? 64 * 1024 : receiveBufferBytes;
        maxMessageBytes = maxMessageBytes <= 0 ? 60 * 1024 : maxMessageBytes;
        bus = bus == null ? new UdpMulticastBus() : bus;
    }

    /**
     * Creates options using the compatibility Phase 2 argument list.
     *
     * @param registryId local registry id
     * @param contextId registry context
     * @param group multicast group
     * @param port port
     * @param networkInterface network interface
     * @param queryDeadline query deadline
     * @param gatherAfterFirstResponse gather duration
     * @param questionTtl question TTL
     * @param maxResults maximum results
     * @param maxPeers maximum peers
     * @param bus multicast adapter
     */
    public MulticastRegistryOptions(
            final String registryId,
            final String contextId,
            final String group,
            final int port,
            final NetworkInterface networkInterface,
            final Duration queryDeadline,
            final Duration gatherAfterFirstResponse,
            final Duration questionTtl,
            final int maxResults,
            final int maxPeers,
            final MulticastBus bus) {
        this(registryId, contextId, group, port, networkInterface, queryDeadline, gatherAfterFirstResponse,
                questionTtl, maxResults, maxPeers, 1, 64 * 1024, 60 * 1024, bus);
    }

    /**
     * Creates default options for one registry id.
     *
     * @param registryId registry id
     * @return options
     */
    public static MulticastRegistryOptions defaults(final String registryId) {
        return new MulticastRegistryOptions(registryId, "default", "239.7.0.1", 47070, null,
                Duration.ofMillis(500L), Duration.ofMillis(25L), Duration.ofSeconds(2L), 64, 32, 1, 64 * 1024, 60 * 1024, new UdpMulticastBus());
    }

    /**
     * Returns the maximum number of peer graph entries retained by a multicast
     * client.
     *
     * <p>The value is derived from the existing peer query limit to preserve
     * constructor compatibility while bounding peer state admitted from UDP and
     * in-memory multicast responses.</p>
     *
     * @return peer graph entry limit
     */
    public int maxPeerGraphEntries() {
        return Math.max(1, this.maxPeers);
    }

    /**
     * Returns the peer graph retention window in milliseconds.
     *
     * <p>Stale peer entries older than twice the configured question TTL are
     * removed before peer graph updates and peer status reads.</p>
     *
     * @return peer graph retention window in milliseconds
     */
    public long peerGraphTtlMillis() {
        long ttlMillis = this.questionTtl.toMillis();
        if (ttlMillis <= 0L || ttlMillis > Long.MAX_VALUE / 2L) {
            return Long.MAX_VALUE;
        }
        return ttlMillis * 2L;
    }
}
