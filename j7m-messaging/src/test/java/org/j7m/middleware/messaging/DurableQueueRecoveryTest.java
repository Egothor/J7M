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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.messaging.AckOptions;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessageId;
import org.j7m.middleware.api.messaging.MessagePriority;
import org.j7m.middleware.api.messaging.MessageState;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.MessagingRecoveryReport;
import org.j7m.middleware.api.messaging.OutboxEntry;
import org.j7m.middleware.api.messaging.OutboxOptions;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.j7m.middleware.api.messaging.InboxOptions;
import org.j7m.middleware.api.messaging.VisibilityTimeout;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.durablelog.DurableLogBuilder;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueRecoveryTest {
    private static final int RETAINED_RECOVERY_LIMIT = 4096;

    @TempDir
    Path directory;

    @Test
    public void recoveryRebuildsEnqueuedAckedLeasedExpiredAndDeadLetteredState() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("available"), EnqueueOptions.defaults());
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("acked"), EnqueueOptions.defaults());
            LeaseBatch acked = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());
            queue.ack(MessagingTestSupport.queue(), acked.leases().getFirst().envelope().messageId(), new AckOptions(acked.leases().getFirst().leaseId()));
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("leased"), EnqueueOptions.defaults());
            queue.lease(MessagingTestSupport.queue(), new ConsumerId("c2"), new LeaseOptions(1, new VisibilityTimeout(Duration.ofSeconds(1L)), null));
        }
        clock.advance(Duration.ofSeconds(2L));
        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            MessagingRecoveryReport report = recovered.recoveryReport();
            assertEquals(1L, report.queuesRecovered());
            assertEquals(2L, recovered.status(MessagingTestSupport.queue()).available());
        }
    }

    @Test
    public void checkpointRecoveryPreservesTerminalDuplicateDecision() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnqueueOptions options = new EnqueueOptions(org.j7m.middleware.api.messaging.MessagePriority.NORMAL, null, null,
                new IdempotencyKey("checkpoint-terminal-idempotency"), null, Instant.EPOCH);
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            org.j7m.middleware.api.messaging.EnqueueResult result =
                    queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("once"), options);
            LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());
            queue.ack(MessagingTestSupport.queue(), result.messageId(), new AckOptions(batch.leases().getFirst().leaseId()));
        }

        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(0L, recovered.status(MessagingTestSupport.queue()).available());
            assertEquals(true, recovered.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("again"), options).duplicate());
        }
    }

    @Test
    public void recoveryStartsAtLatestCheckpointAndDoesNotDecodeHistoricalDomainEvents() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            for (int index = 0; index < 300; index++) {
                queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("bounded-" + index), EnqueueOptions.defaults());
                LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("bounded"), LeaseOptions.defaults());
                queue.ack(MessagingTestSupport.queue(), batch.leases().getFirst().envelope().messageId(),
                        new AckOptions(batch.leases().getFirst().leaseId()));
            }
        }

        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(133L, recovered.recoveryReport().eventsReplayed());
        }
    }

    @Test
    public void recoveryRejectsInvalidPersistentBoolean() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OutboxEntry entry;
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            entry = queue.record(MessagingTestSupport.queue(), MessagingTestSupport.message("outbox"), EnqueueOptions.defaults());
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("entryId", entry.entryId().value());
        fields.put("attempts", "1");
        fields.put("failed", "maybe");
        MessagingTestSupport.appendEvent(this.directory, clock, new QueueEvent("OUTBOX_FAILED", fields));

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void duplicateDecisionRetentionEvictsAtRuntimeButRejectsOverLimitRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        int retainedDecisions = 4096;
        int count = retainedDecisions + 9;
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            for (int index = 0; index < count; index++) {
                EnqueueOptions options = idempotentOptions("bounded-idem-" + index);
                queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("duplicate-" + index), options);
                LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("dedupe"), LeaseOptions.defaults());
                queue.ack(MessagingTestSupport.queue(), batch.leases().getFirst().envelope().messageId(),
                        new AckOptions(batch.leases().getFirst().leaseId()));
            }
            assertEquals(false, queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("old"),
                    idempotentOptions("bounded-idem-0")).duplicate());
            assertEquals(true, queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("new"),
                    idempotentOptions("bounded-idem-" + (count - 1))).duplicate());
        }

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void checkpointOverflowRejectsBeforeAppendingNextDomainEvent() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        QueueOptions options = new QueueOptions(4, 300, 8192, 10, 10, 3, Duration.ofSeconds(10L));
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .clock(clock)
                .maxPayloadBytes(2048L)
                .build()) {
            LogBackedDurableQueue queue = new DurableQueueBuilder().durableLog(log).clock(clock).queueOptions(options).build();
            for (int index = 0; index < 256; index++) {
                queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("overflow-" + index), EnqueueOptions.defaults());
            }

            assertThrows(MessagingException.class,
                    () -> queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("overflow-rejected"), EnqueueOptions.defaults()));
            assertEquals(256L, queue.status(MessagingTestSupport.queue()).available());
            queue.close();

            try (LogBackedDurableQueue recovered = new DurableQueueBuilder().durableLog(log).clock(clock).queueOptions(options).build()) {
                assertEquals(256L, recovered.status(MessagingTestSupport.queue()).available());
                assertEquals(256L, recovered.recoveryReport().eventsReplayed());
            }
        }
    }

    @Test
    public void legacyRecoveryWithoutRecentCheckpointFailsBeyondBoundedTail() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendRawEnqueuedEvents(this.directory, clock, 257);

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void terminalCheckpointRestoreAdvancesMessageGeneratorAndSequence() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, terminalLine("msg-128", 128L), 128L);

        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            org.j7m.middleware.api.messaging.EnqueueResult result =
                    queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("fresh"), EnqueueOptions.defaults());

            assertEquals(new MessageId("msg-129"), result.messageId());
            assertEquals(129L, result.sequence());
        }
    }

    @Test
    public void liveCheckpointRestoreAdvancesLeaseAndOutboxGenerators() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        String body = liveLeasedLine("msg-10", "lease-9", 10L) + outboxLine("msg-20");
        appendCheckpoint(this.directory, clock, body, 10L);

        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            org.j7m.middleware.api.messaging.EnqueueResult result =
                    queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("available"), EnqueueOptions.defaults());
            LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("fresh-consumer"), LeaseOptions.defaults());
            OutboxEntry entry = queue.record(MessagingTestSupport.queue(), MessagingTestSupport.message("outbox-fresh"), EnqueueOptions.defaults());

            assertEquals(new MessageId("msg-21"), result.messageId());
            assertEquals("lease-10", batch.leases().getFirst().leaseId().value());
            assertEquals(new MessageId("msg-22"), entry.entryId());
        }
    }

    @Test
    public void inboxResultMetadataLimitRejectsBeforeEventAppend() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            assertThrows(MessagingException.class,
                    () -> queue.record(new DedupeKey("oversized-inbox-result"), "x".repeat((16 * 1024) + 1), null));
            assertEquals(0L, queue.inboxStatus().recorded());
        }
    }

    @Test
    public void checkpointRestoreAcceptsExactActiveMessageLimit() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, liveAvailableLine("msg-1", 1L) + liveAvailableLine("msg-2", 2L), 2L);
        QueueOptions options = new QueueOptions(4, 2, 8192, 10, 10, 3, Duration.ofSeconds(10L));

        try (LogBackedDurableQueue queue = openWithLimits(this.directory, clock, options, OutboxOptions.defaults(), InboxOptions.defaults())) {
            assertEquals(2L, queue.status(MessagingTestSupport.queue()).available());
        }
    }

    @Test
    public void checkpointRestoreRejectsActiveMessageLimitPlusOne() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, liveAvailableLine("msg-1", 1L) + liveAvailableLine("msg-2", 2L), 2L);
        QueueOptions options = new QueueOptions(4, 1, 8192, 10, 10, 3, Duration.ofSeconds(10L));

        assertThrows(MessagingException.class,
                () -> openWithLimits(this.directory, clock, options, OutboxOptions.defaults(), InboxOptions.defaults()));
    }

    @Test
    public void checkpointRestoreRejectsPendingOutboxLimitPlusOne() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, outboxLine("msg-1") + outboxLine("msg-2"), 2L);

        assertThrows(MessagingException.class,
                () -> openWithLimits(this.directory, clock, new QueueOptions(4, 10, 8192, 10, 10, 3, Duration.ofSeconds(10L)),
                        new OutboxOptions(1, 3), InboxOptions.defaults()));
    }

    @Test
    public void checkpointRestoreRejectsInboxLimitPlusOne() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, inboxLine("dedupe-1") + inboxLine("dedupe-2"), 2L);

        assertThrows(MessagingException.class,
                () -> openWithLimits(this.directory, clock, new QueueOptions(4, 10, 8192, 10, 10, 3, Duration.ofSeconds(10L)),
                        OutboxOptions.defaults(), new InboxOptions(true, 1)));
    }

    @Test
    public void checkpointRestoreRejectsDuplicateActiveMessage() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, liveAvailableLine("msg-1", 1L) + liveAvailableLine("msg-1", 2L), 2L);

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void unsupportedCheckpointVersionFailsRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        CRC32 crc32 = new CRC32();
        byte[] bodyBytes = new byte[0];
        crc32.update(bodyBytes, 0, bodyBytes.length);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("checkpointVersion", "2");
        fields.put("eventCount", "0");
        fields.put("checksum", Long.toUnsignedString(crc32.getValue()));
        fields.put("body", "");
        MessagingTestSupport.appendEvent(this.directory, clock, new QueueEvent("CHECKPOINT", fields));

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void checkpointRestoreRejectsAckedActiveMessageRowWithoutPartialState() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock,
                liveAvailableLine("msg-1", 1L) + activeLineWithState("msg-2", 2L, MessageState.ACKED, "", ""),
                2L);

        assertRejectedCheckpointPublishesNoState(this.directory, clock);
    }

    @Test
    public void checkpointRestoreRejectsDeadLetteredActiveMessageRowWithoutPartialState() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock,
                liveAvailableLine("msg-1", 1L) + activeLineWithState("msg-2", 2L, MessageState.DEAD_LETTERED, "", ""),
                2L);

        assertRejectedCheckpointPublishesNoState(this.directory, clock);
    }

    @Test
    public void checkpointRestoreRejectsAvailableTerminalRowWithoutPartialState() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock,
                liveAvailableLine("msg-1", 1L) + terminalLineWithState("msg-2", 2L, MessageState.AVAILABLE),
                2L);

        assertRejectedCheckpointPublishesNoState(this.directory, clock);
    }

    @Test
    public void checkpointRestoreAcceptsExactTerminalSummaryLimit() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, terminalLines(RETAINED_RECOVERY_LIMIT, 1), RETAINED_RECOVERY_LIMIT);

        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(RETAINED_RECOVERY_LIMIT, queue.recoveryReport().messagesRecovered());
        }
    }

    @Test
    public void checkpointRestoreRejectsTerminalSummaryLimitPlusOne() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, terminalLines(RETAINED_RECOVERY_LIMIT + 1, 1), RETAINED_RECOVERY_LIMIT + 1L);

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void checkpointRestoreAcceptsExactDuplicateDecisionLimit() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, liveDuplicateDecisionLines(RETAINED_RECOVERY_LIMIT, 1), RETAINED_RECOVERY_LIMIT);
        QueueOptions options = new QueueOptions(4, RETAINED_RECOVERY_LIMIT, 8192, 10, 10, 3, Duration.ofSeconds(10L));

        try (LogBackedDurableQueue queue = openWithLimits(this.directory, clock, options, OutboxOptions.defaults(), InboxOptions.defaults())) {
            assertEquals(RETAINED_RECOVERY_LIMIT, queue.status(MessagingTestSupport.queue()).available());
        }
    }

    @Test
    public void checkpointRestoreRejectsDuplicateDecisionLimitPlusOne() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, liveDuplicateDecisionLines(RETAINED_RECOVERY_LIMIT + 1, 1), RETAINED_RECOVERY_LIMIT + 1L);
        QueueOptions options = new QueueOptions(4, RETAINED_RECOVERY_LIMIT + 8, 8192, 10, 10, 3, Duration.ofSeconds(10L));

        assertThrows(MessagingException.class,
                () -> openWithLimits(this.directory, clock, options, OutboxOptions.defaults(), InboxOptions.defaults()));
    }

    @Test
    public void checkpointRestoreAcceptsExactCompletedOutboxLimit() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, completedOutboxLines(RETAINED_RECOVERY_LIMIT, 1), RETAINED_RECOVERY_LIMIT);

        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(RETAINED_RECOVERY_LIMIT, queue.outboxStatus().published());
        }
    }

    @Test
    public void checkpointRestoreRejectsCompletedOutboxLimitPlusOne() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, completedOutboxLines(RETAINED_RECOVERY_LIMIT + 1, 1), RETAINED_RECOVERY_LIMIT + 1L);

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void replayRejectsTerminalSummaryLimitPlusOneInsteadOfEvicting() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, terminalLines(RETAINED_RECOVERY_LIMIT, 1), RETAINED_RECOVERY_LIMIT);
        MessagingTestSupport.appendEvent(this.directory, clock, enqueuedEvent(5000));
        MessagingTestSupport.appendEvent(this.directory, clock, leasedEvent("msg-5001"));
        MessagingTestSupport.appendEvent(this.directory, clock, ackedEvent("msg-5001"));

        assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
    }

    @Test
    public void replayRejectsDuplicateDecisionLimitPlusOneInsteadOfEvicting() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, liveDuplicateDecisionLines(RETAINED_RECOVERY_LIMIT, 1), RETAINED_RECOVERY_LIMIT);
        MessagingTestSupport.appendEvent(this.directory, clock, enqueuedEventWithDuplicateKeys(5000));
        QueueOptions options = new QueueOptions(4, RETAINED_RECOVERY_LIMIT + 8, 8192, 10, 10, 3, Duration.ofSeconds(10L));

        assertThrows(MessagingException.class,
                () -> openWithLimits(this.directory, clock, options, OutboxOptions.defaults(), InboxOptions.defaults()));
    }

    @Test
    public void replayAppliesCompletedOutboxRetentionAfterValidCheckpoint() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCheckpoint(this.directory, clock, completedOutboxLines(RETAINED_RECOVERY_LIMIT, 1), RETAINED_RECOVERY_LIMIT);
        MessagingTestSupport.appendEvent(this.directory, clock, outboxRecordedEvent("msg-5000"));
        MessagingTestSupport.appendEvent(this.directory, clock, outboxPublishedEvent("msg-5000"));

        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(RETAINED_RECOVERY_LIMIT, queue.outboxStatus().published());
            assertEquals(0L, queue.outboxStatus().pending());
        }
    }

    private static EnqueueOptions idempotentOptions(final String key) {
        return new EnqueueOptions(MessagePriority.NORMAL, null, null, new IdempotencyKey(key), null, Instant.EPOCH);
    }

    private static void assertRejectedCheckpointPublishesNoState(final Path directory, final MessagingTestSupport.MutableClock clock) {
        MessagingException exception = assertThrows(MessagingException.class, () -> MessagingTestSupport.open(directory, clock));
        MessagingRecoveryReport report = exception.recoveryReport();
        assertEquals(0L, report.messagesRecovered());
        assertEquals(0L, report.outboxEntriesRecovered());
        assertEquals(0L, report.inboxEntriesRecovered());
    }

    private static LogBackedDurableQueue openWithLimits(
            final Path directory,
            final MessagingTestSupport.MutableClock clock,
            final QueueOptions queueOptions,
            final OutboxOptions outboxOptions,
            final InboxOptions inboxOptions) {
        return new DurableQueueBuilder().directory(directory)
                .clock(clock)
                .queueOptions(queueOptions)
                .outboxOptions(outboxOptions)
                .inboxOptions(inboxOptions)
                .build();
    }

    private static void appendRawEnqueuedEvents(final Path directory, final MessagingTestSupport.MutableClock clock, final int count) {
        try (FileDurableLog log = new DurableLogBuilder().directory(directory).clock(clock).build()) {
            for (int index = 0; index < count; index++) {
                log.append(new LogStreamId("j7m.messaging.events"),
                        List.of(new LogRecord(LogRecordHeader.data(Map.of("j7m-messaging-event", "MESSAGE_ENQUEUED")),
                                QueueEventCodec.encode(enqueuedEvent(index)))),
                        AppendOptions.defaults());
            }
        }
    }

    private static QueueEvent enqueuedEvent(final int index) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("queue", MessagingTestSupport.queue().value());
        fields.put("messageId", "msg-" + (index + 1));
        fields.put("partition", "0");
        fields.put("sequence", Long.toString(index + 1L));
        fields.put("priority", "0");
        fields.put("notBefore", "0");
        fields.put("headers", "");
        fields.put("payload", Base64.getEncoder().encodeToString(("legacy-" + index).getBytes(StandardCharsets.UTF_8)));
        fields.put("idempotencyKey", "");
        fields.put("dedupeKey", "");
        return new QueueEvent("MESSAGE_ENQUEUED", fields);
    }

    private static QueueEvent enqueuedEventWithDuplicateKeys(final int index) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("queue", MessagingTestSupport.queue().value());
        fields.put("messageId", "msg-" + (index + 1));
        fields.put("partition", "0");
        fields.put("sequence", Long.toString(index + 1L));
        fields.put("priority", "0");
        fields.put("notBefore", "0");
        fields.put("headers", "");
        fields.put("payload", Base64.getEncoder().encodeToString(("duplicate-" + index).getBytes(StandardCharsets.UTF_8)));
        fields.put("idempotencyKey", "idem-" + index);
        fields.put("dedupeKey", "dedupe-" + index);
        return new QueueEvent("MESSAGE_ENQUEUED", fields);
    }

    private static QueueEvent leasedEvent(final String messageId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("queue", MessagingTestSupport.queue().value());
        fields.put("messageId", messageId);
        fields.put("leaseId", "lease-5001");
        fields.put("consumerId", "replay-consumer");
        fields.put("leaseUntil", Long.toString(Instant.parse("2026-01-01T01:00:00Z").toEpochMilli()));
        fields.put("consumerGroup", "");
        return new QueueEvent("MESSAGE_LEASED", fields);
    }

    private static QueueEvent ackedEvent(final String messageId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("queue", MessagingTestSupport.queue().value());
        fields.put("messageId", messageId);
        fields.put("leaseId", "lease-5001");
        fields.put("consumerId", "replay-consumer");
        return new QueueEvent("MESSAGE_ACKED", fields);
    }

    private static QueueEvent outboxRecordedEvent(final String entryId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("entryId", entryId);
        fields.put("queue", MessagingTestSupport.queue().value());
        fields.put("payload", Base64.getEncoder().encodeToString("outbox-tail".getBytes(StandardCharsets.UTF_8)));
        fields.put("headers", "");
        fields.put("priority", "0");
        fields.put("partition", "");
        fields.put("orderingKey", "");
        fields.put("idempotencyKey", "");
        fields.put("dedupeKey", "");
        fields.put("notBefore", "0");
        fields.put("createdAt", "0");
        return new QueueEvent("OUTBOX_RECORDED", fields);
    }

    private static QueueEvent outboxPublishedEvent(final String entryId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("entryId", entryId);
        return new QueueEvent("OUTBOX_PUBLISHED", fields);
    }

    private static void appendCheckpoint(final Path directory, final MessagingTestSupport.MutableClock clock, final String body, final long eventCount) {
        CRC32 crc32 = new CRC32();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        crc32.update(bodyBytes, 0, bodyBytes.length);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("checkpointVersion", "1");
        fields.put("eventCount", Long.toString(eventCount));
        fields.put("checksum", Long.toUnsignedString(crc32.getValue()));
        fields.put("body", body);
        MessagingTestSupport.appendEvent(directory, clock, new QueueEvent("CHECKPOINT", fields));
    }

    private static String terminalLine(final String messageId, final long sequence) {
        return checkpointLine("T", MessagingTestSupport.queue().value(), "0", messageId, "ACKED", "0", "", "", Long.toString(sequence));
    }

    private static String terminalLineWithState(final String messageId, final long sequence, final MessageState state) {
        return checkpointLine("T", MessagingTestSupport.queue().value(), "0", messageId, state.name(), "0", "", "", Long.toString(sequence));
    }

    private static String liveLeasedLine(final String messageId, final String leaseId, final long sequence) {
        return checkpointLine("M", MessagingTestSupport.queue().value(), "0", messageId, "0", Long.toString(sequence), "LEASED",
                "0", "0", leaseId, "restored-consumer", Long.toString(Instant.parse("2026-01-01T01:00:00Z").toEpochMilli()),
                "", "", "", Base64.getEncoder().encodeToString("leased".getBytes(StandardCharsets.UTF_8)));
    }

    private static String outboxLine(final String entryId) {
        return checkpointLine("O", entryId, MessagingTestSupport.queue().value(),
                Base64.getEncoder().encodeToString("outbox".getBytes(StandardCharsets.UTF_8)), "", "0", "", "", "", "",
                "0", "0", "0", "false", "false");
    }

    private static String completedOutboxLine(final String entryId) {
        return checkpointLine("O", entryId, MessagingTestSupport.queue().value(),
                Base64.getEncoder().encodeToString("outbox".getBytes(StandardCharsets.UTF_8)), "", "0", "", "", "", "",
                "0", "0", "1", "true", "false");
    }

    private static String liveAvailableLine(final String messageId, final long sequence) {
        return checkpointLine("M", MessagingTestSupport.queue().value(), "0", messageId, "0", Long.toString(sequence), "AVAILABLE",
                "0", "0", "", "", "0", "", "", "", Base64.getEncoder().encodeToString("live".getBytes(StandardCharsets.UTF_8)));
    }

    private static String activeLineWithState(
            final String messageId,
            final long sequence,
            final MessageState state,
            final String leaseId,
            final String consumerId) {
        return checkpointLine("M", MessagingTestSupport.queue().value(), "0", messageId, "0", Long.toString(sequence), state.name(),
                "0", "0", leaseId, consumerId, "0", "", "", "",
                Base64.getEncoder().encodeToString("live".getBytes(StandardCharsets.UTF_8)));
    }

    private static String inboxLine(final String dedupeKey) {
        return checkpointLine("I", dedupeKey, "ACCEPTED", "0", "ok");
    }

    private static String terminalLines(final int count, final int firstId) {
        StringBuilder builder = new StringBuilder(count * 64);
        for (int index = 0; index < count; index++) {
            int id = firstId + index;
            builder.append(terminalLine("msg-" + id, id));
        }
        return builder.toString();
    }

    private static String liveDuplicateDecisionLines(final int count, final int firstId) {
        StringBuilder builder = new StringBuilder(count * 128);
        for (int index = 0; index < count; index++) {
            int id = firstId + index;
            builder.append(liveAvailableLineWithDuplicateKeys("msg-" + id, id, "idem-" + id, "dedupe-" + id));
        }
        return builder.toString();
    }

    private static String completedOutboxLines(final int count, final int firstId) {
        StringBuilder builder = new StringBuilder(count * 96);
        for (int index = 0; index < count; index++) {
            int id = firstId + index;
            builder.append(completedOutboxLine("msg-" + id));
        }
        return builder.toString();
    }

    private static String liveAvailableLineWithDuplicateKeys(
            final String messageId,
            final long sequence,
            final String idempotencyKey,
            final String dedupeKey) {
        return checkpointLine("M", MessagingTestSupport.queue().value(), "0", messageId, "0", Long.toString(sequence), "AVAILABLE",
                "0", "0", "", "", "0", idempotencyKey, dedupeKey, "",
                Base64.getEncoder().encodeToString("live".getBytes(StandardCharsets.UTF_8)));
    }

    private static String checkpointLine(final String kind, final String... values) {
        StringBuilder builder = new StringBuilder(kind);
        for (String value : values) {
            builder.append('|').append(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        builder.append('\n');
        return builder.toString();
    }
}
