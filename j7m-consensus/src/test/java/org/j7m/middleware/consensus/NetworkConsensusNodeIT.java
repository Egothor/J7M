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

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real child-JVM, TCP and durable-filesystem acceptance gates.
 */
@Timeout(240)
class NetworkConsensusNodeIT {
    @TempDir Path directory;

    @Test void tenThousandConcurrentCommandsPartitionAndFullRestartPreserveAcknowledgements() throws Exception {
        try (Cluster cluster = new Cluster(directory, 3)) {
            String leader = cluster.leader();
            List<CompletableFuture<History>> requests = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                String id = "op" + i; long invoked = System.nanoTime();
                requests.add(cluster.nodes.get(leader).ask("APPEND " + id + " 1")
                        .thenApply(position -> new History(id, invoked, System.nanoTime(), Long.parseLong(position))));
            }
            Set<Long> positions = new HashSet<>(); List<History> history = new ArrayList<>();
            for (var request : requests) { History operation = request.get(60, TimeUnit.SECONDS); assertTrue(positions.add(operation.index())); history.add(operation); }
            verifyAndRetainHistory(history);
            assertEquals(10_000, positions.size());
            assertTrue(Long.parseLong(cluster.call(leader, "READ")) >= Collections.max(positions));
            cluster.isolate(leader);
            String replacement = cluster.leaderExcept(leader);
            assertThrows(ExecutionException.class, () -> cluster.nodes.get(leader).ask("READ").get(8, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> cluster.nodes.get(leader).ask("APPEND uncertain 1").get(20, TimeUnit.SECONDS));
            cluster.call(replacement, "APPEND majority 1");
            cluster.heal();
            cluster.kill(leader); cluster.start(leader);
            cluster.awaitCount(10_001);
            for (String id : List.copyOf(cluster.nodes.keySet())) cluster.kill(id);
            for (String id : List.of("a", "b", "c")) cluster.start(id);
            String restored = cluster.leader(); cluster.call(restored, "READ"); cluster.awaitCount(10_001);
            assertEquals("10001,10001", cluster.call(restored, "STATE").substring(0, 11));
            List<CompletableFuture<String>> inFlight = new ArrayList<>();
            for (int i = 0; i < 1000; i++) inFlight.add(cluster.nodes.get(restored).ask("APPEND crash" + i + " 1"));
            inFlight.getFirst().get(20, TimeUnit.SECONDS); cluster.kill(restored);
            long acknowledged = inFlight.stream().filter(f -> f.isDone() && !f.isCompletedExceptionally()).count();
            String afterCrash = cluster.leader(); cluster.call(afterCrash, "READ");
            assertTrue(Long.parseLong(cluster.state(afterCrash).split(",")[0]) >= 10_001 + acknowledged);
            for (int i = 0; i < 1000; i++) cluster.call(afterCrash, "APPEND crash" + i + " 1");
            cluster.start(restored); cluster.awaitCount(11_001);
        }
    }

    @Test void removingCurrentLeaderCanCommitBeforeItsConfigurationReply() throws Exception { removeCurrentLeader(false); }

    @Test void leaderRemovalWaitsForLearnerAcknowledgementsAfterApplicationIsAlreadyVisible() throws Exception { removeCurrentLeader(true); }

    private void removeCurrentLeader(boolean delayLearnerReplies) throws Exception {
        try (Cluster cluster = new Cluster(directory, 3)) {
            String original = cluster.leader();
            cluster.call(original, "APPEND seed-removal 1");
            if (delayLearnerReplies) {
                cluster.proxies.get(original + "d").holdReplies(); cluster.proxies.get(original + "e").holdReplies();
            }
            cluster.start("d"); cluster.start("e");
            await(() -> cluster.state("d").startsWith("1,") && cluster.state("e").startsWith("1,"), 20);
            String target = List.of("a", "b", "c", "d", "e").stream().filter(id -> !id.equals(original))
                    .reduce((left, right) -> left + "," + right).orElseThrow();
            AtomicLong admissionRejections = new AtomicLong();
            ExecutionException reply = assertThrows(ExecutionException.class,
                    () -> configureAfterLearnerCatchup(cluster, original, 0, target, () -> {
                        admissionRejections.incrementAndGet();
                        if (delayLearnerReplies) {
                            cluster.proxies.get(original + "d").releaseReplies(); cluster.proxies.get(original + "e").releaseReplies();
                        }
                    }));
            if (delayLearnerReplies) assertTrue(admissionRejections.get() > 0, "Applied learner state must not substitute for coordinator ACK admission");
            assertTrue(reply.getCause().getMessage().contains("UNKNOWN: leadership changed"), "Actual configuration reply: " + reply.getCause().getMessage());
            String replacement = cluster.leader();
            assertNotEquals(original, replacement);
            assertCommittedConfiguration(cluster, replacement, 1, target.replace(',', ';'));
            ExecutionException stale = assertThrows(ExecutionException.class,
                    () -> cluster.nodes.get(replacement).ask("CONFIG 0 a,b,c,d,e").get(5, TimeUnit.SECONDS));
            assertTrue(stale.getCause().getMessage().contains("CONFLICT"));
            assertCommittedConfiguration(cluster, replacement, 1, target.replace(',', ';'));
            for (String id : target.split(",")) cluster.kill(id);
            for (String id : target.split(",")) cluster.start(id);
            assertCommittedConfiguration(cluster, cluster.leader(), 1, target.replace(',', ';'));
            System.out.println("REMOVED_COORDINATOR reply=UNKNOWN committedEpoch=1 members=" + target);
        }
    }

