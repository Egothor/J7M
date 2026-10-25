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
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.consensus.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplicatedResourceClientIT {
    @TempDir Path directory;
    private final List<NetworkConsensusNode> nodes = new ArrayList<>();
    private final List<ReplicatedResources> machines = new ArrayList<>();
    private Instant deadline() { return Instant.now().plusSeconds(10); }
    private ReplicatedResourceClient client(String id) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        var machine = new ReplicatedResources(); machines.add(machine);
        var config = new NetworkConsensusConfig(id, Map.of(id, new InetSocketAddress("127.0.0.1", port)),
                Set.of(id), directory.resolve(id), new byte[32], Duration.ofMillis(300), Duration.ofMillis(50),
                1_048_576, 16_777_216);
        var node = new NetworkConsensusNode(config, machine); nodes.add(node); node.start();
        long end = System.nanoTime() + 10_000_000_000L;
        while (!id.equals(node.status().leaderId())) {
            if (System.nanoTime() > end) fail("leader not elected");
            Thread.onSpinWait();
        }
        return new ReplicatedResourceClient(node, machine, "tenant/alice", operation -> true,
                () -> new ReplicatedResourceClient.ClockMeasurement(1000, 1000, 0, true));
    }
    private void close() throws Exception { for (var node : nodes) node.close(); }
    @Test void quorumFacadeCoversCasLeaseFencesAuthorizationDeadlineAndReplay() throws Exception {
        try {
            var resource = client("r");
            assertEquals("OK", resource.batch("a", List.of("key", "0", "v"), deadline()).status());
            assertEquals("v", resource.read("key", deadline()).value());
            assertEquals("OK", resource.result("a", deadline()).status());
            assertEquals("OK", resource.batch("a", List.of("key", "0", "v"), deadline()).status());
            assertEquals("OK", resource.advanceTime("time", deadline()).status());
            resource.acquire("lease", "l", "owner", 100, deadline());
            var old = resource.lease("l", deadline()); assertNotNull(old);
            resource.renew("renew", "l", old.version(), "owner", 100, deadline());
            var current = resource.lease("l", deadline()); assertTrue(current.fence() > old.fence());
            assertEquals("CONFLICT", resource.expire("expire", "l", old.version(), deadline()).status());
            assertEquals("CONFLICT", resource.fenced("old", "l", old.fence(), old.leadershipEpoch(), "key", "bad", deadline()).status());
            assertEquals("OK", resource.fenced("new", "l", current.fence(), current.leadershipEpoch(), "key", "good", deadline()).status());
            assertEquals("OK", resource.cancel("cancel", "l", current.version(), deadline()).status());
            assertNull(resource.lease("l", deadline()));
            assertThrows(IOException.class, () -> resource.read("key", Instant.EPOCH));
            assertThrows(IOException.class, () -> resource.batch("late", List.of("x", "0", "v"), Instant.EPOCH));
            assertThrows(IllegalArgumentException.class, () -> resource.batch("bad", List.of("x", "notVersion", "v"), deadline()));
            assertEquals("good", resource.read("key", deadline()).value());
            Thread.currentThread().interrupt();
            try { assertThrows(IOException.class, () -> resource.batch("cancelled", List.of("x", "0", "v"), deadline())); }
            finally { Thread.interrupted(); }
        } finally { close(); }
    }
    @Test void twoIndependentGroupsCommitAndRecoverDurablePreparedLocks() throws Exception {
        try {
            var coordinator = client("c"); var left = client("l"); var right = client("r");
            var tx = new RecoverableTransactions(coordinator, Map.of("left", left, "right", right));
            tx.begin("tx", deadline()); tx.enlist("tx", "left", deadline()); tx.enlist("tx", "right", deadline());
            assertEquals("OK", tx.read("tx", "left", "read", "x", deadline()).status());
            assertEquals("OK", tx.lockRange("tx", "right", "range", "", deadline()).status());
            assertEquals("PREPARED", tx.prepare("tx", "left", List.of("WRITE", "x", "one"), deadline()).status());
            assertEquals("PREPARED", tx.prepare("tx", "right", List.of("WRITE", "y", "two"), deadline()).status());
            assertEquals("IN_DOUBT", tx.recover("tx", deadline()).status());
            assertEquals("IN_DOUBT", tx.recoverPrepared("left", deadline()).getFirst().status());
            assertEquals("CONFLICT", right.batch("phantom", List.of("new", "0", "v"), deadline()).status());
            assertEquals("COMMITTED", tx.commit("tx", deadline()).status());
            assertEquals("COMMITTED", tx.commit("tx", deadline()).status());
            assertEquals("COMMITTED", tx.abort("tx", deadline()).status());
            assertEquals("one", left.read("x", deadline()).value()); assertEquals("two", right.read("y", deadline()).value());
            assertEquals("COMMITTED", tx.status("tx", deadline()).status());
            assertTrue(tx.recoverPrepared("left", deadline()).isEmpty());
            tx.begin("abort", deadline()); tx.enlist("abort", "left", deadline());
            tx.read("abort", "left", "readAbort", "x", deadline());
            assertEquals("ABORTED", tx.abort("abort", deadline()).status());
            assertEquals("ABORTED", tx.recover("abort", deadline()).status());
            assertThrows(IllegalArgumentException.class, () -> tx.enlist("tx", "unknown", deadline()));
            assertThrows(IllegalStateException.class, () -> tx.prepare("missing", "left", List.of("WRITE", "k", "v"), deadline()));
        } finally { close(); }
    }
    @Test void forbiddenClockUncertaintyAndOperationAreRejectedBeforeMutation() throws Exception {
        try {
            client("r"); var node = nodes.getFirst();
            var wrongMachine = new ReplicatedResources();
            assertThrows(IllegalArgumentException.class, () -> new ReplicatedResourceClient(node, wrongMachine,
                    "tenant/alice", op -> true, () -> new ReplicatedResourceClient.ClockMeasurement(0, 0, 0, true)));
            var machine = machines.getFirst();
            var allowed = new AtomicBoolean(false);
            var resource = new ReplicatedResourceClient(node, machine, "tenant/alice", op -> allowed.get(),
                    () -> new ReplicatedResourceClient.ClockMeasurement(61000, 1000, 0, true));
            assertThrows(SecurityException.class, () -> resource.read("x", deadline()));
            allowed.set(true);
            assertThrows(IOException.class, () -> resource.acquire("a", "l", "o", 100, deadline()));
            assertThrows(IOException.class, () -> resource.advanceTime("t", deadline()));
            assertThrows(IOException.class, () -> resource.fenced("f", "l", 1, 1, "x", "unsafe", deadline()));
            assertThrows(IOException.class, () -> resource.lease("l", deadline()));
        } finally { close(); }
    }
    @Test void coordinationMembershipLeadershipAndFencedMutationsUseQuorum() throws Exception {
        try {
            var resource = client("coord"); var coordination = new ReplicatedCoordination(resource);
            assertEquals("OK", coordination.compareAndSet("cas", "key", 0, "v", deadline()).status());
            assertEquals("CONFLICT", coordination.compareAndSet("loser", "key", 0, "v2", deadline()).status());
            assertEquals("OK", coordination.join("join", "member", 1000, deadline()).status());
            var member = coordination.member("member", deadline()); assertNotNull(member);
            assertEquals("OK", coordination.heartbeat("heartbeat", "member", member.version(), 2000, deadline()).status());
            member = coordination.member("member", deadline());
            assertEquals("OK", coordination.leave("leave", "member", member.version(), deadline()).status());
            assertNull(coordination.member("member", deadline()));
            assertEquals("OK", coordination.lead("lead", "activity", "one", 1000, deadline()).status());
            assertEquals("CONFLICT", coordination.lead("competing", "activity", "two", 1000, deadline()).status());
            var leader = coordination.leader("activity", deadline());
            assertEquals("OK", coordination.mutate("protected", "activity", leader.fence(), leader.leadershipEpoch(),
                    "owned", "value", deadline()).status());
            try (var maintenance = new LeaseTimeMaintenance(resource, Duration.ofMillis(100))) {
                long end = System.nanoTime() + 5_000_000_000L;
                while (maintenance.committedTicks() == 0 && System.nanoTime() < end) Thread.onSpinWait();
                assertTrue(maintenance.committedTicks() > 0); assertEquals(0, maintenance.failedTicks());
            }
            assertThrows(IllegalArgumentException.class, () -> new LeaseTimeMaintenance(resource, Duration.ZERO));
        } finally { close(); }
    }

    @Test void malformedIdentifiersAreRejectedBeforeAnyLogMutationAndHealthyWorkStillSucceeds() throws Exception {
        try {
            var resource = client("validation"); String bad = "t".repeat(1025);
            var cases = Map.ofEntries(
                    Map.entry("BEGIN", List.of(bad)), Map.entry("ENLIST", List.of(bad, "p")),
                    Map.entry("SEAL", List.of(bad)), Map.entry("DECIDE", List.of(bad, "COMMITTED")),
                    Map.entry("PREPARE", List.of(bad, "WRITE", "x", "v")),
                    Map.entry("LOCK_READ", List.of(bad, "x")), Map.entry("LOCK_RANGE", List.of(bad, "")),
                    Map.entry("COMMIT", List.of(bad, "1")), Map.entry("ROLLBACK", List.of(bad, "1")),
                    Map.entry("CANCEL", List.of(bad, "1")), Map.entry("EXPIRE", List.of(bad, "1")));
            long before = nodes.getFirst().status().appliedIndex();
            for (var example : cases.entrySet()) assertThrows(IllegalArgumentException.class,
                    () -> resource.execute(example.getKey(), "invalid-" + example.getKey(), example.getValue(), deadline()));
            assertThrows(IllegalArgumentException.class, () -> resource.execute("ENLIST", "invalid-participant", List.of("tx", bad), deadline()));
            assertThrows(IOException.class, () -> resource.execute("TIME", "forged-time", List.of("61000", "61000", "0"), deadline()));
            assertEquals(before, nodes.getFirst().status().appliedIndex());
            assertEquals("OK", resource.batch("healthy", List.of("key", "0", "value"), deadline()).status());
            assertEquals("value", resource.read("key", deadline()).value());
        } finally { close(); }
    }

}
