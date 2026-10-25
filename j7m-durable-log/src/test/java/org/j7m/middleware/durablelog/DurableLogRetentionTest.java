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

package org.j7m.middleware.durablelog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.RetentionPolicy;
import org.j7m.middleware.api.durablelog.SnapshotOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogRetentionTest {
    @TempDir
    Path directory;

    @Test
    public void disabledRetentionKeepsInactiveAndActiveSegments() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.disabled()).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            appendLarge(log, "a");
            appendLarge(log, "b");

            assertEquals(2, segmentFiles().size());
            assertTrue(Files.exists(activeSegmentPath(log)));
            assertEquals(2, log.status().segmentCount());
        }
    }

    @Test
    public void sizeRetentionDeletesEligibleOldInactiveSegmentsButKeepsActiveSegment() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.maxBytes(5000L)).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            appendLarge(log, "removed");
            appendLarge(log, "retained");

            assertEquals(1, segmentFiles().size());
            assertTrue(Files.exists(activeSegmentPath(log)));
            assertEquals(1, log.status().segmentCount());

            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("ret"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertEquals(largePayload("retained"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertTrue(cursor.next().isEmpty());
            }
        }
    }

    @Test
    public void retainedBaseOffsetRecoversAfterRetentionAndReopen() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.maxBytes(5000L)).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            appendLarge(log, "removed");
            appendLarge(log, "retained");
            assertEquals(1, segmentFiles().size());
        }

        try (FileDurableLog recovered = new DurableLogBuilder().directory(this.directory)
                .maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.disabled())
                .fsyncPolicy(FsyncPolicy.PER_APPEND)
                .build();
             LogCursor cursor = recovered.openCursor(DurableLogTestSupport.stream("ret"), LogOffset.beginning(), CursorOptions.defaults())) {
            assertEquals(largePayload("retained"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
            assertTrue(cursor.next().isEmpty());
        }
    }

    @Test
    public void ageRetentionDeletesEligibleOldInactiveSegmentsDeterministically() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.maxAge(Duration.ofHours(1L)))
                .fsyncPolicy(FsyncPolicy.PER_APPEND)
                .clock(clock)
                .build()) {
            appendLarge(log, "old");
            appendLarge(log, "middle");
            Path oldSegment = segmentFiles().getFirst();
            Files.setLastModifiedTime(oldSegment, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));

            clock.set(Instant.parse("2026-01-01T02:00:00Z"));
            appendLarge(log, "new");

            assertEquals(2, segmentFiles().size());
            assertTrue(Files.exists(activeSegmentPath(log)));
            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("ret"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertEquals(largePayload("middle"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertEquals(largePayload("new"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertTrue(cursor.next().isEmpty());
            }
        }
    }

    @Test
    public void activeSegmentIsNeverDeletedEvenWhenItExceedsRetentionLimit() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.maxBytes(1L)).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            appendLarge(log, "active");

            assertEquals(1, segmentFiles().size());
            assertTrue(Files.exists(activeSegmentPath(log)));
            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("ret"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertEquals(largePayload("active"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
            }
        }
    }

    @Test
    public void snapshotMarkerProtectsRetentionAndReplay() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.maxBytes(1L)).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            log.createSnapshot(DurableLogTestSupport.stream("ret"), SnapshotOptions.defaults());
            String protectedPayload = "protected:" + "x".repeat(3900);
            log.append(DurableLogTestSupport.stream("ret"),
                    List.of(DurableLogTestSupport.record(protectedPayload)),
                    AppendOptions.defaults());

            assertEquals(2, segmentFiles().size());
            assertEquals(2, log.status().segmentCount());
            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("ret"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertEquals(org.j7m.middleware.api.durablelog.LogRecordType.SNAPSHOT_MARKER, cursor.next().orElseThrow().header().type());
                assertEquals(protectedPayload, DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertTrue(cursor.next().isEmpty());
            }
        }
    }

    @Test
    public void activeCursorProtectsReplayRangeAcrossMultipleSegments() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.maxBytes(1L)).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            appendLarge(log, "one");
            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("ret"), LogOffset.beginning(), CursorOptions.defaults())) {
                appendLarge(log, "two");
                appendLarge(log, "three");

                assertEquals(3, segmentFiles().size());
                assertEquals(largePayload("one"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertEquals(largePayload("two"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertEquals(largePayload("three"), DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertTrue(cursor.next().isEmpty());
            }
        }
    }

    @Test
    public void snapshotMarkerProtectsReplayRangeAcrossMultipleSegments() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxSegmentBytes(4096L)
                .retentionPolicy(RetentionPolicy.maxBytes(1L)).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            log.createSnapshot(DurableLogTestSupport.stream("ret"), SnapshotOptions.defaults());
            String firstPayload = "snapshot-one:" + "x".repeat(3900);
            String secondPayload = "snapshot-two:" + "x".repeat(3900);
            log.append(DurableLogTestSupport.stream("ret"), List.of(DurableLogTestSupport.record(firstPayload)), AppendOptions.defaults());
            log.append(DurableLogTestSupport.stream("ret"), List.of(DurableLogTestSupport.record(secondPayload)), AppendOptions.defaults());

            assertEquals(3, segmentFiles().size());
            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("ret"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertEquals(org.j7m.middleware.api.durablelog.LogRecordType.SNAPSHOT_MARKER, cursor.next().orElseThrow().header().type());
                assertEquals(firstPayload, DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertEquals(secondPayload, DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertTrue(cursor.next().isEmpty());
            }
        }
    }

    private static void appendLarge(final FileDurableLog log, final String value) {
        log.append(DurableLogTestSupport.stream("ret"),
                List.of(DurableLogTestSupport.record(largePayload(value))),
                AppendOptions.defaults());
    }

    private static String largePayload(final String value) {
        return value + ":" + "x".repeat(3000);
    }

    private List<Path> segmentFiles() throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.list(this.directory)) {
            return stream.filter(SegmentFileFormat::isSegmentFile)
                    .sorted(java.util.Comparator.comparingLong(SegmentFileFormat::segmentId))
                    .toList();
        }
    }

    private Path activeSegmentPath(final FileDurableLog log) {
        return this.directory.resolve(log.status().activeSegment());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(final Instant instant) {
            this.instant = instant;
        }

        void set(final Instant value) {
            this.instant = value;
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
