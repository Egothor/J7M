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
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.PartitionClient;
import org.j7m.middleware.distributed.PartitionEndpoint;
import org.j7m.middleware.distributed.PartitionTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedPartitionProcessIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(8); }
    Path artifact(final String suffix, final int multiplier) throws Exception {
        String body = "package fixture; import org.j7m.middleware.annotations.*; @PartitionHandler(resource=\"counter\") @ShardKey @OrderedBy(\"\") @DataLocality(\"near\") public class Counter {public Counter(){} public int initial(){return 0;} public int apply(int state,String command){return state+Integer.parseInt(command)*" + multiplier + ";} public int query(int state,String query){return state;}}\n";
        return compileArtifact(suffix, Map.of("Counter", body));
    }
    Path recordArtifact() throws Exception {
        return compileArtifact("record", Map.of("Command", "package fixture; import org.j7m.middleware.annotations.*; @SchemaType(id=\"command\",version=1) public record Command(@WireField(id=7) String account,@WireField(id=8) int delta){}",
                "Counter", "package fixture; import org.j7m.middleware.annotations.*; @PartitionHandler(resource=\"counter\") @ShardKey(\"7\") @OrderedBy(\"7\") @DataLocality(\"near\") public class Counter{public Counter(){} public int initial(){return 0;} public int apply(int state,Command command){return state+command.delta();} public int query(int state,String query){return state;}}",
                "Caller", "package fixture; import org.j7m.middleware.annotations.*; import org.j7m.middleware.application.distributed.ManagedPartitionClient; @J7mComponent public class Caller {public final ManagedPartitionClient client; public Caller(@J7mResource(\"partition\") ManagedPartitionClient client){this.client=client;}}"));
    }
    private Path compileArtifact(String suffix, Map<String, String> sources) throws Exception {
        Path sourceRoot = directory.resolve("source-" + suffix).resolve("fixture"); Files.createDirectories(sourceRoot);
        var files = new ArrayList<Path>();
        for (var source : sources.entrySet()) { Path path = sourceRoot.resolve(source.getKey() + ".java"); Files.writeString(path, source.getValue()); files.add(path); }
        Path output = directory.resolve("classes-" + suffix); Files.createDirectories(output);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, manager, null, List.of("-classpath", System.getProperty("j7m.application.distributed.test.classpath"),
                    "-d", output.toString()), null, manager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new org.j7m.middleware.processor.J7mProcessor())); assertTrue(task.call());
        }
        Path jar = directory.resolve("program-" + suffix + ".jar");
        try (var stream = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(output)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                stream.putNextEntry(new JarEntry(output.relativize(file).toString().replace(java.io.File.separatorChar, '/')));
                Files.copy(file, stream); stream.closeEntry();
            }
        }
        return jar;
    }
    final class Child implements AutoCloseable {
        final String id; final List<String> command; Process process; BufferedReader output; BufferedWriter input;
        int service; String group;
        Child(String id, Path artifact, String roster, String config) {
            this.id = id;
            command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-cp",
                    System.getProperty("j7m.application.distributed.test.classpath"), ManagedPartitionNodeProcess.class.getName(), id,
                    directory.resolve("node-" + id).toString(), roster, "a,b,c", artifact.toString(), config, "1"));
        }
        void start() throws Exception {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(directory.resolve(id + ".stderr").toFile())).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            try { String[] ready = read().split(" "); assertEquals("READY", ready[0]); service = Integer.parseInt(ready[1]); group = ready[2]; }
            catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        String read() throws Exception {
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!output.ready() && process.isAlive() && System.nanoTime() < until) Thread.sleep(5);
            assertTrue(output.ready() || !process.isAlive(), "Child response deadline: " + id);
            String line = output.readLine();
            assertNotNull(line, () -> { try { return Files.readString(directory.resolve(id + ".stderr")); } catch (Exception failure) { return "Child exited"; } });
            System.out.println("A10_PROCESS " + id + " pid=" + process.pid() + " " + line); return line;
        }
        String command(String value) throws Exception { input.write(value); input.newLine(); input.flush(); return read(); }
        void stop() throws Exception { assertEquals("STOP true", command("STOP")); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        PartitionEndpoint endpoint() { return new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", service)), "client", new byte[32]); }
        @Override public void close() {
            if (process == null || !process.isAlive()) return;
            process.destroyForcibly(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
    }
    private final class Cluster implements AutoCloseable {
        final Map<String, ServerSocket> reservations = new TreeMap<>(); final Map<String, Child> nodes = new TreeMap<>();
        final String roster;
        Cluster(Path artifact, int members, String configuration) throws Exception {
            this(artifact, members, Map.of("a", configuration, "b", configuration, "c", configuration, "d", configuration, "e", configuration));
        }
        Cluster(Path artifact, int members, Map<String, String> configurations) throws Exception {
            try {
                for (String id : List.of("a", "b", "c", "d", "e")) reservations.put(id, new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()));
                roster = String.join(",", reservations.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue().getLocalPort()).toList());
                for (String id : reservations.keySet()) nodes.put(id, new Child(id, artifact, roster, configurations.get(id)));
                for (String id : List.of("a", "b", "c", "d", "e").subList(0, members)) start(id);
            } catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        void start(String id) throws Exception { var reservation = reservations.remove(id); if (reservation != null) reservation.close(); nodes.get(id).start(); }
        PartitionEndpoint endpoint() { return new PartitionClient(nodes.values().stream().filter(child -> child.process != null && child.process.isAlive()).map(child -> new InetSocketAddress("127.0.0.1", child.service)).toList(), "client", new byte[32]); }
        Child leader() throws Exception {
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < until) {
                for (var child : nodes.values()) if (child.process != null && child.process.isAlive()) {
                    String[] status = child.command("STATUS").split(" ");
                    if (status.length == 5 && status[1].equals(child.id)) return child;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("No elected leader");
        }
        @Override public void close() { nodes.values().forEach(Child::close); reservations.values().forEach(socket -> { try { socket.close(); } catch (Exception ignored) { /* Test cleanup preserves original failure. */ } }); }
    }
    @SuppressWarnings("unchecked")
    ManagedPartitionDefinition<?> definition(URLClassLoader loader) {
        return ServiceLoader.load(ManagedComponentIndex.class, loader).stream().filter(provider -> provider.type().getClassLoader() == loader)
                .map(ServiceLoader.Provider::get).flatMap(index -> index.components().stream()).filter(ManagedPartitionDefinition.class::isInstance)
                .map(value -> (ManagedPartitionDefinition<?>) value).findFirst().orElseThrow();
    }
    @Test void actualThreeJvmGeneratedRecordSchemaUsesStableNumericShardIdentityAndConcreteCodec() throws Exception {
        Path artifact = recordArtifact();
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, getClass().getClassLoader()); var cluster = new Cluster(artifact, 3, "current")) {
            var program = definition(loader);
            var indexes = ServiceLoader.load(org.j7m.middleware.codec.api.SchemaIndex.class, loader).stream()
                    .filter(provider -> provider.type().getClassLoader() == loader).map(ServiceLoader.Provider::get).toList();
            var schemas = org.j7m.middleware.codec.api.SchemaIndexes.registry(indexes);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
            String fingerprint = ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle(digest, Map.of("configuration", "current")), program, schemas, "tenant", 10000);
            String group = cluster.nodes.get("a").group;
            var client = new ManagedPartitionClient((key, deadline) -> new PartitionTopology.Route("partition", group, 1),
                    Map.of(group, new ManagedPartitionClient.Group(cluster.endpoint(), Map.of())), program, fingerprint, "tenant", new DefaultValueCodec(schemas), schemas);
            var constructor = loader.loadClass("fixture.Command").getConstructor(String.class, int.class);
            Object first = constructor.newInstance("account", 2), second = constructor.newInstance("account", 3);
            String key = client.routingKey(first); assertEquals(key, client.routingKey(second));
            var indexesForCaller = ServiceLoader.load(ManagedComponentIndex.class, loader).stream()
                    .filter(provider -> provider.type().getClassLoader() == loader).map(ServiceLoader.Provider::get)
                    .filter(index -> index.components().stream().allMatch(component -> component.type().getName().equals("fixture.Caller"))).toList();
            var resourceFactory = new ResourceBindingFactory(Map.of("partition", ResourceBindingFactory.partitionProgram(client)), Set.of("default"));
            ManagedPartitionClient injected;
            try (var container = org.j7m.middleware.application.components.ManagedContainer.activate(indexesForCaller, 10, null,
                    new org.j7m.middleware.application.components.ManagedHostBindings(Map.of("resource.name", resourceFactory)))) {
                Object caller = container.get(loader.loadClass("fixture.Caller"), "", List.of());
                injected = (ManagedPartitionClient) caller.getClass().getField("client").get(caller);
                assertEquals(client.routingKey(first), injected.routingKey(first));
                assertEquals("OK", injected.execute("injected", constructor.newInstance("account", 0), deadline()).status());
            }
            assertThrows(IllegalStateException.class, () -> injected.routingKey(first));
            assertThrows(IllegalStateException.class, () -> injected.execute("late", first, deadline()));
            var receipt = client.execute("first", first, deadline()); assertEquals("OK", receipt.status());
            assertEquals(receipt, client.execute("first", first, deadline()));
            assertTrue(client.execute("second", second, deadline()).sequence() > receipt.sequence());
            assertEquals(5, client.query(key, "current", Integer.class, deadline()));
            assertEquals(5, client.query(key, "current", int.class, deadline()));
            assertThrows(IllegalArgumentException.class, () -> client.execute("forged", "wrong-key", first, deadline()));
            assertEquals(5, client.query(key, "current", Integer.class, deadline()));
            assertEquals(5, client.query(key, "current", int.class, deadline()));
        }
    }

    @Test void differentLiveProgramConfigurationsCannotFormQuorumOrApplyAnyClientMutation() throws Exception {
        Path artifact = artifact("isolated", 1);
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, getClass().getClassLoader());
                var cluster = new Cluster(artifact, 3, Map.of("a", "one", "b", "two", "c", "three", "d", "one", "e", "one"))) {
            var program = definition(loader); var schemas = new SchemaRegistry(List.of());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
            String fingerprint = ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle(digest, Map.of("configuration", "one")), program, schemas, "tenant", 10000);
            String group = cluster.nodes.get("a").group;
            assertEquals(3, cluster.nodes.values().stream().filter(child -> child.process != null).map(child -> child.group).distinct().count());
            var client = new ManagedPartitionClient((key, deadline) -> new PartitionTopology.Route("partition", group, 1),
                    Map.of(group, new ManagedPartitionClient.Group(cluster.nodes.get("a").endpoint(), Map.of())), program, fingerprint, "tenant", new DefaultValueCodec(schemas), schemas);
            assertThrows(java.io.IOException.class, () -> client.execute("must-not-commit", "1", Instant.now().plusMillis(750)));
            for (var child : cluster.nodes.values()) if (child.process != null) {
                String[] status = child.command("STATUS").split(" "); assertEquals("0", status[4], "Different programs cannot apply even a leader noop");
            }
        }
    }

    @Test void actualGeneratedProgramInThreeJvmsRetainsCompleteReceiptsThroughFiveVoterRestart() throws Exception {
        Path artifact = artifact("current", 1);
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, getClass().getClassLoader());
                var cluster = new Cluster(artifact, 3, "current"); var unavailableLocality = new ServerSocket(0)) {
            var program = definition(loader); var schemas = new SchemaRegistry(List.of());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
            String fingerprint = ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle(digest, Map.of("configuration", "current")), program, schemas, "tenant", 10000);
            String group = cluster.nodes.get("a").group;
            assertEquals(Set.of(group), cluster.nodes.values().stream().filter(child -> child.process != null).map(child -> child.group).collect(java.util.stream.Collectors.toSet()));
            ManagedPartitionClient.Routes routes = (key, deadline) -> new PartitionTopology.Route("partition", group, 1);
            var client = new ManagedPartitionClient(routes, Map.of(group, new ManagedPartitionClient.Group(cluster.endpoint(), Map.of("near",
                    new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", unavailableLocality.getLocalPort())), "client", new byte[32])))), program, fingerprint, "tenant", new DefaultValueCodec(schemas), schemas);
            long previous = 0;
            for (int index = 0; index < 20; index++) {
                var receipt = client.execute("request-" + index, "1", deadline()); assertEquals("OK", receipt.status()); assertTrue(receipt.sequence() > previous); previous = receipt.sequence();
            }
            unavailableLocality.setSoTimeout(1000);
            try (var attemptedPreference = unavailableLocality.accept()) {
                assertTrue(new java.io.DataInputStream(attemptedPreference.getInputStream()).readInt() > 0,
                        "Declared locality must cause an actual bounded network attempt before fallback");
            }
            assertEquals(20, client.query("same-key", "current", deadline()));
            var first = client.execute("request-0", "1", deadline()); assertEquals(1, first.sequence());
            cluster.start("d"); cluster.start("e");
            boolean configured = false; long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (!configured && System.nanoTime() < until) {
                var leader = cluster.leader(); leader.command("MEMBERS a,b,c,d,e");
                String[] status = leader.command("STATUS").split(" "); configured = status.length == 5 && status[2].equals("1") && status[3].equals("5");
                if (!configured) Thread.sleep(10);
            }
            assertTrue(configured); assertTrue(cluster.leader().command("SNAPSHOT").startsWith("SNAPSHOT "));
            for (var child : cluster.nodes.values()) child.stop();
            for (var child : cluster.nodes.values()) child.start();
            assertEquals(Set.of(group), cluster.nodes.values().stream().map(child -> child.group).collect(java.util.stream.Collectors.toSet()));
            var restored = new ManagedPartitionClient(routes, Map.of(group, new ManagedPartitionClient.Group(cluster.endpoint(), Map.of())), program, fingerprint, "tenant", new DefaultValueCodec(schemas), schemas);
            assertEquals(20, restored.query("same-key", "current", deadline()));
            assertEquals(first, restored.execute("request-0", "1", deadline()));
            assertEquals("OK", restored.execute("after-restart", "1", deadline()).status()); assertEquals(21, restored.query("same-key", "current", deadline()));
            String[] status = cluster.leader().command("STATUS").split(" "); assertEquals("1", status[2]); assertEquals("5", status[3]);
            var changed = artifact("different-code", 2);
            var rejected = cluster.nodes.get("e"); rejected.stop(); rejected.command.set(9, changed.toString());
            rejected.process = new ProcessBuilder(rejected.command).redirectError(directory.resolve("wrong-code.stderr").toFile()).start();
            assertTrue(rejected.process.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, rejected.process.exitValue());
            assertEquals(-1, rejected.process.getInputStream().read());
            String rejection = Files.readString(directory.resolve("wrong-code.stderr"));
            assertTrue(rejection.contains("WAL identity mismatch") || rejection.contains("snapshot schema/group"), rejection);
            assertEquals(21, restored.query("same-key", "current", deadline()));

        }
    }
}
