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
import java.time.Duration;

/**
 * Queue configuration limits and policies.
 *
 * @param partitions number of queue partitions
 * @param maxQueuedMessages maximum live queued messages
 * @param maxPayloadBytes maximum payload bytes
 * @param maxLeaseBatchSize maximum lease batch size
 * @param maxActiveLeases maximum concurrently active leases
 * @param maxRetries maximum retry attempts before dead-letter
 * @param defaultVisibilityTimeout default visibility timeout
 */
public record QueueOptions(
        int partitions,
        int maxQueuedMessages,
        int maxPayloadBytes,
        int maxLeaseBatchSize,
        int maxActiveLeases,
        int maxRetries,
        Duration defaultVisibilityTimeout) implements Serializable {
    /**
     * Creates queue options.
     *
     * @throws IllegalArgumentException when limits are invalid
     */
    public QueueOptions {
        partitions = MessagingValues.positive("partitions", partitions);
        maxQueuedMessages = MessagingValues.positive("maxQueuedMessages", maxQueuedMessages);
        maxPayloadBytes = MessagingValues.positive("maxPayloadBytes", maxPayloadBytes);
        maxLeaseBatchSize = MessagingValues.positive("maxLeaseBatchSize", maxLeaseBatchSize);
        maxActiveLeases = MessagingValues.positive("maxActiveLeases", maxActiveLeases);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        defaultVisibilityTimeout = defaultVisibilityTimeout == null ? Duration.ofSeconds(30L) : defaultVisibilityTimeout;
        if (defaultVisibilityTimeout.isNegative() || defaultVisibilityTimeout.isZero()) {
            throw new IllegalArgumentException("defaultVisibilityTimeout must be positive");
        }
    }

    /**
     * Returns conservative defaults.
     *
     * @return default queue options
     */
    public static QueueOptions defaults() {
        return new QueueOptions(4, 100_000, 1024 * 1024, 128, 1024, 3, Duration.ofSeconds(30L));
    }
}
