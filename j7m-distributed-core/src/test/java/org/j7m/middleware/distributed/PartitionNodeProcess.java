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
 * Independent JVM partition/topology fixture using actual TCP and durable local disk.
 */
public final class PartitionNodeProcess {
    private PartitionNodeProcess() { }
    /**
     * Hosts one independent consensus group until killed or its stdin closes.
     * @param args group, disk, consensus port, endpoint port, topology flag, initial epoch
     * @throws Exception on startup or disk failure
     */
    public static void main(String[] args) throws Exception {
        String group = args[0]; byte[] authority = new byte[32]; Arrays.fill(authority, (byte) 7);
        var resource = new ReplicatedResources(100000);
        boolean isTopology = Boolean.parseBoolean(args[4]);
        var partition = isTopology ? null : new PartitionStateMachine(args.length > 7 ? args[7] : "p", group, Long.parseLong(args[5]), resource, authority);
        var topology = isTopology ? new PartitionTopology(authority) : null;
        var peers = Map.of(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(args[2])));
        var config = new NetworkConsensusConfig(group, peers, peers.keySet(), Path.of(args[1]), new byte[32],
                Duration.ofMillis(450), Duration.ofMillis(70), 1048576, 128 * 1024 * 1024);
        var offset = new java.util.concurrent.atomic.AtomicLong();
        var clock = new MeasuredClockHealth(() -> System.currentTimeMillis() + offset.get(), System::nanoTime, System::currentTimeMillis, () -> 1);
        clock.sample(); clock.sample(); byte[] adminKey = new byte[32]; Arrays.fill(adminKey, (byte) 1);
        try (var node = new NetworkConsensusNode(config, isTopology ? topology : partition)) {
            node.start();
            try (var server = new PartitionServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[3])), node,
                    partition, topology, Map.of("app", new PartitionServer.Binding("tenant/test", new byte[32], false, false),
                    "admin", new PartitionServer.Binding("tenant/test", adminKey, true, true)), clock, null);
                    var resourceServer = isTopology || args.length < 7 ? null : new RemoteResourceServer(
                        new InetSocketAddress("127.0.0.1", Integer.parseInt(args[6])),
                        Map.of("app", new RemoteResourceServer.Binding(new PartitionResourceEndpoint(node, partition, resource,
                            "tenant/test", operation -> true, false, clock), new byte[32], false),
                            "admin", new RemoteResourceServer.Binding(new PartitionResourceEndpoint(node, partition, resource,
                            "tenant/test", operation -> true, true, clock), adminKey, true)), null);
                    var input = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.println("READY " + server.address().getPort()); System.out.flush(); String line;
                while ((line = input.readLine()) != null) {
                    if (line.equals("SNAPSHOT")) System.out.println("ACK " + node.snapshot());
                    else if (line.startsWith("OFFSET ")) { offset.set(Long.parseLong(line.substring(7))); System.out.println("ACK"); }
                    else if (line.equals("STATUS")) System.out.println("ACK " + node.status().appliedIndex());
                    else throw new IllegalArgumentException("Unknown process command");
                    System.out.flush();
                }
            }
        }
    }
}
