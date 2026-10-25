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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.durablelog.internal.DurableLogDiagnosticHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogCorruptionDetectionTest {
    @TempDir
    Path directory;

    @Test
    public void checksumMismatchIsDetectedAndNotIgnored() throws Exception {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("corrupt"), List.of(DurableLogTestSupport.record("bad")), AppendOptions.defaults());
        }
        new DurableLogDiagnosticHarness().corruptByte(this.directory, SegmentFileFormat.HEADER_SIZE + 1L);

        try (FileDurableLog recovered = new FileDurableLog(DurableLogConfig.defaults(this.directory))) {
            assertThrows(DurableLogException.class, () -> recovered.recover(RecoveryOptions.defaults()));
        }
    }

    @Test
    public void recoveryFailureClosesLogAgainstFurtherMutation() throws Exception {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("fail-closed"), List.of(DurableLogTestSupport.record("bad")), AppendOptions.defaults());
        }
        new DurableLogDiagnosticHarness().corruptByte(this.directory, SegmentFileFormat.HEADER_SIZE + 1L);

        try (FileDurableLog recovered = new FileDurableLog(DurableLogConfig.defaults(this.directory))) {
            assertThrows(DurableLogException.class, () -> recovered.recover(RecoveryOptions.defaults()));
            assertThrows(DurableLogException.class, () -> recovered.append(DurableLogTestSupport.stream("fail-closed"),
                    List.of(DurableLogTestSupport.record("after-failure")), AppendOptions.defaults()));
        }
    }

    @Test
    public void cursorDoesNotReturnCorruptRecordAfterTailRepair() throws Exception {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("corrupt"), List.of(DurableLogTestSupport.record("bad")), AppendOptions.defaults());
        }
        new DurableLogDiagnosticHarness().corruptByte(this.directory, SegmentFileFormat.HEADER_SIZE + 1L);

        try (FileDurableLog recovered = new FileDurableLog(DurableLogConfig.defaults(this.directory))) {
            recovered.recover(RecoveryOptions.truncateTail());
            try (LogCursor cursor = recovered.openCursor(DurableLogTestSupport.stream("corrupt"), LogOffset.beginning(), new CursorOptions(true))) {
                assertTrue(cursor.next().isEmpty());
            }
            assertEquals(1L, recovered.status().metrics().corruptionCount());
        }
    }

    @Test
    public void hashChainMismatchIsDetectedEvenWhenChecksumIsRewritten() throws Exception {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("hash-chain"),
                    List.of(DurableLogTestSupport.record("one"), DurableLogTestSupport.record("two")),
                    new AppendOptions(1024L, 16, true));
        }

        Path segment = new DurableLogDiagnosticHarness().lastSegment(this.directory);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer firstHeader = ByteBuffer.allocate(SegmentFileFormat.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            SegmentReader.readFully(channel, firstHeader, 0L);
            firstHeader.flip();
            long secondPosition = RecordFrameCodec.frameLength(firstHeader);

            ByteBuffer secondHeader = ByteBuffer.allocate(SegmentFileFormat.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            SegmentReader.readFully(channel, secondHeader, secondPosition);
            secondHeader.flip();
            int streamLength = secondHeader.getInt(24);
            int headerLength = secondHeader.getInt(48);
            int previousHashLength = secondHeader.getInt(56);
            long frameLength = secondHeader.getLong(64);
            assertEquals(32, previousHashLength);

            byte[] frame = new byte[(int) frameLength];
            SegmentReader.readFully(channel, ByteBuffer.wrap(frame), secondPosition);
            int previousHashIndex = SegmentFileFormat.HEADER_SIZE + streamLength + headerLength;
            frame[previousHashIndex] = (byte) (frame[previousHashIndex] ^ 0x01);
            ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(60, 0);
            int checksum = RecordChecksum.checksum(frame);
            ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(60, checksum);
            channel.write(ByteBuffer.wrap(frame), secondPosition);
        }

        try (FileDurableLog recovered = new FileDurableLog(DurableLogConfig.defaults(this.directory))) {
            assertThrows(DurableLogException.class, () -> recovered.recover(RecoveryOptions.defaults()));
        }
    }

    @Test
    public void malformedRecordTypeIsReportedAsDurableLogCorruption() throws Exception {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("type"), List.of(DurableLogTestSupport.record("one")), AppendOptions.defaults());
        }

        Path segment = new DurableLogDiagnosticHarness().lastSegment(this.directory);
        byte[] frame = Files.readAllBytes(segment);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(44, 999);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(60, 0);
        int checksum = RecordChecksum.checksum(frame);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(60, checksum);
        Files.write(segment, frame);

        try (FileDurableLog recovered = new FileDurableLog(DurableLogConfig.defaults(this.directory))) {
            assertThrows(DurableLogException.class, () -> recovered.recover(RecoveryOptions.defaults()));
        }
    }

    @Test
    public void cursorPermitIsReleasedAfterDecodeCorruption() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxOpenCursors(1).build()) {
            log.append(DurableLogTestSupport.stream("cursor-corrupt"), List.of(DurableLogTestSupport.record("one")), AppendOptions.defaults());
            new DurableLogDiagnosticHarness().corruptByte(this.directory, SegmentFileFormat.HEADER_SIZE + 1L);

            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("cursor-corrupt"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertThrows(DurableLogException.class, cursor::next);
            }
            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("cursor-corrupt"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertThrows(DurableLogException.class, cursor::next);
            }
        }
    }

    @Test
    public void recoveryRejectsChecksumValidBatchSizeAboveConfiguredAppendLimit() throws Exception {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .maxRecordsPerAppend(1)
                .fsyncPolicy(FsyncPolicy.PER_APPEND)
                .build()) {
            log.append(DurableLogTestSupport.stream("batch-bound"), List.of(DurableLogTestSupport.record("one")), AppendOptions.defaults());
        }

        Path segment = new DurableLogDiagnosticHarness().lastSegment(this.directory);
        byte[] frame = Files.readAllBytes(segment);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(16, 2);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(60, 0);
        int checksum = RecordChecksum.checksum(frame);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(60, checksum);
        Files.write(segment, frame);

        try (FileDurableLog recovered = new FileDurableLog(new DurableLogConfig(this.directory, 16L * 1024L * 1024L,
                1024L * 1024L, 1, 128, FsyncPolicy.PER_APPEND,
                org.j7m.middleware.api.durablelog.GroupCommitPolicy.defaults(),
                org.j7m.middleware.api.durablelog.LogIntegrityPolicy.CHECKSUM_ONLY,
                org.j7m.middleware.api.durablelog.RetentionPolicy.disabled(), java.time.Clock.systemUTC()))) {
            assertThrows(DurableLogException.class, () -> recovered.recover(RecoveryOptions.defaults()));
        }
    }

    @Test
    public void builderFailureClosesLogSoRepairOpenCanProceed() throws Exception {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("builder-failure"), List.of(DurableLogTestSupport.record("bad")), AppendOptions.defaults());
        }
        new DurableLogDiagnosticHarness().corruptByte(this.directory, SegmentFileFormat.HEADER_SIZE + 1L);

        assertThrows(DurableLogException.class, () -> new DurableLogBuilder().directory(this.directory).build());

        try (FileDurableLog repaired = new DurableLogBuilder().directory(this.directory).truncateTailRecovery().build()) {
            assertTrue(repaired.status().open());
        }
    }
}
