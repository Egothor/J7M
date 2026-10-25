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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogCursorCloseConcurrencyTest {
    @TempDir
    Path directory;

    @Test
    public void closeDoesNotDeadlockWithConcurrentStreamingCursorRead() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .maxSegmentBytes(4096L)
                .fsyncPolicy(FsyncPolicy.PER_APPEND)
                .build()) {
            for (int index = 0; index < 32; index++) {
                log.append(DurableLogTestSupport.stream("cursor-close"),
                        List.of(DurableLogTestSupport.record("payload-" + index + "-" + "x".repeat(3000))),
                        AppendOptions.defaults());
            }
            LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("cursor-close"), LogOffset.beginning(), CursorOptions.defaults());
            Future<?> reader = executor.submit(() -> {
                start.await();
                while (cursor.next().isPresent()) {
                    // Continue until end-of-log or log close invalidates the cursor.
                }
                return null;
            });
            Future<?> closer = executor.submit(() -> {
                start.await();
                log.close();
                return null;
            });

            start.countDown();
            awaitCursorReader(reader);
            closer.get(5L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    @Test
    public void logCloseInvalidatesActiveCursorAndCursorCloseRemainsIdempotent() {
        FileDurableLog log = new DurableLogBuilder().directory(this.directory)
                .maxOpenCursors(1)
                .fsyncPolicy(FsyncPolicy.PER_APPEND)
                .build();
        log.append(DurableLogTestSupport.stream("cursor-close-idempotent"),
                List.of(DurableLogTestSupport.record("payload")), AppendOptions.defaults());
        LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("cursor-close-idempotent"), LogOffset.beginning(), CursorOptions.defaults());

        log.close();
        cursor.close();

        assertTrue(cursor.next().isEmpty());
    }

    private static void awaitCursorReader(final Future<?> reader) throws Exception {
        try {
            reader.get(5L, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof DurableLogException) {
                return;
            }
            throw exception;
        } catch (TimeoutException exception) {
            throw new AssertionError("cursor read did not complete", exception);
        }
    }
}
