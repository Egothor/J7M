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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public final class QueueEventCodecLimitTest {
    @Test
    public void decodeRejectsBytesAboveTheWriteLimit() {
        assertThrows(IllegalArgumentException.class, () -> QueueEventCodec.decode(new byte[QueueEventCodec.maxEncodedEventBytes() + 1]));
    }

    @Test
    public void encodeRejectsEventsAboveTheReadLimit() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("checkpointVersion", "1");
        fields.put("eventCount", "1");
        fields.put("checksum", "0");
        fields.put("body", "x".repeat(QueueEventCodec.maxEncodedEventBytes()));

        assertThrows(IllegalArgumentException.class, () -> QueueEventCodec.encode(new QueueEvent("CHECKPOINT", fields)));
    }

    @Test
    public void encodeEmitsSchemaVersionTwo() {
        byte[] encoded = QueueEventCodec.encode(checkpointEvent(""));

        assertTrue(new String(encoded, StandardCharsets.UTF_8).contains("version=Mg"));
    }

    @Test
    public void versionTwoAckRequiresLeaseOwnershipFields() {
        String encoded = line("version", "2")
                + line("type", "MESSAGE_ACKED")
                + line("queue", "orders")
                + line("messageId", "msg-1");

        assertThrows(IllegalArgumentException.class, () -> QueueEventCodec.decode(encoded.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void legacyVersionOneAckIsRejectedAsUnsafeOwnerSchema() {
        String encoded = line("version", "1")
                + line("type", "MESSAGE_ACKED")
                + line("queue", "orders")
                + line("messageId", "msg-1");

        assertThrows(IllegalArgumentException.class, () -> QueueEventCodec.decode(encoded.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void countedSizeMatchesEncodedLengthForEveryEventType() {
        for (String type : QueueEventCodec.knownTypes()) {
            QueueEvent event = eventForType(type);
            assertEquals(QueueEventCodec.encode(event).length, QueueEventCodec.encodedSize(event));
        }
    }

    @Test
    public void boundedCountStopsAtLimitPlusOne() {
        QueueEvent event = checkpointEvent("x".repeat(4096));

        assertEquals(129L, QueueEventCodec.encodedSize(event, 128L));
    }

    private static QueueEvent checkpointEvent(final String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("checkpointVersion", "1");
        fields.put("eventCount", "1");
        fields.put("checksum", "0");
        fields.put("body", body);
        return new QueueEvent("CHECKPOINT", fields);
    }

    private static QueueEvent eventForType(final String type) {
        Map<String, String> fields = new LinkedHashMap<>();
        switch (type) {
            case "MESSAGE_ENQUEUED" -> {
                fields.put("queue", "orders");
                fields.put("messageId", "msg-1");
                fields.put("partition", "0");
                fields.put("sequence", "1");
                fields.put("priority", "0");
                fields.put("notBefore", "0");
                fields.put("headers", "");
                fields.put("payload", Base64.getEncoder().encodeToString("payload".getBytes(StandardCharsets.UTF_8)));
                fields.put("idempotencyKey", "");
                fields.put("dedupeKey", "");
            }
            case "MESSAGE_LEASED" -> {
                fields.put("queue", "orders");
                fields.put("messageId", "msg-1");
                fields.put("leaseId", "lease-1");
                fields.put("consumerId", "consumer");
                fields.put("leaseUntil", "1");
                fields.put("consumerGroup", "");
            }
            case "MESSAGE_ACKED" -> {
                fields.put("queue", "orders");
                fields.put("messageId", "msg-1");
                fields.put("leaseId", "lease-1");
                fields.put("consumerId", "consumer");
            }
            case "MESSAGE_NACKED", "MESSAGE_DEAD_LETTERED" -> {
                fields.put("queue", "orders");
                fields.put("messageId", "msg-1");
                fields.put("retryCount", "1");
                fields.put("notBefore", "0");
                fields.put("reason", "retry");
                fields.put("leaseId", "lease-1");
                fields.put("consumerId", "consumer");
            }
            case "OUTBOX_RECORDED" -> {
                fields.put("entryId", "msg-2");
                fields.put("queue", "orders");
                fields.put("payload", Base64.getEncoder().encodeToString("payload".getBytes(StandardCharsets.UTF_8)));
                fields.put("headers", "");
                fields.put("priority", "0");
                fields.put("partition", "");
                fields.put("orderingKey", "");
                fields.put("idempotencyKey", "");
                fields.put("dedupeKey", "");
                fields.put("notBefore", "0");
                fields.put("createdAt", "0");
            }
            case "OUTBOX_PUBLISHED" -> fields.put("entryId", "msg-2");
            case "OUTBOX_FAILED" -> {
                fields.put("entryId", "msg-2");
                fields.put("attempts", "1");
                fields.put("failed", "false");
            }
            case "INBOX_RECORDED" -> {
                fields.put("dedupeKey", "dedupe-1");
                fields.put("result", "ok");
                fields.put("recordedAt", "0");
            }
            case "INBOX_DUPLICATE_SUPPRESSED" -> {
                fields.put("dedupeKey", "dedupe-1");
                fields.put("decision", "SUPPRESSED");
            }
            case "CHECKPOINT" -> {
                fields.put("checkpointVersion", "1");
                fields.put("eventCount", "1");
                fields.put("checksum", "0");
                fields.put("body", "");
            }
            default -> throw new IllegalArgumentException("unknown test event type");
        }
        return new QueueEvent(type, fields);
    }

    private static String line(final String key, final String value) {
        return key + "=" + Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "\n";
    }
}
