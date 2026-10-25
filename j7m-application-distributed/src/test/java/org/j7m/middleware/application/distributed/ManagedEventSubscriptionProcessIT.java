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
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedEventSubscriptionProcessIT {
    @TempDir Path directory;
    private static int port() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    final class Child implements AutoCloseable {
        final String id; final List<String> command; final boolean expectsReady; Process process; BufferedReader output; BufferedWriter input;
        Child(String id, String main, String... arguments) throws Exception {
            this(id, true, main, arguments);
        }
        Child(String id, boolean expectsReady, String main, String... arguments) throws Exception {
            this.id = id; this.expectsReady = expectsReady;
            command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx160m", "-cp",
                    System.getProperty("j7m.application.distributed.test.classpath"), main)); command.addAll(List.of(arguments)); start();
        }
        Child(String id,List<String> command) throws Exception {
            this.id=id;this.expectsReady=true;this.command=new ArrayList<>(command);start();
        }
        void start() throws Exception {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(directory.resolve(id + ".stderr").toFile())).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream())); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            try {
                String ready = read();
                if (expectsReady) { assertNotNull(ready, Files.readString(directory.resolve(id + ".stderr"))); assertTrue(ready.startsWith("READY"), ready); }
                else { assertNull(ready, "Rejected application cannot publish readiness"); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue()); }
            }
            catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        String read() throws Exception {
            String line = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }).get(15, TimeUnit.SECONDS);
            System.out.println("A14_PROCESS " + id + " pid=" + process.pid() + " " + line); return line;
        }
        String command(String command) throws Exception { input.write(command); input.newLine(); input.flush(); return read(); }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        @Override public void close() { if (process != null) { process.destroyForcibly(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } } }
    }
    final class Backends implements AutoCloseable {
        final List<Child> nodes = new ArrayList<>(); final List<Integer> sourcePorts = new ArrayList<>();
        final int metadataPort; final RemoteResourceClient source, metadata;
        Backends() throws Exception { this(true); }
        Backends(boolean includeMetadata) throws Exception {
            var peers = new TreeMap<String, Integer>();
            for (String id : List.of("one", "two", "three")) { peers.put(id, port()); sourcePorts.add(port()); }
            String roster = String.join(",", peers.entrySet().stream().map(entry -> entry.getKey()+":"+entry.getValue()).toList());
            metadataPort = port(); int metadataPeer = port();
            source = new RemoteResourceClient(sourcePorts.stream().map(value -> new InetSocketAddress("127.0.0.1", value)).toList(), "app", new byte[32], null);
            metadata = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", metadataPort)), "app", new byte[32], null);
            try {
                int index = 0;
                for (String id : peers.keySet()) nodes.add(new Child(id, "org.j7m.middleware.distributed.ResourceNodeProcess", id,
                        directory.resolve(id).toString(), roster, sourcePorts.get(index++).toString()));
                if (includeMetadata) nodes.add(new Child("metadata", "org.j7m.middleware.distributed.ResourceNodeProcess", "metadata", directory.resolve("metadata").toString(),
                        "metadata:" + metadataPeer, Integer.toString(metadataPort)));
                ready(source); if (includeMetadata) ready(metadata);
            } catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        void ready(ResourceEndpoint endpoint) throws Exception {
            Instant end = deadline(); IOException last = null;
            do {
                try { endpoint.read("readiness", end); return; }
                catch (IOException election) { last = election; java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); }
            } while (Instant.now().isBefore(end));
            throw new IOException("Actual backend readiness deadline", last);
        }
        String ports() { return String.join(",", sourcePorts.stream().map(Object::toString).toList()); }
        Child leader() throws Exception {
            for (var node : nodes.subList(0, 3)) {
                String[] status = node.command("STATUS").split(" ");
                if (status.length == 5 && !status[3].equals("null") && !status[3].isBlank()) {
                    return nodes.subList(0, 3).stream().filter(candidate -> candidate.id.equals(status[3])).findFirst().orElseThrow();
                }
            }
            throw new AssertionError("Actual resource quorum has no observed leader");
        }
        void write(String key) throws IOException { assertEquals("OK", source.execute("BATCH", "input-" + key, List.of(key, "0", key), deadline()).status()); }
        void restart() throws Exception { for (var node : nodes) node.kill(); for (var node : nodes) node.start(); ready(source); if (nodes.size() == 4) ready(metadata); }
        @Override public void close() { nodes.forEach(Child::close); }
    }
    private record Delivery(Socket socket, ManagedEventDelivery.Kind kind, EventJournal.Cursor cursor, byte[] payload) implements AutoCloseable {
        void complete() throws IOException { var output = new DataOutputStream(socket.getOutputStream()); output.writeBoolean(true); output.flush(); }
        @Override public void close() throws IOException { socket.close(); }
    }
    private static Delivery receive(ServerSocket sink) throws IOException {
        Socket socket = sink.accept(); socket.setSoTimeout(10000); var input = new DataInputStream(socket.getInputStream());
        try {
            var kind = ManagedEventDelivery.Kind.valueOf(input.readUTF()); var cursor = new EventJournal.Cursor(input.readUTF(), input.readLong(), input.readLong());
            int size = input.readInt(); if (size < 0 || size > 1048576) throw new IOException("Fixture payload bound");
            byte[] bytes = input.readNBytes(size); if (bytes.length != size) throw new EOFException(); return new Delivery(socket, kind, cursor, bytes);
        } catch (IOException | RuntimeException failure) { socket.close(); throw failure; }
    }
    private static void acknowledged(Child child, long sequence) throws Exception {
        Instant end = deadline();
        do {
            String status = child.command("STATUS"); assertNotNull(status);
            String[] fields = status.split(" ");
            if (fields.length == 4 && fields[1].equals("RUNNING") && Long.parseLong(fields[3]) >= sequence) return;
            if (fields.length > 1 && fields[1].equals("FAILED")) fail(status);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (Instant.now().isBefore(end));
        fail("Actual subscriber ACK deadline");
    }
    private static void stopped(Child child) throws Exception {
        Instant end = deadline();
        do {
            String result = child.command("STOP");
            if (result.equals("STOP true STOPPED")) return;
            assertEquals("STOP false DRAINING", result);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (Instant.now().isBefore(end));
        fail("Actual physical resource drain deadline");
    }
    @Test void trustedHostedFrameLimitRejectsBeforeHandlerAndDoesNotAcknowledgeTheCursor() throws Exception {
        Path bundle = GeneratedEventSubscriptionService.compileBundle(directory.resolve("small-generated"), false, "1KiB");
        try (var backends = new Backends(); var sink = new ServerSocket(0)) {
            backends.source.execute("BATCH", "large-snapshot", List.of("large", "0", "x".repeat(2048)), deadline());
            Path work = directory.resolve("small-subscriber");
            try (var child = new Child("small-subscriber", EventSubscriptionFrontendProcess.class.getName(), bundle.toString(),
                    bundle.getParent().resolve("operator.der").toString(), work.toString(), backends.ports(),
                    Integer.toString(backends.metadataPort), Integer.toString(sink.getLocalPort()))) {
                Instant end = deadline(); String status;
                do {
                    status = child.command("STATUS");
                    if (status.startsWith("STATUS FAILED ")) break;
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                } while (Instant.now().isBefore(end));
                assertEquals("STATUS FAILED HANDLER_FAILED -1", status);
                assertTrue(Files.exists(work.resolve("constructed")));
                assertFalse(Files.exists(work.resolve("constructed-body")), "Host input bound must reject before application entry");
                stopped(child);
            }
        }
    }

    @Test void signedGeneratedSubscriberCrashRedeliversExactCursorAndFullBackendRestartKeepsAcknowledgement() throws Exception {
        Path bundle = GeneratedEventSubscriptionService.compileBundle(directory.resolve("generated"));
        try (var backends = new Backends(); var sink = new ServerSocket(0)) {
            sink.setSoTimeout(15000);
            String[] arguments = {bundle.toString(), bundle.getParent().resolve("operator.der").toString(), directory.resolve("subscriber").toString(),
                    backends.ports(), Integer.toString(backends.metadataPort), Integer.toString(sink.getLocalPort())};
            for (String mode : List.of("missing-factory", "unknown-policy")) {
                String[] negative = Arrays.copyOf(arguments, 7); negative[2] = directory.resolve(mode).toString(); negative[6] = mode;
                try (var rejected = new Child(mode, false, EventSubscriptionFrontendProcess.class.getName(), negative)) {
                    assertFalse(Files.exists(Path.of(negative[2]).resolve("constructed")), "Invalid hosted capability/profile must reject before constructor");
                    String error = Files.readString(directory.resolve(mode + ".stderr"));
                    assertTrue(error.contains("managed activation preparation failed"), error);
                }
            }
            Path partialBundle = GeneratedEventSubscriptionService.compileBundle(directory.resolve("partial-generated"), true);
            for (String mode : List.of("partial-start", "partial-commit", "partial-held")) {
                String[] partial = Arrays.copyOf(arguments, 7); partial[0] = partialBundle.toString(); partial[1] = partialBundle.getParent().resolve("operator.der").toString();
                partial[2] = directory.resolve(mode).toString(); partial[6] = mode;
                try (var failed = new Child(mode, mode.equals("partial-held"), EventSubscriptionFrontendProcess.class.getName(), partial)) {
                    Path constructed = Path.of(partial[2]).resolve("constructed");
                    assertTrue(Files.exists(constructed)); assertFalse(Files.exists(Path.of(constructed + "-body")), "No app callback before global admission commit");
                    if (mode.equals("partial-held")) {
                        assertEquals("STATE DRAINING", failed.command("STATE"));
                        assertFalse(Files.exists(Path.of(constructed + "-closed")), "Actual backend work must retain container/classloader");
                        assertEquals("RELEASED", failed.command("RELEASE"));
                        assertEquals("STOP true STOPPED", failed.command("STOP"));
                        assertTrue(failed.process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, failed.process.exitValue());
                    } else {
                        String error = Files.readString(directory.resolve(mode + ".stderr"));
                        assertTrue(error.contains("A14_PARTIAL stopped=2 drained=2 closed=2"), error);
                        if (mode.equals("partial-commit")) assertTrue(error.contains("guarded=true"), error);
                        assertFalse(error.contains("Subscription bindings must drain before host close"), error);
                    }
                    assertTrue(Files.exists(Path.of(constructed + "-closed")), "Completed failure cleanup must release actual application lifecycle");
                }
            }
            EventJournal.Cursor uncertain;
            try (var child = new Child("subscriber", EventSubscriptionFrontendProcess.class.getName(), arguments)) {
                assertEquals(5, java.util.stream.Stream.concat(backends.nodes.stream().map(node -> node.process.pid()), java.util.stream.Stream.of(child.process.pid())).distinct().count());
                try (var image = receive(sink)) {
                    assertEquals(ManagedEventDelivery.Kind.SNAPSHOT, image.kind()); backends.write("during-snapshot"); image.complete();
                }
                try (var event = receive(sink)) {
                    assertEquals(ManagedEventDelivery.Kind.EVENT, event.kind()); uncertain = event.cursor();
                    assertEquals("during-snapshot", ResourceEvent.decode(event.payload()).changes().get("during-snapshot").value());
                    child.kill(); // Actual USER is blocked at the sink; no source ACK can have happened.
                }
            }
            try (var restarted = new Child("subscriber", EventSubscriptionFrontendProcess.class.getName(), arguments)) {
                try (var replay = receive(sink)) {
                    assertEquals(ManagedEventDelivery.Kind.EVENT, replay.kind()); assertEquals(uncertain, replay.cursor());
                    var leader = backends.leader(); assertEquals("ACK", leader.command("ARM RESULT"));
                    replay.complete();
                    assertEquals("FAULT RESULT", leader.read());
                    leader.kill(); // ACK is in the committed log, but this reply cannot reach the live subscriber.
                }
                acknowledged(restarted, uncertain.sequence()); restarted.kill();
            }
            backends.restart();
            try (var restarted = new Child("subscriber", EventSubscriptionFrontendProcess.class.getName(), arguments)) {
                backends.write("after-restart");
                try (var next = receive(sink)) {
                    assertEquals(ManagedEventDelivery.Kind.EVENT, next.kind()); assertTrue(next.cursor().sequence() > uncertain.sequence());
                    assertEquals("after-restart", ResourceEvent.decode(next.payload()).changes().get("after-restart").value());
                    next.complete(); acknowledged(restarted, next.cursor().sequence());
                }
                stopped(restarted);
                assertTrue(restarted.process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, restarted.process.exitValue());
            }
        }
    }
}
