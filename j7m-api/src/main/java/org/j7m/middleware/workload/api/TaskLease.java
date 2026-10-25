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

package org.j7m.middleware.workload.api;

import java.time.Instant;

/**
 * Current lease ownership for a workload task.
 *
 * @param taskId task id
 * @param owner lease owner
 * @param leaseUntil expiration instant
 * @param fencingToken fencing token proving the ownership generation
 */
public record TaskLease(TaskId taskId, LeaseOwner owner, Instant leaseUntil, FencingToken fencingToken) {
    /**
     * Creates validated lease fields.
     */
    public TaskLease {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId must not be null");
        }
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        leaseUntil = leaseUntil == null ? Instant.EPOCH : leaseUntil;
        fencingToken = fencingToken == null ? new FencingToken(0L) : fencingToken;
    }

    /**
     * Returns whether the lease is valid at the supplied instant.
     *
     * @param now comparison instant
     * @return {@code true} when not expired
     */
    public boolean isLiveAt(final Instant now) {
        Instant checked = now == null ? Instant.EPOCH : now;
        return this.leaseUntil.isAfter(checked);
    }
}
