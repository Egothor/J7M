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

import java.util.Map;

/**
 * Immutable workload scheduler metrics snapshot.
 *
 * @param submitted submitted task count
 * @param running running task count
 * @param completed completed task count
 * @param failed failed task count
 * @param cancelled cancelled task count
 * @param fencingRejected stale fencing rejection count
 * @param overloadedRejected overload rejection count
 * @param resourceDenied resource-policy denial count
 * @param asyncStarted async task count
 * @param oneWayStarted one-way task count
 * @param streamsStarted stream count
 * @param streamCancelled stream cancellation count
 * @param fanOutCalls fan-out call count
 * @param scatterGatherCalls scatter-gather call count
 * @param metadata safe metadata
 */
public record WorkloadMetricsSnapshot(
        long submitted,
        long running,
        long completed,
        long failed,
        long cancelled,
        long fencingRejected,
        long overloadedRejected,
        long resourceDenied,
        long asyncStarted,
        long oneWayStarted,
        long streamsStarted,
        long streamCancelled,
        long fanOutCalls,
        long scatterGatherCalls,
        Map<String, String> metadata) {
    /**
     * Creates immutable metadata.
     */
    public WorkloadMetricsSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
