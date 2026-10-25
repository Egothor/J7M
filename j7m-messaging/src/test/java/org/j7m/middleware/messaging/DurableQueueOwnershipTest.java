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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLog;
import org.j7m.middleware.api.durablelog.LogAppendResult;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecoveryReport;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogSnapshot;
import org.j7m.middleware.api.durablelog.LogStatus;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.durablelog.RetentionPolicy;
import org.j7m.middleware.api.durablelog.SnapshotOptions;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.durablelog.DurableLogBuilder;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueOwnershipTest {
    @TempDir
    Path directory;

    @Test
    public void injectedDurableLogIsBorrowedByDefault() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).clock(clock).build()) {
            LogBackedDurableQueue queue = new DurableQueueBuilder().durableLog(log).clock(clock).build();
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("borrowed"), EnqueueOptions.defaults());
            queue.close();

            log.append(new LogStreamId("ownership-probe"),
                    List.of(new LogRecord(LogRecordHeader.data(Map.of("probe", "true")), new byte[] {1})),
                    AppendOptions.defaults());
            assertEquals(true, log.status().open());
        }
    }

    @Test
    public void ownedDurableLogIsClosedWhenQueueConstructionFails() {
        FailingRecoveryLog log = new FailingRecoveryLog();

        assertThrows(MessagingException.class, () -> new DurableQueueBuilder().ownedDurableLog(log).build());

        assertEquals(1, log.closeCount);
    }

    @Test
    public void borrowedDurableLogIsNotClosedWhenQueueConstructionFails() {
        FailingRecoveryLog log = new FailingRecoveryLog();

        assertThrows(MessagingException.class, () -> new DurableQueueBuilder().durableLog(log).build());

        assertEquals(0, log.closeCount);
    }

    @Test
    public void retentionEnabledBorrowedFileLogIsRejected() {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .retentionPolicy(RetentionPolicy.maxBytes(1L))
                .build()) {
            assertThrows(IllegalArgumentException.class, () -> new DurableQueueBuilder().durableLog(log).build());
        }
    }

    private static final class FailingRecoveryLog implements DurableLog {
        int closeCount;

        @Override
        public LogAppendResult append(final LogStreamId stream, final List<LogRecord> records, final AppendOptions options) {
            throw new AssertionError("append should not be called");
        }

        @Override
        public LogCursor openCursor(final LogStreamId stream, final LogOffset offset, final CursorOptions options) {
            throw new AssertionError("cursor should not be opened");
        }

        @Override
        public LogSnapshot createSnapshot(final LogStreamId stream, final SnapshotOptions options) {
            throw new AssertionError("snapshot should not be created");
        }

        @Override
        public LogRecoveryReport recover(final RecoveryOptions options) {
            throw new IllegalStateException("recover failed");
        }

        @Override
        public LogStatus status() {
            throw new AssertionError("status should not be called");
        }

        @Override
        public void close() {
            this.closeCount++;
        }
    }
}
