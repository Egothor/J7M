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
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
/**
 * Immutable bootstrap and bounded authenticated transport settings. The shared authentication
 * key grants full peer authority and must come from protected deployment configuration. Non-loopback endpoints require mutual TLS. A null TLS context is a loopback-only
 * development profile; its HMAC authenticated frames are not encrypted.
 * Discovery never changes the voting set. A nonvoter may start as a learner.
 * @param nodeId local identity, present in peers
 * @param peers authorized identities and fixed endpoints, including learners
 * @param initialMembers initial voters used only on first bootstrap
 * @param directory exclusively owned durable directory
 * @param authenticationKey at least 32 secret bytes
 * @param electionTimeout minimum election timeout, at least 150 ms
 * @param heartbeatInterval heartbeat interval, positive and less than one third of election timeout
 * @param maxFrameBytes maximum authenticated frame bytes, at least 4096
 * @param maxSnapshotBytes maximum application snapshot bytes, at least maxFrameBytes
 * @param tlsContext mutual TLS context, or null only for loopback development
 * @param groupId stable group identity, distinct for independent replication groups
 */
public record NetworkConsensusConfig(String nodeId, Map<String, InetSocketAddress> peers,
        Set<String> initialMembers, Path directory, byte[] authenticationKey,
        Duration electionTimeout, Duration heartbeatInterval, int maxFrameBytes, int maxSnapshotBytes, SSLContext tlsContext, String groupId) {
    /**
     * Validates and defensively copies settings.
     * @param nodeId local identity
     * @param peers authorized endpoints
     * @param initialMembers initial electorate
     * @param directory durable storage
     * @param authenticationKey shared secret
     * @param electionTimeout minimum election timeout
     * @param heartbeatInterval heartbeat period
     * @param maxFrameBytes frame byte limit
     * @param maxSnapshotBytes snapshot byte limit
     * @param tlsContext mutual TLS context or null on loopback
     * @param groupId stable replication group identity
     * @throws IllegalArgumentException if settings violate bounds
     */
    public NetworkConsensusConfig {
        peers = Map.copyOf(peers); initialMembers = Set.copyOf(initialMembers);
        authenticationKey = authenticationKey.clone();
        if (groupId == null || groupId.isBlank() || groupId.length() > 256) throw new IllegalArgumentException("invalid group identity");
        if (nodeId == null || nodeId.isBlank() || !peers.containsKey(nodeId) || initialMembers.isEmpty()
                || !peers.keySet().containsAll(initialMembers) || authenticationKey.length < 32
                || electionTimeout.toMillis() < 150 || heartbeatInterval.toMillis() < 1
                || heartbeatInterval.multipliedBy(3).compareTo(electionTimeout) >= 0
                || maxFrameBytes < 4096 || maxSnapshotBytes < maxFrameBytes || directory == null)
            throw new IllegalArgumentException("invalid consensus configuration");
        if (tlsContext == null && peers.values().stream().anyMatch(address -> address.getAddress() == null || !address.getAddress().isLoopbackAddress()))
            throw new IllegalArgumentException("non-loopback consensus requires mutual TLS");
    }
    /**
     * Creates a loopback-only authenticated development profile.
     * @param nodeId local identity
     * @param peers authorized loopback endpoints
     * @param initialMembers bootstrap voters
     * @param directory storage directory
     * @param authenticationKey shared secret
     * @param electionTimeout election timeout
     * @param heartbeatInterval heartbeat interval
     * @param maxFrameBytes frame size limit
     * @param maxSnapshotBytes snapshot size limit
     */
    public NetworkConsensusConfig(String nodeId, Map<String, InetSocketAddress> peers,
            Set<String> initialMembers, Path directory, byte[] authenticationKey,
            Duration electionTimeout, Duration heartbeatInterval, int maxFrameBytes, int maxSnapshotBytes) {
        this(nodeId, peers, initialMembers, directory, authenticationKey, electionTimeout,
                heartbeatInterval, maxFrameBytes, maxSnapshotBytes, null, defaultGroup(peers));
    }
    /**
     * Creates a group whose identity is derived from its immutable authorized endpoint roster.
     * @param nodeId local identity
     * @param peers authorized endpoints, identical on every member
     * @param initialMembers bootstrap electorate
     * @param directory storage directory
     * @param authenticationKey shared secret
     * @param electionTimeout minimum election timeout
     * @param heartbeatInterval heartbeat interval
     * @param maxFrameBytes frame limit
     * @param maxSnapshotBytes snapshot limit
     * @param tlsContext mutual TLS context or null for loopback
     */
    public NetworkConsensusConfig(String nodeId, Map<String, InetSocketAddress> peers,
            Set<String> initialMembers, Path directory, byte[] authenticationKey,
            Duration electionTimeout, Duration heartbeatInterval, int maxFrameBytes, int maxSnapshotBytes, SSLContext tlsContext) {
        this(nodeId, peers, initialMembers, directory, authenticationKey, electionTimeout,
                heartbeatInterval, maxFrameBytes, maxSnapshotBytes, tlsContext, defaultGroup(peers));
    }
    private static String defaultGroup(Map<String, InetSocketAddress> peers) {
        String roster = new java.util.TreeMap<>(peers).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().getHostString() + ":" + e.getValue().getPort()).collect(java.util.stream.Collectors.joining(";"));
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(roster.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * Returns an independent secret copy.
     * @return authentication key copy
     */
    @Override public byte[] authenticationKey() { return authenticationKey.clone(); }
}
