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
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceBindingProcessIT {
    @TempDir Path directory;
    byte[] admin, app; Path secret;
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private static int port() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    final class Node implements AutoCloseable {
        final String group; final int endpoint = port(), mapPort = port(); Process process;
        Node(String group, boolean topology, int epoch) throws Exception { this(group, topology, epoch, "map"); }
        Node(String group, boolean topology, int epoch, String kind) throws Exception {
            this.group = group;
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-cp", System.getProperty("j7m.application.distributed.test.classpath"),
                    ResourceBindingNodeProcess.class.getName(), group, Boolean.toString(topology), Integer.toString(epoch), directory.resolve(group).toString(), Integer.toString(port()),
                    Integer.toString(endpoint), Integer.toString(mapPort), secret.toString(), kind).redirectError(directory.resolve(group + ".stderr").toFile()).start();
            try {
                var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = CompletableFuture.supplyAsync(() -> { try { return reader.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); } }).get(10, TimeUnit.SECONDS);
                assertEquals("READY " + group, line);
            } catch (Exception | AssertionError error) { close(); throw error; }
        }
        PartitionClient client(boolean privileged) { return new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", endpoint)), privileged ? "admin" : "app", privileged ? admin : app); }
        PartitionAdminClient admin() { return new PartitionAdminClient(client(true)); }
        ResourceEndpoint map() { return new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", mapPort)), "app", app, null); }
        @Override public void close() { if (process != null && process.isAlive()) { process.destroyForcibly(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } } }
    }
    void keys() throws Exception {
        byte[] material = new byte[96]; new SecureRandom().nextBytes(material); admin = Arrays.copyOfRange(material, 32, 64); app = Arrays.copyOfRange(material, 64, 96);
        secret = directory.resolve("fixture-key.bin"); Files.write(secret, material); Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
    }
    private static byte[] command(String request, String key, String value) { return new ResourceCommand("BATCH", "tenant", request, List.of(key, "0", value)).encode(); }
    private static byte[] result(String request) { return new ResourceCommand("QUERY_RESULT", "tenant", "query", List.of(request)).encode(); }
    private static byte[] read(String key) { return new ResourceCommand("QUERY_READ", "tenant", "query", List.of(key)).encode(); }
    private static ManagedDependency edge(String name, Class<?> type, BindingRefresh mode) { return new ManagedDependency(type, name, List.of(), false,
            Map.of("resource.name", name, "binding.refresh", mode.name(), "binding.policy", "default")); }
    @Test void threeActualGroupsKeepRequestResultAndCursorAcrossOwnershipRefreshWithoutSameCallReplay() throws Exception {
        keys();
        // Three independent JVMs each host one durable group; this gate does not claim3-voter fault tolerance.
        try (var topology = new Node("topology", true, 0); var source = new Node("source", false, 1); var target = new Node("target", false, 0)) {
            var authority = topology.admin(); authority.register("partition", "source", deadline());
            var factory = new ResourceBindingFactory(Map.of("orders", ResourceBindingFactory.partition("partition", new PartitionAdminClient(topology.client(false)),
                    Map.of("source", source.client(false), "target", target.client(false)), Duration.ofSeconds(5))), Set.of("default"));
            ManagedDependency dynamic = edge("orders", PartitionEndpoint.class, BindingRefresh.ON_FAILURE), pinned = edge("orders", PartitionEndpoint.class, BindingRefresh.NEVER);
            factory.validate(dynamic, Scope.Kind.ACTIVATION);
            try (var injected = new GeneratedResourceConsumer(directory.resolve("dynamic"), PartitionEndpoint.class, BindingRefresh.ON_FAILURE, "orders");
                 var pin = new GeneratedResourceConsumer(directory.resolve("pinned"), PartitionEndpoint.class, BindingRefresh.NEVER, "orders")) {
                injected.activate(factory); pin.activate(factory);
                var endpoint = (PartitionEndpoint) injected.value(); var unchanged = (PartitionEndpoint) pin.value();
                byte[] first = command("first", "key", "value"); var original = endpoint.execute(0, "first", first, result("first"), deadline()); assertEquals("OK", original.status());
                var events = new EventSubscriptions(source.map()); var snapshot = events.snapshotAndTail(deadline());
                assertEquals("OK", events.subscribe("subscribe", "consumer", "", snapshot.cursor(), 86400000, deadline()).status());
                assertEquals("OK", endpoint.execute(0, "tail", command("tail", "tail-key", "tail-value"), result("tail"), deadline()).status());
                var cursor = events.poll("consumer", 10, deadline()).events().getFirst().cursor();
                new PartitionMigration(authority, Map.of("source", source.admin(), "target", target.admin())).migrate("handoff", "partition", "target", 1, deadline());
                // First call is returned unchanged from old source. Only the following invocation refreshes.
                var rejected = endpoint.execute(0, "after", command("after", "new-key", "new-value"), result("after"), deadline()); assertEquals("FROZEN", rejected.status());
                assertEquals("FROZEN", unchanged.execute(0, "pin", command("pin", "pin-key", "bad"), result("pin"), deadline()).status());
                var moved = endpoint.execute(0, "after", command("after", "new-key", "new-value"), result("after"), deadline()); assertEquals("OK", moved.status()); assertEquals(2, moved.epoch());
                var replay = endpoint.execute(0, "first", first, result("first"), deadline()); assertArrayEquals(original.payload(), replay.payload());
                assertEquals("OK", endpoint.query(0, read("new-key"), deadline()).status());
                var resumed = new EventSubscriptions(target.map()).poll("consumer", 10, deadline()); assertEquals(cursor, resumed.events().getFirst().cursor());
                injected.close(); assertThrows(IllegalStateException.class, () -> endpoint.query(0, read("key"), deadline()));
            }
        }
    }
    @Test void rawGroupResourceInjectionIsStableAndUnsupportedOwnershipRefreshIsRejected() throws Exception {
        keys();
        try (var node = new Node("source", false, 1)) {
            var factory = new ResourceBindingFactory(Map.of("raw", ResourceBindingFactory.group(node.map())), Set.of("default"));
            assertThrows(IllegalArgumentException.class, () -> factory.validate(edge("raw", ResourceEndpoint.class, BindingRefresh.ON_FAILURE), Scope.Kind.CALL));
            try (var invalid = new GeneratedResourceConsumer(directory.resolve("invalid"), ResourceEndpoint.class, BindingRefresh.ON_FAILURE, "raw")) {
                assertThrows(IllegalStateException.class, () -> invalid.activate(factory)); assertEquals(0, invalid.consumer.getField("created").getInt(null));
            }
            try (var injected = new GeneratedResourceConsumer(directory.resolve("valid"), ResourceEndpoint.class, BindingRefresh.NEVER, "raw")) {
                injected.activate(factory); var endpoint = (ResourceEndpoint) injected.value(); assertNull(endpoint.read("key", deadline())); var first = endpoint.execute("BATCH", "once", List.of("key", "0", "value"), deadline());
                assertEquals("OK", first.status()); assertEquals(first, endpoint.execute("BATCH", "once", List.of("key", "0", "value"), deadline()));
                assertSame(endpoint, injected.value());
            }
        }
    }
}
