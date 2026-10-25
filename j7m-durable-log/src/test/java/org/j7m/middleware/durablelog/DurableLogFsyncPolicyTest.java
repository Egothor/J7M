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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.GroupCommitPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogFsyncPolicyTest {
    @TempDir
    Path directory;

    @Test
    public void perAppendForcesSyncPath() {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("sync"), List.of(DurableLogTestSupport.record("a")), AppendOptions.defaults());
            assertEquals(1L, log.status().metrics().fsyncCount());
        }
    }

    @Test
    public void onCloseSyncsOnClose() {
        FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.ON_CLOSE);
        log.append(DurableLogTestSupport.stream("sync"), List.of(DurableLogTestSupport.record("a")), AppendOptions.defaults());
        assertEquals(0L, log.status().metrics().fsyncCount());
        log.close();
        assertEquals(1L, log.status().metrics().fsyncCount());
    }

    @Test
    public void groupCommitBatchesSyncCalls() {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .fsyncPolicy(FsyncPolicy.GROUP_COMMIT)
                .groupCommitPolicy(new GroupCommitPolicy(2, Duration.ZERO))
                .build()) {
            log.append(DurableLogTestSupport.stream("sync"), List.of(DurableLogTestSupport.record("a")), AppendOptions.defaults());
            assertEquals(0L, log.status().metrics().fsyncCount());
            log.append(DurableLogTestSupport.stream("sync"), List.of(DurableLogTestSupport.record("b")), AppendOptions.defaults());
            assertEquals(1L, log.status().metrics().fsyncCount());
        }
    }

    @Test
    public void groupCommitMaxDelayForcesPendingAppend() {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .fsyncPolicy(FsyncPolicy.GROUP_COMMIT)
                .groupCommitPolicy(new GroupCommitPolicy(100, Duration.ofMillis(25L)))
                .build()) {
            log.append(DurableLogTestSupport.stream("sync"), List.of(DurableLogTestSupport.record("delay")), AppendOptions.defaults());
            assertEquals(0L, log.status().metrics().fsyncCount());
            awaitFsyncCount(log, 1L, Duration.ofSeconds(3L));
        }
    }

    @Test
    public void groupCommitCloseFlushesPendingAppendAndStopsScheduler() {
        FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .fsyncPolicy(FsyncPolicy.GROUP_COMMIT)
                .groupCommitPolicy(new GroupCommitPolicy(100, Duration.ofSeconds(30L)))
                .build();
        log.append(DurableLogTestSupport.stream("sync"), List.of(DurableLogTestSupport.record("close")), AppendOptions.defaults());
        assertEquals(0L, log.status().metrics().fsyncCount());
        assertFalse(log.groupCommitSchedulerShutdownForTests());

        log.close();

        assertEquals(1L, log.status().metrics().fsyncCount());
        assertTrue(log.groupCommitSchedulerShutdownForTests());
    }

    @Test
    public void osBufferedPolicyIsMarkedDevelopmentUnsafeByName() {
        assertTrue(FsyncPolicy.OS_BUFFERED.name().contains("OS_BUFFERED"));
    }

    private static void awaitFsyncCount(final FileDurableLog log, final long expected, final Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (log.status().metrics().fsyncCount() >= expected) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(1L).toNanos());
        }
        assertEquals(expected, log.status().metrics().fsyncCount());
    }
}
