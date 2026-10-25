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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.messaging.AckOptions;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.NackOptions;
import org.j7m.middleware.api.messaging.VisibilityTimeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueVisibilityTimeoutTest {
    @TempDir
    Path directory;

    @Test
    public void leasedMessageIsInvisibleThenRedeliveredAfterTimeoutAndStaleAckFails() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("lease"), EnqueueOptions.defaults());
            LeaseBatch first = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"),
                    new LeaseOptions(1, new VisibilityTimeout(Duration.ofSeconds(5L)), null));
            assertEquals(1, first.leases().size());
            assertTrue(queue.lease(MessagingTestSupport.queue(), new ConsumerId("c2"), LeaseOptions.defaults()).isEmpty());

            clock.advance(Duration.ofSeconds(6L));
            LeaseBatch second = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c2"), LeaseOptions.defaults());
            assertEquals(1, second.leases().size());
            MessagingException staleAck = assertThrows(MessagingException.class,
                    () -> queue.ack(MessagingTestSupport.queue(), first.leases().getFirst().envelope().messageId(), new AckOptions(first.leases().getFirst().leaseId())));
            MessagingTestSupport.assertCode(MiddlewareErrorCode.STALE_FENCING_TOKEN, staleAck);
            MessagingException staleNack = assertThrows(MessagingException.class,
                    () -> queue.nack(MessagingTestSupport.queue(), first.leases().getFirst().envelope().messageId(),
                            new NackOptions(first.leases().getFirst().leaseId(), Instant.EPOCH, "safe")));
            MessagingTestSupport.assertCode(MiddlewareErrorCode.STALE_FENCING_TOKEN, staleNack);
        }
    }
}
