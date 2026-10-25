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

package org.j7m.middleware.application.distributed;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import org.j7m.middleware.consensus.NetworkConsensusConfig;
import org.j7m.middleware.distributed.PartitionServer;
import org.j7m.middleware.distributed.ReplicatedResourceClient;

/**
 * Trusted local hosting configuration for one encoded partition, never application annotation data.
 * The native configuration's initialMembers is the immutable ORIGINAL bootstrap seed even after
 * membership changes. Its groupId is only a bounded operator base; the factory replaces it with
 * the exact program/bootstrap fingerprint before opening storage. Local identity, addresses and
 * secrets may differ between members without changing that fingerprint. Credentials are borrowed
 * configuration values and are defensively copied by their native value types.
 * Native snapshot capacity is128MiB, covering retained native transfer images and tail, not only
 * the16MiB resource snapshot.
 * @param consensus local native transport/storage configuration and immutable original voter seed
 * @param partition stable partition identity
 * @param initialEpoch original bootstrap epoch, zero for an inactive migration target
 * @param resourceScope fixed authenticated scope owning this complete encoded state
 * @param maximumRequests durable receipt capacity,1..10000; no implicit eviction
 * @param serviceAddress explicit partition service bind address, port zero permitted
 * @param principals bounded authenticated service identities; no principal crosses resourceScope
 * @param authorityKey trusted topology authority key of at least32bytes
 * @param clock authenticated clock health source required by the native partition service
 * @param serviceTls mutual TLS context, mandatory outside loopback
 */
public record ManagedPartitionProfile(NetworkConsensusConfig consensus, String partition, long initialEpoch,
        String resourceScope, int maximumRequests, InetSocketAddress serviceAddress,
        Map<String, PartitionServer.Binding> principals, byte[] authorityKey,
        ReplicatedResourceClient.ClockHealth clock, SSLContext serviceTls) {
    /**
     * Validates the complete host profile before component construction or storage access.
     * @throws IllegalArgumentException for unbounded, insecure, cross-scope or inconsistent settings
     * @throws NullPointerException for missing settings
     */
    public ManagedPartitionProfile {
        Objects.requireNonNull(consensus); Objects.requireNonNull(clock); Objects.requireNonNull(serviceAddress);
        principals = Map.copyOf(principals); authorityKey = authorityKey.clone();
        identity(partition); identity(resourceScope);
        if (!consensus.groupId().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,47}") || initialEpoch < 0
                || maximumRequests < 1 || maximumRequests > 10000 || authorityKey.length < 32
                || principals.isEmpty() || principals.size() > 64 || serviceAddress.isUnresolved()
                || serviceTls == null && !serviceAddress.getAddress().isLoopbackAddress()
                || consensus.maxFrameBytes() < ManagedPartitionResource.MAX_COMMAND_BYTES + 4096
                || consensus.maxSnapshotBytes() != 128 * 1024 * 1024
                || consensus.initialMembers().size() > 64 || consensus.peers().size() > 64
                || consensus.peers().values().stream().anyMatch(InetSocketAddress::isUnresolved)) {
            throw new IllegalArgumentException("Invalid managed partition profile");
        }
        consensus.peers().keySet().forEach(ManagedPartitionProfile::identity);
        var keys = new java.util.ArrayList<byte[]>();
        for (var entry : principals.entrySet()) {
            identity(entry.getKey());
            if (keys.stream().anyMatch(key -> java.security.MessageDigest.isEqual(key, entry.getValue().key()))) {
                throw new IllegalArgumentException("Partition principal keys must be distinct");
            }
            keys.add(entry.getValue().key());
            if (!entry.getValue().scope().equals(resourceScope)) throw new IllegalArgumentException("Partition principal scope mismatch");
        }
    }
    /**
     * Returns independent topology authority material.
     * @return copied secret bytes, never intended for application injection
     */
    @Override public byte[] authorityKey() { return authorityKey.clone(); }
    /**
     * Returns a fixed diagnostic without addresses, configuration or credentials.
     * @return redacted profile description
     */
    @Override public String toString() { return "ManagedPartitionProfile[trusted,redacted]"; }
    private static void identity(final String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid partition identity");
        }
    }
}
