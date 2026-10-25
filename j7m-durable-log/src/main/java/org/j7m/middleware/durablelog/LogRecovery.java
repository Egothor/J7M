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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.LogCorruptionAction;
import org.j7m.middleware.api.durablelog.LogIntegrityPolicy;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecoveryReport;
import org.j7m.middleware.api.durablelog.LogSegmentId;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;

/**
 * Recovery scanner for segment files.
 */
final class LogRecovery {
    private final DurableLogConfig config;
    private final SegmentManager segmentManager;
    private final SegmentIndex index;
    private final DurableLogMetrics metrics;
    private final Map<LogStreamId, byte[]> hashHeads;

    LogRecovery(final DurableLogConfig config, final SegmentManager segmentManager, final SegmentIndex index, final DurableLogMetrics metrics,
            final Map<LogStreamId, byte[]> hashHeads) {
        this.config = config;
        this.segmentManager = segmentManager;
        this.index = index;
        this.metrics = metrics;
        this.hashHeads = hashHeads;
    }

    LogRecoveryReport recover(final RecoveryOptions options) throws IOException {
        long start = System.nanoTime();
        this.index.clear();
        this.hashHeads.clear();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long recovered = 0L;
        long truncated = 0L;
        long corrupt = 0L;
        int segments = 0;
        RecoveryBudget budget = new RecoveryBudget(options.maxRecordsScanned());
        SegmentFileListing listing = this.segmentManager.boundedSegmentFiles(options.maxSegmentsScanned());
        if (!listing.complete()) {
            errors.add("segment directory scan limit exceeded");
            throw new DurableLogException(MiddlewareErrorCode.RECOVERY_FAILED, "durable log recovery segment scan limit exceeded");
        }
        for (Path path : listing.paths()) {
            segments++;
            LogSegmentId segmentId = new LogSegmentId(SegmentFileFormat.segmentId(path));
            ScanResult result = this.scanSegment(path, segmentId, options, budget);
            recovered += result.recovered();
            truncated += result.truncated();
            corrupt += result.corrupt();
            warnings.addAll(result.warnings());
            errors.addAll(result.errors());
        }
        long duration = (System.nanoTime() - start) / 1_000_000L;
        this.metrics.addTruncated(truncated);
        this.metrics.addCorruption(corrupt);
        return new LogRecoveryReport(this.index.streamCount(), segments, recovered, truncated, corrupt,
                options.rebuildIndexes() ? segments : 0L, this.index.currentOffsets(), duration, warnings, errors);
    }

