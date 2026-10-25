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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLog;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogAppendResult;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogIntegrityPolicy;
import org.j7m.middleware.api.durablelog.LogMetricsSnapshot;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogRecordType;
import org.j7m.middleware.api.durablelog.LogRecoveryReport;
import org.j7m.middleware.api.durablelog.LogSnapshot;
import org.j7m.middleware.api.durablelog.LogSegmentId;
import org.j7m.middleware.api.durablelog.LogStatus;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.durablelog.SnapshotOptions;

/**
 * File-backed append-only durable log.
 */
public final class FileDurableLog implements DurableLog {
    private static final int MAX_RETENTION_PINS = 1024;

    private final DurableLogConfig config;
    private final SegmentManager segmentManager;
    private final SegmentIndex index;
    private final DurableLogMetrics metrics;
    private final RetentionManager retentionManager;
    private final GroupCommitCoordinator groupCommit;
    private final Semaphore cursors;
    private final AtomicBoolean closed;
    private final AtomicLong batchSequence;
    private final Map<LogStreamId, LogOffset> snapshotOffsets;
    private final Map<LogStreamId, byte[]> hashHeads;
    private final Set<StreamingLogCursor> activeCursors;
    private volatile String lastError;

    /**
     * Creates a log using validated configuration.
     *
     * @param config durable-log configuration
     */
    public FileDurableLog(final DurableLogConfig config) {
        this.config = config;
        this.metrics = new DurableLogMetrics();
        this.index = new SegmentIndex();
        this.segmentManager = new SegmentManager(config);
        this.retentionManager = new RetentionManager(config);
        this.groupCommit = new GroupCommitCoordinator(config.groupCommitPolicy(), this::flushDelayedGroupCommit);
        this.cursors = new Semaphore(config.maxOpenCursors());
        this.closed = new AtomicBoolean(false);
        this.batchSequence = new AtomicLong(1L);
        this.snapshotOffsets = new LinkedHashMap<>();
        this.hashHeads = new LinkedHashMap<>();
        this.activeCursors = Collections.newSetFromMap(new IdentityHashMap<>());
        this.lastError = "";
        try {
            Files.createDirectories(config.directory());
            this.segmentManager.open();
        } catch (IOException exception) {
            throw this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "durable log open failed", exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LogAppendResult append(final LogStreamId stream, final List<LogRecord> records, final AppendOptions options) {
        this.ensureOpen();
        LogStreamId safeStream = stream == null ? null : stream;
        if (safeStream == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "stream id is required", null);
        }
        List<LogRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        if (safeRecords.isEmpty()) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "empty append is not supported", null);
        }
        AppendOptions effective = options == null ? AppendOptions.defaults() : options;
        int maxRecords = effective.maxRecordsPerAppend() == 0 ? this.config.maxRecordsPerAppend() : Math.min(effective.maxRecordsPerAppend(), this.config.maxRecordsPerAppend());
        long maxPayload = effective.maxPayloadBytes() == 0L ? this.config.maxPayloadBytes() : Math.min(effective.maxPayloadBytes(), this.config.maxPayloadBytes());
        if (safeRecords.size() > maxRecords) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "append record limit exceeded", null);
        }
        for (LogRecord record : safeRecords) {
            if (record.payloadLength() > maxPayload) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "record payload limit exceeded", null);
            }
        }
        try {
            long batchId = this.batchSequence.getAndIncrement();
            LogOffset first = this.index.nextOffset(safeStream);
            List<RecordFrameCodec.EncodedFrame> frames = new ArrayList<>(safeRecords.size());
            long totalBytes = 0L;
            boolean hashChain = effective.hashChain() || this.config.integrityPolicy() == LogIntegrityPolicy.CHECKSUM_AND_HASH_CHAIN;
            byte[] previousHash = hashChain
                    ? Arrays.copyOf(this.hashHeads.getOrDefault(safeStream, new byte[0]), this.hashHeads.getOrDefault(safeStream, new byte[0]).length)
                    : new byte[0];
            for (int indexInBatch = 0; indexInBatch < safeRecords.size(); indexInBatch++) {
                LogRecord input = safeRecords.get(indexInBatch);
                LogOffset offset = new LogOffset(first.value() + indexInBatch);
                LogRecordHeader header = new LogRecordHeader(input.header().type(),
                        input.header().timestampMillis() == 0L ? this.config.clock().millis() : input.header().timestampMillis(),
                        input.header().metadata());
                RecordFrame frame = new RecordFrame(batchId, safeRecords.size(), indexInBatch, safeStream, offset, header, input.payload(), previousHash);
                RecordFrameCodec.EncodedFrame encoded = RecordFrameCodec.encode(frame);
                frames.add(encoded);
                totalBytes += encoded.bytes().length;
                if (hashChain) {
                    previousHash = RecordChecksum.sha256(encoded.bytes());
                }
            }
            this.flushGroupCommitBeforeRotation(totalBytes);
            SegmentWriter writer = this.segmentManager.writerFor(totalBytes);
            long startPosition = writer.position();
            for (RecordFrameCodec.EncodedFrame frame : frames) {
                writer.write(frame.bytes());
            }
            boolean fsynced = this.applyFsync(writer.channel());
            for (int indexInBatch = 0; indexInBatch < frames.size(); indexInBatch++) {
                RecordFrame decoded = frames.get(indexInBatch).frame();
                long position = startPosition;
                for (int previous = 0; previous < indexInBatch; previous++) {
                    position += frames.get(previous).bytes().length;
                }
                this.index.add(decoded.stream(), decoded.offset(), new RecordLocation(writer.segmentId(), position, frames.get(indexInBatch).bytes().length));
            }
            this.metrics.recordAppend(safeRecords.size(), totalBytes, this.config.clock().millis());
            if (hashChain) {
                this.hashHeads.put(safeStream, Arrays.copyOf(previousHash, previousHash.length));
            }
            this.retentionManager.apply(this.segmentManager, this.index, this.retentionProtections());
            return new LogAppendResult(safeStream, first, new LogOffset(first.value() + safeRecords.size() - 1L), safeRecords.size(), totalBytes, fsynced);
        } catch (IOException exception) {
            throw this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "durable log append failed", exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LogCursor openCursor(final LogStreamId stream, final LogOffset offset, final CursorOptions options) {
        this.ensureOpen();
        LogStreamId safeStream = stream == null ? null : stream;
        if (safeStream == null || offset == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "stream and offset are required", null);
        }
        CursorOptions effective = options == null ? CursorOptions.defaults() : options;
        Optional<IndexedLocation> location = this.index.firstLocationAtOrAfter(safeStream, offset);
        if (location.isEmpty() && !effective.allowMissingStream() && !this.index.hasStream(safeStream)) {
            throw this.failure(MiddlewareErrorCode.NOT_FOUND, "log stream is not available", null);
        }
        if (!this.cursors.tryAcquire()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "open cursor limit exceeded", null);
        }
        try {
            this.requireRetentionPinCapacity(1);
            StreamingLogCursor cursor = new StreamingLogCursor(this, safeStream, offset);
            this.activeCursors.add(cursor);
            return cursor;
        } catch (IOException exception) {
            this.cursors.release();
            throw this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "durable log cursor failed", exception);
        } catch (RuntimeException exception) {
            this.cursors.release();
            if (exception instanceof DurableLogException durableLogException) {
                this.lastError = durableLogException.code().name();
                throw durableLogException;
            }
            throw exception;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LogSnapshot createSnapshot(final LogStreamId stream, final SnapshotOptions options) {
        SnapshotOptions effective = options == null ? SnapshotOptions.defaults() : options;
        this.requireRetentionPinCapacity(this.snapshotOffsets.containsKey(stream) ? 0 : 1);
        LogRecord marker = SnapshotMarkerWriter.marker(effective.metadata());
        LogAppendResult result = this.append(stream, List.of(marker), AppendOptions.defaults());
        LogSnapshot snapshot = new LogSnapshot(stream, result.firstOffset(), this.config.clock().millis(), effective.metadata());
        this.snapshotOffsets.put(stream, result.firstOffset());
        return snapshot;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LogRecoveryReport recover(final RecoveryOptions options) {
        this.ensureOpen();
        RecoveryOptions effective = options == null ? RecoveryOptions.defaults() : options;
        try {
            LogRecovery recovery = new LogRecovery(this.config, this.segmentManager, this.index, this.metrics, this.hashHeads);
            LogRecoveryReport report = recovery.recover(effective);
            this.metrics.recordRecovery(this.config.clock().millis());
            this.snapshotOffsets.clear();
            this.snapshotOffsets.putAll(this.index.snapshotOffsets());
            return report;
        } catch (DurableLogException exception) {
            this.lastError = exception.code().name();
            this.closeAfterRecoveryFailure(exception);
            throw exception;
        } catch (IOException exception) {
            DurableLogException failure = this.failure(MiddlewareErrorCode.RECOVERY_FAILED, "durable log recovery failed", exception);
            this.closeAfterRecoveryFailure(failure);
            throw failure;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LogStatus status() {
        return new LogStatus(this.config.directory().getFileName() == null ? "." : this.config.directory().getFileName().toString(),
                !this.closed.get(), this.index.streamCount(), this.segmentManager.segmentCount(),
                this.segmentManager.activeSegmentSummary(), this.index.currentOffsets(), this.metrics.snapshot(),
                this.config.fsyncPolicy(), this.config.retentionPolicy(), this.lastError);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        List<StreamingLogCursor> cursorsToClose;
        IOException closeFailure = null;
        synchronized (this) {
            if (!this.closed.compareAndSet(false, true)) {
                return;
            }
            try {
                if (this.config.fsyncPolicy() == FsyncPolicy.ON_CLOSE || this.groupCommit.consumePendingForExplicitFlush()) {
                    this.segmentManager.forceActive();
                    this.metrics.recordFsync();
                }
                this.segmentManager.close();
            } catch (IOException exception) {
                this.lastError = "close failed";
                closeFailure = exception;
            } finally {
                cursorsToClose = new ArrayList<>(this.activeCursors);
                this.activeCursors.clear();
                if (!cursorsToClose.isEmpty()) {
                    this.cursors.release(cursorsToClose.size());
                }
            }
        }
        for (StreamingLogCursor cursor : cursorsToClose) {
            cursor.closeFromOwner();
        }
        this.groupCommit.close();
        if (closeFailure != null) {
            throw this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "durable log close failed", closeFailure);
        }
    }

    /**
     * Returns config for tests and diagnostics.
     *
     * @return durable-log configuration
     */
    public DurableLogConfig config() {
        return this.config;
    }

    /**
     * Finds the latest record offset in a stream whose header metadata contains
     * the supplied key and value while examining at most a bounded number of
     * records.
     *
     * <p>This operation is intended for bounded local recovery of higher-level
     * services that publish authoritative checkpoint records. It scans the
     * in-memory index in reverse order and reads only the fixed frame header and
     * metadata prefix until a matching header is found or
     * {@code maxRecordsExamined} records have been inspected. The method does
     * not allocate, copy, interpret, or checksum payload bytes; full-frame
     * checksum validation is performed by normal recovery and cursor reads. The
     * returned offset is stable until retention removes the corresponding
     * segment; callers that need replay from the offset should open a cursor
     * promptly.</p>
     *
     * @param stream stream to inspect
     * @param metadataKey required metadata key
     * @param metadataValue required metadata value
     * @param maxRecordsExamined hard upper bound on inspected records
     * @return latest matching offset, or empty when no record matches
     * @throws DurableLogException when the log is closed, arguments are invalid,
     *         or the underlying segment cannot be read
     */
    @Override
    public Optional<LogOffset> findLatestOffsetByMetadata(
            final LogStreamId stream,
            final String metadataKey,
            final String metadataValue,
            final int maxRecordsExamined) {
        if (stream == null || metadataKey == null || metadataKey.isBlank() || metadataValue == null || maxRecordsExamined < 1) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "stream and metadata are required", null);
        }
        List<IndexedLocation> candidates = this.metadataLookupCandidates(stream, maxRecordsExamined);
        for (IndexedLocation indexed : candidates) {
            try {
                Map<String, String> metadata = this.readMetadata(indexed.location());
                if (metadataValue.equals(metadata.get(metadataKey))) {
                    return Optional.of(indexed.offset());
                }
            } catch (IOException exception) {
                throw this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "durable log metadata lookup failed", exception);
            }
        }
        return Optional.empty();
    }

    private synchronized List<IndexedLocation> metadataLookupCandidates(final LogStreamId stream, final int maxRecordsExamined) {
        this.ensureOpen();
        List<IndexedLocation> candidates = new ArrayList<>(Math.min(maxRecordsExamined, 1024));
        long lookupOffset = Long.MAX_VALUE;
        Optional<IndexedLocation> next = this.index.lastLocationAtOrBefore(stream, lookupOffset);
        int examined = 0;
        while (next.isPresent() && examined < maxRecordsExamined) {
            IndexedLocation indexed = next.orElseThrow();
            candidates.add(indexed);
            examined++;
            if (indexed.offset().value() == 0L) {
                return candidates;
            }
            lookupOffset = indexed.offset().value() - 1L;
            next = this.index.lastLocationAtOrBefore(stream, lookupOffset);
        }
        return candidates;
    }

    private Map<String, String> readMetadata(final RecordLocation location) throws IOException {
        try (FileChannel channel = FileChannel.open(this.segmentPath(location.segmentId()), StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(SegmentFileFormat.HEADER_SIZE);
            SegmentReader.readFully(channel, header, location.position());
            header.flip();
            int prefixLength = RecordFrameCodec.metadataPrefixLength(header, this.config.maxPayloadBytes(), location.length());
            byte[] prefix = new byte[prefixLength];
            ByteBuffer prefixBuffer = ByteBuffer.wrap(prefix);
            SegmentReader.readFully(channel, prefixBuffer, location.position() + SegmentFileFormat.HEADER_SIZE);
            prefixBuffer.flip();
            return RecordFrameCodec.decodeHeaderMetadata(header, prefix, this.config.maxPayloadBytes(), location.length()).metadata();
        }
    }

    private void closeAfterRecoveryFailure(final Throwable failure) {
        List<StreamingLogCursor> cursorsToClose = new ArrayList<>(this.activeCursors);
        this.activeCursors.clear();
        if (!cursorsToClose.isEmpty()) {
            this.cursors.release(cursorsToClose.size());
        }
        this.closed.set(true);
        try {
            this.segmentManager.close();
        } catch (IOException closeException) {
            failure.addSuppressed(closeException);
        } finally {
            this.groupCommit.close();
        }
        for (StreamingLogCursor cursor : cursorsToClose) {
            cursor.closeFromOwner();
        }
    }

    private boolean applyFsync(final FileChannel channel) throws IOException {
        if (this.config.fsyncPolicy() == FsyncPolicy.PER_APPEND) {
            channel.force(true);
            this.metrics.recordFsync();
            return true;
        }
        if (this.config.fsyncPolicy() == FsyncPolicy.GROUP_COMMIT && this.groupCommit.afterAppend()) {
            channel.force(true);
            this.metrics.recordFsync();
            return true;
        }
        return false;
    }

    private void flushGroupCommitBeforeRotation(final long bytes) throws IOException {
        if (this.config.fsyncPolicy() == FsyncPolicy.GROUP_COMMIT
                && this.groupCommit.hasPending()
                && this.segmentManager.wouldRotate(bytes)
                && this.groupCommit.consumePendingForExplicitFlush()) {
            this.segmentManager.forceActive();
            this.metrics.recordFsync();
        }
    }

    private synchronized void flushDelayedGroupCommit() {
        if (this.closed.get() || this.config.fsyncPolicy() != FsyncPolicy.GROUP_COMMIT || !this.groupCommit.consumePendingForDelayedFlush()) {
            return;
        }
        try {
            this.segmentManager.forceActive();
            this.metrics.recordFsync();
        } catch (IOException exception) {
            this.lastError = MiddlewareErrorCode.DURABLE_LOG_ERROR.name();
        }
    }

    boolean groupCommitSchedulerShutdownForTests() {
        return this.groupCommit.schedulerShutdown();
    }

    synchronized Optional<CursorReadReservation> reserveNextRecord(final StreamingLogCursor cursor) {
        if (this.closed.get() || !this.activeCursors.contains(cursor)) {
            return Optional.empty();
        }
        Optional<IndexedLocation> next = this.index.firstLocationAtOrAfter(cursor.stream(), cursor.nextOffset());
        if (next.isEmpty()) {
            return Optional.empty();
        }
        IndexedLocation indexed = next.orElseThrow();
        return Optional.of(new CursorReadReservation(indexed.offset(), indexed.location()));
    }

    synchronized Optional<LogRecord> publishCursorRead(final StreamingLogCursor cursor, final CursorReadReservation reservation, final RecordFrame frame) {
        if (this.closed.get() || !this.activeCursors.contains(cursor)) {
            return Optional.empty();
        }
        cursor.advance(new LogOffset(reservation.offset().value() + 1L));
        this.metrics.recordRead(reservation.location().length());
        return Optional.of(new LogRecord(frame.header(), frame.payload()));
    }

    void closeCursor(final StreamingLogCursor cursor) {
        boolean removed;
        synchronized (this) {
            removed = this.activeCursors.remove(cursor);
            if (removed) {
                this.cursors.release();
            }
        }
        if (removed) {
            cursor.closeFromOwner();
        }
    }

    Path segmentPath(final LogSegmentId segmentId) {
        return this.segmentManager.path(segmentId);
    }

    long maxPayloadBytes() {
        return this.config.maxPayloadBytes();
    }

    void recordCursorCloseFailure() {
        this.lastError = MiddlewareErrorCode.DURABLE_LOG_ERROR.name();
    }

    DurableLogException cursorFailure(final IOException exception) {
        return this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "durable log cursor failed", exception);
    }

    private List<RetentionProtection> retentionProtections() {
        List<RetentionProtection> protections = new ArrayList<>(this.activeCursors.size() + this.snapshotOffsets.size());
        for (StreamingLogCursor cursor : this.activeCursors) {
            protections.add(new RetentionProtection(cursor.stream(), cursor.nextOffset(), "cursor"));
        }
        for (Map.Entry<LogStreamId, LogOffset> entry : this.snapshotOffsets.entrySet()) {
            protections.add(new RetentionProtection(entry.getKey(), entry.getValue(), "snapshot"));
        }
        return protections;
    }

    private void requireRetentionPinCapacity(final int additionalPins) {
        int pins = this.activeCursors.size() + this.snapshotOffsets.size() + additionalPins;
        if (pins > MAX_RETENTION_PINS) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "durable log retention pin limit exceeded", null);
        }
    }

    private void ensureOpen() {
        if (this.closed.get()) {
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "durable log is closed", null);
        }
    }

    private DurableLogException failure(final MiddlewareErrorCode code, final String message, final Throwable cause) {
        this.lastError = code.name();
        if (cause == null) {
            return new DurableLogException(code, message);
        }
        return new DurableLogException(code, message, cause);
    }
}

