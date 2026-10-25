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

import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.durablelog.LogMetricsSnapshot;

/**
 * Durable-log metrics counters.
 */
final class DurableLogMetrics {
    private final AtomicLong appendCount = new AtomicLong();
    private final AtomicLong readCount = new AtomicLong();
    private final AtomicLong fsyncCount = new AtomicLong();
    private final AtomicLong recoveryCount = new AtomicLong();
    private final AtomicLong corruptionCount = new AtomicLong();
    private final AtomicLong truncatedRecordCount = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong bytesRead = new AtomicLong();
    private final AtomicLong lastAppendTimestampMillis = new AtomicLong();
    private final AtomicLong lastRecoveryTimestampMillis = new AtomicLong();

    /**
     * Creates empty metrics counters.
     */
    public DurableLogMetrics() {
    }

    void recordAppend(final long records, final long bytes, final long timestampMillis) {
        this.appendCount.addAndGet(records);
        this.bytesWritten.addAndGet(bytes);
        this.lastAppendTimestampMillis.set(timestampMillis);
    }

    void recordRead(final long bytes) {
        this.readCount.incrementAndGet();
        this.bytesRead.addAndGet(bytes);
    }

    void recordFsync() {
        this.fsyncCount.incrementAndGet();
    }

    void recordRecovery(final long timestampMillis) {
        this.recoveryCount.incrementAndGet();
        this.lastRecoveryTimestampMillis.set(timestampMillis);
    }

    void addCorruption(final long count) {
        this.corruptionCount.addAndGet(count);
    }

    void addTruncated(final long count) {
        this.truncatedRecordCount.addAndGet(count);
    }

    LogMetricsSnapshot snapshot() {
        return new LogMetricsSnapshot(this.appendCount.get(), this.readCount.get(), this.fsyncCount.get(),
                this.recoveryCount.get(), this.corruptionCount.get(), this.truncatedRecordCount.get(),
                this.bytesWritten.get(), this.bytesRead.get(), this.lastAppendTimestampMillis.get(),
                this.lastRecoveryTimestampMillis.get());
    }
}
