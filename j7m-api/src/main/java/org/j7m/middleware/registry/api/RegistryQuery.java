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
 * Immutable query for registry candidate listing.
 *
 * @param serviceName logical service name
 * @param interfaces required interface names
 * @param requiredParams required parameter key/value pairs
 * @param registryContext registry context
 * @param nowMillis time used for expiration filtering
 * @param limit maximum number of candidates
 * @param deadlineMillis absolute deadline in epoch milliseconds
 * @param maxPeerCount maximum peers that a discovery-backed registry may contact
 * @param maxDepth maximum bootstrap expansion depth
 * @param source preferred discovery source
 */
public record RegistryQuery(
        String serviceName,
        List<String> interfaces,
        Map<String, String> requiredParams,
        String registryContext,
        long nowMillis,
        int limit,
        long deadlineMillis,
        int maxPeerCount,
        int maxDepth,
        DiscoverySource source) {

    /**
     * Creates immutable query fields.
     */
    public RegistryQuery {
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        requiredParams = requiredParams == null ? Map.of() : Map.copyOf(requiredParams);
        registryContext = registryContext == null || registryContext.isBlank() ? "default" : registryContext;
        limit = limit <= 0 ? 1024 : limit;
        maxPeerCount = maxPeerCount <= 0 ? 32 : maxPeerCount;
        maxDepth = Math.max(0, maxDepth);
        source = source == null ? DiscoverySource.UNKNOWN : source;
    }

    /**
     * Creates a compatibility query with Phase 1 fields.
     *
     * @param serviceName logical service name
     * @param interfaces required interface names
     * @param requiredParams required parameters
     * @param registryContext registry context
     * @param nowMillis expiration timestamp
     * @param limit maximum result count
     */
    public RegistryQuery(
            final String serviceName,
            final List<String> interfaces,
            final Map<String, String> requiredParams,
            final String registryContext,
            final long nowMillis,
            final int limit) {
        this(serviceName, interfaces, requiredParams, registryContext, nowMillis, limit, 0L, 32, 3, DiscoverySource.UNKNOWN);
    }
}
