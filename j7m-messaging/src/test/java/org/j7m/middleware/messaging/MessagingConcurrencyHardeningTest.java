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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.api.messaging.AckOptions;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.InboxDuplicateDecision;
import org.j7m.middleware.api.messaging.InboxEntry;
import org.j7m.middleware.api.messaging.InboxOptions;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessageId;
import org.j7m.middleware.api.messaging.MessagePriority;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.NackOptions;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class MessagingConcurrencyHardeningTest {
    @TempDir
    Path directory;

    @Test
    public void concurrentEnqueueAndLeaseDoNotLoseOrDuplicateMessages() throws Exception {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            runConcurrent(8, index -> queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("m-" + index), EnqueueOptions.defaults()));
            assertEquals(8L, queue.status(MessagingTestSupport.queue()).available());

            List<MessageId> leased = runConcurrent(8, index -> {
                LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c-" + index), LeaseOptions.defaults());
                return batch.isEmpty() ? null : batch.leases().getFirst().envelope().messageId();
            });
            Set<MessageId> unique = new HashSet<>();
            for (MessageId id : leased) {
                if (id != null) {
                    unique.add(id);
                }
            }
            assertEquals(8, unique.size());
        }
    }

    @Test
    public void concurrentAckAndNackOfSameLeaseHasSingleWinner() throws Exception {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.enqueue(MessagingTestSupport.queue(), MessagingTestSupport.message("one"), EnqueueOptions.defaults());
            LeaseBatch batch = queue.lease(MessagingTestSupport.queue(), new ConsumerId("c1"), LeaseOptions.defaults());
            MessageId id = batch.leases().getFirst().envelope().messageId();
            AckOptions ackOptions = new AckOptions(batch.leases().getFirst().leaseId());
            NackOptions nackOptions = new NackOptions(batch.leases().getFirst().leaseId(), Instant.EPOCH, "safe");

            List<Boolean> winners = runConcurrent(2, index -> {
                try {
                    if (index == 0) {
                        queue.ack(MessagingTestSupport.queue(), id, ackOptions);
                    } else {
                        queue.nack(MessagingTestSupport.queue(), id, nackOptions);
                    }
                    return Boolean.TRUE;
                } catch (MessagingException exception) {
                    return Boolean.FALSE;
                }
            });
            long successCount = winners.stream().filter(Boolean::booleanValue).count();
            assertEquals(1L, successCount);
        }
    }

    @Test
    public void concurrentOutboxRelayAndInboxDuplicateSuppressionAreDeterministic() throws Exception {
        MessagingTestSupport.MutableClock clock = new MessagingTestSupport.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedDurableQueue queue = MessagingTestSupport.open(this.directory, clock)) {
            queue.record(MessagingTestSupport.queue(), MessagingTestSupport.message("outbox"),
                    new EnqueueOptions(MessagePriority.NORMAL, null, null, new IdempotencyKey("relay-once"), null, Instant.EPOCH));
            List<Integer> relayCounts = runConcurrent(4, index -> Integer.valueOf(queue.relayPending()));
            assertEquals(1, relayCounts.stream().mapToInt(Integer::intValue).sum());

            DedupeKey key = new DedupeKey("inbox-concurrent");
            List<InboxEntry> entries = runConcurrent(6, index -> queue.record(key, "done-" + index, InboxOptions.defaults()));
            long accepted = entries.stream().filter(entry -> entry.decision() == InboxDuplicateDecision.ACCEPTED).count();
            long suppressed = entries.stream().filter(entry -> entry.decision() != InboxDuplicateDecision.ACCEPTED).count();
            assertEquals(1L, accepted);
            assertEquals(5L, suppressed);
        }
    }

    private static <T> List<T> runConcurrent(final int count, final ConcurrentAction<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                final int safeIndex = index;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    return action.run(safeIndex);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(10L, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface ConcurrentAction<T> {
        T run(int index);
    }
}
