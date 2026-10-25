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
import java.time.Instant;
import java.util.*;

/**
 * Resumable cross-group migration coordinator. Durable source/target/topology phases, not coordinator
 * memory, determine recovery. A failure can leave the old source frozen; retry the same handoff until
 * completion. No timeout unfreezes an old owner after possible cutover.
 */
public final class PartitionMigration {
    private final PartitionAdminClient topology;
    private final Map<String, PartitionAdminClient> groups;
    /**
     * Builds a coordinator over independently authenticated remote services.
     * @param topology independent topology quorum group
     * @param groups group identity to privileged transfer endpoint
     */
    public PartitionMigration(PartitionAdminClient topology, Map<String, PartitionAdminClient> groups) {
        this.topology = Objects.requireNonNull(topology); this.groups = Map.copyOf(groups);
    }
    /**
     * Starts or resumes migration with live snapshot+tail catch-up followed by a brief final freeze.
     * @param id stable handoff identity across retries and coordinator restarts
     * @param partition partition identity
     * @param targetGroup destination identity
     * @param expectedEpoch source topology epoch
     * @param deadline absolute deadline; expiry never rolls back a cutover
     * @throws IOException for prepared transactions, unavailable quorum or incomplete/unknown outcome
     */
    public void migrate(String id, String partition, String targetGroup, long expectedEpoch, Instant deadline) throws IOException {
        topology.beginHandoff(id, partition, targetGroup, expectedEpoch, deadline);
        var handoff = topology.handoff(id, deadline);
        PartitionAdminClient source = endpoint(handoff.source()), target = endpoint(handoff.target());
        if (handoff.phase().equals("COMPLETE")) return;
        if (handoff.phase().equals("COPYING")) {
            var sourceState = source.status(deadline);
            if (!sourceState.phase().equals("FROZEN")) source.snapshotAt(expectedEpoch, id, deadline);
            var targetState = target.status(deadline);
            if (!Set.of("STAGED", "ACTIVE").contains(targetState.phase())) target.install(id, expectedEpoch + 1, source.image(id, deadline), deadline);
            for (int attempt = 0; ; attempt++) {
                try {
                    for (int round = 0; round < 4; round++) {
                        var suffix = source.tail(target.status(deadline).logicalIndex(), deadline);
                        if (suffix.isEmpty()) break; target.catchUp(id, suffix, deadline);
                    }
                    source.freeze(expectedEpoch, id, deadline);
                    long finalIndex = source.status(deadline).logicalIndex();
                    while (target.status(deadline).logicalIndex() < finalIndex) {
                        var suffix = source.tail(target.status(deadline).logicalIndex(), deadline);
                        if (suffix.isEmpty()) throw new IOException("Frozen suffix incomplete"); target.catchUp(id, suffix, deadline);
                    }
                    break;
                } catch (IllegalArgumentException expired) {
                    if (!"CURSOR_EXPIRED".equals(expired.getMessage()) || attempt >= 2) throw expired;
                    source.restartImage(id, deadline); target.restartImage(id, deadline);
                    target.install(id, expectedEpoch + 1, source.image(id, deadline), deadline);
                }
            }
            String hash = source.finalHash(id, deadline);
            if (!hash.equals(target.status(deadline).imageHash())) throw new IOException("Final snapshot/suffix digest mismatch");
            topology.cutover(id, expectedEpoch, hash, deadline); handoff = topology.handoff(id, deadline);
        }
        var staged = target.status(deadline);
        if (!staged.phase().equals("ACTIVE") && (!staged.phase().equals("STAGED") || !staged.handoff().equals(id)
                || !staged.imageHash().equals(handoff.imageHash()))) {
            // A crashed/concurrent copier may have reset its unactivated staging after cutover.
            // The permanently frozen source remains the recovery authority until activation.
            source.restartImage(id, deadline);
            if (staged.phase().equals("STAGED") || staged.phase().equals("EMPTY")) target.restartImage(id, deadline);
            byte[] finalImage = source.image(id, deadline);
            if (!PartitionStateMachine.sha256(finalImage).equals(handoff.imageHash())) throw new IOException("Frozen recovery image changed");
            target.install(id, expectedEpoch + 1, finalImage, deadline);
        }
        target.activate(expectedEpoch + 1, id, handoff.imageHash(), topology.proof(id, deadline), deadline);
        topology.complete(id, deadline);
    }
    private PartitionAdminClient endpoint(String group) throws IOException {
        PartitionAdminClient endpoint = groups.get(group); if (endpoint == null) throw new IOException("Unknown configured transfer group"); return endpoint;
    }
}
