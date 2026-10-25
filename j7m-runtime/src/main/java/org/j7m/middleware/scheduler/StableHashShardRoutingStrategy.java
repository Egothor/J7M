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

package org.j7m.middleware.scheduler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.workload.api.ShardKey;
import org.j7m.middleware.workload.api.ShardRoute;
import org.j7m.middleware.workload.api.ShardRoutingStrategy;

/**
 * Stable hash routing strategy for Phase 4 shard metadata.
 *
 * <p>The strategy is stateless and thread-safe. It does not implement database
 * partitioning or consensus; it simply chooses one candidate from a caller
 * supplied healthy candidate list.</p>
 */
public final class StableHashShardRoutingStrategy implements ShardRoutingStrategy {
    /**
     * Creates a stateless stable hash routing strategy.
     */
    public StableHashShardRoutingStrategy() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ShardRoute route(final ShardKey key, final List<ObjectRef> candidates) {
        List<ObjectRef> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (safeCandidates.isEmpty()) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "no shard candidates available");
        }
        ShardKey safeKey = key == null ? new ShardKey("") : key;
        List<ObjectRef> eligible = this.eligibleCandidates(safeCandidates);
        int partition = Math.floorMod(fnv1a32(safeKey.value()), eligible.size());
        ObjectRef selected = eligible.get(partition);
        return new ShardRoute(safeKey, "shard-" + partition, partition, selected,
                Map.of("strategy", "stable-hash", "candidateCount", Integer.toString(eligible.size())));
    }

    private List<ObjectRef> eligibleCandidates(final List<ObjectRef> candidates) {
        long nowMillis = System.currentTimeMillis();
        List<ObjectRef> eligible = new ArrayList<>(candidates.size());
        Set<String> objectIds = new HashSet<>();
        Long topologyGeneration = null;
        for (ObjectRef candidate : candidates) {
            if (candidate == null || candidate.generation() <= 0L || !candidate.isLeaseActive(nowMillis)) {
                continue;
            }
            if (!"trusted".equals(candidate.metadata().get("trustStatus"))) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "missing trusted shard candidate evidence");
            }
            if (!objectIds.add(candidate.runtimeId() + "|" + candidate.objectId())) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "ambiguous shard ownership");
            }
            long candidateTopology = this.optionalPositiveLong(candidate.metadata().get("topologyGeneration"));
            if (candidateTopology <= 0L) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "invalid shard topology generation");
            }
            if (topologyGeneration == null) {
                topologyGeneration = Long.valueOf(candidateTopology);
            } else if (topologyGeneration.longValue() != candidateTopology) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "ambiguous shard topology generation");
            }
            if (this.optionalPositiveLong(candidate.metadata().get("fencingToken")) <= 0L) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "invalid shard fencing token");
            }
            eligible.add(candidate);
        }
        if (eligible.isEmpty()) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "no eligible shard candidates available");
        }
        eligible.sort(Comparator.comparing(ObjectRef::runtimeId).thenComparing(ObjectRef::objectId));
        return List.copyOf(eligible);
    }

    private long optionalPositiveLong(final String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : -1L;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static int fnv1a32(final String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (int index = 0; index < bytes.length; index++) {
            hash ^= bytes[index] & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }
}
