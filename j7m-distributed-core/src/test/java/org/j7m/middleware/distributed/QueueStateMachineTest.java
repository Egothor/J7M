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
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class QueueStateMachineTest {
    private long index;
    private int request;
    private QueueStateMachine state = new QueueStateMachine();
    private String apply(String op, String... args) {
        String id = "r" + ++request;
        state.apply(++index, new ResourceCommand(op, "tenant", id, List.of(args)).encode());
        var result = state.result("tenant", id, index);
        return result == null ? "" : result.value();
    }
    private void time(long time) { apply("Q_TIME", Long.toString(time)); }
    private String send(String queue, String id, int attempts) { return apply("Q_SEND", queue, id, Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)), Integer.toString(attempts)); }
    private String claim(String queue, String member, long generation) { return apply("Q_CLAIM", queue, "group", member, Long.toString(generation), "100", "1"); }
    private String ack(String queue, String member, long generation, String id, String receipt) { return apply("Q_ACK", queue, "group", member, Long.toString(generation), id, receipt); }

    @Test void preservesOrderVisibilityAndDurableAcknowledgementAcrossSnapshot() {
        time(1000); assertEquals("OK\t1", apply("Q_JOIN", "group", "a", "10000"));
        send("q", "one", 3); send("q", "two", 3);
        String first = claim("q", "a", 1); assertTrue(first.startsWith("MESSAGE\tone\t"));
        assertEquals("EMPTY", claim("q", "a", 1));
        assertEquals("ACKED", ack("q", "a", 1, "one", "1"));
        state.restore(state.snapshot());
        assertTrue(claim("q", "a", 1).startsWith("MESSAGE\ttwo\t"));
        assertEquals("STALE_DELIVERY", ack("q", "a", 1, "one", "1"));
        assertTrue(state.transferReady());
    }
    @Test void expiryAndGenerationRebalanceFenceOldConsumersAndTransferUnacknowledged() {
        time(1000); apply("Q_JOIN", "group", "old", "100"); send("q", "one", 3);
        assertTrue(claim("q", "old", 1).startsWith("MESSAGE"));
        time(1100); assertEquals("STALE_GENERATION", ack("q", "old", 1, "one", "1"));
        assertEquals("OK\t3", apply("Q_JOIN", "group", "new", "10000"));
        String next = claim("q", "new", 3); assertTrue(next.startsWith("MESSAGE\tone\t"));
        assertEquals("ACKED", ack("q", "new", 3, "one", "2"));
    }
    @Test void nackAndVisibilityExpiryRouteMaxAttemptsToDlqWithoutBlockingLaterMessages() {
        time(1); apply("Q_JOIN", "group", "a", "10000"); send("q", "bad", 1); send("q", "good", 2);
        claim("q", "a", 1);
        assertEquals("NACKED", apply("Q_NACK", "q", "group", "a", "1", "bad", "1"));
        assertEquals(List.of("bad"), state.deadLetters("tenant", "q", index));
        assertTrue(claim("q", "a", 1).startsWith("MESSAGE\tgood\t"));
        time(101); assertTrue(claim("q", "a", 1).startsWith("MESSAGE\tgood\t"));
        time(201); assertEquals("EMPTY", claim("q", "a", 1));
        assertEquals(List.of("bad", "good"), state.deadLetters("tenant", "q", index));
    }
    @Test void repeatedSendDoesNotDuplicateAndRequestReplayRetainsOriginalResult() {
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1});
        var c = new ResourceCommand("Q_SEND", "tenant", "stable", List.of("q", "one", encoded, "2"));
        state.apply(++index, c.encode()); String original = state.result("tenant", "stable", index).value();
        state.apply(++index, c.encode()); assertEquals(original, state.result("tenant", "stable", index).value());
        assertEquals(original, apply("Q_SEND", "q", "one", encoded, "2"));
        assertEquals("MESSAGE_CONFLICT", send("q", "one", 3));
        assertThrows(IllegalStateException.class, () -> state.result("tenant", "stable", index + 1));
    }
    @Test void validatesWireBoundsBeforeAdmissionAndRestoreIsAtomic() {
        assertThrows(IllegalArgumentException.class, () -> QueueStateMachine.validate(new ResourceCommand("Q_SEND", "tenant", "id", List.of("q", "one", "!!!", "1"))));
        assertThrows(IllegalArgumentException.class, () -> QueueStateMachine.validate(new ResourceCommand("Q_JOIN", "tenant", "id", List.of("g", "m", "0"))));
        assertThrows(IllegalArgumentException.class, () -> QueueStateMachine.validate(new ResourceCommand("Q_ACK", "tenant", "id", List.of("x".repeat(513), "g", "m", "1", "i", "1"))));
        assertThrows(IllegalArgumentException.class, () -> QueueStateMachine.validate(new ResourceCommand("Q_UNKNOWN", "tenant", "id", List.of())));
        send("q", "one", 1); byte[] valid = state.snapshot();
        assertThrows(IllegalArgumentException.class, () -> state.restore(Arrays.copyOf(valid, valid.length - 1)));
        assertArrayEquals(valid, state.snapshot());
    }
    @Test void assignmentAndHeartbeatPersistAndMaintenanceDoesNotBurnResultCapacity() {
        time(100); apply("Q_JOIN", "group", "a", "1000"); send("q", "one", 1);
        assertEquals(List.of("1", "q"), state.assignment("tenant", "group", "a", index));
        assertEquals("OK\t1", apply("Q_HEARTBEAT", "group", "a", "2000"));
        assertEquals("OK\t2", apply("Q_REBALANCE", "group"));
        state.restore(state.snapshot()); assertEquals(List.of("2", "q"), state.assignment("tenant", "group", "a", index));
        int prior = Integer.parseInt(new String(state.query(new ResourceCommand("Q_EVENTS", "tenant", "query", List.of()).encode()), StandardCharsets.UTF_8));
        time(99); time(101);
        assertEquals(prior, Integer.parseInt(new String(state.query(new ResourceCommand("Q_EVENTS", "tenant", "query", List.of()).encode()), StandardCharsets.UTF_8)));
        assertEquals("STALE_MEMBER", apply("Q_HEARTBEAT", "group", "missing", "1000"));
    }
    @Test void batchSendIsAtomicAndRejectsDuplicateOrConflictingIdentities() {
        String payload = Base64.getEncoder().encodeToString(new byte[]{1});
        assertEquals("SENT_BATCH\t2", apply("Q_SEND_BATCH", "q", "2", "one", payload, "two", payload));
        String largest = Base64.getEncoder().encodeToString(new byte[32768]);
        assertDoesNotThrow(() -> QueueStateMachine.validate(new ResourceCommand("Q_SEND_BATCH", "tenant", "bounded", List.of("q", "1", "a", largest, "b", largest))));
        assertThrows(IllegalArgumentException.class, () -> QueueStateMachine.validate(new ResourceCommand("Q_SEND_BATCH", "tenant", "oversized", List.of("q", "1", "a", largest, "b", largest, "c", largest))));
        assertEquals("MESSAGE_CONFLICT", apply("Q_SEND_BATCH", "q", "1", "three", payload, "one", payload));
        apply("Q_JOIN", "group", "a", "1000");
        assertTrue(claim("q", "a", 1).startsWith("MESSAGE\tone\t"));
        ack("q", "a", 1, "one", "1"); claim("q", "a", 1); ack("q", "a", 1, "two", "1");
        assertEquals("EMPTY", claim("q", "a", 1));
        assertThrows(IllegalArgumentException.class, () -> QueueStateMachine.validate(new ResourceCommand("Q_SEND_BATCH", "tenant", "bad", List.of("q", "1", "same", payload, "same", payload))));
    }

    @Test void admissionBindsScopeProtectsTimeAndChecksSnapshotWithoutMutatingLiveState() {
        byte[] command = new ResourceCommand("Q_SEND", "tenant", "send", List.of("q", "one", "AQ==", "1")).encode();
        assertThrows(SecurityException.class, () -> state.validate(command, "other", false));
        byte[] clock = state.timeCommand(100);
        assertTrue(state.timeControl(clock)); assertFalse(state.timeControl(command));
        assertThrows(SecurityException.class, () -> state.validate(clock, "queue-clock", false));
        assertTrue(state.timeSensitive(new ResourceCommand("Q_CLAIM", "tenant", "x", List.of("q", "g", "a", "1", "1", "1")).encode()));
        assertFalse(state.timeSensitive(command));
        state.validate(command, "tenant", false); state.apply(++index, command);
        byte[] snapshot = state.snapshot(); state.validateSnapshot(snapshot); assertArrayEquals(snapshot, state.snapshot());
        assertThrows(IllegalArgumentException.class, () -> new QueueStateMachine("foreign").restore(snapshot));
        TransferableResource copy = state.copyForTransfer();
        byte[] independent = new ResourceCommand("Q_SEND", "tenant", "independent", List.of("q", "two", "Ag==", "1")).encode();
        copy.validateReplayCommand(independent); copy.apply(index + 1, independent);
        assertNull(state.result("tenant", "independent", index));
        state.ownershipEpoch(2); assertThrows(IllegalArgumentException.class, () -> state.ownershipEpoch(1));
        byte[] negativeTime = state.snapshot();
        java.nio.ByteBuffer.wrap(negativeTime, 4 + 2 + 5 + 8, 8).putLong(-1);
        byte[] valid = state.snapshot();
        assertThrows(IllegalArgumentException.class, () -> state.restore(negativeTime)); assertArrayEquals(valid, state.snapshot());
        assertThrows(SecurityException.class, () -> state.validateQuery(new ResourceCommand("Q_RESULT", "other", "query", List.of("send")).encode(), "tenant"));
    }

    @Test void byteRetentionCapacityRejectsBeforeAppendWhileExistingResultsRemainRecoverable() {
        String payload = Base64.getEncoder().encodeToString(new byte[32768]);
        int accepted = 0;
        for (; accepted < 1000; accepted++) {
            var command = new ResourceCommand("Q_SEND", "tenant", "large" + accepted, List.of("q", "m" + accepted, payload, "1"));
            try { state.validate(command.encode(), "tenant", false); }
            catch (IllegalStateException overloaded) { assertTrue(overloaded.getMessage().contains("OVERLOADED")); break; }
            state.apply(++index, command.encode());
        }
        assertTrue(accepted > 0 && accepted < 1000);
        assertNotNull(state.result("tenant", "large0", index));
        var original = new ResourceCommand("Q_SEND", "tenant", "large0", List.of("q", "m0", payload, "1"));
        assertDoesNotThrow(() -> state.validate(original.encode(), "tenant", false));
        assertThrows(IllegalArgumentException.class, () -> state.validate(new ResourceCommand("Q_SEND", "tenant", "large0", List.of("q", "m0", "AQ==", "1")).encode(), "tenant", false));
    }

    @Test void concurrentAdmissionOfDifferentCommandsWithSameIdentityReportsConflictWithoutPoisoningApply() throws Exception {
        byte[] first = new ResourceCommand("Q_SEND", "tenant", "same", List.of("q", "first", "AQ==", "1")).encode();
        byte[] second = new ResourceCommand("Q_SEND", "tenant", "same", List.of("q", "second", "Ag==", "1")).encode();
        state.validate(first, "tenant", false); state.validate(second, "tenant", false);
        state.apply(++index, first);
        assertFalse(state.identityConflict(first)); assertTrue(state.identityConflict(second));
        state.apply(++index, second);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(second));
        byte[] query = new ResourceCommand("Q_RESULT", "tenant", "query", List.of("same", hash)).encode();
        assertEquals("REQUEST_CONFLICT", new String(state.query(query), StandardCharsets.UTF_8));
        assertEquals("SENT\t1", state.result("tenant", "same", index).value());
        assertTrue(apply("Q_SEND", "q", "healthy", "Aw==", "1").startsWith("SENT"));
    }

}
