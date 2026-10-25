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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReplicatedResourcesTest {
    private long index;
    private final ReplicatedResources machine = new ReplicatedResources();
    private ReplicatedResources.Result apply(String op, String request, String... args) {
        machine.apply(++index, new ResourceCommand(op, "tenant/alice", request, List.of(args)).encode());
        return machine.result("tenant/alice", request, index);
    }
    @Test void atomicBatchHasOneStateResultAndEventAndNoPartialConflict() {
        assertEquals("OK", apply("BATCH", "a", "x", "0", "1", "y", "0", "2").status());
        assertEquals("CONFLICT", apply("BATCH", "b", "x", "1", "changed", "y", "0", "bad").status());
        assertEquals("1", machine.queryAtReadIndex("tenant/alice", "x", index).value());
        assertEquals(2, machine.events("tenant/alice", index).size());
        assertTrue(machine.events("tenant/bob", index).isEmpty());
        assertNull(machine.result("tenant/bob", "a", index));
        assertThrows(IllegalStateException.class, () -> machine.queryAtReadIndex("tenant/alice", "x", index + 1));
    }
    @Test void duplicateReplaysResultWithoutRepeatingEffectAndScopeCannotReadIt() {
        var command = new ResourceCommand("BATCH", "tenant/alice", "same", List.of("x", "0", "v"));
        machine.apply(++index, command.encode()); machine.apply(++index, command.encode());
        assertEquals(1, machine.result("tenant/alice", "same", index).index());
        assertEquals(1, machine.events("tenant/alice", index).size());
        assertNull(machine.result("other", "same", index));
        assertThrows(IllegalArgumentException.class, () -> machine.apply(3,
                new ResourceCommand("BATCH", "tenant/alice", "same", List.of("x", "1", "other")).encode()));
    }
    @Test void snapshotRestoresResultsLeasesEventsAndMonotonicFences() {
        apply("TIME", "t", "1000", "1000", "0");
        apply("ACQUIRE", "acq", "leader", "one", "100", "2");
        var lease = machine.lease("tenant/alice", "leader", index);
        apply("LOCK_READ", "lock", "tx", "x");
        apply("PREPARE", "prep", "tx", "WRITE", "x", "v");
        byte[] snapshot = machine.snapshot();
        var restored = new ReplicatedResources(); restored.restore(snapshot);
        assertArrayEquals(snapshot, restored.snapshot());
        assertEquals(lease, restored.lease("tenant/alice", "leader", index));
        assertEquals(List.of("tx"), restored.prepared("tenant/alice", index));
        restored.apply(index + 1, new ResourceCommand("BATCH", "tenant/alice", "blocked", List.of("x", "0", "bad")).encode());
        assertEquals("CONFLICT", restored.result("tenant/alice", "blocked", index + 1).status());
        byte[] beforeRejectedRestore = restored.snapshot();
        byte[] invalid = snapshot.clone(); invalid[3] = 3;
        assertThrows(IllegalArgumentException.class, () -> restored.restore(invalid));
        assertArrayEquals(beforeRejectedRestore, restored.snapshot());
        assertEquals("CONFLICT", restored.result("tenant/alice", "blocked", index + 1).status());
    }
    @Test void staleExpiryAndFenceCannotInvalidateRenewedLease() {
        apply("TIME", "time", "100", "100", "0");
        apply("ACQUIRE", "a", "lease", "owner", "100", "1");
        var old = machine.lease("tenant/alice", "lease", index);
        apply("RENEW", "r", "lease", Long.toString(old.version()), "owner", "200", "2");
        var current = machine.lease("tenant/alice", "lease", index);
        assertTrue(current.fence() > old.fence());
        assertEquals("CONFLICT", apply("EXPIRE", "e", "lease", Long.toString(old.version())).status());
        assertEquals("CONFLICT", apply("FENCED", "f", "lease", Long.toString(old.fence()), "1", "x", "bad").status());
        assertEquals("OK", apply("FENCED", "g", "lease", Long.toString(current.fence()), "2", "x", "good").status());
        assertEquals("OK", apply("CANCEL", "c", "lease", Long.toString(current.version())).status());
        assertNull(machine.lease("tenant/alice", "lease", index));
    }
    @ParameterizedTest
    @ValueSource(longs = {-60_000, 60_000})
    void clockJumpsFailClosed(long jump) {
        apply("TIME", "t", "100000", "100000", "0");
        apply("TIME", "jump", Long.toString(100000 + jump), "100000", "0");
        assertEquals(100000, machine.logicalTime());
        apply("ACQUIRE", "a", "l", "o", "100", "1");
        assertNotNull(machine.lease("tenant/alice", "l", index));
    }
    @Test void lockReadPreventsWriteSkewAndRangePreventsPhantomAcrossSnapshot() {
        apply("BATCH", "seed", "x", "0", "1", "y", "0", "1");
        apply("LOCK_READ", "r1", "tx1", "x");
        apply("LOCK_READ", "r2", "tx1", "y");
        assertEquals("CONFLICT", apply("PREPARE", "p2", "tx2", "WRITE", "x", "0").status());
        apply("PREPARE", "p1", "tx1", "WRITE", "y", "0");
        assertEquals("COMMITTED", apply("COMMIT", "c1", "tx1", "99").status());
        assertEquals("COMMITTED", apply("COMMIT", "c1-other-delivery", "tx1", "99").status());
        apply("LOCK_RANGE", "range", "tx3", "prefix");
        assertEquals("CONFLICT", apply("BATCH", "phantom", "new", "0", "v").status());
        apply("PREPARE", "pr", "tx3", "WRITE", "y", "2");
        byte[] snapshot = machine.snapshot(); var restored = new ReplicatedResources(); restored.restore(snapshot);
        restored.apply(index + 1, new ResourceCommand("BATCH", "tenant/alice", "phantom2", List.of("new", "0", "v")).encode());
        assertEquals("CONFLICT", restored.result("tenant/alice", "phantom2", index + 1).status());
        assertEquals("ABORTED", apply("ROLLBACK", "ab", "tx3", "100").status());
        assertEquals("OK", apply("BATCH", "after", "new", "0", "v").status());
    }
    @Test void unknownVersionNeverMutatesAndResultExpiresWithoutReexecution() {
        byte[] bytes = new ResourceCommand("BATCH", "tenant/alice", "a", List.of("x", "0", "v")).encode();
        bytes[3] = 2;
        assertThrows(IllegalArgumentException.class, () -> machine.apply(1, bytes));
        assertNull(machine.queryAtReadIndex("tenant/alice", "x", 0));
        apply("TIME", "bootstrap-time", "0", "0", "0");
        apply("BATCH", "a", "x", "0", "v");
        apply("TIME", "t", "86400001", "86400001", "0");
        assertEquals("EXPIRED", machine.result("tenant/alice", "a", index).status());
        apply("BATCH", "a", "x", "0", "v");
        assertEquals("v", machine.queryAtReadIndex("tenant/alice", "x", index).value());
    }
    @Test void firstRealClockCalibrationPreservesCommittedResultRetention() {
        apply("BATCH", "before-clock", "key", "0", "value");
        apply("TIME", "calibrate", "1800000000000", "1800000000000", "0");
        assertEquals("OK", machine.result("tenant/alice", "before-clock", index).status());
        apply("TIME", "expire", "1800086400000", "1800086400000", "0");
        assertEquals("EXPIRED", machine.result("tenant/alice", "before-clock", index).status());
    }
    @Test void fencingEpochComesFromCommittedTermRatherThanCallerArguments() {
        machine.apply(1, 9, new ResourceCommand("ACQUIRE", "tenant/alice", "acquire",
                List.of("leader", "owner", "1000", "0")).encode());
        var lease = machine.lease("tenant/alice", "leader", 1);
        assertEquals(9, lease.leadershipEpoch());
        machine.apply(2, 10, new ResourceCommand("RENEW", "tenant/alice", "renew",
                List.of("leader", "1", "owner", "1000", "0")).encode());
        var renewed = machine.lease("tenant/alice", "leader", 2);
        assertEquals(10, renewed.leadershipEpoch()); assertTrue(renewed.fence() > lease.fence());
        var restored = new ReplicatedResources(); restored.restore(machine.snapshot());
        assertEquals(renewed, restored.lease("tenant/alice", "leader", 2));
    }
    @Test void controlTicksDoNotConsumeCapacityAndExpiredReadPayloadIsDiscarded() {
        var bounded = new ReplicatedResources(2);
        for (int i = 1; i <= 100005; i++) bounded.apply(i,
                new ResourceCommand("TIME", "tenant", "clock", List.of(Integer.toString(i), Integer.toString(i), "0")).encode());
        assertTrue(bounded.events("tenant", 100005).isEmpty()); assertFalse(bounded.atCapacity());
        String payload = "value".repeat(1000);
        bounded.apply(100006, new ResourceCommand("BATCH", "tenant", "write", List.of("key", "0", payload)).encode());
        bounded.apply(100007, new ResourceCommand("LOCK_READ", "tenant", "read", List.of("tx", "key")).encode());
        assertTrue(bounded.atCapacity()); int before = bounded.snapshot().length;
        bounded.apply(100008, new ResourceCommand("TIME", "tenant", "gc", List.of("86500006", "86500006", "0")).encode());
        assertEquals("EXPIRED", bounded.result("tenant", "read", 100008).status());
        assertEquals("", bounded.result("tenant", "read", 100008).value());
        assertTrue(bounded.snapshot().length < before - 4000, "Expired large result payload must actually leave storage");
        bounded.apply(100009, new ResourceCommand("BATCH", "tenant", "overflow", List.of("other", "0", "bad")).encode());
        assertNull(bounded.result("tenant", "overflow", 100009));
        assertNull(bounded.queryAtReadIndex("tenant", "other", 100009));
        bounded.apply(100010, new ResourceCommand("TIME", "tenant", "future-tick", List.of("86500007", "86500007", "0")).encode());
        assertEquals(86500007, bounded.logicalTime());
        var restored = new ReplicatedResources(2); restored.restore(bounded.snapshot());
        assertEquals("EXPIRED", restored.result("tenant", "write", 100010).status());
        assertThrows(IllegalArgumentException.class, () -> new ReplicatedResources(0));
        assertThrows(IllegalArgumentException.class, () -> new ReplicatedResources(100001));
    }
    @Test void coordinatorDecisionIsDurableImmutableAndEnlistmentSealed() {
        apply("BEGIN", "begin", "tx"); apply("ENLIST", "enlist", "tx", "p1");
        apply("SEAL", "seal", "tx");
        assertEquals("CONFLICT", apply("ENLIST", "late", "tx", "p2").status());
        apply("DECIDE", "decision", "tx", "COMMITTED");
        long epoch = Long.parseLong(machine.transaction("tenant/alice", "tx", index).value());
        assertTrue(epoch > 0);
        assertEquals("CONFLICT", apply("DECIDE", "abort", "tx", "ABORTED").status());
        assertEquals(List.of("p1"), machine.participants("tenant/alice", "tx", index));
    }
}
