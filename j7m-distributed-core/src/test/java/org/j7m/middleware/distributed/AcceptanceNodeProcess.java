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
import org.j7m.middleware.consensus.*;

/**
 * Real child-JVM CP host used by durable recovery and the hour-long acceptance workload.
 */
public final class AcceptanceNodeProcess {
    private AcceptanceNodeProcess() { }
    static String schema(String kind) { return kind.equals("map") ? "j7m-replicated-map/v2" : "j7m-partition-queue/v1"; }
    static ReplicatedStateMachine state(String kind) {
        return kind.equals("map") ? new ReplicatedResources() : new PartitionStateMachine("acceptance-queue", "queue-owner", 1, new QueueStateMachine("acceptance-queue"), new byte[32]);
    }
    /**
     * Hosts a real node until its control pipe closes or a fault kills its process.
     * @param args nodeId, directory, peer id:port roster, service port, map/queue and consensus generation
     * @throws Exception on startup, control or durable backup failure
     */
    public static void main(String[] args) throws Exception {
        Map<String, InetSocketAddress> peers = new TreeMap<>(); for (String peer : args[2].split(",")) { String[] pair = peer.split(":"); peers.put(pair[0], new InetSocketAddress("127.0.0.1", Integer.parseInt(pair[1]))); }
        ReplicatedStateMachine machine = state(args[4]);
        var config = new NetworkConsensusConfig(args[0], peers, peers.keySet(), Path.of(args[1]), new byte[32], Duration.ofMillis(500), Duration.ofMillis(20), 1048576, 134217728, null, args[5]);
        var clock = new MeasuredClockHealth(System::currentTimeMillis, System::nanoTime, System::currentTimeMillis, () -> 1); clock.sample(); clock.sample();
        try (var node = new NetworkConsensusNode(config, machine); var input = new BufferedReader(new InputStreamReader(System.in))) {
            node.start();
            AutoCloseable endpoint;
            if (machine instanceof ReplicatedResources map) {
                var local = new ReplicatedResourceClient(node, map, "tenant", operation -> true, clock);
                endpoint = new RemoteResourceServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[3])), Map.of("operator", new RemoteResourceServer.Binding(local, new byte[32], true)), null);
            } else endpoint = new PartitionServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[3])), node, (PartitionStateMachine) machine, null, Map.of("operator", new PartitionServer.Binding("tenant", new byte[32], true, true)), clock, null);
            try (endpoint) {
                System.out.println("READY " + args[0]); System.out.flush(); String line;
                while ((line = input.readLine()) != null) {
                    String[] command = line.split(" ", 2);
                    try {
                        switch (command[0]) {
                            case "STATUS" -> System.out.println("ACK " + node.status().leaderId() + " " + node.status().appliedIndex());
                            case "SNAPSHOT" -> System.out.println("ACK " + node.snapshot());
                            case "BACKUP" -> { var metadata = ConsensusBackup.export(node, Path.of(command[1]), schema(args[4]), Instant.now().plusSeconds(10)); System.out.println("ACK " + metadata.index() + " " + metadata.checkpointSha256()); }
                            default -> throw new IllegalArgumentException("Unknown acceptance control");
                        }
                    } catch (IOException error) { System.out.println("ERROR " + error.getMessage()); }
                    System.out.flush();
                }
            }
        }
    }
}
