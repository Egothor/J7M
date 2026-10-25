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

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Durable-log status snapshot.
 *
 * @param logDirectory redacted log directory summary
 * @param open whether the log is open
 * @param streamCount number of streams
 * @param segmentCount number of segment files
 * @param activeSegment redacted active segment summary
 * @param currentOffsets current offset by stream
 * @param metrics metrics snapshot
 * @param fsyncPolicy configured fsync policy
 * @param retentionPolicy configured retention policy
 * @param lastError redacted last error
 */
public record LogStatus(
        String logDirectory,
        boolean open,
        int streamCount,
        int segmentCount,
        String activeSegment,
        Map<LogStreamId, LogOffset> currentOffsets,
        LogMetricsSnapshot metrics,
        FsyncPolicy fsyncPolicy,
        RetentionPolicy retentionPolicy,
        String lastError) implements Serializable {
    /**
     * Creates status.
     */
    public LogStatus {
        logDirectory = logDirectory == null ? "" : logDirectory;
        activeSegment = activeSegment == null ? "" : activeSegment;
        currentOffsets = currentOffsets == null ? Map.of() : Map.copyOf(currentOffsets);
        metrics = metrics == null ? LogMetricsSnapshot.empty() : metrics;
        fsyncPolicy = fsyncPolicy == null ? FsyncPolicy.OS_BUFFERED : fsyncPolicy;
        retentionPolicy = retentionPolicy == null ? RetentionPolicy.disabled() : retentionPolicy;
        lastError = lastError == null ? "" : lastError;
    }

    /**
     * Returns stream statuses.
     *
     * @return immutable stream status list
     */
    public List<LogStreamStatus> streams() {
        return this.currentOffsets.entrySet().stream()
                .map(entry -> new LogStreamStatus(entry.getKey(), entry.getValue()))
                .toList();
    }
}