    @Test void learnerCatchupJointReconfigurationAndLeaderFailurePreserveOneElectorate() throws Exception {
        try (Cluster cluster = new Cluster(directory, 3)) {
            String leader = cluster.leader();
            for (int i = 0; i < 100; i++) cluster.call(leader, "APPEND before" + i + " 1");
            assertThrows(ExecutionException.class, () -> cluster.nodes.get(leader).ask("CONFIG 0 a,b,c,d,e").get(5, TimeUnit.SECONDS));
            cluster.start("d"); cluster.start("e");
            await(() -> cluster.state("d").startsWith("100,") && cluster.state("e").startsWith("100,"), 20);
            cluster.call(leader, "READ");
            CompletableFuture<String> first = cluster.nodes.get(leader).ask("CONFIG 0 a,b,c,d,e");
            CompletableFuture<String> competing = cluster.nodes.get(leader).ask("CONFIG 0 a,b,d,e");
            int successful = 0; List<String> rejections = new ArrayList<>();
            try { assertEquals("1", first.get(20, TimeUnit.SECONDS)); successful++; } catch (ExecutionException rejected) { rejections.add(rejected.getCause().toString()); }
            try { assertEquals("1", competing.get(20, TimeUnit.SECONDS)); successful++; } catch (ExecutionException rejected) { rejections.add(rejected.getCause().toString()); }
            // A committed FINAL may remove its replying coordinator: UNKNOWN is not nonexecution.
            String authority = cluster.leader();
            String[] committed = cluster.status(authority).split(",", -1);
            assertTrue(Set.of("a;b;c;d;e", "a;b;d;e").contains(committed[5]), Arrays.toString(committed));
            assertCommittedConfiguration(cluster, authority, 1, committed[5]);
            assertTrue(successful <= 1, "Both competing proposals must not be acknowledged: " + rejections);
            if (successful == 0) assertTrue(rejections.stream().anyMatch(message -> message.contains("UNKNOWN")), rejections.toString());
            for (String proposal : List.of("a,b,c,d,e", "a,b,d,e")) {
                ExecutionException stale = assertThrows(ExecutionException.class,
                        () -> cluster.nodes.get(authority).ask("CONFIG 0 " + proposal).get(5, TimeUnit.SECONDS));
                assertTrue(stale.getCause().getMessage().contains("CONFLICT"));
            }
            assertCommittedConfiguration(cluster, authority, 1, committed[5]);
            System.out.println("COMPETING_CONFIGURATION acknowledged=" + successful + " committedEpoch=1 members=" + committed[5]);
            // Establish one known five-member configuration before testing its replacement leader.
            long epoch = 1;
            if (!committed[5].equals("a;b;c;d;e")) {
                long barrier = Long.parseLong(cluster.call(authority, "READ"));
                await(() -> cluster.nodes.keySet().stream().allMatch(id -> Long.parseLong(cluster.status(id).split(",")[3]) >= barrier), 20);
                epoch = configureAfterLearnerCatchup(cluster, authority, 1, "a,b,c,d,e");
            }
            cluster.kill(authority);
            String replacement = cluster.leader();
            for (int i = 0; i < 100; i++) cluster.call(replacement, "APPEND during" + i + " 1");
            cluster.start(authority); cluster.awaitCount(200);
            String remaining = replacement + "," + List.of("a", "b", "c", "d", "e").stream().filter(id -> !id.equals(replacement)).limit(2).reduce((a,b) -> a + "," + b).orElseThrow();
            assertEquals(Long.toString(epoch + 1), cluster.call(replacement, "CONFIG " + epoch + " " + remaining));
            cluster.call(replacement, "READ");
            assertEquals(3, cluster.status(replacement).split(",")[5].split(";").length);
            for (String id : remaining.split(",")) { cluster.kill(id); cluster.start(id); }
            String newLeader = cluster.leader(); cluster.call(newLeader, "READ");
            assertEquals(3, cluster.status(newLeader).split(",")[5].split(";").length);
        }
    }

