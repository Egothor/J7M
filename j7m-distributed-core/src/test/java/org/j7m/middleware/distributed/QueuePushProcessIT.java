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
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueuePushProcessIT {
    @TempDir Path directory;
    private static int port() throws IOException { try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static Instant deadline() { return Instant.now().plusSeconds(15); }
    private final class Broker implements AutoCloseable {
        final String id, peers; final int service; Process process; BufferedReader output; BufferedWriter input;
        Broker(String id, String peers) throws Exception { this.id = id; this.peers = peers; service = port(); start(); }
        void start() throws Exception {
            String classpath = System.getProperty("j7m.distributed.test.classpath", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx128m", "-cp", classpath,
                    QueueNodeProcess.class.getName(), id, directory.resolve(id).toString(), peers, Integer.toString(service))
                    .redirectError(directory.resolve(id + ".stderr").toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            assertTrue(read().startsWith("READY"), () -> "Startup failed: " + directory.resolve(id + ".stderr"));
        }
        String read() throws Exception {
            return CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }).get(15, TimeUnit.SECONDS);
        }
        long[] metrics() throws Exception {
            input.write("METRICS\n"); input.flush(); String[] fields = read().split(" ");
            return Arrays.stream(fields).skip(1).mapToLong(Long::parseLong).toArray();
        }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        @Override public void close() {
            if (process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } }
        }
    }
    private static final class Consumer implements Flow.Subscriber<QueueDelivery> {
        final BlockingQueue<Object> received = new ArrayBlockingQueue<>(8); Flow.Subscription subscription;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(QueueDelivery value) { assertTrue(received.offer(value)); }
        @Override public void onError(Throwable failure) { received.offer(failure); }
        @Override public void onComplete() { }
        QueueDelivery take() throws Exception {
            Object value = received.poll(10, TimeUnit.SECONDS); assertNotNull(value, "No complete push delivery");
            if (value instanceof Throwable failure) throw new AssertionError("Push failed", failure);
            return (QueueDelivery) value;
        }
    }
    private static QueueSession reconnect(PartitionClient endpoint, String queue, String member) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); IOException last = null;
        do { try { return endpoint.queueSession(1, queue, "workers", member, 60000, deadline()); }
            catch (IOException unavailable) { last = unavailable; Thread.onSpinWait(); }
        } while (System.nanoTime() < end);
        throw new IOException("Session reconnect failed", last);
    }
    @Test void sixtyFourIdleSessionsUseNoForegroundLogOrQueriesAndPushFullPayloadWithoutClientClaim() throws Exception {
        String peers = "a:" + port() + ",b:" + port() + ",c:" + port();
        try (Broker a = new Broker("a", peers); Broker b = new Broker("b", peers); Broker c = new Broker("c", peers)) {
            List<Broker> brokers = List.of(a, b, c);
            var addresses = brokers.stream().map(value -> new InetSocketAddress("127.0.0.1", value.service)).toList();
            var endpoint = new PartitionClient(addresses, "app", new byte[32]);
            endpoint.status(deadline());
            var queue = new ReplicatedQueue("queue-partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
            List<QueueSession> sessions = new ArrayList<>(); List<Consumer> consumers = new ArrayList<>();
            try {
                for (int i = 0; i < 64; i++) {
                    QueueSession session = reconnect(endpoint, "q" + i, "m" + i); sessions.add(session);
                    Consumer consumer = new Consumer(); consumers.add(consumer); session.subscribe(consumer);
                }
                List<long[]> before = new ArrayList<>(); for (Broker broker : brokers) before.add(broker.metrics());
                CompletableFuture<Void> idleWindow = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute(() -> idleWindow.complete(null));
                idleWindow.get(2, TimeUnit.SECONDS);
                for (int i = 0; i < brokers.size(); i++) {
                    long[] after = brokers.get(i).metrics(); assertArrayEquals(before.get(i), after, "Idle sessions mutated foreground state");
                    assertEquals(0, after[1]); assertEquals(0, after[2]);
                }
                byte[] payload = new byte[32768]; Arrays.fill(payload, (byte) 7);
                assertEquals("SENT\t1", queue.send("send", "q63", "message", payload, 3, deadline()));
                QueueDelivery first = consumers.get(63).take(); assertArrayEquals(payload, first.payload()); assertEquals(1, first.receipt());
                assertEquals("q63", first.queue()); assertEquals("m63", first.member());
                for (QueueSession session : sessions) session.close(); sessions.clear();
                for (Broker broker : brokers) broker.kill(); for (Broker broker : brokers) broker.start();
                try (QueueSession session = reconnect(endpoint, "q63", "m63")) {
                    Consumer consumer = new Consumer(); session.subscribe(consumer); QueueDelivery resumed = consumer.take();
                    assertEquals(first.messageId(), resumed.messageId()); assertEquals(first.sequence(), resumed.sequence());
                    assertEquals(first.member(), resumed.member()); assertEquals(first.attempt(), resumed.attempt());
                    assertArrayEquals(first.payload(), resumed.payload()); assertEquals(first.receipt(), resumed.receipt());
                    assertEquals(first.generation(), resumed.generation());
                    assertEquals("ACKED", queue.ack("ack", resumed.queue(), resumed.group(), resumed.member(), resumed.generation(), resumed.messageId(), resumed.receipt(), deadline()));
                    assertEquals("ACKED", queue.ack("ack", resumed.queue(), resumed.group(), resumed.member(), resumed.generation(), resumed.messageId(), resumed.receipt(), deadline()));
                }
                System.out.println("Queue push actual replica PIDs " + brokers.stream().map(value -> value.process.pid()).toList());
            } finally { for (QueueSession session : sessions) session.close(); }
        }
    }
    @Test void observationalPoisonPushPreservesOriginalReceiptAndOnlyExplicitAdministratorCanRedrive() throws Exception {
        String peers = "a:" + port() + ",b:" + port() + ",c:" + port();
        try (Broker a = new Broker("a", peers); Broker b = new Broker("b", peers); Broker c = new Broker("c", peers)) {
            var addresses = List.of(a, b, c).stream().map(value -> new InetSocketAddress("127.0.0.1", value.service)).toList();
            var endpoint = new PartitionClient(addresses, "app", new byte[32]); endpoint.status(deadline());
            var queue = new ReplicatedQueue("queue-partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
            queue.join("join", "workers", "member", 60000, deadline()); queue.send("send", "poison", "bad", new byte[]{9}, 1, deadline());
            assertTrue(queue.claim("claim", "poison", "workers", "member", 1, 60000, deadline()).startsWith("MESSAGE"));
            assertEquals("NACKED", queue.nack("nack", "poison", "workers", "member", 1, "bad", 1, deadline()));
            try (QueueSession session = endpoint.deadLetterSession(1, "poison", "observer", deadline())) {
                Consumer observer = new Consumer(); session.subscribe(observer); QueueDelivery retained = observer.take();
                assertTrue(retained.observational()); assertEquals(1, retained.receipt()); assertArrayEquals(new byte[]{9}, retained.payload());
                DeadLetterPage first = queue.deadLetterPage("poison", null, 1, deadline());
                assertEquals("bad", first.entries().getFirst().messageId()); assertFalse(first.continuation().isEmpty());
                assertTrue(queue.deadLetterPage("poison", first, 1, deadline()).entries().isEmpty());
                assertThrows(SecurityException.class, () -> queue.deadLetterPage("other", first, 1, deadline()));
                var forged = new DeadLetterPage(first.entries(), first.through(), first.highWater(), "forged");
                assertThrows(SecurityException.class, () -> queue.deadLetterPage("poison", forged, 1, deadline()));
                assertEquals(List.of("bad"), queue.deadLetters("poison", deadline()), "Observation never removes or ACKs retained poison");
                assertThrows(SecurityException.class, () -> queue.redrive("denied", "poison", "bad", 1, "child", 2, deadline()));
                byte[] secret = new byte[32]; Arrays.fill(secret, (byte) 1);
                var admin = new PartitionClient(addresses, "admin", secret);
                var privileged = new ReplicatedQueue("queue-partition", "tenant", (p, d) -> new ReplicatedQueue.Route(admin, 1));
                assertEquals("REDRIVEN\t2", privileged.redrive("redrive", "poison", "bad", 1, "child", 2, deadline()));
                assertEquals("REDRIVEN\t2", privileged.redrive("redrive", "poison", "bad", 1, "child", 2, deadline()));
                DeadLetter audit = queue.deadLetterStream("poison", deadline()).findFirst().orElseThrow();
                assertEquals("child", audit.child());
                assertTrue(queue.claim("child", "poison", "workers", "member", 1, 60000, deadline()).startsWith("MESSAGE\tchild\t"));
            }
        }
    }
}
