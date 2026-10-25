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

/**
 * Authorized routed resource endpoint. Deadline/cancellation may leave an UNKNOWN committed outcome;
 * retry the identical business request identity and command, even after a topology refresh.
 */
public interface PartitionEndpoint {
    /**
     * Commits a nested resource command under an ownership epoch.
     * @param epoch cached router epoch
     * @param requestId stable business identity, unchanged across MOVED retries
     * @param command nested resource bytes
     * @param resultQuery authorized opaque query selecting its durable result
     * @param deadline absolute operation deadline
     * @return status, current local epoch and resource result bytes
     * @throws IOException for unavailable quorum, deadline or unknown outcome
     */
    PartitionReply execute(long epoch, String requestId, byte[] command, byte[] resultQuery, Instant deadline) throws IOException;
    /**
     * Reads under a fresh quorum and ownership barrier.
     * @param epoch cached ownership epoch
     * @param query authorized nested resource query
     * @param deadline absolute deadline
     * @return status and independent resource result
     * @throws IOException for unavailable quorum or deadline
     */
    PartitionReply query(long epoch, byte[] query, Instant deadline) throws IOException;
    /**
     * Opens authenticated direct-payload push; unsupported backends fail closed without polling.
     * @param epoch current ownership epoch
     * @param queue source queue
     * @param group consumer group, ignored for observational DLQ mode
     * @param member stable logical member or observer
     * @param visibilityMillis normal delivery visibility in milliseconds
     * @param observational whether this is a nonmutating retained-DLQ observation
     * @param deadline original handshake deadline
     * @return owned credit-controlled single-subscriber session
     * @throws IOException for unsupported capability, unavailable authority or handshake failure
     */
    default QueueSession subscribeQueue(long epoch, String queue, String group, String member, long visibilityMillis,
            boolean observational, Instant deadline) throws IOException { throw new IOException("Queue push capability unavailable"); }
    /**
     * Opens complete native execution-claim push; unsupported backends fail closed without polling.
     * @param epoch current ownership epoch
     * @param prefix stable run ID prefix, empty for all authorized runs
     * @param kind JOB or SAGA
     * @param owner stable native execution owner
     * @param leaseMillis finite positive native lease
     * @param deadline original authenticated handshake deadline
     * @return owned bounded-credit execution publisher
     * @throws IOException for unavailable capability, authority or quorum
     */
    default ExecutionSession subscribeExecution(long epoch, String prefix, String kind, String owner, long leaseMillis,
            Instant deadline) throws IOException { throw new IOException("Execution push capability unavailable"); }
    /**
     * Reads ownership status through quorum.
     * @param deadline absolute deadline
     * @return local durable status
     * @throws IOException for unavailable quorum or deadline
     */
    PartitionStateMachine.Status status(Instant deadline) throws IOException;
}
