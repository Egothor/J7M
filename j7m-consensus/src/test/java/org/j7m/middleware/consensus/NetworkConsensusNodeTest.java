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

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkConsensusNodeTest {
    @TempDir Path directory;
    private NetworkConsensusConfig config() throws IOException {
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        return new NetworkConsensusConfig("a", Map.of("a", new InetSocketAddress("127.0.0.1", port)), Set.of("a"), directory,
                new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 65536, 1 << 20);
    }
    @Test void rejectsUnsafeBoundsAndExternalPlaintextAndCopiesSecret() throws Exception {
        var valid = config(); byte[] key = valid.authenticationKey(); key[0] = 1; assertEquals(0, valid.authenticationKey()[0]);
        assertThrows(IllegalArgumentException.class, () -> new NetworkConsensusConfig("a", Map.of("a", new InetSocketAddress("192.0.2.1", 10)), Set.of("a"), directory,
                new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 65536, 1 << 20));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConsensusConfig("a", valid.peers(), Set.of("a"), directory,
                new byte[1], Duration.ofMillis(180), Duration.ofMillis(30), 65536, 1 << 20));
    }
    @Test void singleNodeDurabilityDedupeSnapshotAndReadBarrier() throws Exception {
        var config = config(); var state = new NetworkConsensusNodeProcess.Counter(); long index;
        try (var node = new NetworkConsensusNode(config, state)) {
            assertThrows(IOException.class, () -> node.append("x", new byte[8], Instant.now().plusSeconds(1)));
            assertEquals(0, node.recoveredIndex());
            node.start(); awaitLeader(node);
            index = node.append("one", ByteBuffer.allocate(8).putLong(7).array(), Instant.now().plusSeconds(3));
            assertEquals(index, node.append("one", ByteBuffer.allocate(8).putLong(7).array(), Instant.now().plusSeconds(3)));
            assertThrows(IOException.class, () -> node.append("one", new byte[8], Instant.now().plusSeconds(1)));
            assertTrue(node.readIndex(Instant.now().plusSeconds(2)) >= index);
            assertEquals(index, node.snapshot());
            assertEquals(0, node.recoveredIndex(), "New writes cannot fabricate startup recovery");
            assertEquals("1,7," + index, state.text());
            assertThrows(IOException.class, () -> new NetworkConsensusNode(config, new NetworkConsensusNodeProcess.Counter()));
        }
        var restored = new NetworkConsensusNodeProcess.Counter();
        try (var node = new NetworkConsensusNode(config, restored)) {
            assertEquals(index, node.recoveredIndex());
            assertEquals("1,7," + index, restored.text()); node.start(); awaitLeader(node);
            assertEquals(index, node.append("one", ByteBuffer.allocate(8).putLong(7).array(), Instant.now().plusSeconds(3)));
            assertTrue(node.readIndex(Instant.now().plusSeconds(2)) > index);
        }
    }
    @Test void corruptCompleteWalFailsClosedAndTornTailRecovers() throws Exception {
        var config = config();
        try (var node = new NetworkConsensusNode(config, new NetworkConsensusNodeProcess.Counter())) { node.start(); awaitLeader(node); node.append("one", new byte[8], Instant.now().plusSeconds(3)); }
        Path wal = directory.resolve("network.wal"); byte[] valid = Files.readAllBytes(wal);
        Files.write(wal, new byte[] {0,0}, StandardOpenOption.APPEND);
        try (var node = new NetworkConsensusNode(config, new NetworkConsensusNodeProcess.Counter())) { assertTrue(node.status().commitIndex() > 0); }
        assertArrayEquals(valid, Files.readAllBytes(wal));
        valid[valid.length - 1] ^= 1; Files.write(wal, valid);
        assertThrows(IOException.class, () -> new NetworkConsensusNode(config, new NetworkConsensusNodeProcess.Counter()));
    }
    @Test void durableWriteFailurePoisonsNodeBeforeFurtherAdmission() throws Exception {
        var config = config(); var state = new NetworkConsensusNodeProcess.Counter();
        try (var node = new NetworkConsensusNode(config, state)) {
            node.start(); awaitLeader(node);
            java.lang.reflect.Field field = NetworkConsensusNode.class.getDeclaredField("wal"); field.setAccessible(true);
            ((java.nio.channels.FileChannel) field.get(node)).close();
            assertThrows(IOException.class, () -> node.append("failed", new byte[8], Instant.now().plusSeconds(2)));
            assertThrows(IOException.class, () -> node.append("after", new byte[8], Instant.now().plusSeconds(2)));
            assertThrows(IOException.class, () -> node.readIndex(Instant.now().plusSeconds(2)));
            assertEquals("0,0,0", state.text());
        }
    }
    @Test void appliedWatermarkIncludesNoopAndNodeOwnershipIsIdentityBased() throws Exception {
        var config = config(); var state = new ReplicatedStateMachine() {
            long watermark;
            @Override public void apply(long index, byte[] bytes) { }
            @Override public void appliedThrough(long index) { watermark = index; }
            @Override public byte[] snapshot() { return ByteBuffer.allocate(8).putLong(watermark).array(); }
            @Override public void restore(byte[] bytes) { watermark = ByteBuffer.wrap(bytes).getLong(); }
        };
        try (var node = new NetworkConsensusNode(config, state)) {
            assertTrue(node.hosts(state)); assertFalse(node.hosts(new NetworkConsensusNodeProcess.Counter()));
            node.start(); awaitLeader(node); long barrier = node.readIndex(Instant.now().plusSeconds(2));
            assertEquals(barrier, ByteBuffer.wrap(state.snapshot()).getLong());
        }
    }
    @Test void heartbeatCommitsOnlyItsMatchedPrefixRatherThanUnmatchedFollowerTail() throws Exception {
        var base = config();
        var peers = Map.of("a", new InetSocketAddress("127.0.0.1", 32101), "b", base.peers().get("a"));
        var config = new NetworkConsensusConfig("b", peers, Set.of("a", "b"), directory, new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 65536, 1 << 20);
        var state = new NetworkConsensusNodeProcess.Counter();
        try (var node = new NetworkConsensusNode(config, state)) {
            var receive = NetworkConsensusNode.class.getDeclaredMethod("receiveAppend", String.class, DataInputStream.class); receive.setAccessible(true);
            var monitor = NetworkConsensusNode.class.getDeclaredField("mutex"); monitor.setAccessible(true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(1); out.writeLong(0); out.writeLong(0); out.writeLong(0); out.writeInt(2);
            for (int i = 1; i <= 2; i++) { out.writeLong(i); out.writeLong(1); out.writeByte(1); out.writeUTF("entry" + i); out.writeInt(8); out.writeLong(1); }
            synchronized (monitor.get(node)) { receive.invoke(node, "a", new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))); }
            bytes.reset(); out.writeLong(2); out.writeLong(1); out.writeLong(1); out.writeLong(2); out.writeInt(0);
            synchronized (monitor.get(node)) { receive.invoke(node, "a", new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))); }
            assertEquals(1, node.status().commitIndex()); assertEquals("1,1,1", state.text());
        }
    }
    @Test void trustedMaintenanceEntriesApplyWithoutGrowingClientRequestJournal() throws Exception {
        var config = config(); var state = new NetworkConsensusNodeProcess.Counter();
        try (var node = new NetworkConsensusNode(config, state)) {
            node.start(); awaitLeader(node);
            for (int i = 0; i < 10; i++) node.appendControl(new byte[8], Instant.now().plusSeconds(3));
            var journal = NetworkConsensusNode.class.getDeclaredField("requests"); journal.setAccessible(true);
            assertEquals(0, ((Map<?, ?>) journal.get(node)).size());
            assertTrue(state.text().startsWith("10,0,"));
            assertThrows(IllegalArgumentException.class, () -> node.appendControl(new byte[config.maxFrameBytes()], Instant.now().plusSeconds(1)));
            assertThrows(IOException.class, () -> node.appendControl(new byte[8], Instant.now().minusSeconds(1)));
            node.snapshot();
        }
        var restored = new NetworkConsensusNodeProcess.Counter();
        try (var node = new NetworkConsensusNode(config, restored)) { assertTrue(restored.text().startsWith("10,0,")); }
    }
    private static void awaitLeader(NetworkConsensusNode node) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!node.status().leaderId().equals(node.status().nodeId())) {
            if (System.nanoTime() > deadline) fail("no leader"); java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
    }
}
