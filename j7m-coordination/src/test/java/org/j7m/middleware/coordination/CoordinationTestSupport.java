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

package org.j7m.middleware.coordination;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.coordination.CoordinationException;
import org.j7m.middleware.api.coordination.HeartbeatOptions;
import org.j7m.middleware.api.coordination.LeaseOptions;
import org.j7m.middleware.api.coordination.MemberEndpoint;
import org.j7m.middleware.api.coordination.MemberIdentity;
import org.j7m.middleware.api.coordination.MemberId;
import org.j7m.middleware.api.coordination.MemberRole;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CoordinationTestSupport {
    static final LogStreamId EVENT_STREAM = new LogStreamId("j7m-coordination-events");

    private CoordinationTestSupport() {
    }

    static MutableCoordinationClock clock() {
        return new MutableCoordinationClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    static LogBackedCoordinationService open(final Path directory, final MutableCoordinationClock clock) {
        return new CoordinationServiceBuilder()
                .directory(directory)
                .clock(clock)
                .heartbeatOptions(new HeartbeatOptions(Duration.ofSeconds(2L), Duration.ofSeconds(5L)))
                .build();
    }

    static MemberIdentity member(final String id) {
        return new MemberIdentity(new MemberId(id), new MemberEndpoint("local://" + id), MemberRole.COORDINATOR, Map.of("zone", "test"));
    }

    static MemberIdentity member(final String id, final String endpoint) {
        return new MemberIdentity(new MemberId(id), new MemberEndpoint(endpoint), MemberRole.COORDINATOR, Map.of("zone", "test"));
    }

    static LeaseOptions leaseOptions() {
        return new LeaseOptions(Duration.ofSeconds(3L), false);
    }

    static void appendRawEvent(final Path directory, final String raw) {
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(directory))) {
            log.append(EVENT_STREAM, List.of(new LogRecord(LogRecordHeader.data(Map.of("j7m-coordination-event", "raw")),
                    raw.getBytes(StandardCharsets.UTF_8))), AppendOptions.defaults());
        }
    }

    static void appendEvent(final Path directory, final CoordinationEvent event) {
        appendEvents(directory, event);
    }

    static void appendEvents(final Path directory, final CoordinationEvent... events) {
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(directory))) {
            List<LogRecord> records = java.util.Arrays.stream(events)
                    .map(event -> new LogRecord(LogRecordHeader.data(Map.of("j7m-coordination-event", event.type())),
                            CoordinationEventCodec.encode(event)))
                    .toList();
            log.append(EVENT_STREAM, records, AppendOptions.defaults());
        }
    }

    static void assertCode(final MiddlewareErrorCode expected, final CoordinationException exception) {
        assertEquals(expected, exception.code());
        assertEquals(expected, exception.remoteError().code());
    }
}
