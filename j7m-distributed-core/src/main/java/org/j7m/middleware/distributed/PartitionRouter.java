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

package org.j7m.middleware.distributed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Stable key routing over a configured partition set. Ownership can migrate independently of the
 * key-to-partition function; changing the partition set requires a separately governed repartition.
 */
public final class PartitionRouter {
    private final List<String> partitions;
    private final PartitionAdminClient topology;
    /**
     * Creates a deterministic router with lexically canonical partition identities.
     * @param partitions nonempty unique partition identities
     * @param topology authenticated topology read facade
     * @throws IllegalArgumentException for an empty or duplicate partition set
     */
    public PartitionRouter(Collection<String> partitions, PartitionAdminClient topology) {
        if (partitions.isEmpty() || new HashSet<>(partitions).size() != partitions.size()) throw new IllegalArgumentException("Invalid partitions");
        partitions.forEach(PartitionStateMachine::identity); this.partitions = partitions.stream().sorted().toList(); this.topology = Objects.requireNonNull(topology);
    }
    /**
     * Resolves a key through the authoritative owner epoch.
     * @param key UTF-8 application routing key
     * @param deadline absolute deadline
     * @return current partition owner and epoch
     * @throws IOException on unavailable topology quorum
     */
    public PartitionTopology.Route route(String key, Instant deadline) throws IOException {
        Objects.requireNonNull(key); byte[] encoded = key.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 65536) throw new IllegalArgumentException("Routing key too large");
        String hash = PartitionStateMachine.sha256(encoded); int selected = (int) Long.remainderUnsigned(Long.parseUnsignedLong(hash.substring(0, 16), 16), partitions.size());
        return topology.route(partitions.get(selected), deadline);
    }
}
