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

package org.j7m.middleware.messaging;

import java.time.Clock;
import org.j7m.middleware.api.durablelog.DurableLog;
import org.j7m.middleware.api.durablelog.RetentionPolicy;
import org.j7m.middleware.api.messaging.InboxOptions;
import org.j7m.middleware.api.messaging.OutboxOptions;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.j7m.middleware.durablelog.FileDurableLog;

/**
 * Immutable durable queue configuration.
 *
 * @param durableLog durable log substrate
 * @param queueOptions queue limits and policies
 * @param outboxOptions outbox limits and policies
 * @param inboxOptions inbox limits and policies
 * @param clock clock used for deterministic time semantics
 * @param ownsDurableLog whether the queue closes the durable log
 * @param maxEventPayloadBytes maximum encoded queue-event payload size; the
 *        effective value is also capped by the strict queue event codec so a
 *        queue never writes an event that its own recovery codec rejects
 *
 * <p>Duplicate idempotency and dedupe decisions, terminal message summaries,
 * and completed outbox records are retained in bounded deterministic insertion
 * order. Once an old protection entry is evicted, a repeated idempotency key,
 * dedupe key, or completed outbox entry may be treated as a new operation
 * rather than a retained duplicate decision. Live queued messages, pending
 * outbox records, and inbox dedupe entries remain governed by their explicit
 * queue, outbox, and inbox limits.</p>
 */
public record DurableQueueConfig(
        DurableLog durableLog,
        QueueOptions queueOptions,
        OutboxOptions outboxOptions,
        InboxOptions inboxOptions,
        Clock clock,
        boolean ownsDurableLog,
        long maxEventPayloadBytes) {
    /**
     * Creates validated configuration.
     *
     * @throws IllegalArgumentException when {@code durableLog} is null
     */
    public DurableQueueConfig {
        if (durableLog == null) {
            throw new IllegalArgumentException("durableLog is required");
        }
        queueOptions = queueOptions == null ? QueueOptions.defaults() : queueOptions;
        outboxOptions = outboxOptions == null ? OutboxOptions.defaults() : outboxOptions;
        inboxOptions = inboxOptions == null ? InboxOptions.defaults() : inboxOptions;
        clock = clock == null ? Clock.systemUTC() : clock;
        if (maxEventPayloadBytes < 1L || maxEventPayloadBytes > QueueEventCodec.maxEncodedEventBytes()) {
            throw new IllegalArgumentException("maxEventPayloadBytes is out of range");
        }
        if (durableLog instanceof FileDurableLog fileDurableLog
                && fileDurableLog.config().retentionPolicy().mode() != RetentionPolicy.RetentionMode.DISABLED) {
            throw new IllegalArgumentException("durable queue requires retention-disabled durable log");
        }
    }

    /**
     * Creates a borrowed-log configuration using the default event payload
     * limit derived from the supplied durable log.
     *
     * @param durableLog durable log substrate
     * @param queueOptions queue limits and policies
     * @param outboxOptions outbox limits and policies
     * @param inboxOptions inbox limits and policies
     * @param clock clock used for deterministic time semantics
     */
    public DurableQueueConfig(
            final DurableLog durableLog,
            final QueueOptions queueOptions,
            final OutboxOptions outboxOptions,
            final InboxOptions inboxOptions,
            final Clock clock) {
        this(durableLog, queueOptions, outboxOptions, inboxOptions, clock, false, eventPayloadLimit(durableLog, queueOptions));
    }

    private static long eventPayloadLimit(final DurableLog durableLog, final QueueOptions queueOptions) {
        if (durableLog instanceof FileDurableLog fileDurableLog) {
            return Math.min(fileDurableLog.config().maxPayloadBytes(), (long) QueueEventCodec.maxEncodedEventBytes());
        }
        QueueOptions effective = queueOptions == null ? QueueOptions.defaults() : queueOptions;
        long requested = Math.max(4L * 1024L * 1024L, Math.multiplyExact((long) effective.maxPayloadBytes(), 2L));
        return Math.min(requested, (long) QueueEventCodec.maxEncodedEventBytes());
    }
}
