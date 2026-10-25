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

package org.j7m.middleware.examples.annotation;

import org.j7m.middleware.annotations.Backpressure;
import org.j7m.middleware.annotations.DeadLetterHandler;
import org.j7m.middleware.annotations.DurableQueueConsumer;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.InboxHandler;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.OutboxPublisher;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.api.messaging.DurableQueue;

/**
 * Annotation-first durable queue consumer example.
 *
 * <p>The queue-related annotations are descriptor metadata. The injected
 * {@link DurableQueue} remains the public API that enforces leases, retry,
 * dead-letter, outbox, and inbox semantics. The example avoids exposing queue
 * payload bytes in status or audit output.</p>
 */
public final class AnnotationQueueConsumer {
        // J7M teaching: this block demonstrates queue metadata while durable delivery remains owned by the public DurableQueue API.
    private final DurableQueue queue;
    private final AnnotationExampleAudit audit;

    /**
     * Creates a consumer with a public durable queue capability.
     *
     * @param queue injected durable queue
     * @param audit safe audit collector
     */
    @Inject
    public AnnotationQueueConsumer(
            @J7mResource("annotation-events") final DurableQueue queue,
            final AnnotationExampleAudit audit) {
        this.queue = queue;
        this.audit = audit;
    }

    /**
     * Consumes one queue message id.
     *
     * @param eventId public event id
     * @return safe result
     */
    @DurableQueueConsumer(queue = "annotation-events")
    @InboxHandler
    @OutboxPublisher("annotation-outbox")
    @Backpressure(maxConcurrent = 4)
    @Retry(maxAttempts = 2)
    @Idempotent
    public String consume(final String eventId) {
        // J7M teaching: the annotation metadata describes queue dispatch
        // policy, while this compiled support code only records safe evidence
        // through the public DurableQueue facade.
        this.audit.record("queue.consume:" + eventId + ":queue=" + this.queue.getClass().getInterfaces()[0].getSimpleName());
        return "ACK:" + eventId;
    }

    /**
     * Handles a failed message as a safe dead-letter summary.
     *
     * @param eventId public event id
     * @return safe dead-letter result
     */
    @DeadLetterHandler
    public String deadLetter(final String eventId) {
        this.audit.record("queue.dead-letter:" + eventId);
        return "DEAD_LETTER:" + eventId;
    }
}
