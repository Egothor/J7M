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

import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusCommandResult;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusSafetyReport;
import org.j7m.middleware.api.consensus.ConsensusService;
import org.j7m.middleware.api.consensus.ConsensusState;
import org.j7m.middleware.api.consensus.ConsensusStatus;

/**
 * Default local consensus service backed by deterministic simulation primitives.
 */
public final class DefaultConsensusService implements ConsensusService {
    private final ConsensusSimulation simulation;

    /**
     * Creates a service.
     *
     * @param simulation simulation
     */
    public DefaultConsensusService(final ConsensusSimulation simulation) {
        this.simulation = simulation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsensusState startElection(final ConsensusNodeId nodeId) {
        return this.simulation.elect(nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsensusCommandResult submit(final ConsensusCommand command) {
        return this.simulation.submit(command);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsensusStatus status() {
        return this.simulation.status();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsensusSafetyReport safetyReport() {
        return this.simulation.safetyReport();
    }

    /**
     * Returns the deterministic simulation for tests and demos.
     *
     * @return simulation
     */
    public ConsensusSimulation simulation() {
        return this.simulation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.simulation.close();
    }
}
