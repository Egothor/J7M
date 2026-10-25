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

package org.j7m.middleware.integrations;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.j7m.middleware.consensus.*;
import org.j7m.middleware.distributed.*;

/**
 * Real child JVM broker/relay/trace worker fixture; credentials are synthetic loopback test data.
 */
public final class IntegrationProcess {
    private IntegrationProcess() { }
    static Instant deadline() { return Instant.now().plusSeconds(10); }
    static ReplicatedQueue queue(String ports) {
        var addresses = Arrays.stream(ports.split(",")).map(p -> new InetSocketAddress("127.0.0.1", Integer.parseInt(p))).toList();
        var endpoint = new PartitionClient(addresses, "app", new byte[32]);
        return new ReplicatedQueue("queue-partition", "tenant", (partition, end) -> new ReplicatedQueue.Route(endpoint, 1));
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "BROKER" -> broker(args);
            case "RELAY" -> relay(args);
            case "TRACE" -> trace(args);
            case "CONSUME" -> consume(args);
            default -> throw new IllegalArgumentException("Unknown child mode");
        }
    }
    private static void broker(String[] args) throws Exception {
        Map<String, InetSocketAddress> peers = new TreeMap<>();
        for (String encoded : args[3].split(",")) { String[] p = encoded.split(":"); peers.put(p[0], new InetSocketAddress("127.0.0.1", Integer.parseInt(p[1]))); }
        String partition = args.length > 5 && args[5].equals("execution") ? "execution-partition" : "queue-partition";
        TransferableResource resource = partition.equals("execution-partition") ? new ExecutionStateMachine(partition) : new QueueStateMachine(partition);
        var state = new PartitionStateMachine(partition, "broker", 1, resource, new byte[32]);
        var config = new NetworkConsensusConfig(args[1], peers, peers.keySet(), Path.of(args[2]), new byte[32], Duration.ofMillis(500), Duration.ofMillis(5), 1 << 20, 64 << 20);
        var clock = new MeasuredClockHealth(System::currentTimeMillis, System::nanoTime, System::currentTimeMillis, () -> 1); clock.sample(); clock.sample();
        try (var node = new NetworkConsensusNode(config, state)) {
            node.start();
            try (var metrics = new Observability(URI.create("http://127.0.0.1:9/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 4);
                    var watch = metrics.watchConsensus(node);
                    var server = new PartitionServer(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[4])), node, state, null,
                    Map.of("app", new PartitionServer.Binding("tenant", new byte[32], false, false)), clock, null)) {
                System.out.println("READY " + metrics.address().getPort() + " " + server.address().getPort()); System.out.flush();
                try (var input = new BufferedReader(new InputStreamReader(System.in))) { while (input.readLine() != null) { System.out.println("ALIVE"); System.out.flush(); } }
            }
        }
    }
    private static void relay(String[] args) throws Exception {
        Class.forName("org.h2.Driver"); var delivery = new JdbcDelivery("tenant", null);
        if (!args[3].equals("none")) delivery.faultInjector(point -> {
            if (point.equals(args[3])) { System.out.println("FAULT " + point); System.out.flush(); try { new java.util.concurrent.CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        });
        int sent = delivery.relayOnce(() -> DriverManager.getConnection(args[1], "sa", ""), queue(args[2]), "outbox", 256, deadline());
        System.out.println("MARKED " + sent); System.out.flush();
    }
    private static void consume(String[] args) throws Exception {
        Class.forName("org.h2.Driver"); var delivery = new JdbcDelivery("tenant", null); var queue = queue(args[2]);
        queue.join("join", "consumer", "member", 100000, deadline());
        String claim = queue.claim("consumer-before-crash", "outbox", "consumer", "member", 1, 1, deadline());
        if (!claim.startsWith("MESSAGE\t")) throw new IllegalStateException("Missing consumer delivery");
        String[] fields = claim.split("\t");
        try (Connection connection = DriverManager.getConnection(args[1], "sa", "")) {
            connection.setAutoCommit(false);
            delivery.consume(connection, fields[1], Base64.getDecoder().decode(fields[2]), (c,p) -> {
                try (Statement mutation = c.createStatement()) { mutation.executeUpdate("UPDATE application_effect SET amount=amount+1 WHERE id='consumer'"); }
            }, Instant.EPOCH);
            connection.commit();
        }
        System.out.println("FAULT after-consumer-commit"); System.out.flush(); new java.util.concurrent.CountDownLatch(1).await();
    }
    private static void trace(String[] args) throws Exception {
        var queue = queue(args[1]);
        try (var telemetry = new Observability(URI.create(args[2]), new InetSocketAddress("127.0.0.1", 0), 16)) {
            TracedQueue traced = new TracedQueue(queue, telemetry);
            if (args[3].equals("produce")) {
                TraceCarrier remote = new TraceCarrier("00-1234567890abcdef1234567890abcdef-1234567890abcdef-01", "vendor=SecretToken");
                try (var span = telemetry.start(Observability.Operation.RPC, remote)) {
                    var message = new TracedQueue.Message(telemetry.capture(), "SensitivePayload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    System.out.println(traced.send("trace-send", "stage1", "trace-message", message, 10, deadline()));
                }
            } else {
                String stage = args[3].equals("forward") ? "stage1" : "stage2";
                String group = "group-" + stage, member = "worker";
                String join = queue.join("join-" + stage, group, member, 100000, deadline()); long generation = Long.parseLong(join.split("\t")[1]);
                String claim = traced.claim("claim-" + stage, stage, group, member, generation, 100000, deadline());
                if (!claim.startsWith("MESSAGE\t")) throw new IllegalStateException("Expected traced delivery " + claim);
                String[] fields = claim.split("\t");
                traced.consume(Base64.getDecoder().decode(fields[2]), payload -> {
                    if (args[3].equals("forward")) traced.send("forward-send", "stage2", "forward-message", new TracedQueue.Message(telemetry.capture(), payload), 10, deadline());
                    else try (var span = telemetry.start(Observability.Operation.JOB_RUN)) { if (!telemetry.capture().traceparent().contains("1234567890abcdef1234567890abcdef")) throw new IllegalStateException("Lost trace parent"); }
                });
                if (!traced.ack("ack-" + stage, stage, group, member, generation, fields[1], Long.parseLong(fields[4]), deadline()).equals("ACKED")) throw new IllegalStateException("ACK failed");
            }
            System.out.println("EXPORTED " + telemetry.address().getPort()); System.out.flush();
            // The parent waits until real collector delivery before releasing this fixture process.
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        }
    }
}
