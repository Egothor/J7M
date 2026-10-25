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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.messaging.OutboxStatus;
import org.j7m.middleware.durablelog.DurableLogBuilder;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueRetentionBoundTest {
    private static final LogStreamId EVENT_STREAM = new LogStreamId("j7m.messaging.events");
    private static final int RETAINED_COMPLETED_OUTBOX = 4096;

    @TempDir
    Path directory;

    @Test
    public void completedOutboxRetentionIsBoundedBeforeAndAfterRestart() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        appendCompletedOutboxEvents(this.directory, clock, RETAINED_COMPLETED_OUTBOX + 9);

        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            OutboxStatus status = queue.outboxStatus();
            assertEquals(RETAINED_COMPLETED_OUTBOX, status.published());
            assertEquals(0L, status.pending());
        }

        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            OutboxStatus status = recovered.outboxStatus();
            assertEquals(RETAINED_COMPLETED_OUTBOX, status.published());
            assertEquals(0L, status.pending());
        }
    }

    private static void appendCompletedOutboxEvents(final Path directory, final MessagingTestSupport.MutableClock clock, final int count) {
        List<LogRecord> records = new ArrayList<>(512);
        try (FileDurableLog log = new DurableLogBuilder().directory(directory).clock(clock).build()) {
            for (int index = 0; index < count; index++) {
                records.add(record(recordedEvent(index)));
                records.add(record(publishedEvent(index)));
                if ((index + 1) % 100 == 0) {
                    records.add(record(checkpointEvent(index + 1)));
                }
                if (records.size() >= 512) {
                    log.append(EVENT_STREAM, records, AppendOptions.defaults());
                    records.clear();
                }
            }
            if (!records.isEmpty()) {
                log.append(EVENT_STREAM, records, AppendOptions.defaults());
            }
        }
    }

    private static LogRecord record(final QueueEvent event) {
        return new LogRecord(LogRecordHeader.data(Map.of("j7m-messaging-event", event.type())), QueueEventCodec.encode(event));
    }

    private static QueueEvent recordedEvent(final int index) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("entryId", "outbox-" + index);
        fields.put("queue", MessagingTestSupport.queue().value());
        fields.put("payload", Base64.getEncoder().encodeToString(("payload-" + index).getBytes(StandardCharsets.UTF_8)));
        fields.put("headers", QueueEventCodec.encodeStringMap(Map.of("kind", "retention")));
        fields.put("priority", "0");
        fields.put("partition", "");
        fields.put("orderingKey", "");
        fields.put("idempotencyKey", "");
        fields.put("dedupeKey", "");
        fields.put("notBefore", "0");
        fields.put("createdAt", "0");
        return new QueueEvent("OUTBOX_RECORDED", fields);
    }

    private static QueueEvent publishedEvent(final int index) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("entryId", "outbox-" + index);
        return new QueueEvent("OUTBOX_PUBLISHED", fields);
    }

    private static QueueEvent checkpointEvent(final int completedCount) {
        String body = checkpointBody(completedCount);
        CRC32 crc32 = new CRC32();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        crc32.update(bodyBytes, 0, bodyBytes.length);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("checkpointVersion", "1");
        fields.put("eventCount", Integer.toString(completedCount * 2));
        fields.put("checksum", Long.toUnsignedString(crc32.getValue()));
        fields.put("body", body);
        return new QueueEvent("CHECKPOINT", fields);
    }

    private static String checkpointBody(final int completedCount) {
        StringBuilder builder = new StringBuilder(1024);
        int firstRetained = Math.max(0, completedCount - RETAINED_COMPLETED_OUTBOX);
        for (int index = firstRetained; index < completedCount; index++) {
            appendCheckpointLine(builder, "O", "outbox-" + index, MessagingTestSupport.queue().value(),
                    Base64.getEncoder().encodeToString(("payload-" + index).getBytes(StandardCharsets.UTF_8)),
                    QueueEventCodec.encodeStringMap(Map.of("kind", "retention")), "0", "", "", "", "", "0", "0", "0", "true", "false");
        }
        return builder.toString();
    }

    private static void appendCheckpointLine(final StringBuilder builder, final String kind, final String... values) {
        builder.append(kind);
        for (String value : values) {
            builder.append('|').append(Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        builder.append('\n');
    }
}
