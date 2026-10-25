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
import org.j7m.middleware.api.durablelog.RetentionPolicy;

/**
 * Immutable file durable-log configuration.
 *
 * @param directory log directory owned by the durable log
 * @param maxSegmentBytes maximum segment file size before rotation
 * @param maxPayloadBytes maximum payload size accepted for one record
 * @param maxRecordsPerAppend maximum records accepted in one append call
 * @param maxOpenCursors maximum concurrently open cursors
 * @param fsyncPolicy durability sync policy
 * @param groupCommitPolicy group commit batching policy
 * @param integrityPolicy frame integrity policy
 * @param retentionPolicy segment retention policy
 * @param clock timestamp source
 */
public record DurableLogConfig(
        Path directory,
        long maxSegmentBytes,
        long maxPayloadBytes,
        int maxRecordsPerAppend,
        int maxOpenCursors,
        FsyncPolicy fsyncPolicy,
        GroupCommitPolicy groupCommitPolicy,
        LogIntegrityPolicy integrityPolicy,
        RetentionPolicy retentionPolicy,
        Clock clock) {
    /**
     * Creates validated configuration.
     */
    public DurableLogConfig {
        if (directory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        if (maxSegmentBytes < SegmentFileFormat.MIN_SEGMENT_BYTES) {
            throw new IllegalArgumentException("maxSegmentBytes is too small");
        }
        if (maxPayloadBytes < 0L || maxRecordsPerAppend < 1 || maxOpenCursors < 1) {
            throw new IllegalArgumentException("durable log limits are invalid");
        }
        fsyncPolicy = fsyncPolicy == null ? FsyncPolicy.PER_APPEND : fsyncPolicy;
        groupCommitPolicy = groupCommitPolicy == null ? GroupCommitPolicy.defaults() : groupCommitPolicy;
        integrityPolicy = integrityPolicy == null ? LogIntegrityPolicy.CHECKSUM_ONLY : integrityPolicy;
        retentionPolicy = retentionPolicy == null ? RetentionPolicy.disabled() : retentionPolicy;
        clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Creates default configuration for a directory.
     *
     * @param directory log directory
     * @return default durable-log configuration
     */
    public static DurableLogConfig defaults(final Path directory) {
        return new DurableLogConfig(directory, 16L * 1024L * 1024L, 1024L * 1024L, 1024, 128,
                FsyncPolicy.PER_APPEND, GroupCommitPolicy.defaults(), LogIntegrityPolicy.CHECKSUM_ONLY,
                RetentionPolicy.disabled(), Clock.systemUTC());
    }
}
