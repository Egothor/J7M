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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.MessageId;
import org.j7m.middleware.api.messaging.MessagePriority;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.OutboxEntry;
import org.j7m.middleware.api.messaging.OutboxOptions;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.j7m.middleware.api.messaging.QueuePartitionId;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.reliability.OrderingKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class OutboxIntentRecoveryHardeningTest {
    @TempDir
    Path directory;

    @Test
    public void recoveredOutboxRelayUsesFullEnqueueIntent() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        Instant due = clock.instant().plus(Duration.ofMinutes(3L));
        EnqueueOptions options = new EnqueueOptions(new MessagePriority(9), new QueuePartitionId(2), new OrderingKey("customer-17"),
                new IdempotencyKey("outbox-hardening-idem"), new DedupeKey("outbox-hardening-dedupe"), due);
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.record(MessagingTestSupport.queue(), outboxMessage("preserved"), options);
            assertEquals(1L, queue.outboxStatus().pending());
        }

        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(1, recovered.relayPending());
            assertTrue(recovered.lease(MessagingTestSupport.queue(), new ConsumerId("early"), LeaseOptions.defaults()).isEmpty());
            clock.advance(Duration.ofMinutes(4L));
            LeaseBatch batch = recovered.lease(MessagingTestSupport.queue(), new ConsumerId("relay"), LeaseOptions.defaults());
            assertEquals(1, batch.leases().size());
            assertEquals(new QueuePartitionId(2), batch.leases().getFirst().envelope().partitionId());
            assertEquals(new MessagePriority(9), batch.leases().getFirst().envelope().priority());
            assertEquals("preserved", batch.leases().getFirst().envelope().message().header().metadata().get("trace"));

            assertTrue(recovered.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("duplicate"), options).duplicate());
        }
    }

    @Test
    public void publishedOutboxEntryDoesNotRelayAgainAfterRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnqueueOptions options = new EnqueueOptions(MessagePriority.NORMAL, null, null, new IdempotencyKey("published-once"), null, Instant.EPOCH);
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.record(MessagingTestSupport.queue(), outboxMessage("once"), options);
            assertEquals(1, queue.relayPending());
            assertEquals(1L, queue.outboxStatus().published());
        }
        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(0, recovered.relayPending());
            LeaseBatch batch = recovered.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), new LeaseOptions(10, null, null));
            assertEquals(1, batch.leases().size());
        }
    }

    @Test
    public void malformedOutboxRecordedEventFailsRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MessagingTestSupport.appendRawEvent(this.directory, clock,
                line("version", "1")
                        + line("type", "OUTBOX_RECORDED")
                        + line("entryId", "msg-1")
                        + line("payload", java.util.Base64.getEncoder().encodeToString("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        + line("headers", QueueEventCodec.encodeStringMap(Map.of()))
                        + line("priority", "0")
                        + line("partition", "")
                        + line("orderingKey", "")
                        + line("idempotencyKey", "")
                        + line("dedupeKey", "")
                        + line("notBefore", "0")
                        + line("createdAt", "0"));
        MessagingException exception = assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
        MessagingTestSupport.assertCode(MiddlewareErrorCode.RECOVERY_FAILED, exception);
    }

    @Test
    public void duplicatePendingOutboxIntentIsIdempotentWhenPendingLimitFull() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnqueueOptions duplicateOptions = new EnqueueOptions(MessagePriority.NORMAL, null, null,
                new IdempotencyKey("duplicate-pending"), null, Instant.EPOCH);
        try (LogBackedDurableQueue queue = limitedOutbox(this.directory, clock)) {
            OutboxEntry first = queue.record(MessagingTestSupport.queue(), outboxMessage("first"), duplicateOptions);
            OutboxEntry duplicate = queue.record(MessagingTestSupport.queue(), outboxMessage("duplicate"), duplicateOptions);
            MessagingException rejected = assertThrows(MessagingException.class,
                    () -> queue.record(MessagingTestSupport.queue(), outboxMessage("new"),
                            new EnqueueOptions(MessagePriority.NORMAL, null, null, new IdempotencyKey("new"), null, Instant.EPOCH)));

            assertEquals(first.entryId(), duplicate.entryId());
            MessagingTestSupport.assertCode(MiddlewareErrorCode.OVERLOADED, rejected);
            assertEquals(1L, queue.outboxStatus().pending());
        }
    }

    @Test
    public void duplicatePublishedOutboxIntentIsIdempotentWhenPendingLimitFull() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnqueueOptions publishedOptions = new EnqueueOptions(MessagePriority.NORMAL, null, null,
                new IdempotencyKey("duplicate-published"), null, Instant.EPOCH);
        EnqueueOptions pendingOptions = new EnqueueOptions(MessagePriority.NORMAL, null, null,
                new IdempotencyKey("pending"), null, Instant.EPOCH);
        try (LogBackedDurableQueue queue = limitedOutbox(this.directory, clock)) {
            OutboxEntry published = queue.record(MessagingTestSupport.queue(), outboxMessage("published"), publishedOptions);
            assertEquals(1, queue.relayPending());
            queue.record(MessagingTestSupport.queue(), outboxMessage("pending"), pendingOptions);
            OutboxEntry duplicate = queue.record(MessagingTestSupport.queue(), outboxMessage("duplicate"), publishedOptions);

            assertEquals(published.entryId(), duplicate.entryId());
            assertEquals(1L, queue.outboxStatus().pending());
            assertEquals(1L, queue.outboxStatus().published());
        }
    }

    @Test
    public void restartSuppressesDuplicateOutboxIntentBeforeCapacityAdmission() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnqueueOptions options = new EnqueueOptions(MessagePriority.NORMAL, null, null,
                new IdempotencyKey("restart-duplicate"), null, Instant.EPOCH);
        MessageId entryId;
        try (LogBackedDurableQueue queue = limitedOutbox(this.directory, clock)) {
            entryId = queue.record(MessagingTestSupport.queue(), outboxMessage("first"), options).entryId();
        }
        try (LogBackedDurableQueue recovered = limitedOutbox(this.directory, clock)) {
            OutboxEntry duplicate = recovered.record(MessagingTestSupport.queue(), outboxMessage("second"), options);
            assertEquals(entryId, duplicate.entryId());
            assertEquals(1L, recovered.outboxStatus().pending());
        }
    }

    private static QueueMessage outboxMessage(final String trace) {
        return new QueueMessage(new MessageHeader(Map.of("trace", trace)), trace.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static LogBackedDurableQueue limitedOutbox(final Path directory, final MessagingTestSupport.MutableClock clock) {
        return new DurableQueueBuilder().directory(directory)
                .clock(clock)
                .queueOptions(new QueueOptions(4, 100, 8192, 10, 10, 3, Duration.ofSeconds(10L)))
                .outboxOptions(new OutboxOptions(1, 3))
                .build();
    }

    private static String line(final String key, final String value) {
        return key + "=" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n";
    }
}
