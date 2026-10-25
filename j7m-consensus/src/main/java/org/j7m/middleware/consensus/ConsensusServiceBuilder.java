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

package org.j7m.middleware.consensus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.j7m.middleware.api.consensus.ConsensusConfiguration;
import org.j7m.middleware.api.consensus.ConsensusGroupId;
import org.j7m.middleware.api.consensus.ConsensusMember;
import org.j7m.middleware.api.consensus.ConsensusMemberSet;
import org.j7m.middleware.api.consensus.ConsensusNodeId;

/**
 * Builder for local deterministic consensus services.
 */
public final class ConsensusServiceBuilder {
    private Path directory;
    private ConsensusGroupId groupId = new ConsensusGroupId("phase6-consensus");
    private List<ConsensusNodeId> nodeIds = List.of(new ConsensusNodeId("n1"), new ConsensusNodeId("n2"), new ConsensusNodeId("n3"));

    /**
     * Creates a builder.
     */
    public ConsensusServiceBuilder() {
    }

    /**
     * Sets durable storage root.
     *
     * @param directory storage root
     * @return this builder
     */
    public ConsensusServiceBuilder directory(final Path directory) {
        this.directory = directory;
        return this;
    }

    /**
     * Sets group id.
     *
     * @param groupId group id
     * @return this builder
     */
    public ConsensusServiceBuilder groupId(final ConsensusGroupId groupId) {
        this.groupId = groupId;
        return this;
    }

    /**
     * Sets fixed node ids.
     *
     * @param nodeIds node ids
     * @return this builder
     */
    public ConsensusServiceBuilder nodeIds(final List<ConsensusNodeId> nodeIds) {
        this.nodeIds = List.copyOf(nodeIds);
        return this;
    }

    /**
     * Builds a service.
     *
     * @return consensus service
     */
    public DefaultConsensusService build() {
        if (this.directory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        List<ConsensusMember> members = this.nodeIds.stream().map(ConsensusMember::new).toList();
        ConsensusConfiguration configuration = new ConsensusConfiguration(
                this.groupId,
                new ConsensusMemberSet(members),
                Duration.ofMillis(50L),
                Duration.ofMillis(250L));
        return new DefaultConsensusService(new ConsensusSimulation(new ConsensusConfig(configuration, this.directory), 0x65L));
    }
}
