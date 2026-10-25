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

package org.j7m.middleware.demo.messaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.j7m.middleware.api.messaging.AckOptions;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.InboxOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.MessagePriority;
import org.j7m.middleware.api.messaging.NackOptions;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.QueueName;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.messaging.DurableQueueBuilder;
import org.j7m.middleware.messaging.LogBackedDurableQueue;

/**
 * Minimal Phase 6.3 durable queue and outbox/inbox demo.
 */
public final class DurableQueueDemo {
        // J7M teaching: this block demonstrates queue metadata while durable delivery remains owned by the public DurableQueue API.
    private DurableQueueDemo() {
    }

    /**
     * Runs the demo.
     *
     * @param args ignored command-line arguments
     * @throws Exception when temporary directory creation or queue operations fail
     */
    public static void main(final String[] args) throws Exception {
        Path directory = Files.createTempDirectory("j7m-durable-queue-demo-");
        MutableDemoClock clock = new MutableDemoClock(Instant.parse("2026-01-01T00:00:00Z"));
        QueueName queueName = new QueueName("demo");
        try (LogBackedDurableQueue queue = new DurableQueueBuilder().directory(directory).clock(clock).build()) {
            // J7M teaching: enqueue/lease/ack/nack is shown with deterministic
            // time so retry, delayed delivery, and backpressure examples do
            // not depend on wall-clock sleeps or an external broker.
            EnqueueOptions high = new EnqueueOptions(new MessagePriority(10), null, null, new IdempotencyKey("demo-high"), null, Instant.EPOCH);
            queue.enqueue(queueName, message("high-priority"), high);
            queue.enqueue(queueName, message("normal"), EnqueueOptions.defaults());
            queue.enqueue(queueName, message("delayed"), new EnqueueOptions(MessagePriority.NORMAL, null, null, null, null, clock.instant().plus(Duration.ofSeconds(5L))));

            LeaseBatch first = queue.lease(queueName, new ConsumerId("demo-consumer"), LeaseOptions.defaults());
            queue.ack(queueName, first.leases().getFirst().envelope().messageId(), new AckOptions(first.leases().getFirst().leaseId()));
            LeaseBatch second = queue.lease(queueName, new ConsumerId("demo-consumer"), LeaseOptions.defaults());
            queue.nack(queueName, second.leases().getFirst().envelope().messageId(), new NackOptions(second.leases().getFirst().leaseId(), Instant.EPOCH, "demo-retry"));
            clock.advance(Duration.ofSeconds(6L));

            queue.record(queueName, message("outbox"), new EnqueueOptions(MessagePriority.NORMAL, null, null, new IdempotencyKey("demo-outbox"), null, Instant.EPOCH));
            int relayed = queue.relayPending();
            // J7M teaching: the outbox/inbox calls demonstrate duplicate
            // suppression and crash-window evidence without claiming a generic
            // distributed transaction or exactly-once delivery.
            queue.record(new DedupeKey("demo-inbox"), "accepted", InboxOptions.defaults());
            queue.record(new DedupeKey("demo-inbox"), "duplicate", InboxOptions.defaults());

            System.out.println("phase6.3 durable queue directory: " + directory.getFileName());
            System.out.println("phase6.3 leased+acked=1, relayed=" + relayed);
            System.out.println("phase6.3 status available=" + queue.status(queueName).available()
                    + ", delayed=" + queue.status(queueName).delayed()
                    + ", deadLettered=" + queue.status(queueName).deadLettered());
            System.out.println("phase6.3 outbox pending=" + queue.outboxStatus().pending()
                    + ", published=" + queue.outboxStatus().published());
            System.out.println("phase6.3 inbox recorded=" + queue.inboxStatus().recorded()
                    + ", suppressed=" + queue.inboxStatus().suppressed());
        }
        try (LogBackedDurableQueue recovered = new DurableQueueBuilder().directory(directory).clock(clock).build()) {
            System.out.println("phase6.3 recovery events=" + recovered.recoveryReport().eventsReplayed()
                    + ", queues=" + recovered.recoveryReport().queuesRecovered()
                    + ", messages=" + recovered.recoveryReport().messagesRecovered());
            System.out.println("phase6.3 non-goals: no external broker, no consensus, no transactions, no exactly-once");
        }
    }

    private static QueueMessage message(final String value) {
        return new QueueMessage(MessageHeader.empty(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MutableDemoClock extends Clock {
        private Instant instant;

        MutableDemoClock(final Instant instant) {
            this.instant = instant;
        }

        void advance(final Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }
}
