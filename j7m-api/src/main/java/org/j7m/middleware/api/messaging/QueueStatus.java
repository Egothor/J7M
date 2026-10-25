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

package org.j7m.middleware.api.messaging;

import java.io.Serializable;
import java.util.List;

/**
 * Queue status and metrics.
 *
 * @param queue queue name
 * @param depth live message depth
 * @param available available message count
 * @param leased leased message count
 * @param delayed delayed message count
 * @param deadLettered dead-lettered message count
 * @param partitions immutable partition statuses
 * @param metrics queue metrics snapshot
 * @param lastError redacted last error code or message
 */
public record QueueStatus(
        QueueName queue,
        long depth,
        long available,
        long leased,
        long delayed,
        long deadLettered,
        List<QueuePartitionStatus> partitions,
        QueueMetricsSnapshot metrics,
        String lastError) implements Serializable {
    /**
     * Creates queue status.
     *
     * @throws IllegalArgumentException when queue is null
     */
    public QueueStatus {
        if (queue == null) {
            throw new IllegalArgumentException("queue is required");
        }
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
        metrics = metrics == null ? new QueueMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) : metrics;
        lastError = lastError == null ? "" : lastError;
    }
}
