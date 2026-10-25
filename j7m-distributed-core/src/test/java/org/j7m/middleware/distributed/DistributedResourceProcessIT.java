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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DistributedResourceProcessIT {
    @TempDir Path directory;
    private static final byte[] COORDINATOR_KEY = new byte[32];
    static { Arrays.fill(COORDINATOR_KEY, (byte) 1); }
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
    private final class Child implements AutoCloseable {
        final String id, peers; final Path disk; final int resourcePort;
        Process process; BufferedReader output; BufferedWriter input;
        Child(String id, String peers, int resourcePort) throws Exception {
            this.id = id; this.peers = peers; this.resourcePort = resourcePort; disk = directory.resolve(id); start();
        }
        void start() throws Exception {
            String classpath = System.getProperty("j7m.distributed.test.classpath", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx192m", "-cp", classpath, ResourceNodeProcess.class.getName(), id, disk.toString(), peers,
                    Integer.toString(resourcePort)).redirectError(disk.resolveSibling(id + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream()));
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            String ready = CompletableFuture.supplyAsync(() -> {
                try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); }
            }).get(15, TimeUnit.SECONDS);
            assertNotNull(ready, () -> "Child startup failed: " + disk.resolveSibling(id + ".stderr"));
            assertTrue(ready.startsWith("READY"), ready);
        }
        String command(String command) throws Exception {
            input.write(command); input.newLine(); input.flush();
            return CompletableFuture.supplyAsync(() -> {
                try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); }
            }).get(15, TimeUnit.SECONDS);
        }
        void signal(String signal) throws Exception {
            Process command = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid())).start();
            assertTrue(command.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, command.exitValue());
        }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        RemoteResourceClient client() {
            return new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", resourcePort)), "coordinator", COORDINATOR_KEY, null);
        }
        @Override public void close() { if (process != null && process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    }
    private static <T> T await(Callable<T> operation) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L; Exception last = null;
        do {
            try { return operation.call(); } catch (IOException e) { last = e; Thread.onSpinWait(); }
        } while (System.nanoTime() < end);
        throw new AssertionError("No quorum operation completed within development recovery bound", last);
    }
    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_PREPARE", "AFTER_LEFT_PREPARE", "AFTER_BOTH_PREPARE",
            "AFTER_DECISION", "AFTER_LEFT_ACK", "AFTER_ALL_ACK"})
    void killAndRecoverCoordinatorAndIndependentParticipantJvms(String point) throws Exception {
        int c = freePort(), l = freePort(), r = freePort();
        try (var coordinator = new Child("coordinator", "coordinator:" + c, freePort());
                var left = new Child("left", "left:" + l, freePort());
                var right = new Child("right", "right:" + r, freePort())) {
            var cClient = coordinator.client(); var lClient = left.client(); var rClient = right.client();
            var tx = new RecoverableTransactions(cClient, Map.of("left", lClient, "right", rClient));
            await(() -> tx.begin("transaction", deadline()));
            tx.enlist("transaction", "left", deadline()); tx.enlist("transaction", "right", deadline());
            boolean preparedLeft = !point.equals("BEFORE_PREPARE");
            boolean preparedBoth = preparedLeft && !point.equals("AFTER_LEFT_PREPARE");
            boolean committed = Set.of("AFTER_DECISION", "AFTER_LEFT_ACK", "AFTER_ALL_ACK").contains(point);
            if (preparedLeft) await(() -> tx.prepare("transaction", "left", List.of("WRITE", "x", "left-value"), deadline()));
            if (preparedBoth) await(() -> tx.prepare("transaction", "right", List.of("WRITE", "y", "right-value"), deadline()));
            if (committed) cClient.execute("DECIDE", "transaction:decision", List.of("transaction", "COMMITTED"), deadline());
            if (point.equals("AFTER_LEFT_ACK") || point.equals("AFTER_ALL_ACK")) {
                var decision = tx.status("transaction", deadline());
                lClient.execute("COMMIT", "left-ack", List.of("transaction", decision.value()), deadline());
            }
            if (point.equals("AFTER_ALL_ACK")) tx.recover("transaction", deadline());
            coordinator.command("SNAPSHOT"); left.command("SNAPSHOT"); right.command("SNAPSHOT");
            coordinator.kill(); left.kill(); right.kill();
            coordinator.start(); left.start(); right.start();
            var outcome = await(() -> tx.recover("transaction", deadline()));
            if (!committed) {
                assertEquals("IN_DOUBT", outcome.status());
                if (preparedLeft) assertNull(await(() -> lClient.read("x", deadline())));
                outcome = await(() -> tx.abort("transaction", deadline()));
            }
            assertEquals(committed ? "COMMITTED" : "ABORTED", outcome.status());
            assertEquals(committed ? "left-value" : null, Optional.ofNullable(await(() -> lClient.read("x", deadline())))
                    .map(ReplicatedResources.Value::value).orElse(null));
            assertEquals(committed ? "right-value" : null, Optional.ofNullable(await(() -> rClient.read("y", deadline())))
                    .map(ReplicatedResources.Value::value).orElse(null));
            assertEquals(outcome.status(), tx.recover("transaction", deadline()).status());
            assertTrue(lClient.prepared(deadline()).isEmpty()); assertTrue(rClient.prepared(deadline()).isEmpty());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"COPY", "RESULT", "EVENT", "PUBLISH"})
    void killAtAtomicStateResultEventPublicationBoundaryRecoversWholeBatch(String phase) throws Exception {
        try (var child = new Child("atomic", "atomic:" + freePort(), freePort())) {
            var endpoint = child.client(); await(() -> endpoint.read("absent", deadline()));
            child.command("ARM " + phase);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var submission = executor.submit(() -> endpoint.execute("BATCH", "atomic-batch", List.of("left", "0", "one", "right", "0", "two"), deadline()));
                String fault = CompletableFuture.supplyAsync(() -> {
                    try { return child.output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); }
                }).get(15, TimeUnit.SECONDS);
                assertEquals("FAULT " + phase, fault); child.kill();
                assertThrows(ExecutionException.class, () -> submission.get(15, TimeUnit.SECONDS));
            }
            child.start();
            var result = await(() -> endpoint.execute("BATCH", "atomic-batch", List.of("left", "0", "one", "right", "0", "two"), deadline()));
            assertEquals("OK", result.status());
            var left = endpoint.read("left", deadline()); var right = endpoint.read("right", deadline());
            assertEquals("one", left.value()); assertEquals("two", right.value()); assertEquals(left.version(), right.version());
            assertEquals(result.index(), left.version());
            assertEquals(result, endpoint.execute("BATCH", "atomic-batch", List.of("left", "0", "one", "right", "0", "two"), deadline()));
            assertEquals(1, Long.parseLong(child.command("EVENTS").split(" ")[1]));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"PREPARE", "DECIDE", "COMMIT"})
    void lostInFlightReplyAndJvmKillRecoverFromLogsWithoutSnapshots(String boundary) throws Exception {
        try (var coordinator = new Child("coordinator", "coordinator:" + freePort(), freePort());
                var left = new Child("left", "left:" + freePort(), freePort());
                var right = new Child("right", "right:" + freePort(), freePort())) {
            var c = coordinator.client(); var l = left.client(); var r = right.client();
            await(() -> c.read("ready", deadline())); await(() -> l.read("ready", deadline())); await(() -> r.read("ready", deadline()));
            var cut = new java.util.concurrent.atomic.AtomicBoolean();
            ResourceEndpoint faulty = (ResourceEndpoint) java.lang.reflect.Proxy.newProxyInstance(ResourceEndpoint.class.getClassLoader(),
                    new Class<?>[] {ResourceEndpoint.class}, (proxy, method, arguments) -> {
                        ResourceEndpoint target = boundary.equals("DECIDE") ? c : l;
                        Object returned;
                        try { returned = method.invoke(target, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        if (method.getName().equals("execute") && arguments[0].equals(boundary) && cut.compareAndSet(false, true)) {
                            (boundary.equals("DECIDE") ? coordinator : left).kill();
                            throw new IOException("Injected lost response after durable " + boundary + "; outcome UNKNOWN");
                        }
                        return returned;
                    });
            var tx = new RecoverableTransactions(boundary.equals("DECIDE") ? faulty : c,
                    Map.of("left", boundary.equals("DECIDE") ? l : faulty, "right", r));
            await(() -> tx.begin("in-flight", deadline())); tx.enlist("in-flight", "left", deadline()); tx.enlist("in-flight", "right", deadline());
            if (boundary.equals("PREPARE")) {
                assertThrows(IOException.class, () -> tx.prepare("in-flight", "left", List.of("WRITE", "x", "one"), deadline()));
            } else {
                await(() -> tx.prepare("in-flight", "left", List.of("WRITE", "x", "one"), deadline()));
                await(() -> tx.prepare("in-flight", "right", List.of("WRITE", "y", "two"), deadline()));
                assertThrows(IOException.class, () -> tx.commit("in-flight", deadline()));
            }
            assertTrue(cut.get()); (boundary.equals("DECIDE") ? coordinator : left).start();
            var recovery = new RecoverableTransactions(c, Map.of("left", l, "right", r));
            var outcome = await(() -> recovery.recover("in-flight", deadline()));
            if (boundary.equals("PREPARE")) {
                assertEquals("IN_DOUBT", outcome.status()); outcome = await(() -> recovery.abort("in-flight", deadline()));
                assertEquals("ABORTED", outcome.status()); assertNull(l.read("x", deadline())); assertNull(r.read("y", deadline()));
            } else {
                assertEquals("COMMITTED", outcome.status()); assertEquals("one", l.read("x", deadline()).value());
                assertEquals("two", r.read("y", deadline()).value());
            }
            assertEquals(outcome.status(), recovery.recover("in-flight", deadline()).status());
        }
    }
    @Test void distributedReadLocksPreventWriteSkewAndPreparedRangeSurvivesLostCoordinator() throws Exception {
        try (var coordinator = new Child("coordinator", "coordinator:" + freePort(), freePort());
                var left = new Child("left", "left:" + freePort(), freePort());
                var right = new Child("right", "right:" + freePort(), freePort())) {
            var c = coordinator.client(); var l = left.client(); var r = right.client();
            var tx = new RecoverableTransactions(c, Map.of("left", l, "right", r));
            await(() -> l.execute("BATCH", "seed-x", List.of("x", "0", "1"), deadline()));
            await(() -> r.execute("BATCH", "seed-y", List.of("y", "0", "1"), deadline()));
            await(() -> tx.begin("first", deadline())); tx.begin("second", deadline());
            for (String id : List.of("left", "right")) {
                tx.enlist("first", id, deadline()); tx.enlist("second", id, deadline());
            }
            assertEquals("1", tx.read("first", "left", "first-read-x", "x", deadline()).value());
            assertEquals("1", tx.read("first", "right", "first-read-y", "y", deadline()).value());
            assertEquals("CONFLICT", tx.read("second", "right", "second-read-y", "y", deadline()).status());
            assertEquals("ABORTED", tx.status("second", deadline()).status());
            tx.prepare("first", "left", List.of("WRITE", "x", "0"), deadline());
            tx.prepare("first", "right", List.of("READ", "y", Long.toString(r.read("y", deadline()).version())), deadline());
            tx.commit("first", deadline());
            assertEquals("0", l.read("x", deadline()).value()); assertEquals("1", r.read("y", deadline()).value());
            tx.begin("range", deadline()); tx.enlist("range", "left", deadline());
            tx.lockRange("range", "left", "range-read", "prefix", deadline());
            tx.prepare("range", "left", List.of("WRITE", "x", "2"), deadline());
            left.command("SNAPSHOT"); coordinator.kill();
            assertThrows(IOException.class, () -> tx.recover("range", Instant.now().plusMillis(500)));
            assertEquals("CONFLICT", l.execute("BATCH", "phantom", List.of("prefix/new", "0", "bad"), deadline()).status());
            left.kill(); left.start();
            assertEquals("PREPARED", await(() -> l.transaction("range", deadline())).status());
            assertEquals("CONFLICT", l.execute("BATCH", "phantom-restart", List.of("prefix/new", "0", "bad"), deadline()).status());
            coordinator.start(); await(() -> tx.abort("range", deadline()));
            assertEquals("OK", l.execute("BATCH", "after-abort", List.of("prefix/new", "0", "good"), deadline()).status());
            assertEquals("0", l.read("x", deadline()).value());
        }
    }
    @Test void realJvmClockJumpsFailClosedAndAutomaticCommittedTimeReleasesLeaseWithinFiveSeconds() throws Exception {
        try (var child = new Child("clock", "clock:" + freePort(), freePort())) {
            var endpoint = child.client();
            await(() -> endpoint.read("absent", deadline()));
            String term = child.command("STATUS").split(" ")[2];
            var original = await(() -> endpoint.execute("ACQUIRE", "lease-original", List.of("lease", "one", "1500", "0"), deadline()));
            assertEquals("OK", original.status());
            for (long jump : List.of(-60000L, 60000L)) {
                child.command("OFFSET " + jump);
                assertThrows(IOException.class, () -> endpoint.execute("ACQUIRE", "unsafe-" + jump,
                        List.of("other", "two", "1500", "0"), deadline()));
                assertNull(endpoint.read("target", deadline()));
            }
            child.command("OFFSET 0");
            long restoredAt = System.nanoTime();
            ReplicatedResources.Result acquired;
            do {
                try {
                    acquired = endpoint.execute("ACQUIRE", "takeover-" + UUID.randomUUID(),
                            List.of("lease", "two", "1500", "0"), deadline());
                    if (acquired.status().equals("OK")) break;
                } catch (IOException unsafeWhileRecovering) {
                    assertTrue(unsafeWhileRecovering.getMessage().contains("UNKNOWN"),
                            "Recovery may remain unavailable while measured clock health stabilizes");
                }
                acquired = null;
                if (System.nanoTime() - restoredAt > 5_000_000_000L) fail("Expired lease not released within five seconds");
            } while (true);
            assertTrue(Long.parseLong(acquired.value()) > Long.parseLong(original.value()));
            assertEquals("CONFLICT", endpoint.execute("FENCED", "old-owner", List.of("lease", original.value(),
                    term, "target", "stale"), deadline()).status());
            child.command("SNAPSHOT"); child.kill(); child.start();
            await(() -> endpoint.read("target", deadline()));
            assertEquals("CONFLICT", endpoint.execute("FENCED", "old-after-restart", List.of("lease", original.value(),
                    term, "target", "stale"), deadline()).status());
        }
    }
    @Test void threeJvmReplicasHaveOneCasWinnerAndIdenticalAtomicStateAfterRestart() throws Exception {
        int p1 = freePort(), p2 = freePort(), p3 = freePort();
        String peers = "one:" + p1 + ",two:" + p2 + ",three:" + p3;
        try (var one = new Child("one", peers, freePort()); var two = new Child("two", peers, freePort());
                var three = new Child("three", peers, freePort())) {
            var children = List.of(one, two, three);
            var endpoint = new RemoteResourceClient(children.stream().map(child -> new InetSocketAddress("127.0.0.1", child.resourcePort)).toList(),
                    "coordinator", COORDINATOR_KEY, null);
            await(() -> endpoint.read("absent", deadline()));
            try (var pool = Executors.newFixedThreadPool(50)) {
                var futures = new ArrayList<Future<ReplicatedResources.Result>>(); var start = new CountDownLatch(1);
                for (int i = 0; i < 50; i++) {
                    final int id = i;
                    futures.add(pool.submit(() -> { start.await(); return await(() -> endpoint.execute("BATCH", "cas-" + id,
                            List.of("winner", "0", Integer.toString(id)), deadline())); }));
                }
                start.countDown(); int winners = 0;
                for (var future : futures) {
                    var result = future.get(35, TimeUnit.SECONDS);
                    if (result.status().equals("OK")) winners++; else assertEquals("CONFLICT", result.status());
                }
                assertEquals(1, winners);
            }
            try (var pool = Executors.newFixedThreadPool(50)) {
                var futures = new ArrayList<Future<ReplicatedResources.Result>>(); var start = new CountDownLatch(1);
                for (int i = 0; i < 50; i++) {
                    final int id = i;
                    futures.add(pool.submit(() -> { start.await(); return await(() -> endpoint.execute("ACQUIRE", "lease-race-" + id,
                            List.of("race", Integer.toString(id), "10000", "0"), deadline())); }));
                }
                start.countDown(); int winners = 0;
                for (var future : futures) {
                    var result = future.get(35, TimeUnit.SECONDS);
                    if (result.status().equals("OK")) winners++; else assertEquals("CONFLICT", result.status());
                }
                assertEquals(1, winners, "Only quorum can grant a single lease to 50 contenders");
            }
            var expected = endpoint.read("winner", deadline());
            var acquired = endpoint.execute("ACQUIRE", "leader-change-lease", List.of("owned", "old", "1000", "0"), deadline());
            assertEquals("OK", acquired.status());
            Child oldLeader = null; String oldEpoch = null;
            for (var child : children) {
                String[] status = child.command("STATUS").split(" ");
                if (child.id.equals(status[3])) { oldLeader = child; oldEpoch = status[2]; break; }
            }
            assertNotNull(oldLeader); assertNotNull(oldEpoch); oldLeader.signal("STOP");
            Child suspended = oldLeader;
            var majorityEndpoint = new RemoteResourceClient(children.stream().filter(child -> child != suspended)
                    .map(child -> new InetSocketAddress("127.0.0.1", child.resourcePort)).toList(), "coordinator", COORDINATOR_KEY, null);
            assertEquals(acquired, await(() -> majorityEndpoint.execute("ACQUIRE", "leader-change-lease",
                    List.of("owned", "old", "1000", "0"), deadline())), "Retry must preserve result through a leadership epoch change");
            long takeoverDeadline = System.nanoTime() + 5_000_000_000L;
            ReplicatedResources.Result takeover;
            do {
                takeover = await(() -> majorityEndpoint.execute("ACQUIRE", "takeover-" + UUID.randomUUID(),
                        List.of("owned", "new", "10000", "0"), deadline()));
                if (takeover.status().equals("OK")) break;
                if (System.nanoTime() > takeoverDeadline) fail("No takeover after healthy committed expiry");
            } while (true);
            assertTrue(Long.parseLong(takeover.value()) > Long.parseLong(acquired.value()));
            assertEquals("CONFLICT", majorityEndpoint.execute("FENCED", "stale-leader-fence",
                    List.of("owned", acquired.value(), oldEpoch, "protected", "bad"), deadline()).status());
            var renewed = majorityEndpoint.execute("RENEW", "new-owner-renewal", List.of("owned", Long.toString(takeover.index()),
                    "new", "10000", "0"), deadline());
            assertEquals("OK", renewed.status()); assertTrue(Long.parseLong(renewed.value()) > Long.parseLong(takeover.value()));
            oldLeader.signal("CONT");
            String previousEpoch = oldEpoch;
            try {
                assertEquals("CONFLICT", suspended.client().execute("FENCED", "resumed-old-leader",
                        List.of("owned", acquired.value(), previousEpoch, "protected", "stale"), Instant.now().plusSeconds(2)).status());
            } catch (IOException unavailableOldLeader) {
                assertTrue(unavailableOldLeader.getMessage().contains("UNKNOWN"));
            }
            assertNull(await(() -> endpoint.read("protected", deadline())));
            assertEquals("CONFLICT", endpoint.execute("EXPIRE", "stale-expiration-after-resume",
                    List.of("owned", Long.toString(acquired.index())), deadline()).status());
            long renewedDeadline = System.nanoTime() + 5_000_000_000L;
            boolean renewedVisible;
            do {
                renewedVisible = true;
                for (var child : children) {
                    String[] observed = child.command("LEASE owned").split(" ");
                    renewedVisible &= observed.length > 3 && observed[1].equals(Long.toString(renewed.index()))
                            && observed[2].equals(renewed.value());
                }
                if (renewedVisible) break;
            } while (System.nanoTime() < renewedDeadline);
            assertTrue(renewedVisible, "Stale expiration must preserve renewed version and fencing on all replicas");
            for (var child : children) child.command("PAUSE");
            var barrier = endpoint.execute("BATCH", "barrier", List.of("barrier", "0", "v"), deadline());
            long end = System.nanoTime() + 10_000_000_000L; Set<String> hashes;
            do {
                hashes = new HashSet<>(); boolean applied = true;
                for (var child : children) {
                    String[] status = child.command("STATUS").split(" ");
                    applied &= Long.parseLong(status[1]) >= barrier.index(); hashes.add(status[4]);
                }
                if (applied && hashes.size() == 1) break;
            } while (System.nanoTime() < end);
            assertEquals(1, hashes.size(), "Same log must produce identical state/result/event/lock hash");
            for (var child : children) child.command("SNAPSHOT");
            for (var child : children) child.kill();
            for (var child : children) child.start();
            assertEquals(expected, await(() -> endpoint.read("winner", deadline())));
            assertEquals("v", endpoint.read("barrier", deadline()).value());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_DELIVERY", "BEFORE_ACK"})
    void committedJournalAndSubscriptionSurviveProcessKillWithoutLostEvents(String point) throws Exception {
        try (var child = new Child("journal", "journal:" + freePort(), freePort())) {
            var endpoint = child.client(); await(() -> endpoint.read("absent", deadline()));
            var events = new EventSubscriptions(endpoint); var image = events.snapshotAndTail(deadline());
            assertEquals("OK", events.subscribe("subscribe", "consumer", "BATCH", image.cursor(), 86400000, deadline()).status());
            assertEquals("OK", endpoint.execute("BATCH", "change", List.of("x", "0", "committed"), deadline()).status());
            EventJournal.Cursor delivered = null;
            if (point.equals("BEFORE_ACK")) delivered = events.poll("consumer", 10, deadline()).events().getFirst().cursor();
            child.kill(); child.start();
            var page = await(() -> events.poll("consumer", 10, deadline()));
            assertEquals(1, page.events().size()); var event = page.events().getFirst();
            if (delivered != null) assertEquals(delivered, event.cursor());
            var change = ResourceEvent.decode(event.payload()); assertEquals("change", change.command().requestId()); assertEquals("OK", change.status());
            assertEquals("committed", endpoint.read("x", deadline()).value());
            assertEquals("OK", events.resume("resume", "consumer", 86400000, deadline()).status());
            assertEquals(event.cursor(), events.poll("consumer", 10, deadline()).events().getFirst().cursor());
            assertEquals("OK", events.ack("ack", "consumer", page.through(), 86400000, deadline()).status());
            child.command("SNAPSHOT"); child.kill(); child.start();
            assertTrue(await(() -> events.poll("consumer", 10, deadline())).events().isEmpty());
        }
    }
}
