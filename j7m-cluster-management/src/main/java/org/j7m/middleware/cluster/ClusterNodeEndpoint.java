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

package org.j7m.middleware.cluster;

import java.io.IOException;
import java.time.Instant;

/**
 * Authenticated node-side physical lifecycle boundary. Each effect carries authoritative lease
 * identity, epoch and fence; node agents serialize, persist and revalidate these at effect admission.
 */
public interface ClusterNodeEndpoint extends ManagedInvocation {
    /**
     * Actual node state plus trusted schema watermark and consensus availability.
     * @param node application state
     * @param dataSchema highest possibly written schema
     * @param votingMembers committed configuration size
     * @param configurationEpoch committed configuration epoch
     * @param joint whether reconfiguration is in progress
     * @param quorumAvailable current fresh read barrier succeeded
     */
    record Status(NodeView node,int dataSchema,int votingMembers,long configurationEpoch,
            boolean joint,boolean quorumAvailable) {}
    /**
     * Reads actual node state.
     * @param deadline absolute deadline
     * @return local hosting and fresh consensus status
     * @throws IOException unavailable
     */
    Status status(Instant deadline) throws IOException;
    /**
     * Executes one locally journaled fenced physical step, reconciling exact retries.
     * @param operation durable operation identity
     * @param phase DRAIN or REPLACE
     * @param release server catalog identity
     * @param owner lease owner
     * @param fence current fence
     * @param epoch current leadership epoch
     * @param deadline absolute deadline
     * @return actual post-effect hosting view
     * @throws IOException stale authority or uncertain completion
     */
    NodeView step(String operation,String phase,String release,String owner,long fence,long epoch,
            Instant deadline) throws IOException;
}
