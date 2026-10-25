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
import org.j7m.middleware.api.reliability.IdempotencyKey;

/**
 * Durable outbound message intent.
 *
 * <p>The record is immutable, serializable, and thread-safe. The entry contains
 * the message and enqueue options that must be preserved through outbox recovery
 * and relay. Payload data is durable queue data and must not be used in status
 * or exception text.</p>
 *
 * @param entryId outbox entry id
 * @param targetQueue target queue
 * @param message message to relay
 * @param enqueueOptions preserved enqueue options
 * @param idempotencyKey optional idempotency key copied for convenience
 * @param createdAt creation time
 * @param attempts relay attempt count
 * @param published whether the entry was durably marked published
 * @param failed whether the entry exceeded relay attempts
 */
public record OutboxEntry(
        MessageId entryId,
        QueueName targetQueue,
        QueueMessage message,
        EnqueueOptions enqueueOptions,
        IdempotencyKey idempotencyKey,
        Instant createdAt,
        int attempts,
        boolean published,
        boolean failed) implements Serializable {
    /**
     * Creates an outbox entry.
     *
     * @throws IllegalArgumentException when required identity or message fields are absent
     */
    public OutboxEntry {
        if (entryId == null || targetQueue == null || message == null) {
            throw new IllegalArgumentException("entryId, targetQueue, and message are required");
        }
        enqueueOptions = enqueueOptions == null ? EnqueueOptions.defaults() : enqueueOptions;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        MessagingValues.nonNegative("attempts", attempts);
    }
}
