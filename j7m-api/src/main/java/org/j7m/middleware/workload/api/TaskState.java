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

/**
 * Stable lifecycle states for a middleware workload task.
 *
 * <p>The enum is immutable and thread-safe. State transitions are owned by a
 * {@link WorkloadScheduler}; implementations may support only the transitions
 * documented by that scheduler and must not claim durable or exactly-once
 * execution semantics.</p>
 */
public enum TaskState {
    /**
     * The scheduler accepted the task descriptor.
     */
    SUBMITTED,
    /**
     * The task is waiting for capacity or a lease owner.
     */
    QUEUED,
    /**
     * A worker owns a live task lease.
     */
    LEASED,
    /**
     * A worker is actively executing the task.
     */
    RUNNING,
    /**
     * Cancellation has been requested and is being delivered.
     */
    CANCELLING,
    /**
     * The task stopped because cancellation completed.
     */
    CANCELLED,
    /**
     * A checkpoint is being recorded.
     */
    CHECKPOINTING,
    /**
     * The task completed successfully.
     */
    COMPLETED,
    /**
     * The task failed with a middleware or application failure.
     */
    FAILED,
    /**
     * The task expired before valid ownership or completion.
     */
    EXPIRED,
    /**
     * The task owner used a stale fencing token.
     */
    FENCED
}
