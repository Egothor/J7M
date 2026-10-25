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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.messaging.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class MessagingEventValidationHardeningTest {
    @TempDir
    Path directory;

    @Test
    public void unknownEventVersionIsRejected() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MessagingTestSupport.appendRawEvent(this.directory, clock, "version=" + encoded("99") + "\ntype=" + encoded("MESSAGE_ENQUEUED") + "\n");
        MessagingException exception = assertRecoveryFailed(clock);
        assertRecoveryDiagnostic(exception, "decode", "MESSAGE_ENQUEUED", "99");
    }

    @Test
    public void unknownEventTypeIsRejected() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MessagingTestSupport.appendRawEvent(this.directory, clock, "version=" + encoded("1") + "\ntype=" + encoded("QUEUE_CREATED") + "\n");
        MessagingException exception = assertRecoveryFailed(clock);
        assertRecoveryDiagnostic(exception, "decode", "QUEUE_CREATED", "1");
    }

    @Test
    public void missingQueueFieldIsRejected() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MessagingTestSupport.appendRawEvent(this.directory, clock,
                line("version", "1")
                        + line("type", "MESSAGE_ENQUEUED")
                        + line("messageId", "msg-1")
                        + line("partition", "0")
                        + line("sequence", "1")
                        + line("priority", "0")
                        + line("notBefore", "0")
                        + line("headers", QueueEventCodec.encodeStringMap(Map.of()))
                        + line("payload", Base64.getEncoder().encodeToString("PAYLOAD-SECRET".getBytes(StandardCharsets.UTF_8))));
        MessagingException exception = assertRecoveryFailed(clock);
        assertFalse(exception.getMessage().contains("PAYLOAD-SECRET"));
        assertRecoveryDiagnostic(exception, "decode", "MESSAGE_ENQUEUED", "1");
        assertFalse(exception.recoveryReport().errors().toString().contains("PAYLOAD-SECRET"));
        assertFalse(exception.remoteError().details().toString().contains("PAYLOAD-SECRET"));
    }

    @Test
    public void missingMessageIdFieldIsRejected() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MessagingTestSupport.appendRawEvent(this.directory, clock,
                line("version", "1")
                        + line("type", "MESSAGE_ACKED")
                        + line("queue", MessagingTestSupport.queue().value()));
        MessagingException exception = assertRecoveryFailed(clock);
        assertRecoveryDiagnostic(exception, "decode", "MESSAGE_ACKED", "1");
    }

    @Test
    public void missingInboxDedupeFieldIsRejected() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MessagingTestSupport.appendRawEvent(this.directory, clock,
                line("version", "1")
                        + line("type", "INBOX_RECORDED")
                        + line("recordedAt", "0"));
        MessagingException exception = assertRecoveryFailed(clock);
        assertRecoveryDiagnostic(exception, "decode", "INBOX_RECORDED", "1");
    }

    private MessagingException assertRecoveryFailed(final MessagingTestSupport.MutableClock clock) {
        MessagingException exception = assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
        MessagingTestSupport.assertCode(MiddlewareErrorCode.RECOVERY_FAILED, exception);
        return exception;
    }

    private static void assertRecoveryDiagnostic(
            final MessagingException exception,
            final String stage,
            final String eventType,
            final String eventVersion) {
        assertNotNull(exception.recoveryReport());
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED.name(), exception.remoteError().details().get("errorCode"));
        assertEquals(stage, exception.remoteError().details().get("recoveryStage"));
        assertEquals(eventType, exception.remoteError().details().get("eventType"));
        assertEquals(eventVersion, exception.remoteError().details().get("eventVersion"));
        assertTrue(exception.recoveryReport().errors().stream()
                .anyMatch(error -> error.contains("stage=" + stage)
                        && error.contains("eventType=" + eventType)
                        && error.contains("eventVersion=" + eventVersion)
                        && error.contains("errorCode=RECOVERY_FAILED")));
    }

    private static String encoded(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String line(final String key, final String value) {
        return key + "=" + encoded(value) + "\n";
    }
}
