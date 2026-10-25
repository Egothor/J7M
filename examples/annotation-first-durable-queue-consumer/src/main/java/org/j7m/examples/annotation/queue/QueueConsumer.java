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

package org.j7m.examples.annotation.queue;

import org.j7m.middleware.annotations.Backpressure;
import org.j7m.middleware.annotations.DeadLetterHandler;
import org.j7m.middleware.annotations.DurableQueueConsumer;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.InboxHandler;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.IsolationProfile;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.OutboxPublisher;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.api.messaging.DurableQueue;

/**
 * Durable queue consumer example that keeps queue annotations truthful.
 *
 * <p>{@link DurableQueueConsumer}, {@link OutboxPublisher},
 * {@link InboxHandler}, {@link Retry}, {@link Backpressure},
 * {@link Idempotent}, and {@link IsolationProfile} are descriptor metadata in
 * this example. They describe how the method should be wired by a queue
 * dispatcher, but this source file does not implement broker dispatch, does
 * not provide generic exactly-once delivery, and does not enforce isolation
 * policy by itself. The isolation profile name is metadata only and does not
 * imply a container runtime or OS sandbox guarantee.</p>
 *
 * <p>The constructor receives only the public {@link DurableQueue} capability
 * through {@link J7mResource}. That is the API that owns durable queue
 * semantics such as leasing, ack/nack, retry, outbox, inbox, and dead-letter
 * handling. The example returns safe status text and never exposes payload
 * secrets.</p>
 */
public final class QueueConsumer {
    // J7M teaching: queue metadata is visible in source while durable delivery
    // remains owned by the public DurableQueue API.
    private final DurableQueue queue;

    /**
     * Creates a consumer with the public queue capability.
     *
     * @param queue durable queue capability bound under the example resource
     *        name
     */
    @Inject
    public QueueConsumer(@J7mResource("orders") final DurableQueue queue) {
        this.queue = queue;
    }

    /**
     * Handles one message identifier as descriptor-backed business code.
     *
     * <p>The annotations on this method are safe to copy into a real consumer,
     * but the example validates metadata only. A production dispatcher must
     * connect the descriptor to {@link DurableQueue} leasing and outbox/inbox
     * processing before these policies are enforced.</p>
     *
     * @param eventId public event identifier
     * @return safe acknowledgement summary
     */
    @DurableQueueConsumer(queue = "orders")
    @OutboxPublisher("orders-outbox")
    @InboxHandler
    @Backpressure(maxConcurrent = 4)
    @Retry(maxAttempts = 2)
    @Idempotent
    @IsolationProfile("baseline-process")
    public String consume(final String eventId) {
        // J7M teaching: the annotations describe dispatch policy, but this
        // method still calls only the public DurableQueue capability; leasing,
        // retries, and dead-letter movement are not faked here.
        return this.queue.getClass().getSimpleName() + ":ack:" + eventId;
    }

    /**
     * Produces a safe dead-letter summary.
     *
     * <p>{@link DeadLetterHandler} is descriptor metadata in this example. The
     * durable queue service remains responsible for deciding when a message is
     * actually moved to dead letter storage.</p>
     *
     * @param eventId public event identifier
     * @return safe dead-letter text
     */
    @DeadLetterHandler
    public String deadLetter(final String eventId) {
        // J7M teaching: dead-letter handling is represented as descriptor
        // metadata so examples do not overclaim runtime queue dispatch.
        return "dead-letter:" + eventId;
    }
}
