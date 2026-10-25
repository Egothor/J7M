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

package org.j7m.middleware.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.durablelog.DurableLogBuilder;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueCorruptionPropagationTest {
    @TempDir
    Path directory;

    @Test
    public void durableLogCorruptionIsSurfacedSafely() throws Exception {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("bad"), EnqueueOptions.defaults());
        }
        Path segment;
        try (java.util.stream.Stream<Path> stream = Files.list(this.directory)) {
            segment = stream.filter(path -> path.getFileName().toString().endsWith(".j7mlog")).findFirst().orElseThrow();
        }
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, 80L);
            one.flip();
            byte value = one.hasRemaining() ? one.get() : 0;
            one.clear();
            one.put((byte) (value ^ 0x11));
            one.flip();
            channel.write(one, 80L);
        }
        FileDurableLog rawLog = new FileDurableLog(DurableLogConfig.defaults(this.directory));
        MessagingException exception = assertThrows(MessagingException.class,
                () -> new LogBackedDurableQueue(new DurableQueueConfig(rawLog, null, null, null, clock)));
        MessagingTestSupport.assertCode(MiddlewareErrorCode.LOG_CORRUPTION, exception);
        rawLog.close();
    }

    @Test
    public void queueRecoveryFailureClosesQueueAgainstFurtherMutation() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).clock(clock).build()) {
            LogBackedDurableQueue queue = new DurableQueueBuilder().durableLog(log).clock(clock).build();
            log.append(new LogStreamId("j7m.messaging.events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of("j7m-messaging-event", "raw")), "not-an-event".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    AppendOptions.defaults());

            assertThrows(MessagingException.class, queue::recover);
            assertThrows(MessagingException.class,
                    () -> queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("after-failure"), EnqueueOptions.defaults()));
        }
    }
}
