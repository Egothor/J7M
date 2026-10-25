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

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual topology and two independently durable resource groups test epoch-pinned ownership.
 */
class ManagedOwnershipMigrationIT {
    @TempDir Path directory;
    private static int port() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    @Test void originalSessionCannotWriteWhenTheSamePhysicalGroupBecomesOwnerAgainAtANewEpoch() throws Exception {
        var fixture = new ManagedEventSubscriptionProcessIT(); fixture.directory = directory;
        int topologyPort = port(), leftPort = port(), rightPort = port(), leftResource = port(), rightResource = port();
        byte[] adminKey = new byte[32]; Arrays.fill(adminKey, (byte) 1);
        try (var topology = fixture.new Child("topology", "org.j7m.middleware.distributed.PartitionNodeProcess", "topology", directory.resolve("topology").toString(), Integer.toString(port()), Integer.toString(topologyPort), "true", "0");
                var left = fixture.new Child("left", "org.j7m.middleware.distributed.PartitionNodeProcess", "left", directory.resolve("left").toString(), Integer.toString(port()), Integer.toString(leftPort), "false", "1", Integer.toString(leftResource));
                var right = fixture.new Child("right", "org.j7m.middleware.distributed.PartitionNodeProcess", "right", directory.resolve("right").toString(), Integer.toString(port()), Integer.toString(rightPort), "false", "0", Integer.toString(rightResource))) {
            var authority = new PartitionAdminClient(new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", topologyPort)), "admin", adminKey));
            var leftAdmin = new PartitionAdminClient(new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", leftPort)), "admin", adminKey));
            var rightAdmin = new PartitionAdminClient(new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", rightPort)), "admin", adminKey));
            authority.register("p", "left", deadline());
            var leftClient = new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", leftPort)), "app", new byte[32]);
            var rightClient = new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", rightPort)), "app", new byte[32]);
            var leftRead = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", leftResource)), "app", new byte[32], null);
            var rightRead = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", rightResource)), "app", new byte[32], null);
            var backend = ManagedOwnershipBackend.partition("p", "tenant/test", authority, Map.of(
                    "left", new ManagedOwnershipBackend.PartitionGroup(leftRead, leftClient), "right", new ManagedOwnershipBackend.PartitionGroup(rightRead, rightClient)));
            var pinned = backend.open(deadline()); assertEquals(1, pinned.partitionEpoch());
            assertEquals("OK", pinned.execute("ACQUIRE", "acquire", List.of("lease", "owner", "60000", "0"), deadline()).status());
            var lease = pinned.lease("lease", deadline()); assertNotNull(lease);
            var migration = new PartitionMigration(authority, Map.of("left", leftAdmin, "right", rightAdmin));
            migration.migrate("forward", "p", "right", 1, deadline());
            assertThrows(IOException.class, () -> pinned.read("protected", deadline()));
            migration.migrate("back", "p", "left", 2, deadline());
            assertEquals(3, authority.route("p", deadline()).epoch());
            var fresh = backend.open(deadline()); assertEquals(3, fresh.partitionEpoch());
            assertNotNull(fresh.lease("lease", deadline()));
            var arguments = List.of("lease", Long.toString(lease.fence()), Long.toString(lease.leadershipEpoch()), "protected", "forbidden");
            assertThrows(IOException.class, () -> pinned.execute("FENCED", "old-epoch", arguments, deadline()));
            assertNull(fresh.read("protected", deadline()));
            // Positive control: the migrated lease is still live; the old session is denied by its pinned epoch.
            assertEquals("OK", fresh.execute("FENCED", "current-epoch", List.of("lease", Long.toString(lease.fence()),
                    Long.toString(lease.leadershipEpoch()), "protected", "current"), deadline()).status());
            assertEquals("current", fresh.read("protected", deadline()).value());
            assertThrows(IOException.class, () -> pinned.lease("lease", deadline()));
        }
    }
}
