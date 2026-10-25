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
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.j7m.middleware.consensus.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartitionPermissionBoundaryIT {
    @TempDir Path directory;
    private static byte[] key(int id) { byte[] key = new byte[32]; Arrays.fill(key, (byte) id); return key; }
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private final class Host implements AutoCloseable {
        final NetworkConsensusNode node; final PartitionServer server;
        Host(String id, TransferableResource resource) throws Exception {
            int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            var peers = Map.of(id, new InetSocketAddress("127.0.0.1", port));
            var config = new NetworkConsensusConfig(id, peers, peers.keySet(), directory.resolve(id), key(7), Duration.ofMillis(450), Duration.ofMillis(30), 1048576, 134217728);
            var state = new PartitionStateMachine(id, id, 1, resource, key(8)); node = new NetworkConsensusNode(config, state); node.start();
            var clock = new MeasuredClockHealth(System::currentTimeMillis, System::nanoTime, System::currentTimeMillis, () -> 1);
            clock.sample(); clock.sample();
            server = new PartitionServer(new InetSocketAddress("127.0.0.1", 0), node, state, null, Map.of(
                    "app", new PartitionServer.Binding("tenant", key(0), false, false),
                    "admin-only", new PartitionServer.Binding("tenant", key(1), true, false),
                    "maintenance-only", new PartitionServer.Binding("tenant", key(2), false, true)), clock, null);
            client("app").status(deadline());
        }
        PartitionClient client(String principal) { return new PartitionClient(List.of(server.address()), principal, key(principal.equals("app") ? 0 : principal.equals("admin-only") ? 1 : 2)); }
        ReplicatedQueue queue(String principal) { var endpoint = client(principal); return new ReplicatedQueue("queue", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1)); }
        DeduplicatedRpc rpc(String principal) { var endpoint = client(principal); return new DeduplicatedRpc(new ExecutionClient("execution", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1))); }
        @Override public void close() throws Exception { try { server.close(); } finally { node.close(); } }
    }
    @Test void queueRedriveAuthorityNeverBroadensIndependentExecutionReconciliationCapability() throws Exception {
        try (Host queueHost = new Host("queue", new QueueStateMachine("queue")); Host executionHost = new Host("execution", new ExecutionStateMachine("execution"))) {
            var queue = queueHost.queue("app"); queue.join("join", "g", "m", 60000, deadline());
            queue.send("send", "q", "bad", new byte[]{1}, 1, deadline()); queue.claim("claim", "q", "g", "m", 1, 60000, deadline());
            queue.nack("nack", "q", "g", "m", 1, "bad", 1, deadline());
            assertThrows(SecurityException.class, () -> queueHost.queue("maintenance-only").redrive("denied", "q", "bad", 1, "child", 1, deadline()));
            assertEquals("REDRIVEN\t2", queueHost.queue("admin-only").redrive("allowed", "q", "bad", 1, "child", 1, deadline()));
            String hash = "a".repeat(64); var claim = executionHost.rpc("app").claim("rpc-claim", "rpc", hash, 60000, true, deadline());
            long fence = Long.parseLong(claim.fields().get("fence"));
            assertThrows(SecurityException.class, () -> executionHost.rpc("admin-only").reconcile("reconcile-denied", "rpc", hash, fence, "done", "", List.of(), deadline()));
            assertEquals("COMPLETED", executionHost.rpc("maintenance-only").reconcile("reconcile-allowed", "rpc", hash, fence, "done", "", List.of(), deadline()).status());
        }
    }
}
