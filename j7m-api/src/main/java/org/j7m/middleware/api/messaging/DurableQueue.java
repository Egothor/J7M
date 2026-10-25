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

/**
 * Local durable queue contract for Phase 6.3 messaging primitives.
 *
 * <p>Implementations are expected to be thread-safe and may block on local
 * durable-log append, fsync, recovery, and visibility-timeout maintenance.
 * The contract provides local durable enqueue, lease, acknowledge, negative
 * acknowledge, and redacted status operations. It also defines the queue side
 * of the durable outbox/inbox pattern used by higher-level services.</p>
 *
 * <p>This API does not define a broker protocol, cluster membership, consensus,
 * distributed consumer balancing, transaction coordinator, or generic
 * exactly-once delivery. Callers must provide explicit idempotency and
 * duplicate-detection metadata when their domain requires effectively-once
 * patterns. Implementations map overload, stale leases, invalid arguments,
 * recovery failure, and durable storage failure to stable middleware error
 * codes and must redact message payload bytes from status and exception
 * details.</p>
 */
public interface DurableQueue extends AutoCloseable {
    /**
     * Enqueues a message durably before it can be leased.
     *
     * <p>The append must be durable before the message is visible to consumers.
     * Duplicate idempotency or dedupe keys return the previous durable decision
     * according to the implementation's duplicate policy.</p>
     *
     * @param queue queue name to receive the message
     * @param message message payload and safe metadata; payload is not exposed in status
     * @param options enqueue options; defaults when null
     * @return assigned durable message identity and duplicate status
     */
    EnqueueResult enqueue(QueueName queue, QueueMessage message, EnqueueOptions options);

    /**
     * Leases available messages for a consumer and makes them invisible until
     * their visibility timeout expires.
     *
     * <p>Lease acquisition is local and durable enough to reject stale
     * acknowledgements. It is not distributed consumer-group balancing.</p>
     *
     * @param queue queue name
     * @param consumer consumer identity
     * @param options lease options; defaults when null
     * @return leased message batch, possibly empty
     */
    LeaseBatch lease(QueueName queue, ConsumerId consumer, LeaseOptions options);

    /**
     * Acknowledges a currently leased message.
     *
     * <p>The acknowledgement must validate the current lease id. Stale,
     * missing, or mismatched leases fail with a stable fencing/lease error.</p>
     *
     * @param queue queue name
     * @param messageId message identity
     * @param options acknowledge options containing the current lease id
     * @return acknowledge result
     */
    AckResult ack(QueueName queue, MessageId messageId, AckOptions options);

    /**
     * Negatively acknowledges a currently leased message and either requeues it
     * for retry or moves it to the dead-letter set.
     *
     * <p>Retry timing uses the configured deterministic clock. Dead-letter
     * status is visible through redacted queue metrics without exposing payload
     * bytes. The negative acknowledgement must validate the current lease id;
     * stale, missing, or mismatched leases fail with a stable fencing/lease
     * error.</p>
     *
     * @param queue queue name
     * @param messageId message identity
     * @param options negative acknowledge options
     * @return negative acknowledge result
     */
    NackResult nack(QueueName queue, MessageId messageId, NackOptions options);

    /**
     * Returns redacted queue status and metrics.
     *
     * @param queue queue name
     * @return immutable queue status with counters, partition summaries, and last safe error
     */
    QueueStatus status(QueueName queue);

    /**
     * Closes owned resources. Further operations fail with a stable middleware
     * error.
     *
     * <p>Close should be idempotent. Implementations must finish or reject
     * in-flight local operations without leaking filesystem paths, payloads, or
     * credentials in error details.</p>
     */
    @Override
    void close();
}
