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

package org.j7m.middleware.examples.workload;
import org.j7m.middleware.annotations.Idempotent;

/**
 * Minimal workload-processing example service.
 *
 * <p>The service demonstrates task submission, lifecycle state, progress,
 * cancellation, checkpoints, leases, fencing tokens, and status visibility
 * using bounded in-memory state. It is deliberately not a durable queue or
 * distributed compute platform.</p>
 */
public interface WorkloadProcessingService {
    /**
     * Submits one in-memory task.
     *
     * @param payload task payload
     * @return task handle
     */
    TaskHandle submitTask(String payload);

    /**
     * Reads task status.
     *
     * @param handle task handle
     * @return immutable status snapshot
     */
    @Idempotent
    WorkloadTaskStatus status(TaskHandle handle);

    /**
     * Reads task progress.
     *
     * @param handle task handle
     * @return immutable progress snapshot
     */
    @Idempotent
    WorkloadTaskProgress progress(TaskHandle handle);

    /**
     * Creates and returns a task checkpoint.
     *
     * @param handle task handle
     * @return checkpoint metadata
     */
    Checkpoint checkpoint(TaskHandle handle);

    /**
     * Cancels a task.
     *
     * @param handle task handle
     * @return whether cancellation changed the task state
     */
    boolean cancel(TaskHandle handle);

    /**
     * Acquires or renews an example lease.
     *
     * @param handle task handle
     * @param ownerId owner identity
     * @return lease result
     */
    LeaseResult acquireLease(TaskHandle handle, String ownerId);

    /**
     * Completes the task when the supplied fencing token is current.
     *
     * @param handle task handle
     * @param fencingToken caller fencing token
     * @return whether the token was current and completion succeeded
     */
    boolean completeWithFence(TaskHandle handle, long fencingToken);

    /**
     * Returns a compact status line for demo/admin output.
     *
     * @return safe status line
     */
    @Idempotent
    String adminStatus();
}
