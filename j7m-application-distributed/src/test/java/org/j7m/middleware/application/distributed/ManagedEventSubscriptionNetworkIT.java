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
import java.util.concurrent.*;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.consensus.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual replicated TCP backend boundary; generated/owned hosted activation has a separate gate.
 */
class ManagedEventSubscriptionNetworkIT {
    @TempDir Path directory;
    private static int port() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private static void eventually(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) fail("Bounded network state transition deadline");
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }
    private final class Cluster implements AutoCloseable {
        final Map<String, InetSocketAddress> peers = new TreeMap<>();
        final Map<String, Integer> ports = new TreeMap<>();
        final Map<String, NetworkConsensusNode> nodes = new TreeMap<>();
        final Map<String, RemoteResourceServer> servers = new TreeMap<>();
        final Map<String, LeaseTimeMaintenance> clocks = new TreeMap<>();
        final RemoteResourceClient source, metadata;
        Cluster() throws Exception {
            for (String id : List.of("one", "two", "three")) { peers.put(id, new InetSocketAddress("127.0.0.1", port())); ports.put(id, port()); }
            var addresses = ports.values().stream().map(value -> new InetSocketAddress("127.0.0.1", value)).toList();
            byte[] metadataKey = new byte[32]; Arrays.fill(metadataKey, (byte) 1);
            source = new RemoteResourceClient(addresses, "app", new byte[32], null);
            metadata = new RemoteResourceClient(addresses, "metadata", metadataKey, null);
            try { startAll(); } catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        void startAll() throws Exception {
            for (String id : peers.keySet()) {
                var machine = new ReplicatedResources();
                var node = new NetworkConsensusNode(new NetworkConsensusConfig(id, peers, peers.keySet(), directory.resolve(id),
                        new byte[32], Duration.ofMillis(450), Duration.ofMillis(70), 1048576, 16777216), machine);
                nodes.put(id, node); node.start();
                ReplicatedResourceClient.ClockHealth health = () -> {
                    long now = System.currentTimeMillis(); return new ReplicatedResourceClient.ClockMeasurement(now, now, 0, true);
                };
                var app = new ReplicatedResourceClient(node, machine, "application", ignored -> true, health);
                var meta = new ReplicatedResourceClient(node, machine, "host-metadata", ignored -> true, health);
                byte[] metadataKey = new byte[32]; Arrays.fill(metadataKey, (byte) 1);
                servers.put(id, new RemoteResourceServer(new InetSocketAddress("127.0.0.1", ports.get(id)),
                        Map.of("app", new RemoteResourceServer.Binding(app, new byte[32], false),
                                "metadata", new RemoteResourceServer.Binding(meta, metadataKey, false)), null));
                clocks.put(id, new LeaseTimeMaintenance(app, Duration.ofMillis(250)));
            }
            ready();
        }
        void ready() throws Exception {
            Instant end = deadline(); IOException last = null;
            do {
                try { source.read("readiness", end); return; }
                catch (IOException election) { last = election; java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); }
            } while (Instant.now().isBefore(end));
            throw new IOException("Actual quorum readiness failed", last);
        }
        void stop(String id) throws Exception {
            var clock = clocks.remove(id); if (clock != null) clock.close();
            var server = servers.remove(id); if (server != null) server.close();
            var node = nodes.remove(id); if (node != null) node.close();
        }
        void stopLeader() throws Exception {
            String leader = nodes.values().stream().map(node -> node.status().leaderId()).filter(Objects::nonNull)
                    .filter(value -> !value.isBlank()).findFirst().orElseThrow();
            stop(leader); ready();
        }
        void restartAll() throws Exception { for (String id : List.copyOf(nodes.keySet())) stop(id); startAll(); }
        void write(String key) throws IOException {
            assertEquals("OK", source.execute("BATCH", "source-" + key, List.of(key, "0", key), deadline()).status());
        }
        @Override public void close() throws Exception { for (String id : List.copyOf(nodes.keySet())) stop(id); }
    }
    private static ManagedEventDelivery take(BlockingQueue<ManagedEventDelivery> deliveries) throws Exception {
        var value = deliveries.poll(10, TimeUnit.SECONDS); assertNotNull(value, "Actual source delivery deadline"); return value;
    }
    @Test void snapshotTailConcurrentWriteLeaderRefreshAndFullWalRestartKeepSubscriptionProgress() throws Exception {
        try (var cluster = new Cluster()) {
            var profile = new EventSubscriptionProfile(cluster.source, cluster.metadata, "source", Duration.ofSeconds(60),
                    Duration.ofMillis(5), Duration.ofSeconds(2), Duration.ofSeconds(5), 8, 1048576,
                    EventSubscriptionProfile.Restart.RESUME, EventSubscriptionProfile.Expiry.FAIL);
            var deliveries = new LinkedBlockingQueue<ManagedEventDelivery>(); var heldSnapshot = new CompletableFuture<Object>();
            ManagedHostedResources.Invoker invoker = ManagedEventSubscriptionFactoryTest.measured((method, decoder, options) -> {
                try { var delivery = (ManagedEventDelivery) decoder.call()[0]; deliveries.add(delivery);
                    return delivery.kind() == ManagedEventDelivery.Kind.SNAPSHOT ? heldSnapshot : CompletableFuture.completedFuture(null);
                } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
            });
            try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile), 1, 2)) {
                var binding = factory.bind(ManagedEventSubscriptionFactoryTest.feature("BATCH"), ManagedEventSubscriptionFactoryTest.definition("BATCH"),
                        ManagedEventSubscriptionFactoryTest.handler("BATCH"), ManagedEventSubscriptionFactoryTest.BUNDLE, invoker);
                binding.prepare(); binding.start(); binding.commitAdmission();
                var image = take(deliveries); assertEquals(ManagedEventDelivery.Kind.SNAPSHOT, image.kind());
                try (var projection = new DataInputStream(new ByteArrayInputStream(image.payload()))) {
                    assertEquals(1, projection.readInt()); assertEquals(0, projection.readInt(), "Actual separately authorized metadata scope must not appear in application projection");
                    assertEquals(0, projection.available());
                }
                cluster.write("one"); heldSnapshot.complete(null);
                var first = take(deliveries); assertEquals(ManagedEventDelivery.Kind.EVENT, first.kind());
                assertTrue(first.cursor().sequence() > image.cursor().sequence());
                assertEquals("one", ResourceEvent.decode(first.payload()).changes().get("one").value());
                eventually(() -> factory.status("source", "projection").cursor() != null
                        && factory.status("source", "projection").cursor().sequence() >= first.cursor().sequence());
                cluster.stopLeader(); cluster.write("two");
                var second = take(deliveries); assertEquals("two", ResourceEvent.decode(second.payload()).changes().get("two").value());
                eventually(() -> factory.status("source", "projection").cursor().sequence() >= second.cursor().sequence());
                assertTrue(binding.drain(Duration.ofSeconds(3))); binding.close();
                cluster.restartAll();
                var restarted = factory.bind(ManagedEventSubscriptionFactoryTest.feature("BATCH"), ManagedEventSubscriptionFactoryTest.definition("BATCH"),
                        ManagedEventSubscriptionFactoryTest.handler("BATCH"), ManagedEventSubscriptionFactoryTest.BUNDLE, invoker);
                restarted.prepare(); restarted.start(); restarted.commitAdmission();
                eventually(() -> factory.status("source", "projection").cursor() != null);
                cluster.write("three"); var third = take(deliveries);
                assertEquals(ManagedEventDelivery.Kind.EVENT, third.kind());
                assertEquals("three", ResourceEvent.decode(third.payload()).changes().get("three").value());
                assertTrue(third.cursor().sequence() > second.cursor().sequence());
                assertTrue(restarted.drain(Duration.ofSeconds(3))); restarted.close();
                assertTrue(deliveries.isEmpty(), "Leader refresh/restart cannot create a new snapshot or replay an acknowledged event");
            }
        }
    }
}
