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
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsensusBackupIT {
    @TempDir Path directory;
    private static class State implements ReplicatedStateMachine {
        long index; final TreeMap<String, String> values = new TreeMap<>();
        @Override public void apply(long index, byte[] bytes) { this.index = index; String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8); values.put(value, value); }
        @Override public void appliedThrough(long index) { this.index = index; }
        @Override public byte[] snapshot() { try { var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.writeInt(1); out.writeLong(index); out.writeInt(values.size()); for (String value : values.keySet()) out.writeUTF(value); return bytes.toByteArray(); } catch (IOException e) { throw new UncheckedIOException(e); } }
        @Override public void restore(byte[] bytes) { try { var in = new DataInputStream(new ByteArrayInputStream(bytes)); if (in.readInt() != 1) throw new IllegalArgumentException("schema"); long restoredIndex = in.readLong(); int count = in.readInt(); var restored = new TreeMap<String, String>(); if (count < 0 || count > 10000) throw new IllegalArgumentException("count"); for (int i = 0; i < count; i++) { String value = in.readUTF(); restored.put(value, value); } if (in.available() != 0) throw new IllegalArgumentException("trailing"); index = restoredIndex; values.clear(); values.putAll(restored); } catch (IOException e) { throw new IllegalArgumentException(e); } }
    }
    private NetworkConsensusConfig config(String group, Path disk) throws IOException { int port; try (ServerSocket server = new ServerSocket(0)) { port = server.getLocalPort(); } return new NetworkConsensusConfig("node", Map.of("node", new InetSocketAddress("127.0.0.1", port)), Set.of("node"), disk, new byte[32], Duration.ofMillis(300), Duration.ofMillis(20), 65536, 4194304, null, group); }
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private static void elected(NetworkConsensusNode node) throws IOException { long end = System.nanoTime() + 10000000000L; while (!node.status().nodeId().equals(node.status().leaderId())) { if (System.nanoTime() > end) throw new IOException("Election deadline"); Thread.onSpinWait(); } }
    @Test void quorumCheckpointRestoresFreshGenerationMembershipAndIdentityJournal() throws Exception {
        var sourceConfig = config("source", directory.resolve("source")); var state = new State(); Path backup = directory.resolve("backup"); ConsensusBackup.Metadata metadata;
        try (var source = new NetworkConsensusNode(sourceConfig, state)) {
            source.start(); elected(source); long committed = source.append("first", "value".getBytes(java.nio.charset.StandardCharsets.UTF_8), deadline()); metadata = ConsensusBackup.export(source, backup, "state/v1", deadline()); assertTrue(metadata.index() >= committed); assertEquals(Set.of("node"), metadata.members()); assertEquals(1, metadata.requestIdentities());
            assertEquals(metadata, ConsensusBackup.inspect(backup, "state/v1")); assertEquals("source", metadata.sourceGroup()); assertFalse(metadata.checkpointSha256().isBlank());
            source.append("later", "not-in-backup".getBytes(java.nio.charset.StandardCharsets.UTF_8), deadline());
        }
        var targetConfig = config("recovery", directory.resolve("recovery")); assertEquals(metadata, ConsensusBackup.restore(backup, targetConfig, "state/v1", metadata.index(), State::new)); var restored = new State();
        try (var target = new NetworkConsensusNode(targetConfig, restored)) { target.start(); elected(target); assertEquals(Set.of("value"), restored.values.keySet()); long replay = target.append("first", "value".getBytes(java.nio.charset.StandardCharsets.UTF_8), deadline()); assertTrue(replay <= metadata.index()); assertThrows(IOException.class, () -> target.append("first", "different".getBytes(java.nio.charset.StandardCharsets.UTF_8), deadline())); assertEquals(1, restored.values.size()); }
    }
    @Test void corruptionSchemaFloorLiveTargetAndValidatorFailuresNeverPublishRestore() throws Exception {
        Path backup = directory.resolve("backup"); ConsensusBackup.Metadata metadata;
        var sourceConfig = config("source", directory.resolve("source"));
        try (var source = new NetworkConsensusNode(sourceConfig, new State())) { source.start(); elected(source); source.append("id", new byte[]{1}, deadline()); metadata = ConsensusBackup.export(source, backup, "state/v1", deadline()); assertThrows(IOException.class, () -> ConsensusBackup.export(source, backup, "state/v1", deadline())); }
        assertThrows(IOException.class, () -> ConsensusBackup.inspect(backup, "state/v2"));
        var target = config("new", directory.resolve("fresh"));
        assertThrows(IOException.class, () -> ConsensusBackup.restore(backup, target, "state/v1", metadata.index() + 1, State::new)); assertFalse(Files.exists(target.directory()));
        assertThrows(IOException.class, () -> ConsensusBackup.restore(backup, target, "state/v1", 0, () -> new State() { @Override public void restore(byte[] bytes) { throw new IllegalArgumentException("bad resource"); } })); assertFalse(Files.exists(target.directory()));
        assertThrows(IllegalArgumentException.class, () -> ConsensusBackup.restore(backup, sourceConfig, "state/v1", 0, State::new));
        Files.createDirectory(target.directory()); Files.writeString(target.directory().resolve("sentinel"), "preserve"); assertThrows(IOException.class, () -> ConsensusBackup.restore(backup, target, "state/v1", 0, State::new)); assertEquals("preserve", Files.readString(target.directory().resolve("sentinel")));
        byte[] image = Files.readAllBytes(backup.resolve("checkpoint.bin")); image[image.length - 1] ^= 1; Files.write(backup.resolve("checkpoint.bin"), image); assertThrows(IOException.class, () -> ConsensusBackup.inspect(backup, "state/v1"));
    }
    @Test void corruptManifestSymlinksAndInsufficientQuorumFailClosed() throws Exception {
        Path backup = directory.resolve("backup"); var configuration = config("source", directory.resolve("source"));
        try (var source = new NetworkConsensusNode(configuration, new State())) { assertThrows(IOException.class, () -> ConsensusBackup.export(source, backup, "state/v1", Instant.now().plusMillis(50))); assertFalse(Files.exists(backup)); source.start(); elected(source); ConsensusBackup.export(source, backup, "state/v1", deadline()); }
        Path alias = directory.resolve("alias"); Files.createSymbolicLink(alias, backup); assertThrows(IOException.class, () -> ConsensusBackup.inspect(alias, "state/v1"));
        byte[] manifest = Files.readAllBytes(backup.resolve("manifest.bin")); manifest[0] ^= 1; Files.write(backup.resolve("manifest.bin"), manifest); assertThrows(IOException.class, () -> ConsensusBackup.inspect(backup, "state/v1"));
    }
}
