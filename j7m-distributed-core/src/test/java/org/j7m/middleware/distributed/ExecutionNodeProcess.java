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

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.consensus.*;

/**
 * Separate JVM execution replica fixture with synthetic loopback credentials.
 */
public final class ExecutionNodeProcess {
    private static byte[] adminKey() { byte[] key = new byte[32]; Arrays.fill(key, (byte) 1); return key; }
    /**
     * Hosts a transferable execution replica.
     * @param args id, data directory, comma-separated Raft peers and service port
     * @throws Exception on fixture startup or control failure
     */
    public static void main(String[] args) throws Exception {
        Map<String, InetSocketAddress> peers = new TreeMap<>();
        for (String peer : args[2].split(",")) { String[] p = peer.split(":"); peers.put(p[0], new InetSocketAddress("127.0.0.1", Integer.parseInt(p[1]))); }
        String group = args.length > 4 ? args[4] : "broker";
        long epoch = args.length > 5 ? Long.parseLong(args[5]) : 1;
        var armed = new java.util.concurrent.atomic.AtomicReference<String>();
        var queue = new ExecutionStateMachine("execution", request -> {
            if (request.equals(armed.get())) {
                System.out.println("COMMITTED " + request); System.out.flush();
                try { new java.util.concurrent.CountDownLatch(1).await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        });
        PartitionStateMachine state = group.equals("topology") ? null : new PartitionStateMachine("execution", group, epoch, queue, new byte[32]);
        PartitionTopology topology = group.equals("topology") ? new PartitionTopology(new byte[32]) : null;
        ReplicatedStateMachine machine = state == null ? topology : state;
        // The load fixture uses the supported low-latency heartbeat; election and recovery bounds remain unchanged.
        var config = new NetworkConsensusConfig(args[0], peers, peers.keySet(), Path.of(args[1]), new byte[32],
                Duration.ofMillis(500), Duration.ofMillis(5), 1_048_576, 128 * 1024 * 1024);
        AtomicLong offset = new AtomicLong();
        var clock = new MeasuredClockHealth(() -> System.currentTimeMillis() + offset.get(), System::nanoTime, System::currentTimeMillis, () -> 1);
        clock.sample(); clock.sample();
        try (var node = new NetworkConsensusNode(config, machine)) {
            node.start();
            try (var server = new PartitionServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[3])), node,
                    state, topology, Map.of("app", new PartitionServer.Binding("tenant", new byte[32], false, false),
                            "admin", new PartitionServer.Binding("tenant", adminKey(), true, true)), clock, null);
                    var input = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.println("READY " + server.address().getPort()); System.out.flush();
                String line;
                while ((line = input.readLine()) != null) {
                    String[] c = line.split(" ");
                    switch (c[0]) {
                        case "ADDRESS" -> System.out.println("ACK " + server.address().getPort());
                        case "INDEX" -> System.out.println("ACK " + node.status().commitIndex());
                        case "STATUS" -> System.out.println("ACK " + node.status().leaderId());
                        case "SNAPSHOT" -> System.out.println("ACK " + node.snapshot());
                        case "ARM" -> { armed.set(c[1]); System.out.println("ACK"); }
                        case "OFFSET" -> { offset.set(Long.parseLong(c[1])); System.out.println("ACK"); }
                        default -> throw new IllegalArgumentException("Unknown fixture command");
                    }
                    System.out.flush();
                }
            }
        }
    }
}
