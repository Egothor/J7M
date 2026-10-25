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
 * Child JVM fixture. All credentials are synthetic loopback-only test data.
 */
public final class ResourceNodeProcess {
    /**
     * Hosts a resource replica and authenticated facade until stdin closes or the process is killed.
     * @param args node id, directory, id:port comma peers, resource port
     * @throws Exception if startup or fixture command fails
     */
    public static void main(String[] args) throws Exception {
        String id = args[0]; var peers = new TreeMap<String, InetSocketAddress>();
        for (String peer : args[2].split(",")) {
            String[] parts = peer.split(":"); peers.put(parts[0], new InetSocketAddress("127.0.0.1", Integer.parseInt(parts[1])));
        }
        var armed = new java.util.concurrent.atomic.AtomicReference<String>("");
        var machine = new ReplicatedResources(100000, phase -> {
            String target = armed.get();
            if (phase.equals(target) && armed.compareAndSet(target, "")) {
                System.out.println("FAULT " + phase); System.out.flush();
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
        });
        var config = new NetworkConsensusConfig(id, peers, peers.keySet(), Path.of(args[1]), new byte[32],
                Duration.ofMillis(450), Duration.ofMillis(70), 1_048_576, 16_777_216);
        var offset = new AtomicLong();
        var clock = new MeasuredClockHealth(() -> System.currentTimeMillis() + offset.get(), System::nanoTime,
                System::currentTimeMillis, () -> 1);
        clock.sample(); clock.sample();
        try (var node = new NetworkConsensusNode(config, machine)) {
            node.start();
            var client = new ReplicatedResourceClient(node, machine, "tenant/test", op -> true, clock);
            byte[] appKey = new byte[32], coordinatorKey = new byte[32]; Arrays.fill(coordinatorKey, (byte) 1);
            try (var server = new RemoteResourceServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[3])),
                    Map.of("app", new RemoteResourceServer.Binding(client, appKey, false),
                            "coordinator", new RemoteResourceServer.Binding(client, coordinatorKey, true)), null);
                    var maintenance = new LeaseTimeMaintenance(client, Duration.ofMillis(250));
                    var input = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.println("READY " + server.address().getPort()); System.out.flush();
                String line;
                while ((line = input.readLine()) != null) {
                    String[] command = line.split(" ");
                    switch (command[0]) {
                        case "ARM" -> { armed.set(command[1]); System.out.println("ACK"); }
                        case "SNAPSHOT" -> System.out.println("ACK " + node.snapshot());
                        case "OFFSET" -> { offset.set(Long.parseLong(command[1])); System.out.println("ACK"); }
                        case "PAUSE" -> { maintenance.close(); System.out.println("ACK"); }
                        case "EVENTS" -> System.out.println("ACK " + machine.events("tenant/test", node.status().appliedIndex()).size());
                        case "LEASE" -> {
                            var lease = machine.lease("tenant/test", command[1], node.status().appliedIndex());
                            System.out.println(lease == null ? "ACK null" : "ACK " + lease.version() + " " + lease.fence()
                                    + " " + lease.leadershipEpoch());
                        }
                        case "STATUS" -> {
                            var status = node.status();
                            System.out.println("ACK " + status.appliedIndex() + " " + status.term() + " " + status.leaderId()
                                    + " " + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(machine.snapshot())));
                        }
                        default -> throw new IllegalArgumentException("Unknown fixture command");
                    }
                    System.out.flush();
                }
            }
        }
    }
}
