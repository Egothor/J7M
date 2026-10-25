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
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.consensus.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventSubscriptionsIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(25); }
    private static int port() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private final class Source implements AutoCloseable {
        final ReplicatedResources machine;
        final NetworkConsensusNode node;
        final RemoteResourceServer server;
        final RemoteResourceClient remote;
        Source() throws Exception { this(ignored -> { }, ignored -> { }); }
        Source(java.util.function.Consumer<String> resourceProbe, java.util.function.Consumer<String> serverProbe) throws Exception {
            machine = new ReplicatedResources(100000, resourceProbe);
            var config = new NetworkConsensusConfig("s", Map.of("s", new InetSocketAddress("127.0.0.1", port())), Set.of("s"), directory.resolve("source"), new byte[32], Duration.ofMillis(300), Duration.ofMillis(50), 1048576, 67108864);
            node = new NetworkConsensusNode(config, machine); node.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"s".equals(node.status().leaderId())) { if (System.nanoTime() > end) fail("Election deadline"); Thread.onSpinWait(); }
            var local = new ReplicatedResourceClient(node, machine, "tenant", op -> true, () -> new ReplicatedResourceClient.ClockMeasurement(1000, 1000, 0, true));
            var other = new ReplicatedResourceClient(node, machine, "other", op -> true, () -> new ReplicatedResourceClient.ClockMeasurement(1000, 1000, 0, true));
            byte[] otherKey = new byte[32]; Arrays.fill(otherKey, (byte) 1);
            server = new RemoteResourceServer(new InetSocketAddress("127.0.0.1", 0), Map.of("app", new RemoteResourceServer.Binding(local, new byte[32], true), "other", new RemoteResourceServer.Binding(other, otherKey, false)), null, serverProbe);
            remote = new RemoteResourceClient(List.of(server.address()), "app", new byte[32], null);
        }
        @Override public void close() throws Exception { server.close(); node.close(); }
    }
    private static TreeMap<String, ReplicatedResources.Value> projection(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); assertEquals(1, in.readInt()); int count = in.readInt();
        TreeMap<String, ReplicatedResources.Value> values = new TreeMap<>();
        for (int n = 0; n < count; n++) values.put(in.readUTF(), new ReplicatedResources.Value(in.readUTF(), in.readLong()));
        assertEquals(0, in.available()); return values;
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "Deterministic overlap checkpoint"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    @Test void concurrentSnapshotAndTailReconstructsOneHundredThousandChangesAcrossBoundedNetworkFrames() throws Exception {
        CountDownLatch halfway = new CountDownLatch(1), captureStarted = new CountDownLatch(1), requestAccepted = new CountDownLatch(1), writeCommitted = new CountDownLatch(1);
        var captureArmed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var streamArmed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var overlapProved = new java.util.concurrent.atomic.AtomicBoolean(false);
        var overlappingIndex = new java.util.concurrent.atomic.AtomicLong();
        java.util.function.Consumer<String> resourceProbe = phase -> {
            if (phase.equals("SNAPSHOT_CAPTURE") && captureArmed.compareAndSet(true, false)) {
                captureStarted.countDown(); awaitLatch(requestAccepted); streamArmed.set(true);
            }
        };
        java.util.function.Consumer<String> serverProbe = phase -> {
            if (phase.equals("REQUEST batch-50")) requestAccepted.countDown();
            if (phase.equals("SNAPSHOT_CHUNK 0") && streamArmed.compareAndSet(true, false)) {
                awaitLatch(writeCommitted); overlapProved.set(true);
            }
        };
        try (Source source = new Source(resourceProbe, serverProbe); var executor = Executors.newSingleThreadExecutor()) {
            var events = new EventSubscriptions(source.remote);
            var writing = executor.submit(() -> {
                for (int batch = 0; batch < 100; batch++) {
                    if (batch == 50) { halfway.countDown(); awaitLatch(captureStarted); }
                    List<String> triples = new ArrayList<>();
                    for (int key = batch * 1000; key < (batch + 1) * 1000; key++) { triples.add("key-" + key); triples.add("0"); triples.add("value-" + key); }
                    var result = source.remote.execute("BATCH", "batch-" + batch, triples, deadline()); assertEquals("OK", result.status());
                    if (batch == 50) { overlappingIndex.set(result.index()); writeCommitted.countDown(); }
                }
                return true;
            });
            assertTrue(halfway.await(10, TimeUnit.SECONDS));
            var snapshot = events.snapshotAndTail(deadline());
            assertTrue(overlapProved.get(), "A write must commit while the snapshot response stream is paused");
            assertTrue(overlappingIndex.get() > snapshot.index(), "Overlapping commit must follow the captured barrier");
            assertEquals("OK", events.subscribe("subscribe", "tail", "BATCH", snapshot.cursor(), 86400000, deadline()).status());
            assertTrue(writing.get(90, TimeUnit.SECONDS));
            TreeMap<String, ReplicatedResources.Value> reconstructed = projection(snapshot.snapshot());
            long sequence = snapshot.cursor().sequence(); int changes = reconstructed.size();
            while (true) {
                var page = events.poll("tail", 13, deadline()); if (page.events().isEmpty()) break;
                for (var event : page.events()) {
                    assertEquals(++sequence, event.cursor().sequence()); var change = ResourceEvent.decode(event.payload()); assertEquals("OK", change.status());
                    for (var upsert : change.changes().entrySet()) { assertNull(reconstructed.put(upsert.getKey(), upsert.getValue())); changes++; }
                }
                assertEquals("OK", events.ack("ack-" + sequence, "tail", page.through(), 86400000, deadline()).status());
            }
            assertEquals(100000, changes); assertEquals(100000, reconstructed.size());
            var finalImage = events.snapshotAndTail(deadline()); assertEquals(reconstructed, projection(finalImage.snapshot())); assertTrue(finalImage.snapshot().length > ResourceWire.MAX_FRAME);
            assertEquals("OK", events.subscribe("subscribe", "tail", "BATCH", snapshot.cursor(), 86400000, deadline()).status());
            assertEquals("OK", events.resume("resume", "tail", 86400000, deadline()).status()); assertTrue(events.poll("tail", 1, deadline()).events().isEmpty());
        }
    }
    @Test void scopesMalformedFutureCursorsAndConflictingCasAreExplicit() throws Exception {
        try (Source source = new Source()) {
            var events = new EventSubscriptions(source.remote); var snapshot = events.snapshotAndTail(deadline());
            assertEquals("OK", events.subscribe("s", "sub", "BATCH", snapshot.cursor(), 10000, deadline()).status());
            assertEquals("OK", source.remote.execute("BATCH", "a", List.of("x", "0", "one"), deadline()).status());
            assertEquals("CONFLICT", source.remote.execute("BATCH", "b", List.of("x", "0", "bad"), deadline()).status());
            var page = events.poll("sub", 10, deadline()); assertEquals(2, page.events().size()); assertEquals("CONFLICT", ResourceEvent.decode(page.events().getLast().payload()).status());
            assertThrows(IOException.class, () -> events.ack("future", "sub", new EventJournal.Cursor("map", 0, 999), 10000, deadline()));
            assertThrows(IOException.class, () -> events.ack("foreign", "sub", new EventJournal.Cursor("other", 0, 0), 10000, deadline()));
            byte[] otherKey = new byte[32]; Arrays.fill(otherKey, (byte) 1);
            var other = new RemoteResourceClient(List.of(source.server.address()), "other", otherKey, null);
            assertTrue(projection(other.snapshotAndTail(deadline()).snapshot()).isEmpty());
            assertThrows(IOException.class, () -> other.poll("sub", 10, deadline()));
            assertEquals("one", source.remote.read("x", deadline()).value());
            byte[] full = source.machine.snapshot(); assertEquals(2, new DataInputStream(new ByteArrayInputStream(full)).readInt());
            assertTrue(source.machine.transferReady());
        }
    }
}
