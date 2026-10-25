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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.MessagePriority;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.VisibilityTimeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueHeaderRecoveryTest {
    @TempDir
    Path directory;

    @Test
    public void messageHeaderSurvivesAvailableRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), messageWithHeader("available"), EnqueueOptions.defaults());
        }
        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            LeaseBatch batch = recovered.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());
            assertEquals("available", batch.leases().getFirst().envelope().message().header().metadata().get("trace"));
        }
    }

    @Test
    public void messageHeaderSurvivesDelayedRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        Instant due = clock.instant().plus(Duration.ofMinutes(5L));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), messageWithHeader("delayed"),
                    new EnqueueOptions(MessagePriority.NORMAL, null, null, null, null, due));
        }
        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            assertTrue(recovered.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults()).isEmpty());
            clock.advance(Duration.ofMinutes(6L));
            LeaseBatch batch = recovered.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());
            assertEquals("delayed", batch.leases().getFirst().envelope().message().header().metadata().get("trace"));
        }
    }

    @Test
    public void messageHeaderSurvivesLeaseTimeoutRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), messageWithHeader("timeout"), EnqueueOptions.defaults());
            LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"),
                    new LeaseOptions(1, new VisibilityTimeout(Duration.ofSeconds(1L)), null));
            assertFalse(batch.isEmpty());
        }
        clock.advance(Duration.ofSeconds(2L));
        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            LeaseBatch batch = recovered.lease(MessagingTestSupport.queue(), new ConsumerId("c2"), LeaseOptions.defaults());
            assertEquals("timeout", batch.leases().getFirst().envelope().message().header().metadata().get("trace"));
        }
    }

    @Test
    public void malformedHeaderEventFailsRecoveryWithoutPayloadDisclosure() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        Map<String, String> fields = Map.of(
                "queue", MessagingTestSupport.queue().value(),
                "messageId", "msg-1",
                "partition", "0",
                "sequence", "1",
                "priority", "0",
                "notBefore", "0",
                "headers", "not-a-valid-header-map",
                "payload", java.util.Base64.getEncoder().encodeToString("SECRET-PAYLOAD".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "idempotencyKey", "",
                "dedupeKey", "");
        MessagingTestSupport.appendEvent(this.directory, clock, new QueueEvent("MESSAGE_ENQUEUED", fields));

        MessagingException exception = assertThrows(MessagingException.class, () -> MessagingTestSupport.open(this.directory, clock));
        MessagingTestSupport.assertCode(MiddlewareErrorCode.RECOVERY_FAILED, exception);
        assertFalse(exception.getMessage().contains("SECRET-PAYLOAD"));
    }

    private static QueueMessage messageWithHeader(final String trace) {
        return new QueueMessage(new MessageHeader(Map.of("trace", trace, "kind", "hardening")), trace.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
