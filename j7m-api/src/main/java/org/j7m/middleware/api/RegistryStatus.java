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

package org.j7m.middleware.api;

import java.util.List;
import java.util.Map;

/**
 * Immutable summary of the registry visible to a runtime.
 *
 * @param recordCount number of non-expired registry records
 * @param peerCount number of known registry peers
 * @param multicastListenerRunning whether multicast discovery listener is active
 * @param multicastAdapterType multicast adapter type
 * @param multicastEndpoint safe multicast endpoint summary
 * @param multicastMessagesSent multicast messages sent
 * @param multicastMessagesReceived multicast messages received
 * @param activeDiscoveryQuestions active in-flight discovery questions
 * @param peerGraphSize known peer graph size
 * @param bootstrapHintCount configured bootstrap hint count
 * @param contactedBootstrapPeerCount contacted bootstrap peer count
 * @param lookupCacheSize cached lookup/list entry count
 * @param metrics public discovery and statistics counters
 * @param peerIds known peer ids
 * @param recentDiscoveryEvents recent discovery event summaries
 * @param recentHpDecisions recent HP selection summaries
 */
public record RegistryStatus(
        int recordCount,
        int peerCount,
        boolean multicastListenerRunning,
        String multicastAdapterType,
        String multicastEndpoint,
        long multicastMessagesSent,
        long multicastMessagesReceived,
        int activeDiscoveryQuestions,
        int peerGraphSize,
        int bootstrapHintCount,
        int contactedBootstrapPeerCount,
        int lookupCacheSize,
        Map<String, Long> metrics,
        List<String> peerIds,
        List<String> recentDiscoveryEvents,
        List<String> recentHpDecisions) {
    /**
     * Creates immutable status collections.
     */
    public RegistryStatus {
        multicastAdapterType = multicastAdapterType == null || multicastAdapterType.isBlank() ? "none" : multicastAdapterType;
        multicastEndpoint = multicastEndpoint == null ? "" : multicastEndpoint;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        peerIds = peerIds == null ? List.of() : List.copyOf(peerIds);
        recentDiscoveryEvents = recentDiscoveryEvents == null ? List.of() : List.copyOf(recentDiscoveryEvents);
        recentHpDecisions = recentHpDecisions == null ? List.of() : List.copyOf(recentHpDecisions);
    }

    /**
     * Creates a compatibility status with Phase 1 summary fields.
     *
     * @param recordCount active record count
     * @param peerCount known peer count
     */
    public RegistryStatus(final int recordCount, final int peerCount) {
        this(recordCount, peerCount, false, "none", "", 0L, 0L, 0, peerCount, 0, 0, 0, Map.of(), List.of(), List.of(), List.of());
    }
}
