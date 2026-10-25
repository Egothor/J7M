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
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.consensus.*;

/**
 * Actual isolated child-JVM cluster fixture; no production protocol is implemented by this harness.
 */
final class AcceptanceCluster implements AutoCloseable {
    final String kind;
    final List<Replica> replicas = new ArrayList<>();
    private final Path root;
    private final Map<String, InetSocketAddress> peers = new TreeMap<>();
    private String generation;
    private Path storage;
    private final String classpath;
    AcceptanceCluster(Path root, String kind, int count) throws Exception {
        this.root = root.toAbsolutePath(); this.kind = kind; generation = "acceptance-" + kind + "-initial"; storage = this.root.resolve("initial"); Files.createDirectories(storage);
        classpath = System.getProperty("j7m.distributed.test.classpath", System.getProperty("java.class.path"));
        for (int i = 0; i < count; i++) peers.put("n" + i, new InetSocketAddress("127.0.0.1", port()));
        try { for (String id : peers.keySet()) { Replica replica = new Replica(id, port()); replicas.add(replica); replica.start(); } awaitQuorum(); }
        catch (Exception error) { close(); throw error; }
    }
    private void awaitQuorum() throws Exception {
        if (!kind.equals("map")) return;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); IOException last = null;
        do {
            try { map().read("acceptance-readiness", Instant.now().plusSeconds(2)); return; }
            catch (IOException error) { last = error; Thread.onSpinWait(); }
        } while (System.nanoTime() < end);
        throw new IOException("Acceptance quorum did not become readable within30seconds", last);
    }
    private static int port() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    static Instant deadline() { return Instant.now().plusSeconds(25); }
    final class Replica {
        final String id; final int servicePort; Process process; BufferedReader output; BufferedWriter input;
        Replica(String id, int servicePort) { this.id = id; this.servicePort = servicePort; }
        void start() throws Exception {
            String roster = String.join(",", peers.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue().getPort()).toList());
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m", "-cp", classpath, AcceptanceNodeProcess.class.getName(), id, storage.resolve(id).toString(), roster, Integer.toString(servicePort), kind, generation).redirectError(root.resolve(id + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            String ready = line(); if (!Objects.equals(ready, "READY " + id)) throw new IOException("Acceptance node startup failed " + id + ": " + ready);
        }
        String line() throws Exception { return CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); } }).get(20, TimeUnit.SECONDS); }
        String command(String text) throws Exception { input.write(text); input.newLine(); input.flush(); return line(); }
        void kill() throws Exception { if (process != null && process.isAlive()) { process.destroyForcibly(); if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Acceptance node did not stop"); } }
        void signal(String signal) throws Exception { Process control = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid())).start(); if (!control.waitFor(5, TimeUnit.SECONDS) || control.exitValue() != 0) throw new IOException("Fault signal failed"); }
    }
    List<InetSocketAddress> services() { return replicas.stream().map(replica -> new InetSocketAddress("127.0.0.1", replica.servicePort)).toList(); }
    RemoteResourceClient map() { return new RemoteResourceClient(services(), "operator", new byte[32], null); }
    ReplicatedQueue queue() { var endpoint = new PartitionClient(services(), "operator", new byte[32]); return new ReplicatedQueue("acceptance-queue", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(endpoint, 1)); }
    Replica leader() throws Exception {
        for (Replica replica : replicas) if (replica.process.isAlive()) { String[] status = replica.command("STATUS").split(" "); if (status.length >= 2 && status[1].equals(replica.id)) return replica; }
        throw new IOException("No current acceptance leader");
    }
    ConsensusBackup.Metadata backup(Path target) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); Exception last = null;
        do {
            try { String response = leader().command("BACKUP " + target.toAbsolutePath()); if (!response.startsWith("ACK ")) throw new IOException(response); return ConsensusBackup.inspect(target, AcceptanceNodeProcess.schema(kind)); }
            catch (IOException error) { last = error; Thread.onSpinWait(); }
        } while (System.nanoTime() < end);
        throw new IOException("Backup quorum recovery exceeded30seconds", last);
    }
    void restartAll() throws Exception { for (Replica replica : replicas) replica.kill(); for (Replica replica : replicas) replica.start(); awaitQuorum(); }
    void restore(Path backup, long floor) throws Exception {
        for (Replica replica : replicas) replica.kill(); generation = "acceptance-" + kind + "-restored"; storage = root.resolve("restored"); Files.createDirectories(storage);
        for (Replica replica : replicas) {
            var target = new NetworkConsensusConfig(replica.id, peers, peers.keySet(), storage.resolve(replica.id), new byte[32], Duration.ofMillis(500), Duration.ofMillis(20), 1048576, 134217728, null, generation);
            ConsensusBackup.restore(backup, target, AcceptanceNodeProcess.schema(kind), floor, () -> AcceptanceNodeProcess.state(kind));
        }
        for (Replica replica : replicas) replica.start();
        awaitQuorum();
    }
    @Override public void close() { for (Replica replica : replicas) { try { replica.kill(); } catch (Exception error) { throw new IllegalStateException("Acceptance process cleanup failed", error); } } }
}
