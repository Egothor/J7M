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

/**
 * Optional diagnostics SPI for discovery-capable registries.
 *
 * <p>Implementations must return immutable snapshots and must not block
 * indefinitely. Runtime code may use this interface opportunistically; plain
 * {@link RegistryClient} implementations remain valid.</p>
 */
public interface RegistryDiagnostics {
    /**
     * Returns discovery statistics.
     *
     * @return statistics snapshot
     */
    DiscoveryStatistics discoveryStatistics();

    /**
     * Returns recent discovery events.
     *
     * @return immutable event list
     */
    List<DiscoveryEvent> recentDiscoveryEvents();

    /**
     * Returns active in-flight discovery question count.
     *
     * @return active question count
     */
    int activeDiscoveryQuestions();

    /**
     * Returns lookup/list cache size.
     *
     * @return cache size
     */
    int lookupCacheSize();

    /**
     * Returns configured bootstrap hint count.
     *
     * @return bootstrap hint count
     */
    int bootstrapHintCount();

    /**
     * Returns contacted bootstrap peer count.
     *
     * @return contacted peer count
     */
    int contactedBootstrapPeerCount();

    /**
     * Returns whether multicast listener is active.
     *
     * @return true when multicast is active
     */
    boolean multicastListenerRunning();

    /**
     * Returns the multicast adapter type.
     *
     * @return adapter type, such as {@code udp}, {@code in-memory}, or
     *         {@code none}
     */
    default String multicastAdapterType() {
        return "none";
    }

    /**
     * Returns safe multicast endpoint information.
     *
     * @return multicast endpoint summary
     */
    default String multicastEndpoint() {
        return "";
    }
}
