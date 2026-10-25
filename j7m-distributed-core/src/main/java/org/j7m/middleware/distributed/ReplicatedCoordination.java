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
import java.util.List;
import java.util.Objects;

/**
 * Additive quorum-authoritative coordination API. Membership and leadership share replicated leases,
 * logical-time expiration and fencing with resources; the older local coordination API is unchanged.
 * A member's existence never grants a vote in consensus configuration. No local cache authorizes work.
 */
public final class ReplicatedCoordination {
    private final ResourceEndpoint resources;
    /**
     * Creates a coordination facade for one authorized resource scope.
     * @param resources quorum-authoritative facade
     */
    public ReplicatedCoordination(ReplicatedResourceClient resources) {
        this((ResourceEndpoint) resources);
    }
    /**
     * Creates a coordination facade over a raw, partition-wrapped or authenticated remote endpoint.
     * @param resources scope-bound endpoint with lease and measured-time support
     */
    public ReplicatedCoordination(ResourceEndpoint resources) { this.resources = Objects.requireNonNull(resources); }
    /**
     * Performs a versioned CAS; missing keys have version zero.
     * @param request scoped idempotency key
     * @param key coordination key
     * @param expectedVersion expected version
     * @param value new value
     * @param deadline absolute deadline
     * @return authoritative persisted result
     * @throws IOException on unavailable/unknown quorum outcome
     */
    public ReplicatedResources.Result compareAndSet(String request, String key, long expectedVersion,
            String value, Instant deadline) throws IOException {
        return resources.execute("BATCH", request, List.of("coordination/" + key, Long.toString(expectedVersion), value), deadline);
    }
    /**
     * Joins membership with a leased unique member identity.
     * @param request request identity
     * @param member member identity
     * @param durationMillis lease duration
     * @param deadline absolute deadline
     * @return result with epoch-aware fence
     * @throws IOException on quorum or clock-health failure
     */
    public ReplicatedResources.Result join(String request, String member, long durationMillis, Instant deadline) throws IOException {
        return resources.execute("ACQUIRE", request, List.of("membership/" + member, member, Long.toString(durationMillis), "0"), deadline);
    }
    /**
     * Renews only a member's expected live version.
     * @param request request identity
     * @param member member identity
     * @param version expected lease version
     * @param durationMillis renewed duration
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on quorum or clock-health failure
     */
    public ReplicatedResources.Result heartbeat(String request, String member, long version, long durationMillis,
            Instant deadline) throws IOException {
        return resources.execute("RENEW", request, List.of("membership/" + member, Long.toString(version), member, Long.toString(durationMillis), "0"), deadline);
    }
    /**
     * Removes exactly one member lease version.
     * @param request request identity
     * @param member member identity
     * @param version expected version
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on quorum failure
     */
    public ReplicatedResources.Result leave(String request, String member, long version, Instant deadline) throws IOException {
        return resources.execute("CANCEL", request, List.of("membership/" + member, Long.toString(version)), deadline);
    }
    /**
     * Reads a live member through a quorum barrier.
     * @param member member identity
     * @param deadline absolute deadline
     * @return live membership lease or null
     * @throws IOException on quorum failure
     */
    public ReplicatedResources.Lease member(String member, Instant deadline) throws IOException {
        return resources.lease("membership/" + member, deadline);
    }
    /**
     * Attempts exclusive leased leadership of a named activity.
     * @param request request identity
     * @param activity coordinated activity
     * @param owner owner identity
     * @param durationMillis lease duration
     * @param deadline absolute deadline
     * @return persisted result with fencing token
     * @throws IOException on quorum or clock-health failure
     */
    public ReplicatedResources.Result lead(String request, String activity, String owner, long durationMillis,
            Instant deadline) throws IOException {
        return resources.execute("ACQUIRE", request, List.of("leadership/" + activity, owner, Long.toString(durationMillis), "0"), deadline);
    }
    /**
     * Applies a mutation protected by the exact active leadership epoch and fence.
     * @param request request identity
     * @param activity activity owning the lease
     * @param fence fencing token
     * @param epoch leadership epoch
     * @param key target resource key
     * @param value new value
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on quorum failure
     */
    public ReplicatedResources.Result mutate(String request, String activity, long fence, long epoch,
            String key, String value, Instant deadline) throws IOException {
        return resources.execute("FENCED", request, List.of("leadership/" + activity, Long.toString(fence), Long.toString(epoch), key, value), deadline);
    }
    /**
     * Reads live activity leadership through quorum.
     * @param activity coordinated activity
     * @param deadline absolute deadline
     * @return live lease or null
     * @throws IOException on quorum failure
     */
    public ReplicatedResources.Lease leader(String activity, Instant deadline) throws IOException {
        return resources.lease("leadership/" + activity, deadline);
    }
}
