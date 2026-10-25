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
import java.util.Map;
import java.util.Optional;

/**
 * Immutable scheduler task status snapshot.
 *
 * @param ref task reference
 * @param state task state
 * @param progress latest progress
 * @param lease current lease
 * @param result success result
 * @param failure failure descriptor
 * @param checkpoint latest checkpoint
 * @param updatedAt status update time
 * @param metadata safe status metadata
 */
public record TaskStatus(
        TaskRef ref,
        TaskState state,
        TaskProgress progress,
        TaskLease lease,
        TaskResult result,
        TaskFailure failure,
        TaskCheckpoint checkpoint,
        Instant updatedAt,
        Map<String, String> metadata) {
    /**
     * Creates immutable status metadata.
     */
    public TaskStatus {
        if (ref == null) {
            throw new IllegalArgumentException("ref must not be null");
        }
        state = state == null ? TaskState.SUBMITTED : state;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Returns the current lease when the task is leased.
     *
     * @return optional lease
     */
    public Optional<TaskLease> currentLease() {
        return Optional.ofNullable(this.lease);
    }

    /**
     * Returns the successful result when the task completed.
     *
     * @return optional result
     */
    public Optional<TaskResult> successfulResult() {
        return Optional.ofNullable(this.result);
    }

    /**
     * Returns the failure descriptor when the task failed.
     *
     * @return optional failure
     */
    public Optional<TaskFailure> failedResult() {
        return Optional.ofNullable(this.failure);
    }
}