record CursorReadReservation(LogOffset offset, RecordLocation location) {
}

final class StreamingLogCursor implements LogCursor {
    private final FileDurableLog owner;
    private final LogStreamId stream;
    private volatile LogOffset nextOffset;
    private FileChannel channel;
    private long channelSegmentId;
    private volatile boolean closed;

    StreamingLogCursor(final FileDurableLog owner, final LogStreamId stream, final LogOffset nextOffset) throws IOException {
        this.owner = owner;
        this.stream = stream;
        this.nextOffset = nextOffset;
        this.channel = null;
        this.channelSegmentId = Long.MIN_VALUE;
        this.closed = false;
    }

    @Override
    public Optional<LogRecord> next() {
        synchronized (this) {
            if (this.closed) {
                return Optional.empty();
            }
            Optional<CursorReadReservation> reservation = this.owner.reserveNextRecord(this);
            if (reservation.isEmpty()) {
                return Optional.empty();
            }
            CursorReadReservation selected = reservation.orElseThrow();
            try {
                RecordFrame frame = this.read(selected.location());
                return this.owner.publishCursorRead(this, selected, frame);
            } catch (IOException exception) {
                throw this.owner.cursorFailure(exception);
            }
        }
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.owner.closeCursor(this);
        }
    }

    synchronized void closeFromOwner() {
        this.closed = true;
        this.closeChannel();
    }

    LogStreamId stream() {
        return this.stream;
    }

    LogOffset nextOffset() {
        return this.nextOffset;
    }

    void advance(final LogOffset offset) {
        this.nextOffset = offset;
    }

    RecordFrame read(final RecordLocation location) throws IOException {
        if (this.channel == null || this.channelSegmentId != location.segmentId().value()) {
            this.closeChannel();
            this.channel = FileChannel.open(this.owner.segmentPath(location.segmentId()), StandardOpenOption.READ);
            this.channelSegmentId = location.segmentId().value();
        }
        ByteBuffer header = ByteBuffer.allocate(SegmentFileFormat.HEADER_SIZE);
        SegmentReader.readFully(this.channel, header, location.position());
        header.flip();
        long frameLength = RecordFrameCodec.validatedFrameLength(header, this.owner.maxPayloadBytes(), location.length());
        byte[] tail = new byte[(int) frameLength - SegmentFileFormat.HEADER_SIZE];
        ByteBuffer tailBuffer = ByteBuffer.wrap(tail);
        SegmentReader.readFully(this.channel, tailBuffer, location.position() + SegmentFileFormat.HEADER_SIZE);
        tailBuffer.flip();
        return RecordFrameCodec.decode(header, tail, this.owner.maxPayloadBytes()).frame();
    }

    private void closeChannel() {
        if (this.channel != null) {
            try {
                this.channel.close();
            } catch (IOException exception) {
                this.owner.recordCursorCloseFailure();
            } finally {
                this.channel = null;
                this.channelSegmentId = Long.MIN_VALUE;
            }
        }
    }
}
