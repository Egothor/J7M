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
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.tools.Server;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedTransactionProcessIT {
    @TempDir Path directory;
    private static int port() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Instant deadline() { return Instant.now().plusSeconds(10); }
    private final class Child implements AutoCloseable {
        final String id; final List<String> command; Process process; BufferedReader output; BufferedWriter input;
        Child(String id, String main, String... arguments) throws Exception {
            this.id = id; command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-cp", System.getProperty("j7m.application.distributed.test.classpath"), main)); command.addAll(List.of(arguments)); start();
        }
        void start() throws Exception {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(directory.resolve(id + ".stderr").toFile())).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream())); output = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try { String line = read(); assertNotNull(line, Files.readString(directory.resolve(id + ".stderr"))); assertTrue(line.startsWith("READY"), line); }
            catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        String read() throws Exception {
            String line = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }).get(15, TimeUnit.SECONDS);
            System.out.println("A11_CHILD " + id + " pid=" + process.pid() + " " + line); return line;
        }
        void send(String line) throws Exception { input.write(line); input.newLine(); input.flush(); }
        String command(String line) throws Exception { send(line); return read(); }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        @Override public void close() { process.destroyForcibly(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } }
    }
    private final class Groups implements AutoCloseable {
        final List<Child> nodes = new ArrayList<>(); final List<Integer> ports = new ArrayList<>();
        final List<RemoteResourceClient> clients = new ArrayList<>(); RecoverableTransactions recovery;
        Groups() throws Exception {
            try {
                for (String id : List.of("coordinator", "left", "right")) {
                    int service = port(); ports.add(service);
                    nodes.add(new Child(id, "org.j7m.middleware.distributed.ResourceNodeProcess", id, directory.resolve(id).toString(), id + ":" + port(), Integer.toString(service)));
                    byte[] key = new byte[32]; Arrays.fill(key, (byte) 1);
                    clients.add(new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", service)), "coordinator", key, null));
                }
                assertEquals(3, nodes.stream().map(node -> node.process.pid()).distinct().count());
                for (var client : clients) ready(client);
                recovery = new RecoverableTransactions(clients.get(0), Map.of("left", clients.get(1), "right", clients.get(2)));
            } catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        private void ready(ResourceEndpoint client) throws Exception {
            Instant end = deadline(); IOException last = null;
            do {
                try { assertNull(client.transaction("readiness", end)); return; }
                catch (IOException notLeaderYet) { last = notLeaderYet; java.util.concurrent.locks.LockSupport.parkNanos(5000000); }
            } while (Instant.now().isBefore(end));
            throw new IOException("Read-only resource quorum readiness expired", last);
        }
        void restartAll() throws Exception { for (var node : nodes) node.kill(); for (var node : nodes) node.start(); for (var client : clients) ready(client); }
        @Override public void close() { nodes.forEach(Child::close); }
    }
    @Test void realPreparedAndCommittedGroupsRecoverAfterFrontendAndAllBackendJvmCrashesWithoutFalseAbort() throws Exception {
        Path classes = GeneratedTransactionService.compile(directory.resolve("generated"), false);
        for (String boundary : List.of("PREPARE", "DECIDE")) {
            Path original = directory; directory = original.resolve(boundary); Files.createDirectories(directory);
            try (var groups = new Groups()) {
                String id;
                try (var frontend = new Child("frontend", TransactionFrontendProcess.class.getName(), "resource", classes.toString(), boundary,
                        groups.ports.get(0).toString(), groups.ports.get(1).toString(), groups.ports.get(2).toString())) {
                    frontend.send("CALL orders/account 7"); String marker = frontend.read(); assertNotNull(marker); assertTrue(marker.startsWith(boundary + " "), marker);
                    id = marker.substring(boundary.length() + 1); frontend.kill();
                }
                assertEquals("PREPARED", groups.clients.get(1).transaction(id, deadline()).status());
                assertEquals("CONFLICT", groups.clients.get(1).execute("BATCH", "phantom", List.of("orders/phantom", "0", "forbidden"), deadline()).status());
                var ordinary = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", groups.ports.get(1))), "app", new byte[32], null);
                assertThrows(SecurityException.class, () -> ordinary.execute("COMMIT", "unauthorized", List.of(id, "1"), deadline()));
                groups.nodes.getFirst().kill();
                assertThrows(IOException.class, () -> groups.recovery.recover(id, Instant.now().plusMillis(250)));
                assertEquals("PREPARED", groups.clients.get(1).transaction(id, deadline()).status());
                assertNull(groups.clients.get(1).read("orders/account", deadline()));
                for (int i = 1; i < groups.nodes.size(); i++) assertTrue(groups.nodes.get(i).command("SNAPSHOT").startsWith("ACK"));
                groups.restartAll();
                String status = groups.recovery.recover(id, deadline()).status();
                System.out.println("A11_RECOVER " + boundary + " " + id + " " + status);
                if (boundary.equals("PREPARE")) {
                    assertEquals("IN_DOUBT", status); assertEquals("PREPARED", groups.clients.get(1).transaction(id, deadline()).status());
                    assertNull(groups.clients.get(1).read("orders/account", deadline()));
                    assertEquals("ABORTED", groups.recovery.abort(id, deadline()).status());
                } else {
                    assertEquals("COMMITTED", status);
                    var left = groups.clients.get(1).read("orders/account", deadline()); assertEquals("7-left", left.value());
                    assertEquals("7-right", groups.clients.get(2).read("orders/account", deadline()).value());
                    assertEquals("COMMITTED", groups.recovery.abort(id, deadline()).status()); assertEquals(left, groups.clients.get(1).read("orders/account", deadline()));
                }
                assertTrue(groups.clients.get(1).prepared(deadline()).isEmpty()); assertTrue(groups.clients.get(2).prepared(deadline()).isEmpty());
            } finally { directory = original; }
        }
    }
    @Test void actualH2CommitBoundaryFrontendKillLeavesRollbackOrCommittedDataAccordingToRealDecision() throws Exception {
        Path classes = GeneratedTransactionService.compile(directory.resolve("generated"), true);
        var server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + directory.resolve("h2-process").toAbsolutePath();
        try {
            try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) { statement.executeUpdate("CREATE TABLE business_value(id VARCHAR(128) PRIMARY KEY,amount INTEGER)"); }
            for (String boundary : List.of("BEFORE_COMMIT", "AFTER_COMMIT")) {
                try (var frontend = new Child("jdbc-" + boundary, TransactionFrontendProcess.class.getName(), "jdbc", classes.toString(), boundary, url)) {
                    frontend.send("CALL " + boundary + " 7"); assertEquals(boundary + " jdbc", frontend.read()); frontend.kill();
                }
                try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.prepareStatement("SELECT amount FROM business_value WHERE id=?")) {
                    statement.setString(1, boundary);
                    try (var rows = statement.executeQuery()) { assertEquals(boundary.equals("AFTER_COMMIT"), rows.next()); if (boundary.equals("AFTER_COMMIT")) assertEquals(7, rows.getInt(1)); }
                }
            }
            try (var frontend = new Child("jdbc-restarted", TransactionFrontendProcess.class.getName(), "jdbc", classes.toString(), "NONE", url)) { assertEquals("RESULT result-9", frontend.command("CALL fresh 9")); }
        } finally { server.stop(); }
    }
}
