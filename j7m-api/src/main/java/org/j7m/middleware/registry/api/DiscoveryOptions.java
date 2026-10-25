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

import java.time.Duration;

/**
 * Bounded options for discovery operations.
 *
 * @param contextId registry context/application scope
 * @param deadline maximum operation duration
 * @param maxResults maximum records to return
 * @param maxPeers maximum peers to contact
 * @param maxDepth maximum bootstrap expansion depth
 */
public record DiscoveryOptions(String contextId, Duration deadline, int maxResults, int maxPeers, int maxDepth) {
    /**
     * Creates bounded discovery options.
     */
    public DiscoveryOptions {
        contextId = contextId == null || contextId.isBlank() ? "default" : contextId;
        deadline = deadline == null ? Duration.ofSeconds(2L) : deadline;
        maxResults = maxResults <= 0 ? 64 : maxResults;
        maxPeers = maxPeers <= 0 ? 32 : maxPeers;
        maxDepth = Math.max(0, maxDepth);
    }

    /**
     * Returns default Phase 2 discovery options.
     *
     * @return default options
     */
    public static DiscoveryOptions defaults() {
        return new DiscoveryOptions("default", Duration.ofSeconds(2L), 64, 32, 3);
    }
}
