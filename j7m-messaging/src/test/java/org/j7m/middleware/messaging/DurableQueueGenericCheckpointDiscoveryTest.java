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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLog;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.LogAppendResult;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogMetricsSnapshot;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecoveryReport;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogSnapshot;
import org.j7m.middleware.api.durablelog.LogStatus;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.durablelog.SnapshotOptions;
import org.j7m.middleware.api.messaging.AckOptions;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.InboxOptions;
import org.j7m.middleware.api.messaging.OutboxOptions;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.junit.jupiter.api.Test;

public final class DurableQueueGenericCheckpointDiscoveryTest {
    @Test
    public void recoveryUsesDurableLogMetadataLookupWithoutFileCast() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryDurableLog durableLog = new InMemoryDurableLog();
        QueueOptions options = new QueueOptions(4, 512, 8192, 10, 10, 3, Duration.ofSeconds(10L));
        LogBackedDurableQueue queue = new DurableQueueBuilder().durableLog(durableLog).clock(clock).queueOptions(options).build();
        for (int index = 0; index < 300; index++) {
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("checkpoint-" + index), EnqueueOptions.defaults());
            LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("generic"), LeaseOptions.defaults());
            queue.ack(MessagingTestSupport.queue(), batch.leases().getFirst().envelope().messageId(),
                    new AckOptions(batch.leases().getFirst().leaseId()));
        }
        queue.close();

        try (LogBackedDurableQueue recovered = new DurableQueueBuilder().durableLog(durableLog).clock(clock).queueOptions(options).build()) {
            assertEquals(0L, recovered.status(MessagingTestSupport.queue()).available());
            assertEquals(true, durableLog.metadataLookupCalled);
            assertEquals(true, recovered.recoveryReport().eventsReplayed() <= 257L);
        }
    }

    @Test
    public void oversizedEventIsRejectedBeforeDurableAppend() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryDurableLog durableLog = new InMemoryDurableLog();
        QueueOptions options = new QueueOptions(1, 10, 8192, 10, 10, 3, Duration.ofSeconds(10L));
        LogBackedDurableQueue queue = new LogBackedDurableQueue(new DurableQueueConfig(durableLog, options,
                OutboxOptions.defaults(), InboxOptions.defaults(), clock, false, 96L));

        assertThrows(MessagingException.class,
                () -> queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("too-large-event"), EnqueueOptions.defaults()));
        assertEquals(0, durableLog.appendCount);
        queue.close();
    }

    private static final class InMemoryDurableLog implements DurableLog {
        private final Map<LogStreamId, List<LogRecord>> records = new LinkedHashMap<>();
        private boolean closed;
        private boolean metadataLookupCalled;
        private int appendCount;

        @Override
        public synchronized LogAppendResult append(final LogStreamId stream, final List<LogRecord> recordsToAppend, final AppendOptions options) {
            this.ensureOpen();
            List<LogRecord> streamRecords = this.records.computeIfAbsent(stream, ignored -> new ArrayList<>());
            long first = streamRecords.size();
            streamRecords.addAll(recordsToAppend);
            this.appendCount++;
            long bytes = 0L;
            for (LogRecord record : recordsToAppend) {
                bytes += record.payloadLength();
            }
            return new LogAppendResult(stream, new LogOffset(first), new LogOffset(first + recordsToAppend.size() - 1L), recordsToAppend.size(), bytes, false);
        }

        @Override
        public synchronized LogCursor openCursor(final LogStreamId stream, final LogOffset offset, final CursorOptions options) {
            this.ensureOpen();
            List<LogRecord> snapshot = List.copyOf(this.records.getOrDefault(stream, List.of()));
            return new InMemoryCursor(snapshot, offset.value());
        }

        @Override
        public LogSnapshot createSnapshot(final LogStreamId stream, final SnapshotOptions options) {
            throw new UnsupportedOperationException("snapshots are not used by this test");
        }

        @Override
        public synchronized LogRecoveryReport recover(final RecoveryOptions options) {
            this.ensureOpen();
            return new LogRecoveryReport(this.records.size(), 0, this.recordCount(), 0L, 0L, this.records.size(), Map.of(), 0L, List.of(), List.of());
        }

        @Override
        public synchronized LogStatus status() {
            return new LogStatus("memory", !this.closed, this.records.size(), 0, "memory", Map.of(), LogMetricsSnapshot.empty(),
                    null, null, "");
        }

        @Override
        public synchronized Optional<LogOffset> findLatestOffsetByMetadata(
                final LogStreamId stream,
                final String metadataKey,
                final String metadataValue,
                final int maxRecordsExamined) {
            this.ensureOpen();
            this.metadataLookupCalled = true;
            List<LogRecord> streamRecords = this.records.getOrDefault(stream, List.of());
            int examined = 0;
            for (int index = streamRecords.size() - 1; index >= 0 && examined < maxRecordsExamined; index--) {
                LogRecord record = streamRecords.get(index);
                if (metadataValue.equals(record.header().metadata().get(metadataKey))) {
                    return Optional.of(new LogOffset(index));
                }
                examined++;
            }
            return Optional.empty();
        }

        @Override
        public synchronized void close() {
            this.closed = true;
        }

        private synchronized long recordCount() {
            long count = 0L;
            for (List<LogRecord> streamRecords : this.records.values()) {
                count += streamRecords.size();
            }
            return count;
        }

        private void ensureOpen() {
            if (this.closed) {
                throw new DurableLogException(MiddlewareErrorCode.UNAVAILABLE, "durable log is closed");
            }
        }
    }

    private static final class InMemoryCursor implements LogCursor {
        private final List<LogRecord> records;
        private int position;
        private boolean closed;

        InMemoryCursor(final List<LogRecord> records, final long offset) {
            this.records = records;
            this.position = offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, offset);
            this.closed = false;
        }

        @Override
        public Optional<LogRecord> next() {
            if (this.closed || this.position >= this.records.size()) {
                return Optional.empty();
            }
            LogRecord record = this.records.get(this.position);
            this.position++;
            return Optional.of(record);
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
