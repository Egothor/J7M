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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.PartitionAdminClient;
import org.j7m.middleware.distributed.PartitionClient;
import org.j7m.middleware.distributed.PartitionMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedPartitionMigrationIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(8); }
    private static byte[] adminKey() { byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 1); return key; }
    private static PartitionAdminClient admin(int port) {
        return new PartitionAdminClient(new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", port)), "admin", adminKey()));
    }
    private final class Topology implements AutoCloseable {
        final Process process;
        final int port;
        Topology(int peer) throws Exception {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx160m", "-cp",
                    System.getProperty("j7m.application.distributed.test.classpath"), "org.j7m.middleware.distributed.PartitionNodeProcess",
                    "topology", directory.resolve("topology").toString(), Integer.toString(peer), "0", "true", "0")
                    .redirectError(directory.resolve("topology.stderr").toFile()).start();
            try {
                var input = new BufferedReader(new InputStreamReader(process.getInputStream()));
                long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                while (!input.ready() && process.isAlive() && System.nanoTime() < until) Thread.sleep(5);
                assertTrue(input.ready() || !process.isAlive()); String ready = input.readLine();
                assertNotNull(ready, () -> { try { return Files.readString(directory.resolve("topology.stderr")); } catch (Exception failure) { return "Topology exited"; } });
                String[] fields = ready.split(" "); assertEquals("READY", fields[0]); port = Integer.parseInt(fields[1]);
            } catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        @Override public void close() { process.destroyForcibly(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } }
    }
    @Test void authenticatedNativeHandoffTransfersEncodedStateJournalAndHistoricalReceiptsWithoutGroupBypass() throws Exception {
        var fixture = new ManagedPartitionProcessIT(); fixture.directory = directory;
        Path artifact = fixture.artifact("migration", 1);
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, getClass().getClassLoader());
                var sourcePort = new ServerSocket(0); var targetPort = new ServerSocket(0); var topologyPort = new ServerSocket(0)) {
            var program = fixture.definition(loader); var schemas = new SchemaRegistry(List.of());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
            String fingerprint = ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle(digest, Map.of("configuration", "current")), program, schemas, "tenant", 10000);
            var source = fixture.new Child("a", artifact, "a:" + sourcePort.getLocalPort(), "current"); source.command.set(8, "a");
            var target = fixture.new Child("b", artifact, "b:" + targetPort.getLocalPort(), "current"); target.command.set(8, "b"); target.command.set(11, "0");
            int authorityPort = topologyPort.getLocalPort(); topologyPort.close();
            try (source; target; var topology = new Topology(authorityPort)) {
                sourcePort.close(); source.start(); targetPort.close(); target.start(); assertNotEquals(source.group, target.group);
                var authority = admin(topology.port); authority.register("partition", source.group, deadline());
                var routes = (ManagedPartitionClient.Routes) (key, deadline) -> authority.route("partition", deadline);
                var client = new ManagedPartitionClient(routes,
                        Map.of(source.group, new ManagedPartitionClient.Group(source.endpoint(), Map.of()),
                                target.group, new ManagedPartitionClient.Group(target.endpoint(), Map.of())),
                        program, fingerprint, "tenant", new DefaultValueCodec(schemas), schemas);
                long previous = 0;
                for (int index = 0; index < 10; index++) {
                    var receipt = client.execute("before-" + index, "1", deadline()); assertTrue(receipt.sequence() > previous); previous = receipt.sequence();
                }
                var first = client.execute("before-0", "1", deadline());
                var migration = new PartitionMigration(authority, Map.of(source.group, admin(source.service), target.group, admin(target.service)));
                migration.migrate("handoff", "partition", target.group, 1, deadline());
                assertEquals(target.group, authority.route("partition", deadline()).ownerGroup());
                assertEquals(10, client.query("same-key", "current", deadline())); assertEquals(first, client.execute("before-0", "1", deadline()));
                var after = client.execute("after", "1", deadline()); assertTrue(after.sequence() > previous); assertEquals(2, after.epoch());
                assertEquals(11, client.query("same-key", "current", deadline()));
                var codec = new PayloadFrameEngine(new DefaultValueCodec(schemas), schemas, ManagedPartitionResource.MAX_COMMAND_BYTES);
                String key = new PartitionRouting(program, schemas).key("1");
                byte[] old = ManagedPartitionResource.command(fingerprint, source.group, "tenant", "stale", key, codec.encode("1", String.class));
                byte[] oldResult = ManagedPartitionResource.query(fingerprint, source.group, "tenant", "stale", new byte[0]);
                assertEquals("FROZEN", source.endpoint().execute(1, ManagedPartitionResource.requestId("tenant", "stale"), old, oldResult, deadline()).status());
                assertThrows(SecurityException.class, () -> target.endpoint().execute(2, ManagedPartitionResource.requestId("tenant", "stale"), old, oldResult, deadline()));
                target.stop(); target.start();
                var restored = new ManagedPartitionClient(routes,
                        Map.of(source.group, new ManagedPartitionClient.Group(source.endpoint(), Map.of()),
                                target.group, new ManagedPartitionClient.Group(target.endpoint(), Map.of())),
                        program, fingerprint, "tenant", new DefaultValueCodec(schemas), schemas);
                assertEquals(11, restored.query("same-key", "current", deadline())); assertEquals(first, restored.execute("before-0", "1", deadline()));
                assertEquals("OK", restored.execute("after-restart", "1", deadline()).status()); assertEquals(12, restored.query("same-key", "current", deadline()));
            }
        }
    }
}
