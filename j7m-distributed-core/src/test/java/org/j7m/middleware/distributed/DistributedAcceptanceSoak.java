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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.j7m.middleware.consensus.ConsensusBackup;

/**
 * Reproducible full-hour active CP acceptance: real3/5JVMs, faults, durable restart and fresh restore.
 * This qualification harness uses production APIs only; it is neither middleware nor an application demo.
 */
public final class DistributedAcceptanceSoak {
    static final long REQUIRED_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final long CYCLE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long FAULT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private DistributedAcceptanceSoak() { }
    private record ProfileResult(String kind, Properties properties) { }
    private record MapAck(String request, List<String> arguments, ReplicatedResources.Result result) { }
    private record QueueAck(String request, String message, byte[] payload, String sent, String delivery, String acknowledged) { }
    private static final class Worker implements Callable<Properties> {
        final AcceptanceCluster cluster; final Path output; long start, end; final PrintWriter history;
        final List<MapAck> maps = new ArrayList<>(); final List<QueueAck> queues = new ArrayList<>();
        final Set<String> delivered = new HashSet<>();
        long issued, confirmed, faults, restarts, maxRecovery, nextProgress;
        Worker(AcceptanceCluster cluster, Path output, long start, PrintWriter history) { this.cluster = cluster; this.output = output; this.start = start; end = start + REQUIRED_NANOS; this.history = history; nextProgress = start; }
        @Override public Properties call() throws Exception {
            long next = start, nextFault = start + FAULT_NANOS;
            try {
                while (System.nanoTime() < end) {
                    if (Files.exists(output.resolve("STOP")) || Thread.currentThread().isInterrupted()) throw new IOException("Acceptance soak cancelled");
                    if (System.nanoTime() >= nextFault) { fault(); nextFault = System.nanoTime() + FAULT_NANOS; }
                    else work();
                    if (cluster.kind.equals("map") && confirmed % 100 == 0) consumeEvents();
                    long now = System.nanoTime();
                    if (now >= nextProgress) { log("progress", "confirmed=" + confirmed + " faults=" + faults); nextProgress = now + TimeUnit.SECONDS.toNanos(10); }
                    next = Math.max(next + CYCLE_NANOS, System.nanoTime());
                    // Fixed-rate pacing defines workload intensity; it does not wait for a safety assertion to become true.
                    if (next > System.nanoTime()) LockSupport.parkNanos(next - System.nanoTime());
                }
                long elapsed = System.nanoTime() - start;
                if (elapsed < REQUIRED_NANOS) throw new AssertionError("Active soak interval was shortened");
                log("active-complete", "activeNanos=" + elapsed + " confirmed=" + confirmed);
                verify(false); cluster.restartAll(); restarts++; verify(false);
                if (cluster.kind.equals("map")) consumeEvents();
                Path backup = output.resolve(cluster.kind + "-final-backup"); ConsensusBackup.Metadata metadata = cluster.backup(backup);
                cluster.restore(backup, metadata.index()); verify(true);
                log("restore-verified", "index=" + metadata.index() + " checkpoint=" + metadata.checkpointSha256());
                Properties result = new Properties(); result.setProperty("profile", Integer.toString(cluster.replicas.size())); result.setProperty("activeNanos", Long.toString(elapsed)); result.setProperty("confirmed", Long.toString(confirmed)); result.setProperty("faults", Long.toString(faults)); result.setProperty("fullRestarts", Long.toString(restarts)); result.setProperty("maxRecoveryMillis", Long.toString(maxRecovery)); result.setProperty("restoreVerified", "true"); result.setProperty("backupIndex", Long.toString(metadata.index())); result.setProperty("backupSha256", metadata.checkpointSha256()); result.setProperty("ledgerSha256", ledger());
                return result;
            } catch (Throwable error) { log("failure", error.getClass().getName()); throw error; }
        }
        private void initialize() throws Exception {
            if (cluster.kind.equals("map")) {
                retry(() -> cluster.map().read("absent", attemptDeadline()));
                var events = new EventSubscriptions(cluster.map()); var view = events.snapshotAndTail(attemptDeadline());
                require("OK", events.subscribe("soak-subscribe", "soak-consumer", "BATCH", view.cursor(), 86400000, attemptDeadline()).status());
                require("PREPARED", retry(() -> cluster.map().execute("PREPARE", "soak-prepare", List.of("soak-transaction", "WRITE", "held-key", "prepared-value"), attemptDeadline())).status());
            } else require("OK\t1", retry(() -> cluster.queue().join("soak-join", "workers", "worker", 86400000, attemptDeadline())));
        }
        private Instant attemptDeadline() { return Instant.now().plusSeconds(3); }
        private void work() throws Exception { complete(++issued); }
        private void complete(long number) throws Exception {
            String request = "work-" + number;
            if (cluster.kind.equals("map")) {
                List<String> arguments = List.of("key-" + number, "0", "value-" + number);
                var result = retry(() -> cluster.map().execute("BATCH", request, arguments, attemptDeadline())); require("OK", result.status()); maps.add(new MapAck(request, arguments, result));
            } else {
                String message = "message-" + number; byte[] payload = ("payload-" + number).getBytes(StandardCharsets.UTF_8);
                String sent = retry(() -> cluster.queue().send(request, "q", message, payload, 3, attemptDeadline())); if (!sent.startsWith("SENT\t")) throw new AssertionError("Queue send: " + sent);
                String delivery = retry(() -> cluster.queue().claim("claim-" + number, "q", "workers", "worker", 1, 86400000, attemptDeadline())); String[] fields = delivery.split("\t");
                if (fields.length != 6 || !fields[0].equals("MESSAGE") || !fields[1].equals(message) || !Arrays.equals(Base64.getDecoder().decode(fields[2]), payload)) throw new AssertionError("Queue delivery identity/payload mismatch");
                String ack = retry(() -> cluster.queue().ack("ack-" + number, "q", "workers", "worker", 1, message, Long.parseLong(fields[4]), attemptDeadline())); require("ACKED", ack); queues.add(new QueueAck(request, message, payload, sent, delivery, ack));
            }
            confirmed++;
        }
        private void fault() throws Exception {
            int mode = (int) (faults % 5); long began = System.nanoTime(); faults++; List<AcceptanceCluster.Replica> victims = new ArrayList<>();
            if (mode == 3) { cluster.restartAll(); restarts++; work(); log("full-restart", "fault=" + faults); }
            else if (mode == 4) {
                int missing = cluster.replicas.size() / 2 + 1; victims.addAll(cluster.replicas.subList(0, missing)); for (var replica : victims) replica.kill();
                long pending = ++issued; boolean acknowledged = false;
                try {
                    if (cluster.kind.equals("map")) { var value = cluster.map().execute("BATCH", "work-" + pending, List.of("key-" + pending, "0", "value-" + pending), Instant.now().plusSeconds(2)); acknowledged = value.status().equals("OK"); }
                    else acknowledged = cluster.queue().send("work-" + pending, "q", "message-" + pending, ("payload-" + pending).getBytes(StandardCharsets.UTF_8), 3, Instant.now().plusSeconds(2)).startsWith("SENT\t");
                } catch (IOException expected) { log("quorum-rejected", "fault=" + faults); }
                if (acknowledged) throw new AssertionError("Minority acknowledged a new mutation");
                for (var replica : victims) replica.start(); complete(pending); log("quorum-restored", "fault=" + faults);
            } else {
                AcceptanceCluster.Replica leader = cluster.leader();
                if (mode == 0) victims.add(leader);
                else { for (var replica : cluster.replicas) if (replica != leader && victims.size() < (mode == 2 ? (cluster.replicas.size() - 1) / 2 : 1)) victims.add(replica); }
                for (var replica : victims) replica.kill(); work(); for (var replica : victims) replica.start(); log(mode == 0 ? "leader-kill" : "minority-kill", "victims=" + victims.size());
            }
            long recovery = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began); maxRecovery = Math.max(maxRecovery, recovery); if (recovery > 30000) throw new AssertionError("Recovery exceeded development30second profile: " + recovery); log("fault-recovered", "millis=" + recovery);
            if (faults % 15 == 0) { if (cluster.kind.equals("map")) consumeEvents(); var metadata = cluster.backup(output.resolve(cluster.kind + "-checkpoint-" + faults)); log("checkpoint", "index=" + metadata.index() + " sha256=" + metadata.checkpointSha256()); }
        }
        private void consumeEvents() throws Exception {
            var events = new EventSubscriptions(cluster.map());
            while (true) {
                var page = retry(() -> events.poll("soak-consumer", 256, attemptDeadline()));
                for (var event : page.events()) { var payload = ResourceEvent.decode(event.payload()); if (payload.status().equals("OK")) delivered.add(payload.command().requestId()); }
                if (page.events().isEmpty()) break;
                require("OK", retry(() -> events.ack("event-ack-" + page.through().sequence(), "soak-consumer", page.through(), 86400000, attemptDeadline())).status());
            }
        }
        private void verify(boolean restored) throws Exception {
            if (cluster.kind.equals("map")) {
                var image = retry(() -> cluster.map().snapshotAndTail(attemptDeadline())); Map<String, ReplicatedResources.Value> projection = projection(image.snapshot());
                for (MapAck ack : maps) require(new ReplicatedResources.Value(ack.arguments.get(2), ack.result.index()), projection.get(ack.arguments.getFirst()));
                for (int i = 0; i < maps.size(); i += 100) { MapAck ack = maps.get(i); require(ack.result, retry(() -> cluster.map().execute("BATCH", ack.request, ack.arguments, attemptDeadline()))); }
                require(List.of("soak-transaction"), retry(() -> cluster.map().prepared(attemptDeadline())));
                require("CONFLICT", retry(() -> cluster.map().execute("BATCH", "prepared-protection-" + restored, List.of("held-key", "0", "bad"), attemptDeadline())).status());
                if (!restored) { consumeEvents(); for (MapAck ack : maps) if (!delivered.contains(ack.request)) throw new AssertionError("Event view lost confirmed mutation"); }
                else { require("COMMITTED", retry(() -> cluster.map().execute("COMMIT", "soak-decision", List.of("soak-transaction", "1"), attemptDeadline())).status()); require("prepared-value", retry(() -> cluster.map().read("held-key", attemptDeadline())).value()); }
            } else {
                var endpoint = new PartitionClient(cluster.services(), "operator", new byte[32]);
                for (QueueAck ack : queues) {
                    require(ack.sent, queueResult(endpoint, ack.request));
                    require(ack.delivery, queueResult(endpoint, "claim-" + ack.request.substring(5)));
                    require(ack.acknowledged, queueResult(endpoint, "ack-" + ack.request.substring(5)));
                }
                require("EMPTY", retry(() -> cluster.queue().claim("verify-empty-" + restored + "-" + restarts, "q", "workers", "worker", 1, 86400000, attemptDeadline())));
                require(List.of(), retry(() -> cluster.queue().deadLetters("q", attemptDeadline())));
            }
            log("state-verified", "restored=" + restored + " confirmed=" + confirmed);
        }
        private String queueResult(PartitionClient endpoint, String request) throws Exception {
            byte[] query = new ResourceCommand("Q_RESULT", "tenant", "acceptance-query", List.of(request)).encode();
            PartitionReply reply = retry(() -> endpoint.query(1, query, attemptDeadline())); require("OK", reply.status());
            return new String(reply.payload(), StandardCharsets.UTF_8);
        }
        private String ledger() throws Exception {
            Path path = output.resolve(cluster.kind + "-confirmed-ledger.bin");
            try (var out = new DataOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
                out.writeInt(1); out.writeUTF(cluster.kind); out.writeLong(confirmed);
                for (MapAck ack : maps) { out.writeUTF(ack.request); for (String argument : ack.arguments) out.writeUTF(argument); out.writeUTF(ack.result.status()); out.writeLong(ack.result.index()); }
                for (QueueAck ack : queues) { out.writeUTF(ack.request); out.writeUTF(ack.message); out.writeUTF(ack.sent); out.writeUTF(ack.delivery); out.writeUTF(ack.acknowledged); }
            }
            return sha(path);
        }
        private <T> T retry(Callable<T> operation) throws Exception { long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); IOException last = null; do { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Acceptance workload interrupted"); try { return operation.call(); } catch (IOException error) { last = error; Thread.onSpinWait(); } } while (System.nanoTime() < limit); throw new IOException("Operation failed to recover within30seconds", last); }
        private void log(String event, String data) { synchronized (history) { long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); history.println(millis + "\t" + cluster.kind + "\t" + event + "\t" + data); history.flush(); System.out.println("SOAK " + cluster.kind + " elapsedMs=" + millis + " " + event + " " + data); System.out.flush(); } }
    }
    /**
     * Executes both profiles for at least60minutes of overlapping active fault workload.
     * There is deliberately no duration override. Write STOP in the output directory to cancel; a
     * cancelled or failed run never produces PASS. Output includes actual timings and source hashes.
     * @param args fresh evidence directory and optional repository root (default current directory)
     * @throws Exception if any safety/recovery condition or final restore proof fails
     */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectory(output); Path repository = args.length > 1 ? Path.of(args[1]).toAbsolutePath() : Path.of("").toAbsolutePath();
        Properties source = sourceHashes(repository); store(output.resolve("source-hashes.properties"), source);
        Properties summary = new Properties(); summary.setProperty("status", "RUNNING"); summary.setProperty("startedUtc", Instant.now().toString()); store(output.resolve("summary.properties"), summary);
        try (var map = new AcceptanceCluster(output.resolve("map"), "map", 3); var queue = new AcceptanceCluster(output.resolve("queue"), "queue", 5); var history = new PrintWriter(Files.newBufferedWriter(output.resolve("fault-history.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)); var execution = Executors.newFixedThreadPool(2)) {
            Worker mapWork = new Worker(map, output, 0, history), queueWork = new Worker(queue, output, 0, history); mapWork.initialize(); queueWork.initialize();
            long start = System.nanoTime(); mapWork.start = start; mapWork.end = start + REQUIRED_NANOS; queueWork.start = start; queueWork.end = start + REQUIRED_NANOS;
            CompletionService<ProfileResult> completed = new ExecutorCompletionService<>(execution);
            Future<ProfileResult> left = completed.submit(() -> new ProfileResult("map", mapWork.call())), right = completed.submit(() -> new ProfileResult("queue", queueWork.call()));
            Map<String, Properties> profiles = new HashMap<>();
            try { for (int i = 0; i < 2; i++) { ProfileResult profile = completed.take().get(); profiles.put(profile.kind, profile.properties); } }
            catch (Exception failure) { left.cancel(true); right.cancel(true); throw failure; }
            Properties mapResult = profiles.get("map"), queueResult = profiles.get("queue");
            if (!source.equals(sourceHashes(repository))) throw new IOException("Production/harness source changed during soak; evidence requires review and rerun");
            for (var entry : mapResult.entrySet()) summary.setProperty("map." + entry.getKey(), entry.getValue().toString()); for (var entry : queueResult.entrySet()) summary.setProperty("queue." + entry.getKey(), entry.getValue().toString());
            summary.setProperty("elapsedNanos", Long.toString(System.nanoTime() - start)); summary.setProperty("finishedUtc", Instant.now().toString()); summary.setProperty("historySha256", sha(output.resolve("fault-history.tsv"))); summary.setProperty("status", "PASS"); store(output.resolve("summary.properties"), summary);
        } catch (Throwable error) { summary.setProperty("status", "FAIL"); summary.setProperty("failure", error.getClass().getName()); summary.setProperty("finishedUtc", Instant.now().toString()); store(output.resolve("summary.properties"), summary); throw error; }
        verifyEvidence(output, repository); System.out.println("SOAK PASS " + output);
    }
    static Properties sourceHashes(Path repository) throws Exception {
        Properties hashes = new Properties();
        for (String directory : List.of("j7m-consensus/src/main/java", "j7m-distributed-core/src/main/java")) {
            try (var files = Files.walk(repository.resolve(directory))) { for (Path path : files.filter(file -> file.toString().endsWith(".java")).sorted().toList()) hashes.setProperty(repository.relativize(path).toString(), sha(path)); }
        }
        for (String name : List.of("DistributedAcceptanceSoak", "AcceptanceCluster", "AcceptanceNodeProcess")) { Path path = repository.resolve("j7m-distributed-core/src/test/java/org/j7m/middleware/distributed/" + name + ".java"); hashes.setProperty(repository.relativize(path).toString(), sha(path)); }
        return hashes;
    }
    static void verifyEvidence(Path output, Path repository) throws Exception {
        Properties summary = load(output.resolve("summary.properties"));
        if (!summary.getProperty("status", "").equals("PASS") || Long.parseLong(summary.getProperty("elapsedNanos", "0")) < REQUIRED_NANOS) throw new IOException("No completed actual60minute soak evidence");
        if (!load(output.resolve("source-hashes.properties")).equals(sourceHashes(repository))) throw new IOException("Soak evidence does not bind current source");
        for (String kind : List.of("map", "queue")) {
            if (Integer.parseInt(summary.getProperty(kind + ".profile", "0")) != (kind.equals("map") ? 3 : 5) || Long.parseLong(summary.getProperty(kind + ".activeNanos", "0")) < REQUIRED_NANOS || Long.parseLong(summary.getProperty(kind + ".confirmed", "0")) < 1000 || Long.parseLong(summary.getProperty(kind + ".faults", "0")) < 10 || Long.parseLong(summary.getProperty(kind + ".fullRestarts", "0")) < 1 || Long.parseLong(summary.getProperty(kind + ".maxRecoveryMillis", "999999")) > 30000 || !Boolean.parseBoolean(summary.getProperty(kind + ".restoreVerified", "false"))) throw new IOException("Incomplete profile evidence: " + kind);
            if (!sha(output.resolve(kind + "-confirmed-ledger.bin")).equals(summary.getProperty(kind + ".ledgerSha256"))) throw new IOException("Workload ledger checksum mismatch");
            var backup = ConsensusBackup.inspect(output.resolve(kind + "-final-backup"), AcceptanceNodeProcess.schema(kind));
            if (backup.index() != Long.parseLong(summary.getProperty(kind + ".backupIndex")) || !backup.checkpointSha256().equals(summary.getProperty(kind + ".backupSha256"))) throw new IOException("Final recovery manifest mismatch");
        }
        if (!sha(output.resolve("fault-history.tsv")).equals(summary.getProperty("historySha256"))) throw new IOException("Fault history checksum mismatch");
        if (Duration.between(Instant.parse(summary.getProperty("startedUtc")), Instant.parse(summary.getProperty("finishedUtc"))).toNanos() < REQUIRED_NANOS) throw new IOException("Wall-clock evidence interval shorter than60minutes");
        Map<String, Set<String>> phases = new HashMap<>(); phases.put("map", new HashSet<>()); phases.put("queue", new HashSet<>());
        Map<String, Long> recovered = new HashMap<>(), active = new HashMap<>();
        try (var lines = Files.lines(output.resolve("fault-history.tsv"))) {
            for (String line : lines.toList()) {
                String[] fields = line.split("\\t", 4); if (fields.length != 4 || !phases.containsKey(fields[1]) || Long.parseLong(fields[0]) < 0 || fields[2].equals("failure")) throw new IOException("Invalid failure history");
                phases.get(fields[1]).add(fields[2]); if (fields[2].equals("fault-recovered")) recovered.merge(fields[1], 1L, Long::sum);
                if (fields[2].equals("active-complete")) active.put(fields[1], Long.parseLong(fields[0]));
            }
        }
        for (String kind : phases.keySet()) if (!phases.get(kind).containsAll(Set.of("progress", "leader-kill", "minority-kill", "full-restart", "quorum-rejected", "quorum-restored", "checkpoint", "state-verified", "restore-verified", "active-complete")) || recovered.getOrDefault(kind, 0L) != Long.parseLong(summary.getProperty(kind + ".faults")) || active.getOrDefault(kind, 0L) < TimeUnit.HOURS.toMillis(1)) throw new IOException("Actual fault/workload trail incomplete: " + kind);
    }
    static void store(Path path, Properties properties) throws IOException { Path next = path.resolveSibling(path.getFileName() + ".next"); try (OutputStream output = Files.newOutputStream(next)) { properties.store(output, "Actual distributed acceptance evidence"); } Files.move(next, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
    private static Properties load(Path path) throws IOException { Properties properties = new Properties(); try (InputStream input = Files.newInputStream(path)) { properties.load(input); } return properties; }
    private static String sha(Path path) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); } return HexFormat.of().formatHex(digest.digest()); }
    private static Map<String, ReplicatedResources.Value> projection(byte[] bytes) throws IOException { Map<String, ReplicatedResources.Value> values = new TreeMap<>(); try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) { if (in.readInt() != 1) throw new IOException("Projection schema"); int count = in.readInt(); if (count < 0 || count > 100000) throw new IOException("Projection count"); for (int i = 0; i < count; i++) values.put(in.readUTF(), new ReplicatedResources.Value(in.readUTF(), in.readLong())); if (in.available() != 0) throw new IOException("Projection trailing bytes"); } return values; }
    private static void require(Object expected, Object actual) { if (!Objects.equals(expected, actual)) throw new AssertionError("Acceptance invariant mismatch: expected=" + expected + " actual=" + actual); }
}
