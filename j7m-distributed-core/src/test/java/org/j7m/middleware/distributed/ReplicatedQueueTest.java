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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReplicatedQueueTest {
    static class Endpoint implements PartitionEndpoint {
        final QueueStateMachine state = new QueueStateMachine(); long index;
        @Override public PartitionReply execute(long epoch, String request, byte[] command, byte[] query, Instant deadline) {
            state.validate(command, "tenant", false); state.validateQuery(query, "tenant");
            state.apply(++index, command); return new PartitionReply("OK", epoch, state.query(query));
        }
        @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) { return new PartitionReply("OK", epoch, state.query(query)); }
        @Override public PartitionStateMachine.Status status(Instant deadline) { throw new UnsupportedOperationException(); }
    }
    @Test void facadeExposesQueueMembershipDeliveryAndQueries() throws Exception {
        Endpoint endpoint = new Endpoint(); ReplicatedQueue queue = new ReplicatedQueue("partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
        Instant deadline = Instant.now().plusSeconds(10);
        assertEquals("partition", queue.partitionId()); assertEquals("tenant", queue.scopeId());
        assertEquals("OK\t1", queue.join("join", "g", "m", 1000, deadline));
        assertEquals("SENT\t1", queue.send("send", "q", "message", new byte[]{1}, 2, deadline));
        assertEquals(List.of("1", "q"), queue.assignment("g", "m", deadline));
        assertEquals("OK\t1", queue.heartbeat("heartbeat", "g", "m", 1000, deadline));
        assertTrue(queue.claim("claim1", "q", "g", "m", 1, 100, deadline).startsWith("MESSAGE\tmessage\t"));
        assertEquals("NACKED", queue.nack("nack", "q", "g", "m", 1, "message", 1, deadline));
        assertTrue(queue.claim("claim2", "q", "g", "m", 1, 100, deadline).startsWith("MESSAGE"));
        assertEquals("ACKED", queue.ack("ack", "q", "g", "m", 1, "message", 2, deadline));
        assertEquals("SENT_BATCH\t1", queue.sendBatch("batch", "other", Map.of("other-message", new byte[0]), 1, deadline));
        assertEquals("OK\t2", queue.rebalance("rebalance", "g", deadline));
        assertTrue(queue.deadLetters("q", deadline).isEmpty());
        assertEquals("EMPTY", queue.claim("empty", "q", "g", "m", 2, 100, deadline));
    }
    @Test void movedRefreshPreservesIdenticalRequestAndCommand() throws Exception {
        Endpoint valid = new Endpoint(); AtomicInteger resolutions = new AtomicInteger(); List<byte[]> sent = new ArrayList<>();
        Endpoint moved = new Endpoint() {
            @Override public PartitionReply execute(long epoch, String id, byte[] command, byte[] query, Instant deadline) {
                assertEquals("stable", id); sent.add(command); return new PartitionReply("MOVED", 2, new byte[0]);
            }
        };
        ReplicatedQueue queue = new ReplicatedQueue("partition", "tenant", (p, d) -> new ReplicatedQueue.Route(resolutions.getAndIncrement() == 0 ? moved : valid, resolutions.get()));
        assertEquals("SENT\t1", queue.send("stable", "q", "message", new byte[]{1}, 1, Instant.now().plusSeconds(10)));
        ResourceCommand accepted = ResourceCommand.decode(sent.getFirst()); assertEquals("stable", accepted.requestId());
        assertEquals("SENT\t1", new String(valid.state.query(new ResourceCommand("Q_RESULT", "tenant", "q", List.of("stable")).encode()), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> queue.send("bad", "q", "m", new byte[32769], 1, Instant.now().plusSeconds(10)));
    }
    @Test void deadlineAndUnknownRoutingFailExplicitlyWithoutSubmitting() {
        Endpoint endpoint = new Endpoint(); ReplicatedQueue queue = new ReplicatedQueue("partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
        assertThrows(IOException.class, () -> queue.send("expired", "q", "m", new byte[0], 1, Instant.EPOCH));
        assertEquals(0, endpoint.index);
        assertThrows(IOException.class, () -> queue.observeDeadLetters("q", "observer", Instant.now().plusSeconds(1)));
        assertEquals(0, endpoint.index, "Unsupported reactive capability must never fall back to a durable claim");
        assertThrows(IllegalArgumentException.class, () -> new ReplicatedQueue.Route(endpoint, 0));
    }
    @Test void concurrentFacadeSubmissionsWithConflictingIdentityReturnOneConflict() throws Exception {
        var admitted = new java.util.concurrent.CyclicBarrier(2);
        Endpoint endpoint = new Endpoint() {
            @Override public PartitionReply execute(long epoch, String request, byte[] command, byte[] query, Instant deadline) {
                state.validate(command, "tenant", false);
                try { admitted.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                synchronized (this) { state.apply(++index, command); return new PartitionReply("OK", epoch, state.query(query)); }
            }
        };
        ReplicatedQueue queue = new ReplicatedQueue("partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> queue.send("same", "q", "first", new byte[]{1}, 1, Instant.now().plusSeconds(10)));
            var second = executor.submit(() -> queue.send("same", "q", "second", new byte[]{2}, 1, Instant.now().plusSeconds(10)));
            Set<String> replies = Set.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Set.of("SENT\t1", "REQUEST_CONFLICT"), replies);
        }
    }

}