    private ScanResult scanSegment(
            final Path path,
            final LogSegmentId segmentId,
            final RecoveryOptions options,
            final RecoveryBudget budget) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long recovered = 0L;
        long truncated = 0L;
        long corrupt = 0L;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long position = 0L;
            long size = channel.size();
            while (position < size) {
                long batchStart = position;
                List<FrameWithLocation> batch = new ArrayList<>();
                int expectedBatchSize = -1;
                try {
                    DecodedAt decoded = this.readAt(channel, segmentId, position, size);
                    expectedBatchSize = decoded.frame().batchSize();
                    if (expectedBatchSize > this.config.maxRecordsPerAppend()) {
                        throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log batch size exceeds configured bound");
                    }
                    budget.reserve(expectedBatchSize);
                    batch.add(new FrameWithLocation(decoded.frame(), new RecordLocation(segmentId, position, decoded.length()), decoded.bytes()));
                    position += decoded.length();
                    for (int count = 1; count < expectedBatchSize; count++) {
                        decoded = this.readAt(channel, segmentId, position, size);
                        batch.add(new FrameWithLocation(decoded.frame(), new RecordLocation(segmentId, position, decoded.length()), decoded.bytes()));
                        position += decoded.length();
                    }
                    validateBatch(batch);
                    for (FrameWithLocation frame : batch) {
                        this.verifyAndRecordHash(frame.frame(), frame.bytes());
                        this.index.observeRecovered(frame.frame(), frame.location());
                        recovered++;
                    }
                } catch (EOFException exception) {
                    truncated += expectedBatchSize < 0 ? Math.max(1, batch.size()) : expectedBatchSize;
                    if (options.corruptionAction() != LogCorruptionAction.TRUNCATE_TAIL) {
                        errors.add("truncated tail detected");
                        throw new DurableLogException(MiddlewareErrorCode.RECOVERY_FAILED, "durable log truncated tail detected", exception);
                    }
                    this.segmentManager.truncate(segmentId, batchStart);
                    warnings.add("truncated tail removed");
                    break;
                } catch (RecoveryBudgetExceededException exception) {
                    errors.add("recovery scan limit exceeded");
                    throw exception;
                } catch (DurableLogException exception) {
                    corrupt++;
                    truncated += expectedBatchSize < 0 ? Math.max(1, batch.size()) : expectedBatchSize;
                    if (options.corruptionAction() != LogCorruptionAction.TRUNCATE_TAIL) {
                        errors.add("corrupt record detected");
                        throw exception;
                    }
                    this.segmentManager.truncate(segmentId, batchStart);
                    warnings.add("corrupt tail removed");
                    break;
                }
            }
        }
        return new ScanResult(recovered, truncated, corrupt, warnings, errors);
    }

    private DecodedAt readAt(final FileChannel channel, final LogSegmentId segmentId, final long position, final long size) throws IOException {
        if (position + SegmentFileFormat.HEADER_SIZE > size) {
            throw new EOFException("truncated durable log header");
        }
        ByteBuffer header = ByteBuffer.allocate(SegmentFileFormat.HEADER_SIZE);
        SegmentReader.readFully(channel, header, position);
        header.flip();
        long availableBytes = size - position;
        long frameLength;
        try {
            frameLength = RecordFrameCodec.validatedFrameLength(header, this.config.maxPayloadBytes(), availableBytes);
        } catch (DurableLogException exception) {
            if (position + SegmentFileFormat.HEADER_SIZE > size) {
                throw new EOFException("truncated durable log frame");
            }
            throw exception;
        }
        if (position + frameLength > size) {
            throw new EOFException("truncated durable log frame");
        }
        byte[] tail = new byte[(int) frameLength - SegmentFileFormat.HEADER_SIZE];
        ByteBuffer tailBuffer = ByteBuffer.wrap(tail);
        SegmentReader.readFully(channel, tailBuffer, position + SegmentFileFormat.HEADER_SIZE);
        tailBuffer.flip();
        byte[] bytes = new byte[(int) frameLength];
        for (int index = 0; index < SegmentFileFormat.HEADER_SIZE; index++) {
            bytes[index] = header.get(index);
        }
        System.arraycopy(tail, 0, bytes, SegmentFileFormat.HEADER_SIZE, tail.length);
        DecodedFrame decoded = RecordFrameCodec.decode(header, tail, this.config.maxPayloadBytes());
        return new DecodedAt(decoded.frame(), decoded.length(), bytes);
    }

    private static void validateBatch(final List<FrameWithLocation> batch) {
        if (batch.isEmpty()) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log batch is empty");
        }
        RecordFrame first = batch.get(0).frame();
        int expectedBatchSize = first.batchSize();
        if (batch.size() != expectedBatchSize) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log batch size is invalid");
        }
        for (int index = 0; index < batch.size(); index++) {
            RecordFrame frame = batch.get(index).frame();
            if (frame.batchId() != first.batchId()
                    || frame.batchSize() != expectedBatchSize
                    || frame.batchIndex() != index
                    || !frame.stream().equals(first.stream())
                    || frame.offset().value() != first.offset().value() + index) {
                throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log batch consistency is invalid");
            }
        }
    }

    private void verifyAndRecordHash(final RecordFrame frame, final byte[] bytes) {
        byte[] expectedPrevious = this.hashHeads.getOrDefault(frame.stream(), new byte[0]);
        boolean verify = frame.previousHash().length > 0 || this.config.integrityPolicy() == LogIntegrityPolicy.CHECKSUM_AND_HASH_CHAIN;
        if (verify && !Arrays.equals(expectedPrevious, frame.previousHash())) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log hash chain mismatch");
        }
        this.hashHeads.put(frame.stream(), RecordChecksum.sha256(bytes));
    }
}

record DecodedAt(RecordFrame frame, int length, byte[] bytes) {
}

record FrameWithLocation(RecordFrame frame, RecordLocation location, byte[] bytes) {
}

record ScanResult(long recovered, long truncated, long corrupt, List<String> warnings, List<String> errors) {
}

final class RecoveryBudget {
    private final long maxRecords;
    private long records;

    RecoveryBudget(final long maxRecords) {
        this.maxRecords = maxRecords;
    }

    void reserve(final int count) {
        if (count < 1 || this.records > this.maxRecords - count) {
            throw new RecoveryBudgetExceededException("durable log recovery record scan limit exceeded");
        }
        this.records += count;
    }
}

final class RecoveryBudgetExceededException extends DurableLogException {
    RecoveryBudgetExceededException(final String message) {
        super(MiddlewareErrorCode.RECOVERY_FAILED, message);
    }
}
