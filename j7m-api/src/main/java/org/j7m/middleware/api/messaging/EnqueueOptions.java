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
import java.time.Instant;
import java.util.Optional;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.reliability.OrderingKey;

/**
 * Options controlling durable queue enqueue behavior.
 *
 * <p>The record is immutable, serializable, and thread-safe. Phase 6.3 durable
 * queue and outbox recovery preserve these options where they affect message
 * delivery: priority, explicit partition, ordering key, idempotency key, dedupe
 * key, and not-before delivery time. These options do not imply broker-wide
 * ordering, distributed balancing, or generic exactly-once delivery.</p>
 *
 * @param priority message priority, where larger values lease first
 * @param partitionId explicit target partition, or null for key/hash routing
 * @param orderingKey optional ordering/routing key
 * @param idempotencyKey optional explicit idempotency key
 * @param dedupeKey optional explicit duplicate-detection key
 * @param notBefore earliest delivery time
 */
public record EnqueueOptions(
        MessagePriority priority,
        QueuePartitionId partitionId,
        OrderingKey orderingKey,
        IdempotencyKey idempotencyKey,
        DedupeKey dedupeKey,
        Instant notBefore) implements Serializable {
    /**
     * Creates enqueue options with conservative defaults for null optional
     * fields.
     */
    public EnqueueOptions {
        priority = priority == null ? MessagePriority.NORMAL : priority;
        notBefore = notBefore == null ? Instant.EPOCH : notBefore;
    }

    /**
     * Returns default enqueue options.
     *
     * @return default options
     */
    public static EnqueueOptions defaults() {
        return new EnqueueOptions(MessagePriority.NORMAL, null, null, null, null, Instant.EPOCH);
    }

    /**
     * Returns the optional explicit partition id.
     *
     * @return explicit partition when present
     */
    public Optional<QueuePartitionId> explicitPartition() {
        return Optional.ofNullable(this.partitionId);
    }
}
