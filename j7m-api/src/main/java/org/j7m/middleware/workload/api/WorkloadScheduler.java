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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Transport-neutral task scheduling, execution and lease-ownership API.
 *
 * <p>Implementations must be thread-safe. The baseline API intentionally does
 * not require durable storage, consensus, distributed transactions, or
 * exactly-once execution. Authorization, resource policy, overload, and audit
 * behavior are implementation responsibilities.</p>
 */
public interface WorkloadScheduler extends AutoCloseable {
    /**
     * Returns the scheduler id.
     *
     * @return scheduler id
     */
    String schedulerId();

    /**
     * Submits a task.
     *
     * @param submission submission request
     * @return task handle
     */
    WorkloadHandle submit(TaskSubmission submission);

    /**
     * Acquires task ownership under a new fencing generation.
     *
     * <p>A live lease held by another owner can prevent acquisition. A granted lease
     * identifies the owner and expiry; it does not itself fence an external database
     * or establish durable recovery. Those guarantees belong to the implementation.</p>
     *
     * @param taskId task id
     * @param owner lease owner
     * @param duration requested lease lifetime; null selects the implementation default
     * @return new lease
     */
    TaskLease acquireLease(TaskId taskId, LeaseOwner owner, Duration duration);

    /**
     * Extends an existing live lease while retaining its fencing generation.
     *
     * <p>The owner and token must match current authority. Renewal must not revive
     * an expired or superseded owner; obtain fresh authority after ownership loss.</p>
     *
     * @param taskId task id
     * @param owner lease owner
     * @param token fencing token supplied by owner
     * @param duration renewed lifetime measured from renewal; null selects the implementation default
     * @return renewed lease
     */
    TaskLease renewLease(TaskId taskId, LeaseOwner owner, FencingToken token, Duration duration);

    /**
     * Releases a live lease.
     *
     * @param taskId task id
     * @param owner lease owner
     * @param token fencing token
     */
    void releaseLease(TaskId taskId, LeaseOwner owner, FencingToken token);

    /**
     * Reports task progress.
     *
     * @param taskId task id
     * @param owner lease owner
     * @param token fencing token
     * @param progress progress snapshot
     */
    void reportProgress(TaskId taskId, LeaseOwner owner, FencingToken token, TaskProgress progress);

    /**
     * Records explicit recoverable task metadata under current lease authority.
     *
     * <p>This is an application safe point, not a serialized Java stack. The
     * implementation determines storage durability, retention, metadata limits and
     * redaction. A stale owner must not replace the current checkpoint.</p>
     *
     * @param taskId task id
     * @param owner lease owner
     * @param token fencing token
     * @param metadata checkpoint metadata
     * @return checkpoint snapshot
     */
    TaskCheckpoint checkpoint(TaskId taskId, LeaseOwner owner, FencingToken token, Map<String, String> metadata);

    /**
     * Publishes the task result under current lease authority and selects terminal completion.
     *
     * <p>Result size/type limits belong to the scheduler profile. Completion is not
     * atomic with unrelated external effects. A cancelled or timed-out caller does
     * not establish that physical work has stopped or that this result was absent.</p>
     *
     * @param taskId task id
     * @param owner lease owner
     * @param token fencing token
     * @param result result value
     */
    void complete(TaskId taskId, LeaseOwner owner, FencingToken token, Object result);

    /**
     * Fails a task.
     *
     * @param taskId task id
     * @param owner lease owner
     * @param token fencing token
     * @param failure failure descriptor
     */
    void fail(TaskId taskId, LeaseOwner owner, FencingToken token, TaskFailure failure);

    /**
     * Requests cancellation.
     *
     * @param taskId task id
     */
    void cancel(TaskId taskId);

    /**
     * Returns current task status.
     *
     * @param taskId task id
     * @return status snapshot
     */
    TaskStatus status(TaskId taskId);

    /**
     * Lists local task status snapshots.
     *
     * @return local statuses
     */
    List<TaskStatus> listTasks();

    /**
     * Executes a task asynchronously.
     *
     * @param submission submission request
     * @param executor executor
     * @return future-like result
     */
    AsyncResult<Object> submitAsync(TaskSubmission submission, WorkloadExecutor executor);

    /**
     * Enqueues a one-way task and returns after local acceptance.
     *
     * @param submission submission request
     * @param executor executor
     * @return task reference
     */
    TaskRef submitOneWay(TaskSubmission submission, WorkloadExecutor executor);

    /**
     * Starts a bounded server-style stream.
     *
     * @param submission submission request
     * @param producer producer callback
     * @param <T> item type
     * @return stream handle
     */
    <T> StreamHandle<T> stream(TaskSubmission submission, StreamProducer<T> producer);

    /**
     * Invokes a function over fan-out candidates.
     *
     * @param request request
     * @param invoker per-candidate invoker
     * @return fan-out result
     */
    FanOutResult fanOut(FanOutRequest request, CandidateInvoker invoker);

    /**
     * Invokes a function over scatter-gather candidates.
     *
     * @param request request
     * @param invoker per-candidate invoker
     * @return scatter-gather result
     */
    ScatterGatherResult scatterGather(ScatterGatherRequest request, CandidateInvoker invoker);

    /**
     * {@inheritDoc}
     */
    @Override
    void close();
}
