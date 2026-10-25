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

class ReplicatedQueueProcessIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private static int port() throws IOException { try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static String classpath() { return System.getProperty("j7m.distributed.test.classpath", System.getProperty("java.class.path")); }
    private final class Broker implements AutoCloseable {
        final String id, peers; final int service; Process process; BufferedReader output; BufferedWriter input;
        Broker(String id, String peers, int service) throws Exception { this.id = id; this.peers = peers; this.service = service; start(); }
        void start() throws Exception {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-cp", classpath(),
                    QueueNodeProcess.class.getName(), id, directory.resolve(id).toString(), peers, Integer.toString(service))
                    .redirectError(directory.resolve(id + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            String ready = read(); assertNotNull(ready, "Broker startup stderr " + directory.resolve(id + ".stderr")); assertTrue(ready.startsWith("READY"), ready);
        }
        String read() throws Exception { return CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); } }).get(15, TimeUnit.SECONDS); }
        String command(String command) throws Exception { input.write(command); input.newLine(); input.flush(); return read(); }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        @Override public void close() { if (process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    }
    private Process consumer(String member, String ports, boolean hold) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx96m", "-cp", classpath(),
                QueueConsumerProcess.class.getName(), member, ports, directory.resolve(member + ".acks").toString(), Boolean.toString(hold))
                .redirectError(directory.resolve(member + ".consumer.stderr").toFile()).redirectOutput(directory.resolve(member + ".consumer.stdout").toFile()).start();
    }
    private static <T> T await(Callable<T> call) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L; Exception last = null;
        do { try { return call.call(); } catch (IOException e) { last = e; Thread.onSpinWait(); } } while (System.nanoTime() < end);
        throw new AssertionError("Quorum recovery exceeded profile", last);
    }
    private List<String> logs() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String member : List.of("a", "b", "c")) { Path file = directory.resolve(member + ".acks"); if (Files.exists(file)) lines.addAll(Files.readAllLines(file)); }
        return lines;
    }
    @Test void authenticatedQueueFailsClosedOnClockJumpsAndSubquorum() throws Exception {
        int p1 = port(), p2 = port(), p3 = port(); String peers = "n1:" + p1 + ",n2:" + p2 + ",n3:" + p3;
        try (Broker one = new Broker("n1", peers, port()); Broker two = new Broker("n2", peers, port()); Broker three = new Broker("n3", peers, port())) {
            List<Broker> brokers = List.of(one, two, three);
            var addresses = brokers.stream().map(b -> new InetSocketAddress("127.0.0.1", b.service)).toList();
            var endpoint = new PartitionClient(addresses, "app", new byte[32]);
            var queue = new ReplicatedQueue("queue-partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
            await(() -> queue.join("join", "workers", "a", 100000, deadline()));
            queue.send("send", "q", "message", new byte[0], 1, deadline());
            byte[] unknownKey = new byte[32]; Arrays.fill(unknownKey, (byte) 99);
            var unauthorized = new PartitionClient(addresses, "app", unknownKey);
            assertThrows(IOException.class, () -> unauthorized.status(Instant.now().plusMillis(300)));
            byte[] time = new ResourceCommand("Q_TIME", "tenant", "forged-time", List.of("9999999999999")).encode();
            byte[] query = new ResourceCommand("Q_RESULT", "tenant", "query", List.of("forged-time")).encode();
            assertThrows(SecurityException.class, () -> endpoint.execute(1, "forged-time", time, query, Instant.now().plusMillis(300)));
            byte[] administrativeKey = new byte[32]; Arrays.fill(administrativeKey, (byte) 1);
            var administrative = new PartitionClient(addresses, "admin", administrativeKey);
            assertThrows(SecurityException.class, () -> administrative.execute(1, "forged-time", time, query, Instant.now().plusMillis(300)));
            for (long offset : new long[]{60000, -60000}) {
                for (Broker broker : brokers) broker.command("OFFSET " + offset);
                assertThrows(IOException.class, () -> queue.claim("unsafe:" + offset, "q", "workers", "a", 1, 100, Instant.now().plusMillis(300)));
            }
            for (Broker broker : brokers) broker.command("OFFSET 0");
            assertTrue(await(() -> queue.claim("healthy", "q", "workers", "a", 1, 10000, deadline())).startsWith("MESSAGE"));
            assertEquals("NACKED", queue.nack("nack", "q", "workers", "a", 1, "message", 1, deadline()));
            assertEquals(List.of("message"), queue.deadLetters("q", deadline()));
            two.kill(); three.kill();
            assertThrows(IOException.class, () -> queue.send("minority", "q", "new", new byte[0], 1, Instant.now().plusMillis(500)));
            two.start(); three.start();
            assertEquals(List.of("message"), await(() -> queue.deadLetters("q", deadline())));
            assertTrue(await(() -> queue.send("minority", "q", "new", new byte[0], 1, deadline())).startsWith("SENT"));
        }
    }

    @Test void tenThousandMessagesThreeConsumerJvmsLeaderRestartAndRebalanceLoseNothing() throws Exception {
        int p1 = port(), p2 = port(), p3 = port(); String peers = "n1:" + p1 + ",n2:" + p2 + ",n3:" + p3;
        try (Broker one = new Broker("n1", peers, port()); Broker two = new Broker("n2", peers, port()); Broker three = new Broker("n3", peers, port())) {
            List<Broker> brokers = List.of(one, two, three);
            List<InetSocketAddress> addresses = brokers.stream().map(b -> new InetSocketAddress("127.0.0.1", b.service)).toList();
            var endpoint = new PartitionClient(addresses, "app", new byte[32]);
            var queue = new ReplicatedQueue("queue-partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
            await(() -> queue.join("join-a", "workers", "a", 86400000, deadline()));
            queue.join("join-b", "workers", "b", 86400000, deadline()); queue.join("join-c", "workers", "c", 86400000, deadline());
            try (var executor = Executors.newFixedThreadPool(3)) {
                List<Future<?>> sends = new ArrayList<>();
                for (int partition = 0; partition < 3; partition++) {
                    int lane = partition;
                    sends.add(executor.submit(() -> {
                        for (int base = lane; base < 10000; base += 3 * 256) {
                            Map<String, byte[]> batch = new LinkedHashMap<>();
                            for (int i = base; i < Math.min(10000, base + 3 * 256); i += 3)
                                batch.put("m" + i, Integer.toString(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            int number = base;
                            try { assertTrue(await(() -> queue.sendBatch("send-batch:" + number, "q" + lane, batch, 3, deadline())).startsWith("SENT_BATCH")); }
                            catch (Exception e) { throw new CompletionException(e); }
                        }
                    }));
                }
                for (Future<?> send : sends) send.get(180, TimeUnit.SECONDS);
            }
            String ports = one.service + "," + two.service + "," + three.service;
            List<Process> consumers = new ArrayList<>();
            try {
                Process a = consumer("a", ports, true); consumers.add(a);
                consumers.add(consumer("b", ports, false)); consumers.add(consumer("c", ports, false));
                long waitHeld = System.nanoTime() + 20_000_000_000L;
                while (!Files.exists(directory.resolve("a.acks.held")) && System.nanoTime() < waitHeld) Thread.onSpinWait();
                assertTrue(Files.exists(directory.resolve("a.acks.held")), "Consumer a must claim before the injected fault");
                String[] held = Files.readString(directory.resolve("a.acks.held")).split("\t");
                a.destroyForcibly(); assertTrue(a.waitFor(10, TimeUnit.SECONDS));
                assertEquals("OK\t4", queue.rebalance("rebalance", "workers", deadline()));
                String heldQueue = "q" + Math.floorMod(Integer.parseInt(held[1].substring(1)), 3);
                assertEquals("STALE_GENERATION", queue.ack("stale-ack", heldQueue, "workers", "a", 3, held[1], Long.parseLong(held[4]), deadline()));
                String leader = one.command("STATUS").substring(4);
                Broker killed = brokers.stream().filter(b -> b.id.equals(leader)).findFirst().orElseThrow(); killed.kill();
                consumers.add(consumer("a", ports, false));
                await(() -> queue.heartbeat("after-kill", "workers", "a", 86400000, deadline())); killed.start();
                long end = System.nanoTime() + 180_000_000_000L;
                List<String> received = logs();
                while (received.size() < 10000 && System.nanoTime() < end) { Thread.onSpinWait(); received = logs(); }
                assertEquals(10000, received.size(), () -> "Consumer errors " + directory);
                Set<String> identities = new HashSet<>(); Map<String, Long> positions = new HashMap<>();
                for (String line : received) {
                    String[] row = line.split("\t"); assertTrue(identities.add(row[1]), "Acknowledged identity appeared twice " + row[1]);
                    long sequence = Long.parseLong(row[2]); assertTrue(sequence > positions.getOrDefault(row[0], 0L)); positions.put(row[0], sequence);
                }
                assertEquals(10000, identities.size()); assertTrue(identities.contains(held[1]));
            } finally { for (Process consumer : consumers) { consumer.destroyForcibly(); assertTrue(consumer.waitFor(10, TimeUnit.SECONDS)); } }
            for (Broker broker : brokers) broker.command("SNAPSHOT");
            for (Broker broker : brokers) broker.kill();
            for (Broker broker : brokers) broker.start();
            assertEquals("OK\t5", await(() -> queue.rebalance("post-restart", "workers", deadline())));
            for (String member : List.of("a", "b", "c")) {
                List<String> assignment = queue.assignment("workers", member, deadline());
                for (String name : assignment.subList(1, assignment.size())) assertEquals("EMPTY", queue.claim("empty:" + name, name, "workers", member, 5, 1000, deadline()));
            }
            assertTrue(queue.send("send-retry:0", "q0", "m0", new byte[]{48}, 3, deadline()).startsWith("SENT"));
        }
    }
}
