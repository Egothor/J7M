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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.GroupCommitPolicy;
import org.j7m.middleware.api.durablelog.LogCorruptionAction;
import org.j7m.middleware.api.durablelog.LogIntegrityPolicy;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogRecoveryReport;
import org.j7m.middleware.api.durablelog.RetentionPolicy;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogBoundedRecoveryTest {
    @TempDir
    Path directory;

    @Test
    public void recordScanCapFailsClosedWithoutDiscardingValidHistory() {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("bounded"), List.of(
                    DurableLogTestSupport.record("one"),
                    DurableLogTestSupport.record("two"),
                    DurableLogTestSupport.record("three")), AppendOptions.defaults());
        }

        FileDurableLog capped = openWithoutBuilderRecovery(this.directory);
        DurableLogException failure = assertThrows(DurableLogException.class,
                () -> capped.recover(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true, 10, 2L)));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("record scan limit"));
        assertFalse(capped.status().open());

        try (FileDurableLog recovered = openWithoutBuilderRecovery(this.directory)) {
            LogRecoveryReport report = recovered.recover(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true, 10, 10L));
            assertEquals(3L, report.recordsRecovered());
            assertEquals(0L, report.recordsTruncated());
            assertEquals(0L, report.corruptRecordsFound());
        }
    }

    @Test
    public void recordScanCapWithTailTruncationFailsClosedWithoutTruncatingValidHistory() {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("bounded-truncate"), List.of(
                    DurableLogTestSupport.record("one"),
                    DurableLogTestSupport.record("two"),
                    DurableLogTestSupport.record("three")), AppendOptions.defaults());
        }

        FileDurableLog capped = openWithoutBuilderRecovery(this.directory);
        DurableLogException failure = assertThrows(DurableLogException.class,
                () -> capped.recover(RecoveryOptions.bounded(LogCorruptionAction.TRUNCATE_TAIL, true, 10, 2L)));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("record scan limit"));
        assertFalse(capped.status().open());

        try (FileDurableLog recovered = openWithoutBuilderRecovery(this.directory)) {
            LogRecoveryReport report = recovered.recover(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true, 10, 10L));
            assertEquals(3L, report.recordsRecovered());
            assertEquals(0L, report.recordsTruncated());
            assertEquals(0L, report.corruptRecordsFound());
        }
    }

    @Test
    public void segmentScanCapFailsClosedBeforeUnboundedIndexRebuild() {
        byte[] payload = "x".repeat(900).getBytes(StandardCharsets.UTF_8);
        try (FileDurableLog log = new DurableLogBuilder()
                .directory(this.directory)
                .maxSegmentBytes(4096L)
                .maxPayloadBytes(2048L)
                .fsyncPolicy(FsyncPolicy.PER_APPEND)
                .build()) {
            for (int index = 0; index < 8; index++) {
                log.append(DurableLogTestSupport.stream("segments"),
                        List.of(new LogRecord(LogRecordHeader.data(Map.of("kind", "bounded")), payload)),
                        AppendOptions.defaults());
            }
            assertTrue(log.status().segmentCount() > 1);
        }

        FileDurableLog capped = openWithoutBuilderRecovery(this.directory);
        DurableLogException failure = assertThrows(DurableLogException.class,
                () -> capped.recover(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true, 1, 100L)));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("segment scan limit"));
        assertFalse(capped.status().open());

        try (FileDurableLog recovered = openWithoutBuilderRecovery(this.directory)) {
            LogRecoveryReport report = recovered.recover(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true, 10, 100L));
            assertEquals(8L, report.recordsRecovered());
            assertTrue(report.segmentsScanned() > 1);
        }
    }

    @Test
    public void directoryScanCapConsumesUnrelatedEntriesAndFailsClosedBeforeReplay() throws Exception {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("directory-bound"),
                    List.of(DurableLogTestSupport.record("one")), AppendOptions.defaults());
        }
        for (int index = 0; index < 32; index++) {
            Files.writeString(this.directory.resolve("unrelated-" + index + ".tmp"), "ignored", StandardCharsets.UTF_8);
        }
        Files.writeString(this.directory.resolve("segment-not-a-number.j7mlog"), "malformed", StandardCharsets.UTF_8);

        SegmentManager manager = new SegmentManager(config(this.directory));
        SegmentFileListing listing = manager.boundedSegmentFiles(4);
        assertEquals(4, listing.entriesExamined());
        assertFalse(listing.complete());
        assertTrue(listing.paths().size() <= 4);

        FileDurableLog capped = openWithoutBuilderRecovery(this.directory);
        DurableLogException failure = assertThrows(DurableLogException.class,
                () -> capped.recover(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true, 4, 100L)));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("segment scan limit"));
        assertFalse(capped.status().open());

        try (FileDurableLog recovered = openWithoutBuilderRecovery(this.directory)) {
            LogRecoveryReport report = recovered.recover(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true, 64, 100L));
            assertEquals(1L, report.recordsRecovered());
            assertEquals(0L, report.recordsTruncated());
        }
    }

    private static FileDurableLog openWithoutBuilderRecovery(final Path directory) {
        return new FileDurableLog(config(directory));
    }

    private static DurableLogConfig config(final Path directory) {
        return new DurableLogConfig(directory, 16L * 1024L * 1024L, 1024L * 1024L, 1024, 128,
                FsyncPolicy.PER_APPEND, GroupCommitPolicy.defaults(), LogIntegrityPolicy.CHECKSUM_ONLY,
                RetentionPolicy.disabled(), Clock.systemUTC());
    }
}
