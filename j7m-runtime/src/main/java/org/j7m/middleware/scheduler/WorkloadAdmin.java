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

package org.j7m.middleware.scheduler;

import java.util.List;
import org.j7m.middleware.workload.api.TaskStatus;

/**
 * Minimal admin/status formatter for workload scheduler state.
 *
 * <p>The class is immutable and thread-safe when the supplied scheduler is
 * thread-safe. It exposes safe operational posture only: task counts, current
 * task states, lease expiry, fencing token versions, progress, and workload
 * counters. It does not expose payloads or credential material.</p>
 */
public final class WorkloadAdmin {
    private final InMemoryWorkloadScheduler scheduler;

    /**
     * Creates a workload admin view.
     *
     * @param scheduler scheduler to inspect
     */
    public WorkloadAdmin(final InMemoryWorkloadScheduler scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        this.scheduler = scheduler;
    }

    /**
     * Formats a human-readable status snapshot.
     *
     * @return safe status text
     */
    public String formatStatus() {
        WorkloadMetricsSnapshot metrics = this.scheduler.metrics();
        List<TaskStatus> tasks = this.scheduler.listTasks();
        StringBuilder builder = new StringBuilder(512);
        builder.append("WorkloadStatus{schedulerId=").append(this.scheduler.schedulerId())
                .append(", queued=").append(tasks.stream().filter(status -> status.state().name().equals("QUEUED")).count())
                .append(", running=").append(metrics.running())
                .append(", completed=").append(metrics.completed())
                .append(", failed=").append(metrics.failed())
                .append(", cancelled=").append(metrics.cancelled())
                .append(", overloadedRejected=").append(metrics.overloadedRejected())
                .append(", fencingRejected=").append(metrics.fencingRejected())
                .append(", async=").append(metrics.asyncStarted())
                .append(", oneWay=").append(metrics.oneWayStarted())
                .append(", streams=").append(metrics.streamsStarted())
                .append(", fanOut=").append(metrics.fanOutCalls())
                .append(", scatterGather=").append(metrics.scatterGatherCalls())
                .append('}');
        for (TaskStatus status : tasks) {
            builder.append(System.lineSeparator())
                    .append("  task=").append(status.ref().taskId().value())
                    .append(", service=").append(status.ref().serviceName())
                    .append(", state=").append(status.state())
                    .append(", progress=").append(status.progress() == null ? "n/a" : status.progress().percent())
                    .append(", leaseUntil=").append(status.lease() == null ? "none" : status.lease().leaseUntil())
                    .append(", fencingToken=").append(status.lease() == null ? "none" : status.lease().fencingToken().value());
        }
        return builder.toString();
    }
}
