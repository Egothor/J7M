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

import java.nio.file.Path;
import java.time.Instant;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.NackOptions;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableQueueNackRetryDeadLetterTest {
    @TempDir
    Path directory;

    @Test
    public void nackRequeuesAndMaxRetriesDeadLettersDurably() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = new DurableQueueBuilder().directory(this.directory).clock(clock)
                .queueOptions(new QueueOptions(1, 100, 8192, 10, 10, 2, java.time.Duration.ofSeconds(10L))).build()) {
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("retry"), EnqueueOptions.defaults());
            LeaseBatch first = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());
            queue.nack(MessagingTestSupport.queue(), first.leases().getFirst().envelope().messageId(), new NackOptions(first.leases().getFirst().leaseId(), Instant.EPOCH, "safe"));
            assertEquals(1L, queue.status(MessagingTestSupport.queue()).available());
            LeaseBatch second = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());
            queue.nack(MessagingTestSupport.queue(), second.leases().getFirst().envelope().messageId(), new NackOptions(second.leases().getFirst().leaseId(), Instant.EPOCH, "safe"));
            assertEquals(1L, queue.status(MessagingTestSupport.queue()).deadLettered());
        }
        try (LogBackedDurableQueue recovered = MessagingTestSupport.open(this.directory, clock)) {
            assertEquals(1L, recovered.status(MessagingTestSupport.queue()).deadLettered());
        }
    }

    @Test
    public void nackWithoutCurrentLeaseTokenFailsSafely() {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("retry"), EnqueueOptions.defaults());
            LeaseBatch first = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());

            assertThrows(MessagingException.class,
                    () -> queue.nack(MessagingTestSupport.queue(), first.leases().getFirst().envelope().messageId(), NackOptions.defaults()));
        }
    }
}
