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

import java.nio.file.Path;
import java.time.Clock;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.GroupCommitPolicy;
import org.j7m.middleware.api.durablelog.LogIntegrityPolicy;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.durablelog.RetentionPolicy;

/**
 * Builder for {@link FileDurableLog}.
 *
 * <p>The builder opens the file-backed durable log and performs production
 * recovery before returning it. The default recovery policy is fail-fast: an
 * incomplete or corrupt tail rejects the open instead of modifying storage.
 * Callers that are intentionally performing crash-tail repair must opt in with
 * {@link #truncateTailRecovery()} or {@link #recoveryOptions(RecoveryOptions)}.
 * Instances are mutable and not thread-safe.</p>
 */
public final class DurableLogBuilder {
    private Path directory;
    private long maxSegmentBytes = 16L * 1024L * 1024L;
    private long maxPayloadBytes = 1024L * 1024L;
    private int maxRecordsPerAppend = 1024;
    private int maxOpenCursors = 128;
    private FsyncPolicy fsyncPolicy = FsyncPolicy.PER_APPEND;
    private GroupCommitPolicy groupCommitPolicy = GroupCommitPolicy.defaults();
    private LogIntegrityPolicy integrityPolicy = LogIntegrityPolicy.CHECKSUM_ONLY;
    private RetentionPolicy retentionPolicy = RetentionPolicy.disabled();
    private Clock clock = Clock.systemUTC();
    private RecoveryOptions recoveryOptions = RecoveryOptions.defaults();

    /**
     * Creates a builder with default settings.
     */
    public DurableLogBuilder() {
    }

    /**
     * Selects the storage directory opened and recovered by {@link #build()}.
     * The directory is required; this setter does not open files or validate the path.
     *
     * @param value required log directory; a null value is rejected when building
     * @return this builder
     */
    public DurableLogBuilder directory(final Path value) {
        this.directory = value;
        return this;
    }

    /**
     * Sets the segment rotation threshold in bytes; the default is 16 MiB.
     * Values below 4096 bytes are rejected by {@link #build()}.
     *
     * @param value maximum segment size
     * @return this builder
     */
    public DurableLogBuilder maxSegmentBytes(final long value) {
        this.maxSegmentBytes = value;
        return this;
    }

    /**
     * Sets the per-record payload limit in bytes; the default is 1 MiB.
     * Zero permits only empty payloads; negative values fail at {@link #build()}.
     *
     * @param value maximum payload size
     * @return this builder
     */
    public DurableLogBuilder maxPayloadBytes(final long value) {
        this.maxPayloadBytes = value;
        return this;
    }

    /**
     * Sets the maximum number of records accepted by one append; the default is 1024.
     * The value must be positive and is validated by {@link #build()}.
     *
     * @param value maximum records per append call
     * @return this builder
     */
    public DurableLogBuilder maxRecordsPerAppend(final int value) {
        this.maxRecordsPerAppend = value;
        return this;
    }

    /**
     * Sets the maximum simultaneously open read cursors; the default is 128.
     * The value must be positive and is validated by {@link #build()}.
     *
     * @param value maximum concurrently open cursors
     * @return this builder
     */
    public DurableLogBuilder maxOpenCursors(final int value) {
        this.maxOpenCursors = value;
        return this;
    }

    /**
     * Selects when accepted writes are synchronized to storage.
     * The default, also used for a null value at build time, is {@code PER_APPEND}.
     *
     * @param value fsync policy
     * @return this builder
     */
    public DurableLogBuilder fsyncPolicy(final FsyncPolicy value) {
        this.fsyncPolicy = value;
        return this;
    }

    /**
     * Configures finite batching for group synchronization.
     * A null value selects {@link GroupCommitPolicy#defaults()} at build time.
     *
     * @param value group commit policy
     * @return this builder
     */
    public DurableLogBuilder groupCommitPolicy(final GroupCommitPolicy value) {
        this.groupCommitPolicy = value;
        return this;
    }

    /**
     * Selects frame-integrity verification for the opened log.
     * A null value selects {@code CHECKSUM_ONLY} at build time.
     *
     * @param value integrity policy
     * @return this builder
     */
    public DurableLogBuilder integrityPolicy(final LogIntegrityPolicy value) {
        this.integrityPolicy = value;
        return this;
    }

    /**
     * Selects segment retention for the opened log.
     * Retention is disabled by default and when a null value is built.
     *
     * @param value retention policy
     * @return this builder
     */
    public DurableLogBuilder retentionPolicy(final RetentionPolicy value) {
        this.retentionPolicy = value;
        return this;
    }

    /**
     * Supplies the timestamp source borrowed by the opened log.
     * A null value selects {@link Clock#systemUTC()} at build time.
     *
     * @param value timestamp source
     * @return this builder
     */
    public DurableLogBuilder clock(final Clock value) {
        this.clock = value;
        return this;
    }

    /**
     * Sets recovery options used during {@link #build()}.
     *
     * <p>Passing {@code null} restores the fail-fast default. Tail truncation is
     * a destructive repair action and should be selected only for crash-repair
     * paths that have already accepted dropping the incomplete final frame.</p>
     *
     * @param value recovery options for the open path
     * @return this builder
     */
    public DurableLogBuilder recoveryOptions(final RecoveryOptions value) {
        this.recoveryOptions = value == null ? RecoveryOptions.defaults() : value;
        return this;
    }

    /**
     * Selects explicit crash-tail truncation recovery for {@link #build()}.
     *
     * @return this builder
     */
    public DurableLogBuilder truncateTailRecovery() {
        this.recoveryOptions = RecoveryOptions.truncateTail();
        return this;
    }

    /**
     * Opens the configured directory and recovers its durable state before returning ownership.
     *
     * <p>Configuration is validated at this point, not by the fluent setters. Default recovery
     * fails on an incomplete or corrupt tail; explicit tail truncation can discard an incomplete
     * final frame. On recovery failure the opened log is closed, and any close failure is attached
     * as a suppressed exception. The caller must close a successfully returned log.</p>
     *
     * @return opened and recovered durable log owned by the caller
     * @throws IllegalArgumentException if the directory or configured size/count bounds are invalid
     * @throws RuntimeException if opening storage or recovering the selected journal fails
     */
    public FileDurableLog build() {
        DurableLogConfig config = new DurableLogConfig(this.directory, this.maxSegmentBytes, this.maxPayloadBytes,
                this.maxRecordsPerAppend, this.maxOpenCursors, this.fsyncPolicy, this.groupCommitPolicy,
                this.integrityPolicy, this.retentionPolicy, this.clock);
        FileDurableLog log = new FileDurableLog(config);
        try {
            log.recover(this.recoveryOptions);
            return log;
        } catch (RuntimeException exception) {
            try {
                log.close();
            } catch (RuntimeException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }
}
