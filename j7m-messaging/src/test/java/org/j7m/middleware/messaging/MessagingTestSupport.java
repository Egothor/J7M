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

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.QueueName;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.j7m.middleware.durablelog.DurableLogBuilder;
import org.j7m.middleware.durablelog.FileDurableLog;

final class MessagingTestSupport {
    private MessagingTestSupport() {
    }

    static QueueName queue() {
        return new QueueName("orders");
    }

    static QueueMessage message(final String value) {
        return new QueueMessage(new MessageHeader(Map.of("kind", "test")), value.getBytes(StandardCharsets.UTF_8));
    }

    static String payload(final QueueMessage message) {
        return new String(message.payload(), StandardCharsets.UTF_8);
    }

    static LogBackedDurableQueue open(final Path directory, final MutableClock clock) {
        return new DurableQueueBuilder().directory(directory)
                .clock(clock)
                .queueOptions(new QueueOptions(4, 100, 8192, 10, 10, 3, Duration.ofSeconds(10L)))
                .build();
    }

    static void assertPayload(final QueueMessage message, final String expected) {
        assertEquals(expected, payload(message));
    }

    static void assertCode(final MiddlewareErrorCode expected, final MessagingException exception) {
        assertEquals(expected, exception.code());
    }

    static void appendEvent(final Path directory, final MutableClock clock, final QueueEvent event) {
        try (FileDurableLog log = new DurableLogBuilder().directory(directory).clock(clock).build()) {
            log.append(new LogStreamId("j7m.messaging.events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of("j7m-messaging-event", event.type())),
                    QueueEventCodec.encode(event))), AppendOptions.defaults());
        }
    }

    static void appendRawEvent(final Path directory, final MutableClock clock, final String payload) {
        try (FileDurableLog log = new DurableLogBuilder().directory(directory).clock(clock).build()) {
            log.append(new LogStreamId("j7m.messaging.events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of("j7m-messaging-event", "raw")),
                    payload.getBytes(StandardCharsets.UTF_8))), AppendOptions.defaults());
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(final Instant instant) {
            this.instant = instant;
        }

        void advance(final Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        void set(final Instant value) {
            this.instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }
}
