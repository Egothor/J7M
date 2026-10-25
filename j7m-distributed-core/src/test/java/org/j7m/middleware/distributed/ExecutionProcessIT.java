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

class ExecutionProcessIT {
    @TempDir Path directory;
    private static final String HASH = "a".repeat(64);
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private static int port() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private final class Replica implements AutoCloseable {
        final String id, peers; final int service; Process process; BufferedReader output; BufferedWriter input;
        Replica(String id, String peers, int service) throws Exception { this.id = id; this.peers = peers; this.service = service; start(); }
        void start() throws Exception {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-cp", System.getProperty("j7m.distributed.test.classpath", System.getProperty("java.class.path")), ExecutionNodeProcess.class.getName(), id, directory.resolve(id).toString(), peers, Integer.toString(service)).redirectError(directory.resolve(id + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream())); assertTrue(read().startsWith("READY"));
        }
        String read() throws Exception { return CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); } }).get(15, TimeUnit.SECONDS); }
        String command(String text) throws Exception { input.write(text); input.newLine(); input.flush(); return read(); }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        @Override public void close() { if (process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    }
    private final class Cluster implements AutoCloseable {
        final List<Replica> replicas;
        final ExecutionClient client;
        final DurableJobs jobs;
        final DurableSagas sagas;
        final DeduplicatedRpc rpc;
        Cluster() throws Exception {
            String peers = "n1:" + port() + ",n2:" + port() + ",n3:" + port(); replicas = List.of(new Replica("n1", peers, port()), new Replica("n2", peers, port()), new Replica("n3", peers, port()));
            var endpoint = new PartitionClient(replicas.stream().map(replica -> new InetSocketAddress("127.0.0.1", replica.service)).toList(), "app", new byte[32]);
            client = new ExecutionClient("execution", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(endpoint, 1)); jobs = new DurableJobs(client); sagas = new DurableSagas(client); rpc = new DeduplicatedRpc(client);
            assertEquals("NOT_FOUND", jobs.status("absent", deadline()).status());
        }
        void restartAll() throws Exception { for (Replica replica : replicas) replica.kill(); for (Replica replica : replicas) replica.start(); }
        @Override public void close() { for (Replica replica : replicas) replica.close(); }
    }
    @ParameterizedTest
    @ValueSource(strings = {"SUBMIT", "CLAIM", "CHECKPOINT", "COMPLETE", "CANCEL"})
    void everyJobBoundarySurvivesAllReplicaRestartAndStaleOwnerCannotMutate(String boundary) throws Exception {
        try (Cluster cluster = new Cluster()) {
            assertEquals("READY", cluster.jobs.submit("submit", "job", "input", deadline()).status());
            long fence = 0;
            if (!boundary.equals("SUBMIT")) fence = Long.parseLong(cluster.jobs.claim("claim", "job", "old", boundary.equals("COMPLETE") ? 10000 : 100, deadline()).fields().get("fence"));
            if (Set.of("CHECKPOINT", "COMPLETE").contains(boundary)) cluster.jobs.checkpoint("checkpoint", "job", "old", fence, "saved", deadline());
            if (boundary.equals("COMPLETE")) assertEquals("COMPLETED", cluster.jobs.complete("complete", "job", "old", fence, "result", List.of("protected", "0", "once"), deadline()).status());
            if (boundary.equals("CANCEL")) cluster.jobs.cancel("cancel", "job", deadline());
            cluster.restartAll(); var status = cluster.jobs.status("job", deadline()); assertEquals("input", status.fields().get("input"));
            if (boundary.equals("COMPLETE")) { assertEquals("COMPLETED", status.status()); assertEquals("once", cluster.client.query("E_VALUE", "protected", deadline()).fields().get("value")); }
            else if (boundary.equals("CANCEL")) assertEquals("CANCELLED", status.status());
            else {
                var takeover = cluster.jobs.claim("takeover", "job", "new", 10000, deadline()); assertEquals("RUNNING", takeover.status()); long newFence = Long.parseLong(takeover.fields().get("fence")); assertTrue(newFence > fence);
                if (boundary.equals("CHECKPOINT")) assertEquals("saved", takeover.fields().get("checkpoint"));
                if (fence != 0) assertEquals("FENCED", cluster.jobs.complete("stale", "job", "old", fence, "bad", List.of("protected", "0", "bad"), deadline()).status());
                assertEquals("COMPLETED", cluster.jobs.complete("new-complete", "job", "new", newFence, "result", List.of("protected", "0", "once"), deadline()).status());
            }
        }
    }
    @Test void administrativeCompensationAndOwnerResolutionSurviveQuorumRestartWithOriginalIntent() throws Exception {
        try (var cluster = new Cluster()) {
            byte[] key = new byte[32]; Arrays.fill(key,(byte)1);
            var endpoint = new PartitionClient(cluster.replicas.stream().map(replica -> new InetSocketAddress("127.0.0.1",replica.service)).toList(),"admin",key);
            var admin = new DurableSagas(new ExecutionClient("execution","tenant",(partition,deadline) -> new ReplicatedQueue.Route(endpoint,1)));
            cluster.sagas.start("start-control","controlled","input",2,10,deadline());
            var claim = cluster.sagas.claim("claim-control","controlled","old",60000,deadline()); long fence = Long.parseLong(claim.fields().get("fence"));
            cluster.sagas.step("step-control","controlled","old",fence,"one","external-key","undo",true,true,deadline());
            var unknown = cluster.sagas.resolveStep("unknown-control","controlled","old",fence,"one",DurableSagas.StepResolution.UNKNOWN,"reply-lost",deadline());
            assertEquals("UNKNOWN",unknown.status());
            assertThrows(SecurityException.class,() -> cluster.sagas.requestCompensation("denied-control","controlled",Long.parseLong(unknown.fields().get("version")),deadline()));
            var decision = admin.requestCompensation("decision-control","controlled",Long.parseLong(unknown.fields().get("version")),deadline());
            assertEquals("UNKNOWN",decision.status()); assertEquals("true",decision.fields().get("compensationRequested"));
            cluster.restartAll();
            assertEquals(decision,admin.requestCompensation("decision-control","controlled",Long.parseLong(unknown.fields().get("version")),deadline()));
            assertEquals("UNKNOWN",cluster.sagas.status("controlled",deadline()).status());
            assertEquals("COMPENSATING",admin.reconcile("reconcile-control","controlled","one",Long.parseLong(cluster.sagas.status("controlled",deadline()).fields().get("version")),true,"established",deadline()).status());
            var next = cluster.sagas.claim("next-control","controlled","next",60000,deadline());
            assertEquals("COMPENSATING",next.status()); long nextFence=Long.parseLong(next.fields().get("fence")); assertTrue(nextFence>fence);
            cluster.sagas.compensate("undo-control","controlled","next",nextFence,"one",true,deadline());
            assertEquals("COMPENSATED",cluster.sagas.compensationResult("undone-control","controlled","next",nextFence,"one",true,"undone",deadline()).status());
            assertEquals("COMPENSATED",cluster.sagas.status("controlled",deadline()).status());
        }
    }
    @Test void oneHundredParallelRpcDuplicatesCommitExactlyOneProtectedMutationAndReplayAfterCrash() throws Exception {
        try (Cluster cluster = new Cluster(); var executor = Executors.newFixedThreadPool(16)) {
            List<Future<ExecutionResult>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) { int duplicate = i; futures.add(executor.submit(() -> cluster.rpc.invoke("invoke-" + duplicate, "request", HASH, 60000, "answer", "", List.of("counter", "0", "once"), deadline()))); }
            ExecutionResult expected = futures.getFirst().get(20, TimeUnit.SECONDS); assertEquals("COMPLETED", expected.status()); for (Future<ExecutionResult> future : futures) assertEquals(expected, future.get(20, TimeUnit.SECONDS));
            assertEquals("99", cluster.client.statistics(deadline()).fields().get("rpcReplays"));
            var before = cluster.client.query("E_VALUE", "counter", deadline()); cluster.restartAll();
            assertEquals(expected, cluster.rpc.invoke("after-restart", "request", HASH, 60000, "answer", "", List.of("counter", "0", "once"), deadline())); assertEquals(before, cluster.client.query("E_VALUE", "counter", deadline()));
            assertEquals("CONFLICT", cluster.rpc.invoke("wrong-input", "request", "b".repeat(64), 60000, "different", "", List.of(), deadline()).status());
        }
    }
    @Test void crashAfterQuorumCommitBeforeReplyReplaysRpcResultOnNewLeader() throws Exception {
        try (Cluster cluster = new Cluster(); var executor = Executors.newSingleThreadExecutor()) {
            Replica leader = null; for (Replica replica : cluster.replicas) if (replica.command("STATUS").equals("ACK " + replica.id)) leader = replica;
            assertNotNull(leader); leader.command("ARM commit-before-reply");
            Future<ExecutionResult> request = executor.submit(() -> cluster.rpc.invoke("commit-before-reply", "request", HASH, 60000, "stored", "", List.of("effect", "0", "once"), deadline()));
            assertEquals("COMMITTED commit-before-reply", leader.read()); leader.kill();
            ExecutionResult result = request.get(15, TimeUnit.SECONDS); assertEquals("COMPLETED", result.status()); assertEquals("stored", result.fields().get("result"));
            leader.start(); cluster.restartAll(); assertEquals(result, cluster.rpc.invoke("replay", "request", HASH, 60000, "stored", "", List.of("effect", "0", "once"), deadline()));
            assertEquals("once", cluster.client.query("E_VALUE", "effect", deadline()).fields().get("value"));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"START", "CLAIM", "INTENT", "STEP_COMPLETE", "COMPENSATE", "COMPENSATION_RESULT", "NON_IDEMPOTENT_INTENT"})
    void everySagaBoundarySurvivesAllReplicaRestartAndUnknownNeedsAdministrativeReconciliation(String boundary) throws Exception {
        try (Cluster cluster = new Cluster()) {
            cluster.sagas.start("start", "saga", "saga-input", 2, 10, deadline()); long fence = 0;
            if (!boundary.equals("START")) fence = Long.parseLong(cluster.sagas.claim("claim", "saga", "old", 200, deadline()).fields().get("fence"));
            if (Set.of("INTENT", "STEP_COMPLETE", "COMPENSATE", "COMPENSATION_RESULT", "NON_IDEMPOTENT_INTENT").contains(boundary)) cluster.sagas.step("intent", "saga", "old", fence, "step", "stable-external-key", "undo-input", !boundary.equals("NON_IDEMPOTENT_INTENT"), false, deadline());
            if (Set.of("STEP_COMPLETE", "COMPENSATE", "COMPENSATION_RESULT").contains(boundary)) cluster.sagas.completeStep("step-done", "saga", "old", fence, "step", "step-result", List.of("protected", "0", "once"), deadline());
            if (Set.of("COMPENSATE", "COMPENSATION_RESULT").contains(boundary)) cluster.sagas.compensate("compensate", "saga", "old", fence, "step", true, deadline());
            if (boundary.equals("COMPENSATION_RESULT")) cluster.sagas.compensationResult("comp-done", "saga", "old", fence, "step", true, "undone", deadline());
            cluster.restartAll(); var status = cluster.sagas.status("saga", deadline()); assertEquals("saga-input", status.fields().get("input"));
            if (boundary.equals("COMPENSATION_RESULT")) { assertEquals("COMPENSATED", status.status()); return; }
            var takeover = cluster.sagas.claim("takeover", "saga", "new", 10000, deadline()); long newFence = Long.parseLong(takeover.fields().get("fence")); assertTrue(newFence > fence);
            if (boundary.equals("NON_IDEMPOTENT_INTENT")) {
                assertEquals("UNKNOWN", takeover.status());
                assertEquals("UNKNOWN", cluster.sagas.step("blind-retry", "saga", "new", newFence, "other", "another-key", "undo", false, false, deadline()).status());
                assertThrows(SecurityException.class, () -> cluster.sagas.reconcile("unauthorized", "saga", "step", Long.parseLong(cluster.sagas.status("saga",deadline()).fields().get("version")), true, "external-established", deadline()));
                byte[] adminKey = new byte[32]; Arrays.fill(adminKey, (byte) 1);
                var adminEndpoint = new PartitionClient(cluster.replicas.stream().map(replica -> new InetSocketAddress("127.0.0.1", replica.service)).toList(), "admin", adminKey);
                var admin = new DurableSagas(new ExecutionClient("execution", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(adminEndpoint, 1)));
                assertEquals("RUNNING", admin.reconcile("reconcile", "saga", "step", Long.parseLong(cluster.sagas.status("saga",deadline()).fields().get("version")), true, "external-established", deadline()).status());
            } else if (boundary.equals("COMPENSATE")) assertEquals("COMPENSATED", cluster.sagas.compensationResult("recover-comp", "saga", "new", newFence, "step", true, "undone", deadline()).status());
            else if (boundary.equals("INTENT")) assertEquals("RUNNING", cluster.sagas.completeStep("recover-step", "saga", "new", newFence, "step", "recovered", List.of("protected", "0", "once"), deadline()).status());
            else if (boundary.equals("STEP_COMPLETE")) { assertEquals("step-result", takeover.fields().get("stepResult")); assertEquals("once", cluster.client.query("E_VALUE", "protected", deadline()).fields().get("value")); }
            else { cluster.sagas.step("last", "saga", "new", newFence, "last", "last-key", "undo", true, true, deadline()); assertEquals("COMPLETED", cluster.sagas.completeStep("last-done", "saga", "new", newFence, "last", "result", List.of(), deadline()).status()); }
        }
    }
    @Test void publicJournalViewsRpcExpiryAndChangingTraceSpansUsePersistedFirstContext() throws Exception {
        try (Cluster cluster = new Cluster()) {
            var span = new java.util.concurrent.atomic.AtomicInteger(); var completed = new java.util.concurrent.atomic.AtomicInteger(); var closed = new java.util.concurrent.atomic.AtomicInteger();
            var endpoint = new PartitionClient(cluster.replicas.stream().map(replica -> new InetSocketAddress("127.0.0.1", replica.service)).toList(), "app", new byte[32]);
            ExecutionClient.Observer observer = operation -> { span.incrementAndGet(); return new ExecutionClient.Observation() { @Override public void completed(ExecutionResult result) { completed.incrementAndGet(); } @Override public void close() { closed.incrementAndGet(); } }; };
            var client = new ExecutionClient("execution", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(endpoint, 1), () -> Map.of("traceparent", "00-" + "1".repeat(32) + "-" + String.format("%016x", span.get()) + "-01"), observer);
            try (var observation = observer.begin("observer-contract")) { observation.completed(new ExecutionResult("OK", Map.of())); }
            assertThrows(IllegalArgumentException.class, () -> client.execute("J_CLAIM", "malformed", List.of("job", "worker", "0"), deadline()));
            var jobs = new DurableJobs(client); var first = jobs.submit("submit-with-span", "job", "input", deadline()); var replay = jobs.submit("submit-with-span", "job", "input", deadline()); assertEquals(first, replay); assertFalse(first.context().isEmpty());
            var events = new ExecutionEvents(client); var view = events.snapshotAndTail(deadline()); assertEquals(first, view.runs().get("job"));
            assertEquals("OK", events.subscribe("subscribe", "subscriber", "", view.cursor(), 10000, deadline()).status());
            var claim = jobs.claim("claim", "job", "worker", 10000, deadline()); assertEquals(first.context(), claim.context());
            assertEquals("RUNNING", ExecutionResult.decode(client.queryBytes("E_RUN", List.of("job"), deadline())).status());
            jobs.checkpoint("checkpoint", "job", "worker", Long.parseLong(claim.fields().get("fence")), "progress", deadline());
            var page = events.poll("subscriber", 10, deadline()); assertEquals(2, page.events().size()); assertEquals("job", ExecutionResult.decode(page.events().getLast().payload()).fields().get("entity"));
            assertEquals("OK", events.ack("ack", "subscriber", page.through(), 10000, deadline()).status()); assertTrue(events.poll("subscriber", 10, deadline()).events().isEmpty()); assertEquals("OK", events.resume("resume", "subscriber", 10000, deadline()).status());
            var rpc = new DeduplicatedRpc(client); assertEquals("COMPLETED", rpc.invoke("short-lived", "short", HASH, 100, "stored", "", List.of(), deadline()).status());
            Instant expiresBy = deadline(); ExecutionResult expired;
            do { expired = rpc.status("short", deadline()); if (expired.status().equals("EXPIRED")) break; } while (Instant.now().isBefore(expiresBy));
            assertEquals("EXPIRED", expired.status()); assertEquals("EXPIRED", rpc.invoke("short-lived", "short", HASH, 100, "stored", "", List.of(), deadline()).status());
            assertTrue(completed.get() > 0); assertEquals(span.get(), closed.get()); assertEquals("1", client.statistics(deadline()).fields().get("jobs"));
        }
    }
    @Test void unknownExternalRpcAfterLostReplyNeverAcquiresAgainAndRequiresAuthorizedReconciliation() throws Exception {
        try (Cluster cluster = new Cluster(); ServerSocket external = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var count = new java.util.concurrent.atomic.AtomicInteger();
            var effect = executor.submit(() -> { try (Socket connection = external.accept()) { assertEquals(1, connection.getInputStream().read()); count.incrementAndGet(); } return true; });
            var claim = cluster.rpc.claim("claim-external", "external", HASH, 60000, true, deadline()); assertEquals("true", claim.fields().get("acquired")); long fence = Long.parseLong(claim.fields().get("fence"));
            try (Socket connection = new Socket("127.0.0.1", external.getLocalPort())) { connection.getOutputStream().write(1); connection.getOutputStream().flush(); assertEquals(-1, connection.getInputStream().read()); }
            assertTrue(effect.get(10, TimeUnit.SECONDS)); assertEquals("UNKNOWN", cluster.rpc.unknown("unknown", "external", HASH, fence, deadline()).status()); cluster.restartAll();
            var recovery = cluster.rpc.claim("claim-recovery", "external", HASH, 60000, true, deadline()); assertEquals("UNKNOWN", recovery.status()); assertEquals("false", recovery.fields().get("acquired")); assertEquals(1, count.get());
            assertEquals("UNKNOWN", cluster.rpc.complete("blind-complete", "external", HASH, fence, "unverified", "", List.of(), deadline()).status());
            assertThrows(SecurityException.class, () -> cluster.rpc.reconcile("unauthorized", "external", HASH, fence, "established", "", List.of(), deadline()));
            byte[] key = new byte[32]; Arrays.fill(key, (byte) 1); var endpoint = new PartitionClient(cluster.replicas.stream().map(replica -> new InetSocketAddress("127.0.0.1", replica.service)).toList(), "admin", key);
            var administrative = new DeduplicatedRpc(new ExecutionClient("execution", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(endpoint, 1)));
            assertEquals("COMPLETED", administrative.reconcile("reconciled", "external", HASH, fence, "established", "", List.of(), deadline()).status()); assertEquals(1, count.get());
        }
    }
    @Test void exactSamePhysicalClaimCallsPermitOneExternalTcpEffectAndRestartNeverReissuesPermit() throws Exception {
        try (Cluster cluster = new Cluster(); var service = Executors.newSingleThreadExecutor(); ServerSocket external = new ServerSocket(0); var workers = Executors.newFixedThreadPool(10)) {
            var effects = new java.util.concurrent.atomic.AtomicInteger();
            service.submit(() -> {
                while (!external.isClosed()) {
                    try (Socket connection = external.accept()) {
                        assertEquals(1, connection.getInputStream().read()); effects.incrementAndGet(); connection.getOutputStream().write(1); connection.getOutputStream().flush();
                    } catch (IOException e) { if (!external.isClosed()) throw new UncheckedIOException(e); }
                }
            });
            Replica leader = null; for (Replica replica : cluster.replicas) if (replica.command("STATUS").equals("ACK " + replica.id)) leader = replica;
            assertNotNull(leader); leader.command("ARM same-operation");
            List<Future<ExecutionResult>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(workers.submit(() -> {
                var claim = cluster.rpc.claim("same-operation", "external", HASH, 60000, true, deadline());
                if (claim.fields().get("acquired").equals("true")) {
                    try (Socket effect = new Socket("127.0.0.1", external.getLocalPort())) { effect.getOutputStream().write(1); effect.getOutputStream().flush(); assertEquals(1, effect.getInputStream().read()); }
                }
                return claim;
            }));
            assertEquals("COMMITTED same-operation", leader.read()); leader.kill();
            long acquired = 0; for (var call : calls) if (call.get(20, TimeUnit.SECONDS).fields().get("acquired").equals("true")) acquired++;
            assertEquals(1, acquired); assertEquals(1, effects.get());
            leader.start(); cluster.restartAll(); var recovery = cluster.rpc.claim("same-operation", "external", HASH, 60000, true, deadline());
            assertEquals("UNKNOWN", recovery.status()); assertEquals("false", recovery.fields().get("acquired")); assertEquals(1, effects.get());
        }
    }
    @Test void multistepCompensationRecoversExactEarlierActionFromPublicApiAfterRestart() throws Exception {
        try (Cluster cluster = new Cluster(); var service = Executors.newSingleThreadExecutor(); ServerSocket external = new ServerSocket(0)) {
            List<String> handled = new CopyOnWriteArrayList<>();
            service.submit(() -> {
                while (!external.isClosed()) {
                    try (Socket connection = external.accept()) {
                        var input = new DataInputStream(connection.getInputStream()); String key = input.readUTF(), compensation = input.readUTF(); handled.add(key + ":" + compensation); connection.getOutputStream().write(1); connection.getOutputStream().flush();
                    } catch (IOException e) { if (!external.isClosed()) throw new UncheckedIOException(e); }
                }
            });
            cluster.sagas.start("start", "saga", "input", 2, 10, deadline()); long token = Long.parseLong(cluster.sagas.claim("claim", "saga", "worker", 10000, deadline()).fields().get("fence"));
            for (String step : List.of("first", "second")) { cluster.sagas.step("intent-" + step, "saga", "worker", token, step, "key-" + step, "undo-" + step, true, false, deadline()); cluster.sagas.completeStep("done-" + step, "saga", "worker", token, step, "done", List.of(), deadline()); }
            assertEquals(List.of("first", "second"), cluster.sagas.steps("saga", deadline()).stream().map(step -> step.fields().get("step")).toList());
            var selected = cluster.sagas.compensate("comp-second", "saga", "worker", token, "second", true, deadline());
            performCompensation(external.getLocalPort(), selected);
            cluster.sagas.compensationResult("undone-second", "saga", "worker", token, "second", true, "undone", deadline());
            cluster.restartAll();
            var recovered = cluster.sagas.status("saga", deadline()); assertEquals("second", recovered.fields().get("step"));
            var steps = cluster.sagas.steps("saga", deadline()); assertEquals("COMPENSATED", steps.getLast().status());
            var first = cluster.sagas.stepStatus("saga", "first", deadline()); assertEquals("DONE", first.status()); assertEquals("key-first", first.fields().get("idempotencyKey")); assertEquals("undo-first", first.fields().get("compensation"));
            var earlier = cluster.sagas.compensate("comp-first", "saga", recovered.fields().get("owner"), Long.parseLong(recovered.fields().get("fence")), first.fields().get("step"), true, deadline()); assertEquals("first", earlier.fields().get("step"));
            cluster.restartAll(); var active = cluster.sagas.status("saga", deadline()); assertEquals("first", active.fields().get("step")); assertEquals("COMPENSATING", active.fields().get("stepStatus"));
            performCompensation(external.getLocalPort(), active);
            assertEquals("COMPENSATED", cluster.sagas.compensationResult("undone-first", "saga", active.fields().get("owner"), Long.parseLong(active.fields().get("fence")), "first", true, "undone", deadline()).status());
            assertEquals(List.of("key-second:undo-second", "key-first:undo-first"), handled);
            assertTrue(cluster.sagas.steps("saga", deadline()).stream().allMatch(step -> step.status().equals("COMPENSATED")));
        }
    }
    private static void performCompensation(int port, ExecutionResult selected) throws IOException {
        try (Socket connection = new Socket("127.0.0.1", port)) { var output = new DataOutputStream(connection.getOutputStream()); output.writeUTF(selected.fields().get("idempotencyKey")); output.writeUTF(selected.fields().get("compensation")); output.flush(); assertEquals(1, connection.getInputStream().read()); }
    }

    @Test void lazyRunListingExceedsAtomicProjectionAndRetainsOneInitialBoundAcrossQuorumPages() throws Exception {
        try(var cluster=new Cluster()) {
            for(int i=0;i<190;i++) cluster.jobs.submit("large-submit-"+i,String.format("large-%03d",i),"x".repeat(12000),deadline());
            var events=new ExecutionEvents(cluster.client);
            assertThrows(IOException.class,()->events.snapshotAndTail(deadline()));
            var scan=events.runs("large-",deadline());
            cluster.jobs.submit("late-submit","large-999","late",deadline());
            var ids=new TreeSet<String>(); for(var entry:scan) { assertTrue(ids.add(entry.getKey()));assertEquals("READY",entry.getValue().status()); }
            assertEquals(190,ids.size());assertFalse(ids.contains("large-999"));
            assertEquals("OK",events.subscribe("scan-subscribe","scan-tail","",scan.cursor(),60000,deadline()).status());
            assertTrue(events.poll("scan-tail",256,deadline()).events().stream().anyMatch(event->ExecutionResult.decode(event.payload()).fields().get("entity").equals("large-999")));
            var claim=cluster.jobs.claim("renew-claim","large-000","owner",10000,deadline());long fence=Long.parseLong(claim.fields().get("fence"));
            assertEquals(fence,Long.parseLong(cluster.jobs.renew("renew-live","large-000","owner",fence,10000,deadline()).fields().get("fence")));
            cluster.jobs.complete("renew-done","large-000","owner",fence,"done",List.of(),deadline());
            assertEquals("CONFLICT",cluster.jobs.renew("renew-terminal","large-000","owner",fence,10000,deadline()).status());
            cluster.restartAll();assertEquals("COMPLETED",cluster.jobs.status("large-000",deadline()).status());
            int total=0; for(var ignored:events.runs(deadline())) total++; assertEquals(191,total);
        }
    }

    private static final class PushConsumer implements java.util.concurrent.Flow.Subscriber<ExecutionDelivery> {
        final BlockingQueue<Object> deliveries=new ArrayBlockingQueue<>(8);java.util.concurrent.Flow.Subscription subscription;
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(ExecutionDelivery delivery){assertTrue(deliveries.offer(delivery));}
        @Override public void onError(Throwable failure){deliveries.offer(failure);}
        @Override public void onComplete(){ }
        ExecutionDelivery take()throws Exception {Object value=deliveries.poll(10,TimeUnit.SECONDS);assertNotNull(value);if(value instanceof Throwable failure)throw new AssertionError("Execution push failed",failure);return (ExecutionDelivery)value;}
    }
    private static ExecutionSession reconnectExecution(PartitionClient endpoint,String prefix,String owner)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);IOException last=null;
        do{try{return endpoint.executionSession(1,prefix,"JOB",owner,60000,deadline());}catch(IOException unavailable){last=unavailable;Thread.onSpinWait();}}while(System.nanoTime()<end);
        throw new IOException("Execution session reconnect",last);
    }
    @Test void executionPushResumesCurrentUnicodeCheckpointAfterLeaderLossWithoutReusingHistoricalClaim()throws Exception {
        try(var cluster=new Cluster()) {
            var endpoint=new PartitionClient(cluster.replicas.stream().map(replica->new InetSocketAddress("127.0.0.1",replica.service)).toList(),"app",new byte[32]);
            try(var session=reconnectExecution(endpoint,"unicode","push-worker")) {
                var consumer=new PushConsumer();session.subscribe(consumer);
                cluster.jobs.submit("unicode-submit","unicode-job","漢".repeat(16000),deadline());
                var first=consumer.take();assertEquals("unicode-job",first.run());assertEquals("漢".repeat(16000),first.claim().fields().get("input"));
                long fence=Long.parseLong(first.claim().fields().get("fence"));
                cluster.jobs.checkpoint("unicode-checkpoint","unicode-job","push-worker",fence,"界".repeat(16000),deadline());
                String leader=cluster.replicas.getFirst().command("STATUS").substring(4);cluster.replicas.stream().filter(replica->replica.id.equals(leader)).findFirst().orElseThrow().kill();
                try(var resumed=reconnectExecution(endpoint,"unicode","push-worker")) {
                    var next=new PushConsumer();resumed.subscribe(next);var delivery=next.take();
                    assertEquals(fence,Long.parseLong(delivery.claim().fields().get("fence")));assertEquals("界".repeat(16000),delivery.claim().fields().get("checkpoint"));assertTrue(delivery.encodedBytes()>65536);
                    assertEquals("COMPLETED",cluster.jobs.complete("unicode-complete","unicode-job","push-worker",fence,"done",List.of(),deadline()).status());
                    next.subscription.request(1);assertNull(next.deliveries.poll(150,TimeUnit.MILLISECONDS));
                }
            }
        }
    }
    @Test void sixtyFourIdleExecutionSessionsDoNotAppendForegroundClockOrClaimCommands()throws Exception {
        try(var cluster=new Cluster()) {
            var endpoint=new PartitionClient(cluster.replicas.stream().map(replica->new InetSocketAddress("127.0.0.1",replica.service)).toList(),"app",new byte[32]);
            var sessions=new ArrayList<ExecutionSession>();
            try {
                for(int i=0;i<64;i++){var session=reconnectExecution(endpoint,"empty-"+i,"worker-"+i);sessions.add(session);session.subscribe(new PushConsumer());}
                var leaderId=cluster.replicas.getFirst().command("STATUS").substring(4);var leader=cluster.replicas.stream().filter(replica->replica.id.equals(leaderId)).findFirst().orElseThrow();
                String before=leader.command("INDEX");Thread.sleep(150);assertEquals(before,leader.command("INDEX"));
                assertThrows(IOException.class,()->endpoint.executionSession(1,"excess","JOB","excess",60000,deadline()));
            }finally{sessions.forEach(ExecutionSession::close);}
        }
    }
}
