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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;

/**
 * Thread-safe in-memory implementation of {@link WorkloadProcessingService}.
 *
 * <p>All state is local to this service instance and is guarded by the
 * instance monitor. The implementation is intentionally small and
 * demonstration-only.</p>
 */
public final class WorkloadProcessingServiceImpl implements WorkloadProcessingService {
    private final AtomicLong ids;
    private final Map<String, TaskRecord> tasks;

    /**
     * Creates an empty workload-processing service.
     */
    public WorkloadProcessingServiceImpl() {
        this.ids = new AtomicLong();
        this.tasks = new LinkedHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized TaskHandle submitTask(final String payload) {
        String id = "task-" + this.ids.incrementAndGet();
        TaskRecord record = new TaskRecord(new TaskHandle(id), payload == null ? "" : payload);
        record.state = "RUNNING";
        record.progress = new WorkloadTaskProgress(10.0D, "accepted");
        record.updatedAt = Instant.now();
        this.tasks.put(id, record);
        return record.handle;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized WorkloadTaskStatus status(final TaskHandle handle) {
        TaskRecord record = this.record(handle);
        return new WorkloadTaskStatus(record.handle, record.state, record.ownerId, record.fencingToken, record.updatedAt);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized WorkloadTaskProgress progress(final TaskHandle handle) {
        return this.record(handle).progress;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized Checkpoint checkpoint(final TaskHandle handle) {
        TaskRecord record = this.record(handle);
        record.checkpoint = new Checkpoint("checkpoint-" + record.handle.taskId(), record.handle.taskId(), Instant.now(),
                "payload-length=" + record.payload.length());
        record.progress = new WorkloadTaskProgress(Math.max(record.progress.percent(), 50.0D), "checkpointed");
        record.updatedAt = Instant.now();
        return record.checkpoint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean cancel(final TaskHandle handle) {
        TaskRecord record = this.record(handle);
        if ("COMPLETED".equals(record.state) || "CANCELLED".equals(record.state)) {
            return false;
        }
        record.state = "CANCELLED";
        record.progress = new WorkloadTaskProgress(record.progress.percent(), "cancelled");
        record.updatedAt = Instant.now();
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LeaseResult acquireLease(final TaskHandle handle, final String ownerId) {
        TaskRecord record = this.record(handle);
        record.ownerId = ownerId == null || ownerId.isBlank() ? "anonymous-owner" : ownerId;
        record.fencingToken++;
        record.leaseUntil = Instant.now().plus(Duration.ofSeconds(30L));
        record.updatedAt = Instant.now();
        return new LeaseResult(true, record.ownerId, record.fencingToken, record.leaseUntil);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean completeWithFence(final TaskHandle handle, final long fencingToken) {
        TaskRecord record = this.record(handle);
        if (record.fencingToken != fencingToken) {
            return false;
        }
        record.state = "COMPLETED";
        record.progress = new WorkloadTaskProgress(100.0D, "completed");
        record.updatedAt = Instant.now();
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized String adminStatus() {
        long cancelled = this.tasks.values().stream().filter(record -> "CANCELLED".equals(record.state)).count();
        long completed = this.tasks.values().stream().filter(record -> "COMPLETED".equals(record.state)).count();
        return "tasks=" + this.tasks.size() + ", completed=" + completed + ", cancelled=" + cancelled;
    }

    private TaskRecord record(final TaskHandle handle) {
        if (handle == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task handle is required");
        }
        TaskRecord record = this.tasks.get(handle.taskId());
        if (record == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "task not found");
        }
        return record;
    }

    private static final class TaskRecord {
        private final TaskHandle handle;
        private final String payload;
        private String state;
        private WorkloadTaskProgress progress;
        private Checkpoint checkpoint;
        private String ownerId;
        private long fencingToken;
        private Instant leaseUntil;
        private Instant updatedAt;

        private TaskRecord(final TaskHandle handle, final String payload) {
            this.handle = handle;
            this.payload = payload;
            this.state = "SUBMITTED";
            this.progress = new WorkloadTaskProgress(0.0D, "submitted");
            this.checkpoint = new Checkpoint("checkpoint-empty", handle.taskId(), Instant.EPOCH, "");
            this.ownerId = "";
            this.fencingToken = 0L;
            this.leaseUntil = Instant.EPOCH;
            this.updatedAt = Instant.now();
        }
    }
}
