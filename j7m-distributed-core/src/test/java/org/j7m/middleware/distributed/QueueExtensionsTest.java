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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueueExtensionsTest {
    private static final class Host implements PartitionEndpoint {
        final QueueStateMachine state = new QueueStateMachine(); long index;
        @Override public synchronized PartitionReply execute(long epoch, String request, byte[] bytes, byte[] query, Instant deadline) {
            state.validate(bytes, "tenant", true); state.apply(++index, bytes);
            assertEquals(state.snapshot().length, state.retainedSnapshotBytes(), "Every mutation must account exact serialized fields");
            return new PartitionReply("OK", epoch, state.query(query));
        }
        @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) {
            state.validateQuery(query, "tenant"); return new PartitionReply("OK", epoch, state.query(query));
        }
        @Override public PartitionStateMachine.Status status(Instant deadline) { throw new UnsupportedOperationException(); }
        void time(long value) { state.apply(++index, state.timeCommand(value)); }
    }
    private final Host host = new Host();
    private final ReplicatedQueue queue = new ReplicatedQueue("queue", "tenant", (p, d) -> new ReplicatedQueue.Route(host, 1));
    private final Instant deadline = Instant.now().plusSeconds(30);
    private QueueDelivery delivery(String id, int attempts) {
        return new QueueDelivery("q", "g", "m", 1, id, new byte[]{3}, 1, attempts, attempts);
    }
    @Test void emptyPersistenceIsExplicitAndPositiveGrantRemainsDurable() throws Exception {
        queue.join("join", "g", "m", 10000, deadline);
        for (int i = 0; i < 300; i++) assertEquals("EMPTY", queue.claim("idle" + i, "q", "g", "m", 1, 100, deadline));
        assertNull(host.state.result("tenant", "idle299", host.index));
        assertEquals("EMPTY", queue.claim("retained", "q", "g", "m", 1, 100, true, deadline));
        assertEquals("EMPTY", host.state.result("tenant", "retained", host.index).value());
        queue.send("send", "q", "id", new byte[]{3}, 1, deadline);
        assertTrue(queue.claim("idle299", "q", "g", "m", 1, 100, deadline).startsWith("MESSAGE"));
        byte[] snapshot = host.state.snapshot(); host.state.restore(snapshot);
        assertTrue(queue.claim("idle299", "q", "g", "m", 1, 100, deadline).startsWith("MESSAGE"));
        assertEquals("EMPTY", queue.claim("retained", "q", "g", "m", 1, 100, true, deadline));
    }
    @Test void renewVisibilityPreservesReceiptAndRejectsExpiryGenerationAndWindowOverflow() throws Exception {
        host.time(1000); queue.join("join", "g", "m", 86400000, deadline);
        queue.send("send", "q", "id", new byte[]{3}, 2, deadline);
        queue.claim("claim", "q", "g", "m", 1, 100, deadline);
        host.time(1050);
        assertEquals("RENEWED\t1250", queue.renewVisibility("renew", delivery("id", 1), 200, deadline));
        host.time(1100);
        assertEquals("RENEWAL_LIMIT", queue.renewVisibility("too-long", delivery("id", 1), 86400000, deadline));
        queue.rebalance("rebalance", "g", deadline);
        assertEquals("STALE_GENERATION", queue.renewVisibility("stale", delivery("id", 1), 200, deadline));
        host.time(1300);
        QueueDelivery currentGeneration = new QueueDelivery("q", "g", "m", 2, "id", new byte[]{3}, 1, 1, 1);
        assertEquals("STALE_DELIVERY", queue.renewVisibility("expired", currentGeneration, 200, deadline));
    }
    @Test void redriveIsAuthorizedAtomicAndAuditLinkedAcrossRestore() throws Exception {
        queue.join("join", "g", "m", 10000, deadline); queue.send("send", "q", "id", new byte[]{3}, 1, deadline);
        queue.claim("claim", "q", "g", "m", 1, 100, deadline); queue.nack("nack", "q", "g", "m", 1, "id", 1, deadline);
        byte[] denied = new ResourceCommand("Q_REDRIVE", "tenant", "denied", List.of("q", "id", "1", "child", "2", "admin")).encode();
        assertThrows(SecurityException.class, () -> host.state.validate(denied, "tenant", false));
        byte[] before = host.state.snapshot();
        assertEquals("STALE_DELIVERY", queue.redrive("wrong", "q", "id", 2, "child", 2, deadline));
        assertEquals(1, queue.deadLetterPage("q", 0, 0, 256, deadline).entries().size());
        assertEquals("REDRIVEN\t2", queue.redrive("redrive", "q", "id", 1, "child", 2, deadline));
        assertEquals("REDRIVEN\t2", queue.redrive("redrive", "q", "id", 1, "child", 2, deadline));
        assertEquals("ALREADY_REDRIVEN", queue.redrive("other", "q", "id", 1, "second-child", 2, deadline));
        host.state.restore(host.state.snapshot());
        DeadLetter letter = queue.deadLetterStream("q", deadline).findFirst().orElseThrow();
        assertEquals("child", letter.child()); assertArrayEquals(new byte[]{3}, letter.payload());
        assertTrue(queue.claim("child-claim", "q", "g", "m", 1, 100, deadline).startsWith("MESSAGE\tchild\t"));
        byte[] corrupt = before.clone(); corrupt[3] = 99; byte[] valid = host.state.snapshot();
        assertThrows(IllegalArgumentException.class, () -> host.state.restore(corrupt)); assertArrayEquals(valid, host.state.snapshot());
    }
    @Test void automaticPagingTraversesMoreThan256AndStopsAtInitialHighWater() throws Exception {
        queue.join("join", "g", "m", 10000, deadline);
        for (int i = 0; i < 300; i++) poison(i);
        var iterator = queue.deadLetterStream("q", deadline).iterator();
        assertTrue(iterator.hasNext()); poison(300);
        int count = 0;
        while (iterator.hasNext()) { assertEquals(++count, iterator.next().sequence()); }
        assertEquals(300, count);
        assertThrows(IllegalArgumentException.class, () -> queue.deadLetterPage("q", 0, Long.MAX_VALUE, 1, deadline));
        assertThrows(IllegalArgumentException.class, () -> queue.deadLetterPage("q", 0, 0, 257, deadline));
        byte[] foreign = new ResourceCommand("Q_DLQ_PAGE", "foreign", "query", List.of("q", "0", "0", "1")).encode();
        assertThrows(SecurityException.class, () -> host.state.validateQuery(foreign, "tenant"));
    }
    @Test void historicalPayloadAndReceiptReplayRemainExactAfterRenewTakeoverAckDlqRedriveAndRestore() throws Exception {
        queue.join("join", "g", "m", 10000, deadline); queue.send("send", "q", "id", new byte[]{3}, 3, deadline);
        String original = queue.claim("original", "q", "g", "m", 1, 100, deadline);
        assertTrue(queue.renewVisibility("renew", delivery("id", 1), 1000, deadline).startsWith("RENEWED"));
        queue.rebalance("rebalance", "g", deadline);
        assertTrue(queue.claim("takeover", "q", "g", "m", 2, 1000, deadline).startsWith("MESSAGE"));
        assertEquals("ACKED", queue.ack("ack", "q", "g", "m", 2, "id", 2, deadline));
        assertEquals(original, queue.claim("original", "q", "g", "m", 1, 100, deadline));
        queue.send("poison-send", "q", "poison", new byte[]{4}, 1, deadline);
        String poison = queue.claim("poison-claim", "q", "g", "m", 2, 1000, deadline);
        queue.nack("poison-nack", "q", "g", "m", 2, "poison", 1, deadline);
        queue.redrive("redrive", "q", "poison", 1, "child", 1, deadline);
        byte[] bytes = host.state.snapshot(); host.state.restore(bytes);
        assertEquals(original, queue.claim("original", "q", "g", "m", 1, 100, deadline));
        assertEquals(poison, queue.claim("poison-claim", "q", "g", "m", 2, 1000, deadline));
        assertEquals(bytes.length, host.state.retainedSnapshotBytes());
    }
    private void poison(int value) throws Exception {
        String id = "m" + value; queue.send("send" + value, "q", id, id.getBytes(StandardCharsets.UTF_8), 1, deadline);
        queue.claim("claim" + value, "q", "g", "m", 1, 100, deadline);
        queue.nack("nack" + value, "q", "g", "m", 1, id, 1, deadline);
    }
}
