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
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplicatedStateMachineTermTest {
    @TempDir Path directory;

    @Test void defaultTermCallbackPreservesLegacyImplementationsAndTheirFailures() {
        var legacy = new RecordingMachine();
        legacy.apply(7, 3, new byte[] {9});
        assertEquals(7, legacy.index);
        assertArrayEquals(new byte[] {9}, legacy.command);
        assertThrows(IllegalArgumentException.class, () -> legacy.apply(8, 4, new byte[0]));
    }

    @Test void committedAndReplayedCallbacksUseDurableEntryTermRatherThanCurrentTerm() throws Exception {
        var config = new NetworkConsensusConfig("b", Map.of(
                "a", new InetSocketAddress("127.0.0.1", 32101),
                "b", new InetSocketAddress("127.0.0.1", 32102)), Set.of("a", "b"), directory,
                new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 65536, 1 << 20);
        var machine = new TermMachine();
        try (var node = new NetworkConsensusNode(config, machine)) {
            var receive = NetworkConsensusNode.class.getDeclaredMethod("receiveAppend", String.class, DataInputStream.class);
            receive.setAccessible(true);
            var mutex = NetworkConsensusNode.class.getDeclaredField("mutex");
            mutex.setAccessible(true);
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            // A newer leader commits an entry retained from an earlier leadership term.
            out.writeLong(5); out.writeLong(0); out.writeLong(0); out.writeLong(1); out.writeInt(1);
            out.writeLong(1); out.writeLong(3); out.writeByte(1); out.writeUTF("command");
            out.writeInt(1); out.writeByte(9);
            synchronized (mutex.get(node)) {
                receive.invoke(node, "a", new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            }
            assertEquals(5, node.status().term());
            assertEquals(1, machine.index);
            assertEquals(3, machine.term);
            assertArrayEquals(new byte[] {9}, machine.command);
        }
        var replayed = new TermMachine();
        try (var node = new NetworkConsensusNode(config, replayed)) {
            assertEquals(5, node.status().term());
            assertEquals(1, replayed.index);
            assertEquals(3, replayed.term);
            assertArrayEquals(new byte[] {9}, replayed.command);
        }
    }

    private static class RecordingMachine implements ReplicatedStateMachine {
        long index;
        byte[] command;
        @Override public void apply(long index, byte[] command) {
            if (command.length == 0) throw new IllegalArgumentException("empty command");
            this.index = index;
            this.command = command.clone();
        }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restore(byte[] snapshot) { }
    }

    private static final class TermMachine extends RecordingMachine {
        long term;
        @Override public void apply(long index, long term, byte[] command) {
            super.apply(index, command);
            this.term = term;
        }
    }
}
