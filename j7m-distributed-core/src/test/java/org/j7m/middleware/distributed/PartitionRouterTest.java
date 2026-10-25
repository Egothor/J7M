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
import java.net.*;
import java.time.*;
import java.nio.file.*;
import org.j7m.middleware.consensus.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartitionRouterTest {
    @TempDir Path directory;
    @Test void authoritativeRoutingIsStableAcrossCallerPartitionOrderAndRejectsInvalidKeys() throws Exception {
        int port; try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        var peers = Map.of("node", new InetSocketAddress("127.0.0.1", port)); var topology = new PartitionTopology(new byte[32]);
        var config = new NetworkConsensusConfig("node", peers, peers.keySet(), directory, new byte[32], Duration.ofMillis(450), Duration.ofMillis(70), 1048576, 16777216);
        var clock = new MeasuredClockHealth(System::currentTimeMillis, System::nanoTime, System::currentTimeMillis, () -> 1);
        try (var node = new NetworkConsensusNode(config, topology)) {
            node.start(); try (var server = new PartitionServer(new InetSocketAddress("127.0.0.1", 0), node, null, topology,
                    Map.of("admin", new PartitionServer.Binding("tenant", new byte[32], true, false)), clock, null)) {
                var authority = new PartitionAdminClient(new PartitionClient(List.of(server.address()), "admin", new byte[32]));
                Instant deadline = Instant.now().plusSeconds(10); authority.register("a", "left", deadline); authority.register("b", "right", deadline);
                var first = new PartitionRouter(List.of("a", "b"), authority); var reversed = new PartitionRouter(List.of("b", "a"), authority);
                for (String key : List.of("", "user1", "user2", "žluťoučký")) assertEquals(first.route(key, deadline), reversed.route(key, deadline));
                assertThrows(IllegalArgumentException.class, () -> first.route("x".repeat(65537), deadline));
                assertThrows(IllegalArgumentException.class, () -> new PartitionRouter(List.of(), authority));
                assertThrows(IllegalArgumentException.class, () -> new PartitionRouter(List.of("a", "a"), authority));
            }
        }
    }
}
