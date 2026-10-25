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
 * Durable scheduler operations over an ownership-fenced execution partition; no executor is hidden.
 * Workers poll persisted status, claim a lease, and present its fence on every checkpoint/completion.
 */
public final class DurableJobs {
    private final ExecutionClient client;
    /**
     * Binds a scope-authorized execution client.
     * @param client execution transport
     */
    public DurableJobs(ExecutionClient client) { this.client = Objects.requireNonNull(client); }
    /**
     * Submits a durable job with persisted input and trace context.
     * @param request stable operation identity
     * @param job job identity
     * @param input opaque bounded input
     * @param deadline operation deadline
     * @return READY or CONFLICT
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult submit(String request, String job, String input, Instant deadline) throws IOException { return client.execute("J_SUBMIT", request, List.of(job, input, client.context()), deadline); }
    /**
     * Submits a durable occurrence whose live claim cannot overlap another run in this group.
     * The group is persisted with the job; omitting it from a later claim cannot bypass FORBID.
     * Expired uncooperative Java may still execute, but its native fence cannot protect new effects.
     * @param request stable submission request
     * @param job stable occurrence identity
     * @param input bounded persisted input
     * @param serialGroup stable authenticated-scope logical execution group
     * @param deadline original deadline
     * @return READY, CONFLICT or OVERLOADED
     * @throws IOException on unavailable quorum or ambiguous submission
     */
    public ExecutionResult submit(String request,String job,String input,String serialGroup,Instant deadline)throws IOException {
        return client.execute("J_SUBMIT",request,List.of(job,input,client.context(),serialGroup),deadline);
    }
    /**
     * Claims unowned or expired work, issuing a globally increasing ownership fence.
     * @param request stable operation identity
     * @param job job identity
     * @param owner worker identity
     * @param leaseMillis duration in (0,86400000]
     * @param deadline operation deadline
     * @return RUNNING with fence, checkpoint and context, or OWNED
     * @throws IOException on unhealthy clock, unavailable quorum or unknown completion
     */
    public ExecutionResult claim(String request, String job, String owner, long leaseMillis, Instant deadline) throws IOException { return client.execute("J_CLAIM", request, List.of(job, owner, Long.toString(leaseMillis)), deadline); }
    /**
     * Renews a currently RUNNING unexpired claim without changing its fence.
     * Terminal jobs and stale owners cannot renew or acquire new effect authority.
     * @param request stable operation identity
     * @param job job identity
     * @param owner exact current owner
     * @param fence exact current fence
     * @param leaseMillis finite positive renewed lease duration, at most one day
     * @param deadline original operation deadline
     * @return current RUNNING claim, CONFLICT or FENCED
     * @throws IOException on unavailable quorum, unhealthy clock or ambiguous completion
     */
    public ExecutionResult renew(String request, String job, String owner, long fence, long leaseMillis, Instant deadline) throws IOException {
        return client.execute("J_RENEW", request, List.of(job, owner, Long.toString(fence), Long.toString(leaseMillis)), deadline);
    }

    /**
     * Persists progress only for the exact live owner and fence.
     * @param request stable operation identity
     * @param job job identity
     * @param owner worker identity
     * @param fence current claim token
     * @param checkpoint opaque bounded progress
     * @param deadline operation deadline
     * @return persisted progress or FENCED
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult checkpoint(String request, String job, String owner, long fence, String checkpoint, Instant deadline) throws IOException { return client.execute("J_CHECKPOINT", request, List.of(job, owner, Long.toString(fence), checkpoint), deadline); }
    /**
     * Atomically completes work, result and protected-map CAS effects under its live fence.
     * @param request stable operation identity
     * @param job job identity
     * @param owner worker identity
     * @param fence current claim token
     * @param result persisted bounded result
     * @param effects key/expectedVersion/newValue triples, applied atomically
     * @param deadline operation deadline
     * @return COMPLETED, CONFLICT or FENCED
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult complete(String request, String job, String owner, long fence, String result, List<String> effects, Instant deadline) throws IOException {
        List<String> args = new ArrayList<>(List.of(job, owner, Long.toString(fence), result)); args.addAll(effects); return client.execute("J_COMPLETE", request, args, deadline); }
    /**
     * Cancels pending/running work and fences its previous owner.
     * @param request stable operation identity
     * @param job job identity
     * @param deadline operation deadline
     * @return CANCELLED or ALREADY_COMPLETED
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult cancel(String request, String job, Instant deadline) throws IOException { return client.execute("J_CANCEL", request, List.of(job), deadline); }
    /**
     * Reads persisted job input, progress, result and ownership.
     * @param job job identity
     * @param deadline operation deadline
     * @return durable job status or NOT_FOUND
     * @throws IOException on unavailable quorum
     */
    public ExecutionResult status(String job, Instant deadline) throws IOException { return client.query("E_RUN", job, deadline); }
}
