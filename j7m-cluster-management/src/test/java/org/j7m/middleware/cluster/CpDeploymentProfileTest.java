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

package org.j7m.middleware.cluster;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.j7m.middleware.consensus.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CpDeploymentProfileTest {
    @TempDir Path directory;
    private static Map<String, InetSocketAddress> roster(int size) throws IOException {
        var ports = new ArrayList<ServerSocket>(); var roster = new LinkedHashMap<String, InetSocketAddress>();
        try { for (int i = 0; i < size; i++) { var socket = new ServerSocket(0); ports.add(socket); roster.put("node-" + i, new InetSocketAddress("127.0.0.1", socket.getLocalPort())); } }
        finally { for (var socket : ports) socket.close(); }
        return roster;
    }
    @ParameterizedTest @EnumSource(CpDeploymentProfile.class)
    void configurationHasQualifiedBoundsAndDefensiveAuthorizationCopies(CpDeploymentProfile profile) throws Exception {
        var roster = roster(profile.voters()); byte[] secret = new byte[32]; secret[0] = 7;
        var config = profile.configuration("node-0", roster, directory.resolve("node"), secret, null, "explicit-group");
        assertEquals(profile.voters(), config.initialMembers().size()); assertEquals(roster.keySet(), config.initialMembers());
        assertEquals(Duration.ofMillis(500), config.electionTimeout()); assertEquals(Duration.ofMillis(20), config.heartbeatInterval());
        assertEquals(1048576, config.maxFrameBytes()); assertEquals(134217728, config.maxSnapshotBytes());
        assertEquals(Duration.ofMillis(500), profile.clockUncertaintyAssumption()); assertEquals(Duration.ofSeconds(30), profile.recoveryAssumption());
        secret[0] = 9; roster.clear(); assertEquals(7, config.authenticationKey()[0]); assertEquals(profile.voters(), config.peers().size());
        byte[] copy = config.authenticationKey(); copy[0] = 10; assertEquals(7, config.authenticationKey()[0]);
        assertEquals("explicit-group", config.groupId()); assertFalse(Files.exists(config.directory()));
    }
    @ParameterizedTest @EnumSource(CpDeploymentProfile.class)
    void configurationRejectsWrongVotersDuplicateEndpointsAndInsecureRemoteTransport(CpDeploymentProfile profile) throws Exception {
        var valid = roster(profile.voters());
        var smaller = new LinkedHashMap<>(valid); smaller.remove("node-0");
        assertThrows(IllegalArgumentException.class, () -> profile.configuration("node-1", smaller, directory, new byte[32], null, "group"));
        var duplicate = new LinkedHashMap<>(valid); duplicate.put("node-1", duplicate.get("node-0"));
        assertThrows(IllegalArgumentException.class, () -> profile.configuration("node-0", duplicate, directory, new byte[32], null, "group"));
        var zero = new LinkedHashMap<>(valid); zero.put("node-0", new InetSocketAddress("127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class, () -> profile.configuration("node-0", zero, directory, new byte[32], null, "group"));
        var remote = new LinkedHashMap<>(valid); remote.put("node-0", new InetSocketAddress("192.0.2.1", 12345));
        assertThrows(IllegalArgumentException.class, () -> profile.configuration("node-0", remote, directory, new byte[32], null, "group"));
        assertThrows(IllegalArgumentException.class, () -> profile.configuration("unknown", valid, directory, new byte[32], null, "group"));
        assertThrows(IllegalArgumentException.class, () -> profile.configuration("node-0", valid, directory, new byte[31], null, "group"));
        assertThrows(IllegalArgumentException.class, () -> profile.configuration("node-0", valid, directory, new byte[32], null, " "));
    }
    private static final class State implements ReplicatedStateMachine {
        long value;
        @Override public void apply(long index, byte[] command) { value += command[0]; }
        @Override public byte[] snapshot() { return ByteBuffer.allocate(8).putLong(value).array(); }
        @Override public void restore(byte[] snapshot) { if (snapshot.length != 8) throw new IllegalArgumentException("Snapshot size"); value = ByteBuffer.wrap(snapshot).getLong(); }
    }
    @ParameterizedTest @EnumSource(CpDeploymentProfile.class)
    void configurationStartsActualTcpQuorumAndRestartsExistingDurableDirectories(CpDeploymentProfile profile) throws Exception {
        var roster = roster(profile.voters()); var nodes = new ArrayList<NetworkConsensusNode>(); var states = new ArrayList<State>();
        try {
            start(profile, roster, nodes, states); NetworkConsensusNode leader = leader(nodes);
            long index = leader.append("committed", new byte[]{3}, Instant.now().plusSeconds(10)); assertTrue(index > 0);
            leader.readIndex(Instant.now().plusSeconds(10)); assertEquals(3, states.get(nodes.indexOf(leader)).value);
            for (var node : nodes) node.close(); nodes.clear(); states.clear();
            start(profile, roster, nodes, states); leader = leader(nodes);
            assertEquals(index, leader.append("committed", new byte[]{3}, Instant.now().plusSeconds(10)));
            leader.readIndex(Instant.now().plusSeconds(10)); assertEquals(3, states.get(nodes.indexOf(leader)).value);
        } finally { for (var node : nodes) node.close(); }
    }
    private void start(CpDeploymentProfile profile, Map<String, InetSocketAddress> roster, List<NetworkConsensusNode> nodes, List<State> states) throws Exception {
        for (String id : roster.keySet()) { var state = new State(); var config = profile.configuration(id, roster, directory.resolve(id), new byte[32], null, "profile-" + profile.name()); var node = new NetworkConsensusNode(config, state); states.add(state); nodes.add(node); node.start(); }
    }
    private static NetworkConsensusNode leader(List<NetworkConsensusNode> nodes) throws IOException {
        long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do { for (var node : nodes) if (node.status().nodeId().equals(node.status().leaderId())) { try { node.readIndex(Instant.now().plusMillis(500)); return node; } catch (IOException notReady) { Thread.onSpinWait(); } } } while (System.nanoTime() < limit);
        throw new IOException("Configured TCP quorum did not elect an authoritative leader");
    }
}
