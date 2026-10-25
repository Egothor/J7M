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

package org.j7m.middleware.integrations;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.j7m.middleware.distributed.*;

final class IntegrationCluster implements AutoCloseable {
    private static final Map<Process, Path> diagnostics = Collections.synchronizedMap(new WeakHashMap<>());
    @FunctionalInterface interface BrokerLauncher { Process start(Path directory, String... arguments) throws IOException; }
    final List<Process> brokers = new ArrayList<>();
    final List<Integer> metrics = new ArrayList<>();
    final String ports;
    final ReplicatedQueue queue;
    final PartitionEndpoint endpoint;
    static int port() throws IOException { try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    static String classpath() { return System.getProperty("j7m.integrations.test.classpath", System.getProperty("java.class.path")); }
    static Process child(Path directory, String... arguments) throws IOException {
        Files.createDirectories(directory); List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx128m", "-cp", classpath(), IntegrationProcess.class.getName()));
        command.addAll(Arrays.asList(arguments));
        Path stderr = directory.resolve("stderr-" + UUID.randomUUID());
        Process process = new ProcessBuilder(command).redirectError(stderr.toFile()).start();
        diagnostics.put(process, stderr); return process;
    }
    IntegrationCluster(Path directory) throws Exception { this(directory, false); }
    IntegrationCluster(Path directory, boolean execution) throws Exception { this(directory, execution, IntegrationCluster::child); }
    IntegrationCluster(Path directory, boolean execution, BrokerLauncher launcher) throws Exception {
        List<ServerSocket> reservations = new ArrayList<>();
        List<String> services = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) reservations.add(new ServerSocket(0, 0, InetAddress.getLoopbackAddress()));
            String peers = "a:" + reservations.get(0).getLocalPort() + ",b:" + reservations.get(1).getLocalPort() + ",c:" + reservations.get(2).getLocalPort();
            int index = 0;
            for (String id : List.of("a", "b", "c")) {
                // Keep future peers reserved; the child owns allocation of its service socket.
                reservations.get(index++).close();
                Process process = launcher.start(directory, "BROKER", id, directory.resolve(id).toString(), peers, "0", execution ? "execution" : "queue");
                brokers.add(process);
                String ready = read(process.inputReader());
                assertNotNull(ready, () -> startupDiagnostic(id, process));
                String[] fields = ready.split(" ");
                assertEquals(3, fields.length, ready); assertEquals("READY", fields[0], ready);
                metrics.add(Integer.parseInt(fields[1])); services.add(fields[2]);
            }
            ports = String.join(",", services); queue = IntegrationProcess.queue(ports);
            endpoint = new PartitionClient(services.stream().map(p -> new InetSocketAddress("127.0.0.1", Integer.parseInt(p))).toList(), "app", new byte[32]);
            if (execution) await(() -> endpoint.status(Instant.now().plusSeconds(1)));
            else await(() -> queue.join("fixture-join", "fixture", "member", 100000, Instant.now().plusSeconds(1)));
        } catch (Exception | AssertionError failure) {
            close(); throw failure;
        } finally {
            for (ServerSocket reservation : reservations) try { reservation.close(); } catch (IOException ignored) { /* Preserve the original startup failure. */ }
        }
    }

    private static String startupDiagnostic(String id, Process process) {
        Path path = diagnostics.get(process);
        String detail = "stderr unavailable";
        if (path != null) try (var input = Files.newInputStream(path)) {
            detail = new String(input.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unavailable) { detail = "stderr could not be read"; }
        return "Broker " + id + " exited before READY; exit=" + (process.isAlive() ? "running" : process.exitValue()) + "; " + detail;
    }
    static String read(BufferedReader reader) throws Exception {
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) { return tasks.submit(reader::readLine).get(15, TimeUnit.SECONDS); }
    }
    static <T> T await(Callable<T> operation) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos(); IOException last = null;
        do { try { return operation.call(); } catch (IOException retry) { last = retry; LockSupport.parkNanos(5_000_000); } } while (System.nanoTime() < end);
        throw new AssertionError("Quorum did not recover", last);
    }
    static void kill(Process process) throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
    @Override public void close() { for (Process broker : brokers) { broker.destroyForcibly(); try { broker.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
}