    private static void assertCommittedConfiguration(Cluster cluster, String leader, long epoch, String members) throws Exception {
        long barrier = Long.parseLong(cluster.call(leader, "READ"));
        String[] status = cluster.status(leader).split(",", -1);
        assertTrue(Long.parseLong(status[3]) >= barrier, "Fresh quorum barrier must be applied");
        assertEquals(Long.toString(epoch), status[4]);
        assertEquals(members, status[5]);
        assertEquals("0", status[6], "The unique winning configuration must be FINAL, not pending joint state");
    }

    private static long configureAfterLearnerCatchup(Cluster cluster, String leader, long epoch, String members) throws Exception {
        return configureAfterLearnerCatchup(cluster, leader, epoch, members, () -> { });
    }

    private static long configureAfterLearnerCatchup(Cluster cluster, String leader, long epoch, String members, Runnable rejectedBeforeAdmission) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            try { return Long.parseLong(cluster.call(leader, "CONFIG " + epoch + " " + members)); }
            catch (ExecutionException notCaughtUp) {
                if (!notCaughtUp.getCause().getMessage().startsWith("NOT_CAUGHT_UP:") || System.nanoTime() >= deadline) throw notCaughtUp;
                // Admission rejected before any log entry; wait only for the learner ACK precondition.
                String[] status = cluster.status(leader).split(",", -1);
                assertEquals(Long.toString(epoch), status[4]);
                assertEquals("0", status[6], "Rejected admission must not leave a pending joint configuration");
                rejectedBeforeAdmission.run();
                LockSupport.parkNanos(10_000_000);
            }
        }
    }

    @Test void hundredThousandCommandsCompactionOfflineReplicaAndResumeRecoverIdenticalState() throws Exception {
        try (Cluster cluster = new Cluster(directory, 3)) {
            String leader = cluster.leader(); String offline = List.of("a", "b", "c").stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow();
            cluster.kill(offline);
            for (int offset = 0; offset < 100_000; offset += 1000) {
                List<CompletableFuture<String>> batch = new ArrayList<>();
                for (int i = offset; i < offset + 1000; i++) batch.add(cluster.nodes.get(leader).ask("APPEND load" + i + " 1"));
                for (var operation : batch) operation.get(60, TimeUnit.SECONDS);
            }
            cluster.call(leader, "READ"); String before = cluster.call(leader, "STATE");
            assertTrue(before.startsWith("100000,100000,"));
            cluster.call(leader, "SNAPSHOT");
            long compactedSize = Files.size(directory.resolve(leader).resolve("network.wal"));
            assertTrue(compactedSize < 10_000_000, "compacted journal should discard entry payloads");
            cluster.start(offline);
            await(() -> hasPartial(directory.resolve(offline)), 20);
            cluster.kill(offline);
            assertTrue(hasPartial(directory.resolve(offline)), "interrupted transfer retains a resumable chunk");
            cluster.start(offline);
            await(() -> cluster.state(offline).equals(before), 60);
            cluster.kill(offline); cluster.start(offline);
            await(() -> cluster.state(offline).equals(before), 20);
            cluster.kill(leader); cluster.start(leader);
            String current = cluster.leader(); cluster.call(current, "READ"); cluster.awaitCount(100_000);
            assertEquals(before, cluster.state(leader));
            long replay = Long.parseLong(cluster.call(current, "APPEND load99999 1"));
            assertTrue(replay > 0); assertTrue(cluster.state(current).startsWith("100000,"));
        }
    }

    @Test void snapshotDuringPendingJointChangeRetainsVotingAuthorityAfterCrash() throws Exception {
        try (Cluster cluster = new Cluster(directory, 3)) {
            String leader = cluster.leader(); cluster.call(leader, "APPEND seed 1");
            cluster.start("d"); cluster.start("e");
            await(() -> cluster.state("d").startsWith("1,") && cluster.state("e").startsWith("1,"), 15);
            cluster.call(leader, "READ");
            String reachable = List.of("a", "b", "c").stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow();
            for (String id : List.of("a", "b", "c", "d", "e")) if (!id.equals(leader) && !id.equals(reachable)) cluster.isolate(id);
            CompletableFuture<String> changing = cluster.nodes.get(leader).ask("CONFIG 0 a,b,c,d,e");
            await(() -> cluster.status(leader).endsWith(",5"), 5);
            await(() -> cluster.status(reachable).endsWith(",5"), 5);
            cluster.call(leader, "SNAPSHOT"); cluster.kill(leader); cluster.start(leader);
            assertEquals("5", cluster.status(leader).split(",")[6]);
            assertEquals("0", cluster.status(leader).split(",")[4]);
            cluster.heal(); String replacement = cluster.leader();
            await(() -> cluster.status(replacement).split(",")[4].equals("1"), 20);
            assertEquals("a;b;c;d;e", cluster.status(replacement).split(",")[5]);
            assertThrows(ExecutionException.class, () -> changing.get(3, TimeUnit.SECONDS));
        }
    }
    private static boolean hasPartial(Path directory) {
        try (var files = Files.newDirectoryStream(directory, "snapshot-*.partial")) {
            for (Path path : files) if (Files.size(path) > 0) return true;
            return false;
        } catch (IOException e) { return false; }
    }

    private record History(String requestId, long invoked, long returned, long index) { }
    private static void verifyAndRetainHistory(List<History> history) throws IOException {
        List<History> invoked = history.stream().sorted(Comparator.comparingLong(History::invoked)).toList();
        PriorityQueue<History> completions = new PriorityQueue<>(Comparator.comparingLong(History::returned)); completions.addAll(history);
        long completedPrefixMaximum = 0;
        for (History operation : invoked) {
            while (!completions.isEmpty() && completions.peek().returned() < operation.invoked()) completedPrefixMaximum = Math.max(completedPrefixMaximum, completions.remove().index());
            assertTrue(operation.index() > completedPrefixMaximum, "real-time order violated by " + operation.requestId());
        }
        Path artifact = Path.of("build/reports/network-consensus/linearization-history.csv"); Files.createDirectories(artifact.getParent());
        StringBuilder csv = new StringBuilder("requestId,invokedNanos,returnedNanos,committedIndex\n");
        for (History operation : history) csv.append(operation.requestId()).append(',').append(operation.invoked()).append(',').append(operation.returned()).append(',').append(operation.index()).append('\n');
        Files.writeString(artifact, csv);
    }

    static void await(BooleanSupplier condition, int seconds) {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("condition did not converge"); LockSupport.parkNanos(5_000_000); }
    }
    static final class Cluster implements AutoCloseable {
        final Path directory;
        final Map<String, Child> nodes = new ConcurrentHashMap<>();
        final Map<String, Integer> ports = new HashMap<>();
        final Map<String, Proxy> proxies = new HashMap<>();
        final Set<String> isolated = ConcurrentHashMap.newKeySet();
        Cluster(Path directory, int count) throws Exception {
            this.directory = directory;
            for (String id : List.of("a", "b", "c", "d", "e")) try (ServerSocket socket = new ServerSocket(0)) { ports.put(id, socket.getLocalPort()); }
            for (String from : ports.keySet()) for (String to : ports.keySet()) if (!from.equals(to)) proxies.put(from + to, new Proxy(from, to, ports.get(to), isolated));
            for (String id : List.of("a", "b", "c").subList(0, count)) start(id);
        }
        void start(String id) throws Exception {
            String endpoints = ports.keySet().stream().sorted().map(peer -> peer + ":" + (peer.equals(id) ? ports.get(peer) : proxies.get(id + peer).server.getLocalPort())).reduce((a,b) -> a + "," + b).orElseThrow();
            nodes.put(id, new Child(id, directory.resolve(id), endpoints));
        }
        void kill(String id) { Child child = nodes.remove(id); if (child != null) child.close(); }
        void isolate(String id) { isolated.add(id); proxies.values().forEach(Proxy::drop); }
        void heal() { isolated.clear(); }
        String call(String id, String command) throws Exception { return nodes.get(id).ask(command).get(25, TimeUnit.SECONDS); }
        String state(String id) { try { return call(id, "STATE"); } catch (Exception e) { return ""; } }
        String status(String id) { try { return call(id, "STATUS"); } catch (Exception e) { return ",0,0,0,0,,0"; } }
        String leader() { return leaderExcept(""); }
        String leaderExcept(String excluded) {
            String[] result = {null};
            await(() -> {
                for (String id : nodes.keySet()) if (!id.equals(excluded) && !isolated.contains(id)) {
                    String[] status = status(id).split(",");
                    if (status.length > 0 && status[0].equals(id)) {
                        try { call(id, "READ"); result[0] = id; return true; } catch (Exception ignored) { }
                    }
                }
                return false;
            }, 25); return result[0];
        }
        void awaitCount(long count) { await(() -> nodes.keySet().stream().allMatch(id -> state(id).startsWith(count + ",")), 30); }
        @Override public void close() { nodes.values().forEach(Child::close); proxies.values().forEach(Proxy::close); }
    }
    static final class Child implements AutoCloseable {
        final Process process; final BufferedWriter writer; final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
        final AtomicLong sequence = new AtomicLong();
        final BlockingQueue<String> faults = new LinkedBlockingQueue<>();
        Child(String id, Path path, String endpoints) throws Exception {
            Files.createDirectories(path);
            String classpath = System.getProperty("j7m.consensus.test.classpath", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m", "-cp", classpath,
                    NetworkConsensusNodeProcess.class.getName(), id, path.toString(), endpoints, "a,b,c").redirectError(path.resolve("process.err").toFile()).start();
            writer = process.outputWriter(); BufferedReader reader = process.inputReader();
            CompletableFuture<Void> ready = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try { String line; while ((line = reader.readLine()) != null) {
                    if (line.equals("READY")) { ready.complete(null); continue; }
                    if (line.startsWith("FAULT ")) { faults.add(line.substring(6)); continue; }
                    String[] parts = line.split(" ", 3); CompletableFuture<String> future = pending.remove(parts[0]);
                    if (future != null) { if (parts[1].equals("OK")) future.complete(parts.length == 3 ? parts[2] : ""); else future.completeExceptionally(new IOException(parts.length == 3 ? parts[2] : "error")); }
                } } catch (IOException e) { ready.completeExceptionally(e); }
                finally { pending.values().forEach(f -> f.completeExceptionally(new IOException("process stopped"))); }
            });
            ready.get(10, TimeUnit.SECONDS);
        }
        CompletableFuture<String> ask(String command) {
            String id = Long.toString(sequence.incrementAndGet()); CompletableFuture<String> future = new CompletableFuture<>(); pending.put(id, future);
            synchronized (writer) { try { writer.write(id + " " + command + "\n"); writer.flush(); } catch (IOException e) { future.completeExceptionally(e); } }
            return future;
        }
        @Override public void close() { process.destroyForcibly(); try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
    static final class Proxy implements AutoCloseable {
        final ServerSocket server; final String source, target; final int port; final Set<String> isolated;
        final Set<Socket> active = ConcurrentHashMap.newKeySet(); volatile boolean closed;
        private volatile CountDownLatch replyGate;
        Proxy(String source, String target, int port, Set<String> isolated) throws IOException {
            this.source = source; this.target = target; this.port = port; this.isolated = isolated; server = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> { while (!closed) try {
                Socket inbound = server.accept();
                Thread.ofVirtual().start(() -> {
                    try (inbound; Socket outbound = new Socket()) {
                        if (isolated.contains(source) || isolated.contains(target)) return;
                        outbound.connect(new InetSocketAddress("127.0.0.1", port), 100); active.add(inbound); active.add(outbound);
                        Thread relay = Thread.ofVirtual().start(() -> copy(inbound, outbound));
                        CountDownLatch gate = replyGate;
                        if (gate != null && !gate.await(10, TimeUnit.SECONDS)) throw new IOException("Test reply gate deadline");
                        copy(outbound, inbound); relay.join(1000);
                    } catch (IOException ignored) { } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { active.remove(inbound); }
                });
            } catch (IOException ignored) { } });
        }
        private static void copy(Socket from, Socket to) { try { from.getInputStream().transferTo(to.getOutputStream()); to.shutdownOutput(); } catch (IOException ignored) { } }
        void holdReplies() { replyGate = new CountDownLatch(1); }
        void releaseReplies() { CountDownLatch gate = replyGate; if (gate != null) { gate.countDown(); replyGate = null; } }
        void drop() { if (isolated.contains(source) || isolated.contains(target)) for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { } }
        @Override public void close() { closed = true; releaseReplies(); try { server.close(); } catch (IOException ignored) { } active.forEach(socket -> { try { socket.close(); } catch (IOException ignored) { } }); }
    }
}
