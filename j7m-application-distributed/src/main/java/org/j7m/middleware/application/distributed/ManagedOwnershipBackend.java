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

import java.io.*;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.distributed.*;

/**
 * Opaque trusted lease-and-sink source. A claim pins one session; an existing context never follows
 * a new partition owner. Every protected mutation uses native FENCED in the exact resource that owns
 * its lease. This adapter adds no native protocol and supplies no arbitrary-effect callback.
 */
public final class ManagedOwnershipBackend {
    /**
     * Borrowed views of the same physical partition-wrapped resource and authorized scope.
     * The host must configure this pair correctly. A newly generated private owner identity plus
     * exact result version/fence checks reject a mismatched lease view before application entry.
     * @param resource fixed group's authorized map/lease read facade
     * @param partition same group's authorized epoch-aware command/query facade
     */
    public record PartitionGroup(ResourceEndpoint resource, PartitionEndpoint partition) {
        /**
         * Requires both borrowed concrete capability views.
         * @param resource fixed lease-read endpoint
         * @param partition same resource's epoch-aware endpoint
         * @throws NullPointerException for null input
         */
        public PartitionGroup { Objects.requireNonNull(resource); Objects.requireNonNull(partition); }
        /**
         * Omits endpoint and credential data.
         * @return fixed safe label
         */
        @Override public String toString() { return "OwnershipPartitionGroup[borrowed]"; }
    }
    @FunctionalInterface private interface Open { Session open(Instant deadline) throws IOException; }
    interface Session {
        ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException;
        ReplicatedResources.Lease lease(String key, Instant deadline) throws IOException;
        ReplicatedResources.Value read(String key, Instant deadline) throws IOException;
        long partitionEpoch();
    }
    private final Open source;
    private ManagedOwnershipBackend(Open source) { this.source = source; }

    /**
     * Borrows a fixed authoritative resource group. Native leader changes within that same group
     * remain supported; migrating partitions must instead use the explicitly pinned partition form.
     * The host must not pass an endpoint that silently switches resource groups.
     * @param endpoint authorized non-migrating group endpoint
     * @return opaque borrowed backend, without starting workers or acquiring a lease
     * @throws NullPointerException for null endpoint
     * @throws IllegalArgumentException for the known local moving-partition facade
     */
    public static ManagedOwnershipBackend group(ResourceEndpoint endpoint) {
        Objects.requireNonNull(endpoint);
        if (endpoint instanceof PartitionResourceEndpoint) throw new IllegalArgumentException("Moving partitions require an epoch-pinned ownership source");
        return new ManagedOwnershipBackend(deadline -> new Session() {
            public ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant end) throws IOException { return endpoint.execute(operation, request, arguments, end); }
            public ReplicatedResources.Lease lease(String key, Instant end) throws IOException { return endpoint.lease(key, end); }
            public ReplicatedResources.Value read(String key, Instant end) throws IOException { return endpoint.read(key, end); }
            public long partitionEpoch() { return 0; }
        });
    }

    /**
     * Resolves topology only before each new claim, then pins all mutations to that ownership epoch.
     * A frozen/moved group revokes the existing claim rather than replaying its body against a new
     * owner. Both native lease and FENCED commands use the same pinned inner resource and scope.
     * @param partitionId stable partition identity
     * @param authenticatedScope exact native authenticated scope, never taken from application metadata
     * @param topology borrowed authoritative topology read client
     * @param groups at most64 fixed, authorized matching resource/partition pairs
     * @return opaque source supporting new claims after authoritative handoff
     * @throws IllegalArgumentException for invalid identities or inventory
     * @throws NullPointerException for null collaborators
     */
    public static ManagedOwnershipBackend partition(String partitionId, String authenticatedScope,
            PartitionAdminClient topology, Map<String, PartitionGroup> groups) {
        Objects.requireNonNull(topology); var inventory = Map.copyOf(groups);
        if (partitionId == null || !partitionId.matches("[A-Za-z0-9_.-]{1,128}") || authenticatedScope == null
                || !authenticatedScope.matches("[A-Za-z0-9_.:/-]{1,128}") || inventory.isEmpty() || inventory.size() > 64
                || inventory.keySet().stream().anyMatch(name -> !name.matches("[A-Za-z0-9_.-]{1,128}"))) {
            throw new IllegalArgumentException("Invalid pinned ownership source");
        }
        return new ManagedOwnershipBackend(deadline -> {
            var route = topology.route(partitionId, deadline); var group = inventory.get(route.ownerGroup());
            if (group == null || !route.partition().equals(partitionId) || route.epoch() < 1) throw new IOException("Ownership route is unavailable");
            var session = new Pinned(partitionId, route.ownerGroup(), route.epoch(), authenticatedScope, group);
            session.verify(deadline); return session;
        });
    }
    Session open(Instant deadline) throws IOException { return source.open(deadline); }

    private record Pinned(String identity, String groupId, long epoch, String scope, PartitionGroup group) implements Session {
        void verify(Instant deadline) throws IOException {
            var status = group.partition().status(deadline);
            if (!status.partition().equals(identity) || !status.group().equals(groupId) || status.epoch() != epoch
                    || !status.phase().equals("ACTIVE")) throw new IOException("Ownership epoch is unavailable; outcome UNKNOWN");
        }
        public ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
            byte[] command = new ResourceCommand(operation, scope, request, arguments).encode();
            byte[] query = new ResourceCommand("QUERY_RESULT", scope, "ownership-result", List.of(request)).encode();
            var reply = group.partition().execute(epoch, request, command, query, deadline); check(reply);
            try (var input = new DataInputStream(new ByteArrayInputStream(reply.payload()))) {
                if (!input.readBoolean()) throw new IOException("Ownership result is unavailable; outcome UNKNOWN");
                var result = new ReplicatedResources.Result(input.readUTF(), input.readUTF(), input.readLong());
                if (input.available() != 0) throw new IOException("Invalid ownership result frame"); return result;
            }
        }
        public ReplicatedResources.Lease lease(String key, Instant deadline) throws IOException {
            verify(deadline); var lease = group.resource().lease(key, deadline); verify(deadline); return lease;
        }
        public ReplicatedResources.Value read(String key, Instant deadline) throws IOException {
            var reply = group.partition().query(epoch, new ResourceCommand("QUERY_READ", scope, "ownership-read", List.of(key)).encode(), deadline); check(reply);
            try (var input = new DataInputStream(new ByteArrayInputStream(reply.payload()))) {
                var value = input.readBoolean() ? new ReplicatedResources.Value(input.readUTF(), input.readLong()) : null;
                if (input.available() != 0) throw new IOException("Invalid ownership value frame"); return value;
            }
        }
        private void check(PartitionReply reply) throws IOException {
            if (!reply.status().equals("OK") || reply.epoch() != epoch) throw new IOException("Ownership epoch is unavailable; outcome UNKNOWN");
        }
        public long partitionEpoch() { return epoch; }
    }
    /**
     * Omits source and authorization data from incidental diagnostics.
     * @return fixed safe label
     */
    @Override public String toString() { return "ManagedOwnershipBackend[host-owned]"; }
}
