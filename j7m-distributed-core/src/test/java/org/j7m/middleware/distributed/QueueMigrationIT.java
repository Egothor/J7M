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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueMigrationIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(20); }
    private static int port() throws IOException { try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static byte[] adminKey() { byte[] key = new byte[32]; Arrays.fill(key, (byte) 1); return key; }
    private final class Host implements AutoCloseable {
        final String group; final int raft, service; final long epoch;
        Process process; BufferedReader output; BufferedWriter input;
        Host(String group, long epoch) throws Exception { this.group = group; this.epoch = epoch; raft = port(); service = port(); start(); }
        void start() throws Exception {
            String classpath = System.getProperty("j7m.distributed.test.classpath", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx160m", "-cp", classpath,
                    QueueNodeProcess.class.getName(), group, directory.resolve(group).toString(), group + ":" + raft,
                    Integer.toString(service), group, Long.toString(epoch)).redirectError(directory.resolve(group + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            String ready = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); } }).get(15, TimeUnit.SECONDS);
            assertNotNull(ready, () -> "Startup error " + directory.resolve(group + ".stderr")); assertTrue(ready.startsWith("READY"));
        }
        PartitionClient client(String principal) { return new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", service)), principal, principal.equals("admin") ? adminKey() : new byte[32]); }
        PartitionAdminClient admin() { return new PartitionAdminClient(client("admin")); }
        void snapshot() throws Exception {
            input.write("SNAPSHOT\n"); input.flush();
            String response = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); } }).get(15, TimeUnit.SECONDS);
            assertNotNull(response); assertTrue(response.startsWith("ACK"));
        }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        @Override public void close() { if (process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    }
    private static <T> T await(Callable<T> operation) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L; Exception last = null;
        do { try { return operation.call(); } catch (IOException e) { last = e; Thread.onSpinWait(); } } while (System.nanoTime() < end);
        throw new AssertionError("Recovery exceeded development profile", last);
    }
    @Test void migrationCarriesAcknowledgementsPendingClaimsGroupsDedupeAndJournalAcrossTargetRestart() throws Exception {
        try (Host topology = new Host("topology", 0); Host source = new Host("source", 1); Host target = new Host("target", 0)) {
            var authority = topology.admin(); await(() -> { authority.register("queue-partition", "source", deadline()); return true; });
            var routed = new RoutedPartitionEndpoint("queue-partition", new PartitionAdminClient(topology.client("app")), Map.of("source", source.client("app"), "target", target.client("app")));
            var queue = new ReplicatedQueue("queue-partition", "tenant", (p, d) -> new ReplicatedQueue.Route(routed, 1));
            assertEquals("OK\t1", queue.join("join", "g", "a", 86400000, deadline()));
            Map<String, byte[]> messages = new LinkedHashMap<>(); messages.put("acked", new byte[]{1}); messages.put("pending", new byte[]{2}); messages.put("last", new byte[]{3});
            assertEquals("SENT_BATCH\t3", queue.sendBatch("batch", "q", messages, 3, deadline()));
            assertTrue(queue.claim("first", "q", "g", "a", 1, 60000, deadline()).startsWith("MESSAGE\tacked\t"));
            assertEquals("ACKED", queue.ack("first-ack", "q", "g", "a", 1, "acked", 1, deadline()));
            String pending = queue.claim("pending-claim", "q", "g", "a", 1, 60000, deadline()); assertTrue(pending.startsWith("MESSAGE\tpending\t"));
            String beforeEvents = new String(routed.query(1, new ResourceCommand("Q_EVENTS", "tenant", "query", List.of()).encode(), deadline()).payload(), java.nio.charset.StandardCharsets.UTF_8);
            var migration = new PartitionMigration(authority, Map.of("source", source.admin(), "target", target.admin()));
            migration.migrate("queue-handoff", "queue-partition", "target", 1, deadline());
            assertEquals(2, authority.route("queue-partition", deadline()).epoch());
            target.snapshot(); target.kill(); target.start(); source.kill();
            assertEquals(List.of("1", "q"), await(() -> queue.assignment("g", "a", deadline())));
            assertEquals(pending, queue.claim("pending-claim", "q", "g", "a", 1, 60000, deadline()));
            assertEquals("ACKED", queue.ack("first-ack", "q", "g", "a", 1, "acked", 1, deadline()));
            assertEquals("EMPTY", queue.claim("blocked", "q", "g", "a", 1, 60000, deadline()));
            String afterEvents = new String(routed.query(2, new ResourceCommand("Q_EVENTS", "tenant", "query", List.of()).encode(), deadline()).payload(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(Integer.parseInt(afterEvents) >= Integer.parseInt(beforeEvents));
            assertEquals("OK\t2", queue.rebalance("rebalance", "g", deadline()));
            assertEquals("STALE_GENERATION", queue.ack("old", "q", "g", "a", 1, "pending", 1, deadline()));
            assertTrue(queue.claim("takeover", "q", "g", "a", 2, 60000, deadline()).startsWith("MESSAGE\tpending\t"));
            assertEquals("ACKED", queue.ack("pending-ack", "q", "g", "a", 2, "pending", 2, deadline()));
            assertTrue(queue.claim("last", "q", "g", "a", 2, 60000, deadline()).startsWith("MESSAGE\tlast\t"));
            assertEquals("ACKED", queue.ack("last-ack", "q", "g", "a", 2, "last", 1, deadline()));
            assertEquals("EMPTY", queue.claim("empty", "q", "g", "a", 2, 60000, deadline()));
        }
    }
}
