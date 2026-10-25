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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartitionMigrationProcessIT {
    @TempDir Path directory;
    private static final byte[] ADMIN = new byte[32];
    static { Arrays.fill(ADMIN, (byte) 1); }
    private static Instant deadline() { return Instant.now().plusSeconds(25); }
    private static int port() throws IOException { try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private final class Child implements AutoCloseable {
        private final String group;
        private final boolean topology;
        private final long epoch;
        private final int consensusPort = port(), endpointPort = port(), resourcePort = port();
        private Process process;
        private BufferedReader output;
        private BufferedWriter input;
        Child(String group, boolean topology, long epoch) throws Exception { this.group = group; this.topology = topology; this.epoch = epoch; start(); }
        void start() throws Exception {
            String classpath = System.getProperty("j7m.distributed.test.classpath", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp", classpath,
                    PartitionNodeProcess.class.getName(), group, directory.resolve(group).toString(), Integer.toString(consensusPort),
                    Integer.toString(endpointPort), Boolean.toString(topology), Long.toString(epoch), Integer.toString(resourcePort), group.equals("coordinator") ? "coord" : "p")
                    .redirectError(directory.resolve(group + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            String ready = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }).get(15, TimeUnit.SECONDS);
            assertNotNull(ready, () -> { try { return "JVM failed: " + group + ": " + Files.readString(directory.resolve(group + ".stderr")); } catch (IOException failure) { return failure.toString(); } }); assertTrue(ready.startsWith("READY"), ready);
        }
        PartitionClient client(boolean admin) { return new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", endpointPort)), admin ? "admin" : "app", admin ? ADMIN : new byte[32]); }
        PartitionAdminClient admin() { return new PartitionAdminClient(client(true)); }
        void snapshot() throws Exception {
            input.write("SNAPSHOT\n"); input.flush(); String ack = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }).get(15, TimeUnit.SECONDS);
            assertNotNull(ack); assertTrue(ack.startsWith("ACK"), ack);
        }
        void offset(long milliseconds) throws Exception {
            input.write("OFFSET " + milliseconds + "\n"); input.flush();
            String ack = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }).get(15, TimeUnit.SECONDS);
            assertEquals("ACK", ack);
        }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        @Override public void close() { if (process != null && process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } } }
    }
    private static byte[] resultQuery(String id) { return new ResourceCommand("QUERY_RESULT", "tenant/test", "query", List.of(id)).encode(); }
    private static byte[] readQuery(String key) { return new ResourceCommand("QUERY_READ", "tenant/test", "query", List.of(key)).encode(); }
    private static byte[] batch(String id, List<String> triples) { return new ResourceCommand("BATCH", "tenant/test", id, triples).encode(); }
    private static String value(PartitionReply reply) throws IOException {
        assertEquals("OK", reply.status()); DataInputStream in = PartitionStateMachine.input(reply.payload()); if (!in.readBoolean()) return null;
        String value = in.readUTF(); in.readLong(); PartitionStateMachine.end(in); return value;
    }
    @Test void hundredThousandKeysLiveSuffixAllParticipantCrashesAndSameIdentityMovedRetry() throws Exception {
        try (var topology = new Child("topology", true, 0); var left = new Child("left", false, 1); var right = new Child("right", false, 0)) {
            var authority = topology.admin(); authority.register("p", "left", deadline());
            PartitionClient app = left.client(false);
            for (int batch = 0; batch < 100; batch++) {
                List<String> triples = new ArrayList<>(); for (int n = batch * 1000; n < (batch + 1) * 1000; n++) triples.addAll(List.of("key" + n, "0", "value" + n));
                String id = "seed" + batch; assertEquals("OK", app.execute(1, id, batch(id, triples), resultQuery(id), deadline()).status());
            }
            authority.beginHandoff("migration", "p", "right", 1, deadline());
            left.admin().snapshotAt(1, "migration", deadline()); byte[] pinned = left.admin().image("migration", deadline());
            var acknowledged = new AtomicInteger();
            var writes = CompletableFuture.runAsync(() -> {
                for (int n = 0; n < 100; n++) {
                    String id = "update" + n;
                    try { var reply = app.execute(1, id, batch(id, List.of("key" + n, "1", "updated" + n)), resultQuery(id), deadline());
                        assertEquals("OK", reply.status()); acknowledged.incrementAndGet(); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                }
            });
            right.admin().install("migration", 2, pinned, deadline()); writes.get(30, TimeUnit.SECONDS);
            assertEquals(100, acknowledged.get());
            assertTrue(left.admin().status(deadline()).logicalIndex() > right.admin().status(deadline()).logicalIndex(), "Writes must be captured by a real nonempty suffix");
            // All three independently durable groups crash at distinct unfinished phases.
            left.snapshot(); left.kill(); left.start();
            right.snapshot(); right.kill(); right.start();
            right.admin().catchUp("migration", left.admin().tail(right.admin().status(deadline()).logicalIndex(), deadline()), deadline());
            left.admin().freeze(1, "migration", deadline());
            String finalHash = left.admin().finalHash("migration", deadline()); assertEquals(finalHash, right.admin().status(deadline()).imageHash());
            authority.cutover("migration", 1, finalHash, deadline()); topology.snapshot(); topology.kill(); topology.start();
            right.admin().restartImage("migration", deadline());
            new PartitionMigration(authority, Map.of("left", left.admin(), "right", right.admin())).migrate("migration", "p", "right", 1, deadline());
            assertEquals("COMPLETE", authority.handoff("migration", deadline()).phase());
            byte[] proof = authority.proof("migration", deadline());
            assertThrows(IOException.class, () -> right.admin().activate(2, "migration", finalHash, new byte[32], deadline()));
            right.admin().activate(2, "migration", finalHash, proof, deadline()); authority.complete("migration", deadline()); assertEquals("right", authority.route("p", deadline()).ownerGroup());
            String sameId = "update0"; byte[] sameCommand = batch(sameId, List.of("key0", "1", "updated0"));
            assertEquals("FROZEN", app.execute(1, sameId, sameCommand, resultQuery(sameId), deadline()).status());
            assertEquals("MOVED", right.client(false).execute(1, sameId, sameCommand, resultQuery(sameId), deadline()).status());
            assertEquals("OK", right.client(false).execute(2, sameId, sameCommand, resultQuery(sameId), deadline()).status());
            for (int n = 0; n < 100; n++) assertEquals("updated" + n, value(right.client(false).query(2, readQuery("key" + n), deadline())));
            for (int n : List.of(100, 999, 1000, 50000, 99999)) assertEquals("value" + n, value(right.client(false).query(2, readQuery("key" + n), deadline())));
            var routed = new RoutedPartitionEndpoint("p", new PartitionAdminClient(topology.client(false)), Map.of("left", app, "right", right.client(false)));
            assertEquals("updated0", value(routed.query(0, readQuery("key0"), deadline())));
            assertEquals("OK", routed.execute(0, sameId, sameCommand, resultQuery(sameId), deadline()).status());
            assertEquals("right", routed.status(deadline()).group());
            right.snapshot(); right.kill(); right.start(); assertEquals("updated0", value(right.client(false).query(2, readQuery("key0"), deadline())));
        }
    }
    @Test void publicEventSubscriptionsAndRecoverableTransactionsRemainUsableAfterHandoff() throws Exception {
        try (var topology = new Child("topology", true, 0); var source = new Child("left", false, 1);
                var target = new Child("right", false, 0); var coordinator = new Child("coordinator", false, 1)) {
            topology.admin().register("p", "left", deadline());
            var application = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", source.resourcePort),
                    new InetSocketAddress("127.0.0.1", target.resourcePort)), "app", new byte[32], null);
            var participant = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", source.resourcePort),
                    new InetSocketAddress("127.0.0.1", target.resourcePort)), "admin", ADMIN, null);
            var coordinatorEndpoint = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", coordinator.resourcePort)), "admin", ADMIN, null);
            var subscriptions = new EventSubscriptions(application); var tx = new RecoverableTransactions(coordinatorEndpoint, Map.of("map", participant));
            var coordination = new ReplicatedCoordination(application);
            source.client(false).execute(1, "initial", batch("initial", List.of("initial", "0", "v")), resultQuery("initial"), deadline());
            coordinator.client(false).status(deadline());
            assertEquals("OK", coordination.compareAndSet("cas-before", "shared", 0, "before", deadline()).status());
            long coordinationVersion = application.read("coordination/shared", deadline()).version();
            coordination.join("join", "worker", 60000, deadline()); var memberBefore = coordination.member("worker", deadline()); assertNotNull(memberBefore);
            coordination.lead("lead", "job", "worker", 60000, deadline()); var leaderBefore = coordination.leader("job", deadline()); assertNotNull(leaderBefore);
            assertEquals("OK", coordination.mutate("protected-before", "job", leaderBefore.fence(), leaderBefore.leadershipEpoch(), "protected", "before", deadline()).status());
            var barrier = subscriptions.snapshotAndTail(deadline());
            subscriptions.subscribe("subscribe", "reader", "BATCH", barrier.cursor(), 60000, deadline());
            application.execute("BATCH", "first", List.of("first", "0", "v1"), deadline());
            var first = subscriptions.poll("reader", 10, deadline()); assertEquals(1, first.events().size());
            subscriptions.ack("ack", "reader", first.through(), 60000, deadline());
            application.execute("BATCH", "unacked", List.of("unacked", "0", "v2"), deadline());
            var pending = subscriptions.poll("reader", 10, deadline()); assertEquals(1, pending.events().size());
            assertThrows(IOException.class, () -> new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", target.resourcePort)), "app", new byte[32], null).read("first", Instant.now().plusSeconds(2)));
            tx.begin("transaction", deadline()); tx.enlist("transaction", "map", deadline());
            tx.prepare("transaction", "map", List.of("WRITE", "prepared", "tx-value"), deadline());
            assertEquals(List.of("transaction"), participant.prepared(deadline())); assertEquals(List.of("map"), coordinatorEndpoint.participants("transaction", deadline()));
            var migration = new PartitionMigration(topology.admin(), Map.of("left", source.admin(), "right", target.admin()));
            assertThrows(IOException.class, () -> migration.migrate("public", "p", "right", 1, deadline()));
            assertEquals("PREPARED", participant.transaction("transaction", deadline()).status());
            tx.abort("transaction", deadline()); assertTrue(participant.prepared(deadline()).isEmpty());
            migration.migrate("public", "p", "right", 1, deadline());
            assertEquals("ABORTED", tx.recover("transaction", deadline()).status());
            assertEquals("ABORTED", participant.transaction("transaction", deadline()).status()); assertTrue(participant.prepared(deadline()).isEmpty());
            assertEquals("OK", coordination.compareAndSet("cas-after", "shared", coordinationVersion, "after", deadline()).status());
            assertEquals("after", application.read("coordination/shared", deadline()).value());
            var memberAfter = coordination.member("worker", deadline()); assertEquals(memberBefore, memberAfter);
            assertEquals("OK", coordination.heartbeat("heartbeat", "worker", memberAfter.version(), 60000, deadline()).status());
            assertTrue(coordination.member("worker", deadline()).fence() > memberBefore.fence());
            assertEquals(leaderBefore, coordination.leader("job", deadline()));
            participant.execute("RENEW", "renew-leader", List.of("leadership/job", Long.toString(leaderBefore.version()), "worker", "60000", "0"), deadline());
            var renewedLeader = coordination.leader("job", deadline()); assertTrue(renewedLeader.fence() > leaderBefore.fence());
            assertEquals("CONFLICT", coordination.mutate("stale-protected", "job", leaderBefore.fence(), leaderBefore.leadershipEpoch(), "protected", "stale", deadline()).status());
            assertEquals("OK", coordination.mutate("new-protected", "job", renewedLeader.fence(), renewedLeader.leadershipEpoch(), "protected", "after", deadline()).status());
            assertThrows(IllegalArgumentException.class, () -> new LeaseTimeMaintenance(participant, Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> new LeaseTimeMaintenance(participant, Duration.ofMillis(1001)));
            try (var maintenance = new LeaseTimeMaintenance(participant, Duration.ofMillis(100))) {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (maintenance.committedTicks() == 0 && System.nanoTime() < end) java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
                assertTrue(maintenance.committedTicks() > 0, "Remote maintenance must commit through the migrated owner");
            }
            assertThrows(SecurityException.class, () -> application.advanceTime("unauthorized-clock", deadline()));
            target.offset(60000);
            assertThrows(IOException.class, () -> coordination.member("worker", Instant.now().plusSeconds(2)));
            assertThrows(IOException.class, () -> coordination.leader("job", Instant.now().plusSeconds(2)));
            assertThrows(IOException.class, () -> coordination.heartbeat("unsafe-heartbeat", "worker", memberAfter.version(), 60000, Instant.now().plusSeconds(2)));
            target.offset(0);
            long healthyEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) { try { assertNotNull(coordination.member("worker", Instant.now().plusSeconds(1))); break; }
                catch (IOException unhealthy) { if (System.nanoTime() >= healthyEnd) throw unhealthy; } }
            subscriptions.resume("resume", "reader", 60000, deadline()); var resumed = subscriptions.poll("reader", 10, deadline());
            assertEquals(pending.events().getFirst().cursor(), resumed.events().getFirst().cursor());
            subscriptions.ack("ack-after", "reader", resumed.through(), 60000, deadline());
            application.execute("BATCH", "new-owner", List.of("new-owner", "0", "v3"), deadline());
            var newEvent = subscriptions.poll("reader", 10, deadline()); assertEquals(1, newEvent.events().size()); assertEquals(2, newEvent.events().getFirst().cursor().epoch());
            assertEquals("v3", application.read("new-owner", deadline()).value()); assertNotNull(subscriptions.snapshotAndTail(deadline()));
            assertThrows(IOException.class, () -> new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", source.resourcePort)), "app", new byte[32], null).read("first", Instant.now().plusSeconds(2)));
            assertThrows(SecurityException.class, () -> application.execute("COMMIT", "forged-decision", List.of("transaction", "1"), deadline()));
            migration.migrate("reverse", "p", "left", 2, deadline());
            assertEquals(3, topology.admin().route("p", deadline()).epoch());
            assertEquals("left", topology.admin().route("p", deadline()).ownerGroup());
            assertEquals("v3", application.read("new-owner", deadline()).value());
            subscriptions.resume("reverse-resume", "reader", 60000, deadline());
            assertEquals("ABORTED", tx.recover("transaction", deadline()).status());
            var liveMember = coordination.member("worker", deadline()); assertNotNull(liveMember);
            assertEquals("OK", coordination.leave("leave", "worker", liveMember.version(), deadline()).status()); assertNull(coordination.member("worker", deadline()));
            assertEquals("after", application.read("protected", deadline()).value());
        }
    }
    @Test void unauthorizedTopologyCrossTenantAndMalformedControlCannotDamageHealthyGroup() throws Exception {
        try (var topology = new Child("topology", true, 0); var source = new Child("left", false, 1)) {
            assertThrows(SecurityException.class, () -> new PartitionAdminClient(topology.client(false)).register("p", "left", deadline()));
            topology.admin().register("p", "left", deadline());
            assertThrows(IllegalArgumentException.class, () -> source.client(true).exchange("PARTITION_COMMAND", new byte[] {7, 1}, deadline()));
            assertThrows(IllegalArgumentException.class, () -> topology.client(true).exchange("TOPOLOGY_COMMAND", new byte[] {3, 1}, deadline()));
            byte[] foreign = new ResourceCommand("BATCH", "other/tenant", "forged", List.of("secret", "0", "v")).encode();
            assertThrows(SecurityException.class, () -> source.client(false).execute(1, "forged", foreign, resultQuery("forged"), deadline()));
            assertEquals("OK", source.client(false).execute(1, "healthy", batch("healthy", List.of("k", "0", "v")), resultQuery("healthy"), deadline()).status());
            assertEquals("v", value(source.client(false).query(1, readQuery("k"), deadline())));
            assertEquals(1, topology.admin().route("p", deadline()).epoch());
            byte[] forgedTime = new ResourceCommand("TIME", "tenant/test", "forged-time", List.of("9999999999999", "9999999999999", "0")).encode();
            assertThrows(SecurityException.class, () -> source.client(true).execute(1, "forged-time", forgedTime, resultQuery("forged-time"), deadline()));
        }
    }
}
