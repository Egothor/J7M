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
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedDeduplicationProcessIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private static String classpath() { return System.getProperty("j7m.application.distributed.test.classpath"); }
    private static int port() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private final class Child implements AutoCloseable {
        final List<String> command; final String id; Process process; BufferedReader output; BufferedWriter input;
        Child(String id, String main, String... args) throws Exception {
            this.id = id; command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-cp", classpath(), main)); command.addAll(List.of(args)); start();
        }
        void start() throws Exception {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(directory.resolve(id + ".stderr").toFile())).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            try { String ready = read(); assertNotNull(ready, Files.readString(directory.resolve(id + ".stderr"))); assertTrue(ready.startsWith("READY"), ready); }
            catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        String read() throws Exception { return CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); } }).get(15, TimeUnit.SECONDS); }
        void send(String line) throws Exception { input.write(line); input.newLine(); input.flush(); }
        String command(String line) throws Exception { send(line); return read(); }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        @Override public void close() { process.destroyForcibly(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
    private final class Cluster implements AutoCloseable {
        final List<Child> nodes = new ArrayList<>(); final List<Integer> services = new ArrayList<>(); ExecutionClient execution;
        Cluster() throws Exception {
            String peers = "n1:" + port() + ",n2:" + port() + ",n3:" + port();
            try {
                for (int i = 1; i <= 3; i++) { int service = port(); services.add(service); nodes.add(new Child("n" + i, "org.j7m.middleware.distributed.ExecutionNodeProcess", "n" + i, directory.resolve("n" + i).toString(), peers, Integer.toString(service))); }
                assertEquals(3, nodes.stream().map(node -> node.process.pid()).distinct().count());
                var endpoint = new PartitionClient(services.stream().map(p -> new InetSocketAddress("127.0.0.1", p)).toList(), "app", new byte[32]);
                execution = new ExecutionClient("execution", "tenant", (partition, end) -> new ReplicatedQueue.Route(endpoint, 1));
                assertEquals("NOT_FOUND", execution.query("E_VALUE", "readiness", deadline()).status());
            } catch (Exception failure) { close(); throw failure; }
        }
        String ports() { return String.join(",", services.stream().map(Object::toString).toList()); }
        void restart() throws Exception { for (var node : nodes) node.kill(); for (var node : nodes) node.start(); assertEquals("NOT_FOUND", execution.query("E_VALUE", "readiness", deadline()).status()); }
        @Override public void close() { nodes.forEach(Child::close); }
    }
    private static final class Effects implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        volatile java.util.function.BiConsumer<String, String> beforeReply = (key, request) -> { };
        final ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>(); final ExecutorService listener = Executors.newSingleThreadExecutor();
        Effects() throws IOException {
            listener.submit(() -> {
                while (!server.isClosed()) {
                    try (var socket = server.accept()) {
                        var input = new DataInputStream(socket.getInputStream()); String key = input.readUTF(); String request = input.readUTF(); counts.merge(key, 1, Integer::sum); beforeReply.accept(key, request);
                        var output = new DataOutputStream(socket.getOutputStream()); output.writeBoolean(true); output.flush();
                    } catch (IOException failure) { if (!server.isClosed()) throw new UncheckedIOException(failure); }
                }
            });
        }
        int count(String key) { return counts.getOrDefault(key, 0); }
        @Override public void close() throws Exception { server.close(); listener.shutdown(); assertTrue(listener.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    private static ManagedDeduplicationPolicy policy(ExecutionClient execution, Duration retention) {
        var schemas = new SchemaRegistry(List.of()); return new ManagedDeduplicationPolicy(Map.of("default", new ManagedDeduplicationPolicy.Registration(new DeduplicatedRpc(execution), new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas), retention)));
    }
    private static ResourceBindingFactory resources() { return new ResourceBindingFactory(Map.of("deduplication", ResourceBindingFactory.deduplicationContext()), Set.of("default")); }
    @Test void hundredConcurrentAnnotatedClaimsExecuteOneBodyAndAtomicCasThenSurviveFullQuorumRestart() throws Exception {
        Path classes = GeneratedDeduplicationService.compile(directory.resolve("generated"));
        try (var cluster = new Cluster(); var effects = new Effects(); var generated = new GeneratedDeduplicationService(classes, resources());
                var binding = policy(cluster.execution, Duration.ofSeconds(30)).bind(generated.contract, generated.service, generated.business);
                var callers = Executors.newFixedThreadPool(100); var actors = Executors.newFixedThreadPool(8)) {
            System.setProperty("effect.port", Integer.toString(effects.server.getLocalPort())); var start = new CountDownLatch(1);
            List<Future<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) calls.add(callers.submit(() -> { assertTrue(start.await(3, TimeUnit.SECONDS)); return DeduplicationFrontendProcess.invoke(generated, binding, actors, "duplicate", "value"); }));
            start.countDown(); int completed = 0;
            for (var call : calls) {
                try { assertEquals("result-value", call.get(15, TimeUnit.SECONDS)); completed++; }
                catch (ExecutionException expectedInProgress) { assertInstanceOf(ExecutionException.class, expectedInProgress.getCause()); }
            }
            assertTrue(completed > 0); assertEquals(1, effects.count("duplicate"));
            var protectedValue = cluster.execution.query("E_VALUE", "protected-duplicate", deadline()); assertEquals("value", protectedValue.fields().get("value"));
            cluster.restart();
            assertEquals("result-value", DeduplicationFrontendProcess.invoke(generated, binding, actors, "duplicate", "value"));
            assertEquals(protectedValue, cluster.execution.query("E_VALUE", "protected-duplicate", deadline())); assertEquals(1, effects.count("duplicate"));
            assertThrows(ExecutionException.class, () -> DeduplicationFrontendProcess.invoke(generated, binding, actors, "duplicate", "changed"));
            assertEquals(1, effects.count("duplicate"));
        } finally { System.clearProperty("effect.port"); }
    }
    @Test void actualQuorumTimeExpiryNeverReexecutesAnnotatedBodyOrProtectedCas() throws Exception {
        Path classes = GeneratedDeduplicationService.compile(directory.resolve("generated"));
        try (var cluster = new Cluster(); var effects = new Effects(); var generated = new GeneratedDeduplicationService(classes, resources());
                var binding = policy(cluster.execution, Duration.ofSeconds(1)).bind(generated.contract, generated.service, generated.business);
                var actor = Executors.newSingleThreadExecutor()) {
            System.setProperty("effect.port", Integer.toString(effects.server.getLocalPort()));
            assertEquals("result-value", DeduplicationFrontendProcess.invoke(generated, binding, actor, "expiry", "value"));
            var before = cluster.execution.query("E_VALUE", "protected-expiry", deadline());
            boolean expired = false; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() - end < 0) {
                try { assertEquals("result-value", DeduplicationFrontendProcess.invoke(generated, binding, actor, "expiry", "value")); }
                catch (ExecutionException outcome) {
                    var failure = assertInstanceOf(org.j7m.middleware.api.MiddlewareException.class, outcome.getCause());
                    assertEquals("EXPIRED", failure.remoteError().details().get("status")); expired = true; break;
                }
            }
            assertTrue(expired, "Committed healthy quorum time must expose retained expiry");
            assertEquals(1, effects.count("expiry")); assertEquals(before, cluster.execution.query("E_VALUE", "protected-expiry", deadline()));
        } finally { System.clearProperty("effect.port"); }
    }
    @Test void leaderKilledAfterDurableResultCommitBeforeReplyReplaysStoredOutcomeWithoutSecondEffect() throws Exception {
        Path classes = GeneratedDeduplicationService.compile(directory.resolve("generated"));
        try (var cluster = new Cluster(); var effects = new Effects(); var generated = new GeneratedDeduplicationService(classes, resources());
                var binding = policy(cluster.execution, Duration.ofSeconds(30)).bind(generated.contract, generated.service, generated.business);
                var caller = Executors.newSingleThreadExecutor(); var actor = Executors.newSingleThreadExecutor()) {
            System.setProperty("effect.port", Integer.toString(effects.server.getLocalPort()));
            Child leader = null;
            for (var node : cluster.nodes) if (node.command("STATUS").equals("ACK " + node.id)) leader = node;
            assertNotNull(leader); Child armedLeader = leader;
            var armed = new CountDownLatch(1); var release = new CountDownLatch(1); var operation = new java.util.concurrent.atomic.AtomicReference<String>();
            effects.beforeReply = (key, request) -> {
                try { operation.set("complete-" + request); assertEquals("ACK", armedLeader.command("ARM " + operation.get())); armed.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            };
            var outcome = caller.submit(() -> DeduplicationFrontendProcess.invoke(generated, binding, actor, "lost-reply", "value"));
            try {
                assertTrue(armed.await(5, TimeUnit.SECONDS)); release.countDown();
                assertEquals("COMMITTED " + operation.get(), armedLeader.read()); armedLeader.kill();
                assertEquals("result-value", outcome.get(12, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertEquals(1, effects.count("lost-reply"));
            effects.beforeReply = (key, request) -> { };
            assertEquals("result-value", DeduplicationFrontendProcess.invoke(generated, binding, actor, "lost-reply", "value"));
            assertEquals(1, effects.count("lost-reply"));
            assertEquals("value", cluster.execution.query("E_VALUE", "protected-lost-reply", deadline()).fields().get("value"));
        } finally { System.clearProperty("effect.port"); }
    }
    @Test void frontendKillsBeforeEffectAfterEffectAndAfterDurableCompletionNeverReenterAmbiguousBodies() throws Exception {
        Path classes = GeneratedDeduplicationService.compile(directory.resolve("generated"));
        try (var cluster = new Cluster(); var effects = new Effects()) {
            for (String phase : List.of("before-effect", "after-effect", "normal")) {
                try (var frontend = new Child("frontend-" + phase, DeduplicationFrontendProcess.class.getName(), classes.toString(), cluster.ports(), Integer.toString(effects.server.getLocalPort()))) {
                    frontend.send("CALL " + phase + " " + phase);
                    String response = frontend.read();
                    assertEquals(phase.equals("normal") ? "RESULT result-normal" : phase.equals("before-effect") ? "BODY_BEFORE_EFFECT" : "BODY_AFTER_EFFECT", response);
                    frontend.kill();
                }
                try (var restarted = new Child("restart-" + phase, DeduplicationFrontendProcess.class.getName(), classes.toString(), cluster.ports(), Integer.toString(effects.server.getLocalPort()))) {
                    assertEquals(phase.equals("normal") ? "RESULT result-normal" : "UNKNOWN", restarted.command("CALL " + phase + " " + phase));
                }
                assertEquals(phase.equals("before-effect") ? 0 : 1, effects.count(phase));
                var protectedValue = cluster.execution.query("E_VALUE", "protected-" + phase, deadline());
                assertEquals(phase.equals("normal") ? "OK" : "NOT_FOUND", protectedValue.status());
            }
        }
    }
}
