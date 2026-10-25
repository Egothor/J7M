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

package org.j7m.middleware.api.durablelog;

import java.util.List;
import java.util.Optional;

/**
 * Thread-safe append-only durable log contract.
 *
 * <p>The durable log is the local persistence substrate for higher-level J7M
 * reliability services. Implementations own file handles or equivalent local
 * durable resources and may block on append, fsync, segment rotation, cursor
 * reads, snapshot marker writes, and recovery scans. The API is local-only: it
 * is not a replicated log consensus protocol, not a queue, not a broker, and
 * not a transaction coordinator.</p>
 *
 * <p>Implementations must validate stream identifiers, offsets, record sizes,
 * checksums, frame boundaries, and recovery semantics. Durable-log failures,
 * corrupted records, invalid arguments, and unavailable/closed resources must
 * map to stable middleware error codes. Status and recovery reports are
 * redacted safe summaries and must not expose application payload contents
 * unless the caller explicitly reads records through a cursor.</p>
 */
public interface DurableLog extends AutoCloseable {
    /**
     * Appends records atomically to one stream.
     *
     * <p>Either every record in the supplied batch receives an offset in order
     * or the append fails without reporting partial success. Implementations may
     * apply configured sync and batching policy before returning.</p>
     *
     * @param stream logical stream identifier
     * @param records records to append in order
     * @param options append options controlling durability and validation policy
     * @return append result with assigned offsets
     */
    LogAppendResult append(LogStreamId stream, List<LogRecord> records, AppendOptions options);

    /**
     * Opens a deterministic replay cursor.
     *
     * <p>The returned cursor owns read resources and must be closed by the
     * caller. Cursor reads may block on local I/O but do not perform network
     * access.</p>
     *
     * @param stream logical stream identifier
     * @param offset first offset to read
     * @param options cursor options
     * @return closeable cursor
     */
    LogCursor openCursor(LogStreamId stream, LogOffset offset, CursorOptions options);

    /**
     * Finds the latest offset in a stream whose record header metadata contains
     * the supplied key and value while examining no more than the caller's
     * bound.
     *
     * <p>This method is an optional bounded metadata discovery facility for
     * higher-level services that publish compact checkpoint or marker records.
     * It exposes only logical offsets and header metadata matching semantics; it
     * does not expose segment names, local paths, frame locations, checksums,
     * payload bytes, storage layout, or implementation-specific indexes. The
     * returned offset is a hint for a prompt replay cursor and may become
     * unavailable if retention removes the record before the caller opens the
     * cursor.</p>
     *
     * <p>The default implementation returns empty because the base
     * {@link LogCursor} contract does not expose each returned record's durable
     * offset after retention has possibly advanced the stream. Implementations
     * with safe metadata indexes or cursor-offset tracking may override this
     * method with a bounded lookup, but they must preserve the same bound,
     * redaction, lifecycle, and error semantics. The method does not require
     * network access.</p>
     *
     * <p>If the log is closed, unavailable, corrupted, or interrupted through an
     * implementation-specific blocking I/O mechanism, implementations should
     * report the same stable durable-log error model used by cursor reads. This
     * interface does not clear the interrupted status and does not treat the
     * metadata key or value as authorization; callers remain responsible for
     * authentication and authorization at the service boundary. Error messages
     * and status derived from this operation must not contain payload bytes,
     * secrets, absolute paths, or storage internals.</p>
     *
     * @param stream logical stream identifier to inspect
     * @param metadataKey required header metadata key
     * @param metadataValue required header metadata value
     * @param maxRecordsExamined hard upper bound on non-empty records inspected
     * @return newest matching offset within the bound, or empty when none is
     *         found
     * @throws DurableLogException when the log is closed, the arguments are
     *         invalid, or implementation-specific metadata lookup detects a
     *         durable-log failure
     */
    default Optional<LogOffset> findLatestOffsetByMetadata(
            final LogStreamId stream,
            final String metadataKey,
            final String metadataValue,
            final int maxRecordsExamined) {
        if (stream == null || metadataKey == null || metadataKey.isBlank() || metadataValue == null || maxRecordsExamined < 1) {
            throw new DurableLogException(org.j7m.middleware.api.MiddlewareErrorCode.INVALID_ARGUMENT, "stream and metadata are required");
        }
        return Optional.empty();
    }

    /**
     * Writes a snapshot marker record.
     *
     * <p>A snapshot marker is metadata for consumers that compact their own
     * state. The durable log does not interpret application snapshot payloads or
     * install consensus snapshots.</p>
     *
     * @param stream logical stream identifier
     * @param options snapshot marker options
     * @return snapshot marker metadata
     */
    LogSnapshot createSnapshot(LogStreamId stream, SnapshotOptions options);

    /**
     * Scans storage and rebuilds in-memory/index state.
     *
     * <p>Recovery must detect malformed frames, checksum mismatches, truncated
     * tails, and semantic offset/index inconsistencies according to
     * {@code options}. The returned report is safe for logs and status.</p>
     *
     * @param options recovery options
     * @return recovery report
     */
    LogRecoveryReport recover(RecoveryOptions options);

    /**
     * Returns current status and metrics.
     *
     * @return immutable redacted status snapshot for local durable-log resources
     */
    LogStatus status();

    /**
     * Closes owned resources.
     *
     * <p>Close should be idempotent. After close, operations should reject with
     * a stable unavailable/closed error. Implementations must not hide append or
     * recovery failures by silently dropping records.</p>
     */
    @Override
    void close();
}
