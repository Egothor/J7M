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

import org.j7m.middleware.api.consensus.AppendEntriesRequest;
import org.j7m.middleware.api.consensus.AppendEntriesResponse;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.RequestVoteRequest;
import org.j7m.middleware.api.consensus.RequestVoteResponse;

/**
 * Deterministic in-process consensus message.
 */
public sealed interface ConsensusMessage permits ConsensusMessage.VoteRequest, ConsensusMessage.VoteResponse, ConsensusMessage.AppendRequest, ConsensusMessage.AppendResponse {
    /**
     * Returns sender id.
     *
     * @return sender
     */
    ConsensusNodeId from();

    /**
     * Returns receiver id.
     *
     * @return receiver
     */
    ConsensusNodeId to();

    /**
     * Vote request message.
     *
     * @param from sender
     * @param to receiver
     * @param request vote request
     */
    record VoteRequest(ConsensusNodeId from, ConsensusNodeId to, RequestVoteRequest request) implements ConsensusMessage {
    }

    /**
     * Vote response message.
     *
     * @param from sender
     * @param to receiver
     * @param response vote response
     */
    record VoteResponse(ConsensusNodeId from, ConsensusNodeId to, RequestVoteResponse response) implements ConsensusMessage {
    }

    /**
     * Append request message.
     *
     * @param from sender
     * @param to receiver
     * @param request append request
     */
    record AppendRequest(ConsensusNodeId from, ConsensusNodeId to, AppendEntriesRequest request) implements ConsensusMessage {
    }

    /**
     * Append response message.
     *
     * @param from sender
     * @param to receiver
     * @param response append response
     */
    record AppendResponse(ConsensusNodeId from, ConsensusNodeId to, AppendEntriesResponse response) implements ConsensusMessage {
    }
}
