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
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.j7m.middleware.consensus.*;
import org.j7m.middleware.distributed.*;

/**
 * Actual independently durable group fixture; resource adapter tests never implement a fake wire.
 */
public final class ResourceBindingNodeProcess {
    private ResourceBindingNodeProcess() { }
    public static void main(String[] args) throws Exception {
        String group = args[0]; boolean topologyGroup = Boolean.parseBoolean(args[1]);
        byte[] material = Files.readAllBytes(Path.of(args[7])); byte[] authority = Arrays.copyOfRange(material, 0, 32), adminKey = Arrays.copyOfRange(material, 32, 64), appKey = Arrays.copyOfRange(material, 64, 96);
        String kind = args.length > 8 ? args[8] : "map";
        TransferableResource resource = kind.equals("queue") ? new QueueStateMachine("partition") : kind.equals("execution") ? new ExecutionStateMachine("partition") : new ReplicatedResources(100000);
        var topology = topologyGroup ? new PartitionTopology(authority) : null;
        var partition = topologyGroup ? null : new PartitionStateMachine("partition", group, Long.parseLong(args[2]), resource, authority);
        var peers = Map.of(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(args[4])));
        var config = new NetworkConsensusConfig(group, peers, peers.keySet(), Path.of(args[3]), authority, Duration.ofMillis(450), Duration.ofMillis(30), 1048576, 134217728, null, "binding-" + group);
        var clock = new MeasuredClockHealth(System::currentTimeMillis, System::nanoTime, System::currentTimeMillis, () -> 1); clock.sample(); clock.sample();
        try (var node = new NetworkConsensusNode(config, topologyGroup ? topology : partition)) {
            node.start();
            try (var server = new PartitionServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[5])), node, partition, topology,
                    Map.of("app", new PartitionServer.Binding("tenant", appKey, false, false), "admin", new PartitionServer.Binding("tenant", adminKey, true, true)), clock, null);
                 var mapServer = topologyGroup || !kind.equals("map") ? null : new RemoteResourceServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[6])),
                         Map.of("app", new RemoteResourceServer.Binding(new PartitionResourceEndpoint(node, partition, (ReplicatedResources) resource, "tenant", op -> true, false, clock), appKey, false)), null);
                 var input = new BufferedReader(new InputStreamReader(System.in))) {
                // A listening socket is not an authoritative resource: prove quorum before READY.
                long readinessLimit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                IOException lastReadinessFailure = null;
                while (true) {
                    if (System.nanoTime() >= readinessLimit) throw new IOException("Resource quorum readiness deadline", lastReadinessFailure);
                    try { node.readIndex(Instant.now().plusMillis(500)); break; }
                    catch (IOException notLeaderYet) { lastReadinessFailure = notLeaderYet; Thread.onSpinWait(); }
                }
                if (!topologyGroup && kind.equals("map") && Long.parseLong(args[2]) > 0) {
                    var probe = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[6]))), "app", appKey, null);
                    probe.read("fixture-readiness", Instant.now().plusSeconds(2));
                }
                System.out.println("READY " + group); System.out.flush(); while (input.readLine() != null) { System.out.println("ALIVE"); System.out.flush(); }
            }
        }
    }
}
