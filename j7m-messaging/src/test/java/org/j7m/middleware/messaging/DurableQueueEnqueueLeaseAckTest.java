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
import java.time.Instant;
import org.j7m.middleware.api.messaging.AckOptions;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessageId;
import org.j7m.middleware.api.messaging.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueEnqueueLeaseAckTest {
    @TempDir
    Path directory;

    @Test
    public void enqueueLeaseAndAckRemovesMessageAndSurvivesRecovery() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MessageId id;
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            id = queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("one"), EnqueueOptions.defaults()).messageId();
            LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new org.j7m.middleware.api.messaging.ConsumerId("c1"), LeaseOptions.defaults());
            assertEquals(1, batch.leases().size());
            MessagingTestSupport.assertPayload(batch.leases().getFirst().envelope().message(), "one");
            queue.ack(MessagingTestSupport.queue(), id, new AckOptions(batch.leases().getFirst().leaseId()));
            assertEquals(0L, queue.status(MessagingTestSupport.queue()).available());
        }

        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(0L, recovered.status(MessagingTestSupport.queue()).available());
            assertTrue(recovered.lease(MessagingTestSupport.queue(), new org.j7m.middleware.api.messaging.ConsumerId("c1"), LeaseOptions.defaults()).isEmpty());
        }
    }

    @Test
    public void ackWithoutCurrentLeaseTokenFailsSafely() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            MessageId id = queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("one"), EnqueueOptions.defaults()).messageId();
            LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new org.j7m.middleware.api.messaging.ConsumerId("c1"), LeaseOptions.defaults());
            assertEquals(1, batch.leases().size());

            assertThrows(MessagingException.class, () -> queue.ack(MessagingTestSupport.queue(), id, AckOptions.defaults()));
        }
    }
}
