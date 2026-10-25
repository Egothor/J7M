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

package org.j7m.middleware.registry.api;

import java.util.List;
import java.util.Map;

/**
 * Immutable registry peer status used by decentralized discovery.
 *
 * @param peerId peer identifier
 * @param alive whether peer is alive
 * @param state liveness state
 * @param overloaded whether peer reports overload
 * @param quality coarse latency quality marker
 * @param local whether the peer is the local registry
 * @param trustStatus placeholder trust marker
 * @param lastContactMillis last contact timestamp
 * @param lastLatencyNanos last measured latency
 * @param loadScore load score where lower is better
 * @param reliabilityScore reliability score in the range {@code 0.0..1.0}
 * @param endpoints registry endpoints
 * @param supportedProtocolVersions supported protocol versions
 * @param knownContexts known registry contexts
 * @param sourcePeerId peer that reported this status
 * @param lastSeenMillis last seen timestamp
 * @param metadata public metadata
 */
public record RegistryPeerStatus(
        String peerId,
        boolean alive,
        RegistryPeerState state,
        boolean overloaded,
        RegistryPeerQuality quality,
        boolean local,
        RegistryTrustStatus trustStatus,
        long lastContactMillis,
        long lastLatencyNanos,
        double loadScore,
        double reliabilityScore,
        List<RegistryEndpoint> endpoints,
        List<Integer> supportedProtocolVersions,
        List<String> knownContexts,
        String sourcePeerId,
        long lastSeenMillis,
        Map<String, String> metadata) {
    /**
     * Creates immutable metadata.
     */
    public RegistryPeerStatus {
        peerId = peerId == null || peerId.isBlank() ? "unknown" : peerId;
        state = state == null ? (alive ? RegistryPeerState.ALIVE : RegistryPeerState.UNKNOWN) : state;
        quality = quality == null ? RegistryPeerQuality.UNKNOWN : quality;
        trustStatus = trustStatus == null ? RegistryTrustStatus.UNVERIFIED : trustStatus;
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        supportedProtocolVersions = supportedProtocolVersions == null ? List.of(1) : List.copyOf(supportedProtocolVersions);
        knownContexts = knownContexts == null ? List.of() : List.copyOf(knownContexts);
        sourcePeerId = sourcePeerId == null ? "" : sourcePeerId;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Creates a compatibility peer status with Phase 1 fields.
     *
     * @param peerId peer identifier
     * @param alive whether peer is alive
     * @param metadata public metadata
     */
    public RegistryPeerStatus(final String peerId, final boolean alive, final Map<String, String> metadata) {
        this(peerId, alive, alive ? RegistryPeerState.ALIVE : RegistryPeerState.UNKNOWN, false,
                RegistryPeerQuality.UNKNOWN, false, RegistryTrustStatus.UNVERIFIED, 0L, 0L, 0.5D, alive ? 1.0D : 0.0D,
                List.of(), List.of(1), List.of(), "", 0L, metadata);
    }
}
