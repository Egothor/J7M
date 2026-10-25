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

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.application.components.ManagedInvocationPolicy;
import org.j7m.middleware.distributed.*;

/**
 * Separate frontend JVM for generated transaction dispatch and controlled actual durable boundaries.
 */
public final class TransactionFrontendProcess {
    private static String phase;
    private static void boundary(String operation, String id) throws InterruptedException {
        if (operation.equals(phase)) { System.out.println(operation + " " + id); System.out.flush(); new CountDownLatch(1).await(); }
    }
    private static RemoteResourceClient client(String port) {
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 1);
        return new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", Integer.parseInt(port))), "coordinator", key, null);
    }
    private static ResourceEndpoint instrument(ResourceEndpoint delegate, String operation) {
        return new ResourceEndpoint() {
            @Override public ReplicatedResources.Result execute(String command, String request, List<String> arguments, Instant deadline) throws IOException {
                var result = delegate.execute(command, request, arguments, deadline);
                if (command.equals(operation)) {
                    try { boundary(command, arguments.getFirst()); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("Fixture interrupted", stopped); }
                }
                return result;
            }
            @Override public ReplicatedResources.Value read(String key, Instant deadline) throws IOException { return delegate.read(key, deadline); }
            @Override public ReplicatedResources.Result transaction(String id, Instant deadline) throws IOException { return delegate.transaction(id, deadline); }
            @Override public List<String> prepared(Instant deadline) throws IOException { return delegate.prepared(deadline); }
            @Override public List<String> participants(String id, Instant deadline) throws IOException { return delegate.participants(id, deadline); }
        };
    }
    private static Connection connection(String url) throws SQLException {
        var physical = DriverManager.getConnection(url, "sa", "");
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (self, method, args) -> {
            if (method.getName().equals("commit")) boundary("BEFORE_COMMIT", "jdbc");
            try {
                Object result = method.invoke(physical, args);
                if (method.getName().equals("commit")) boundary("AFTER_COMMIT", "jdbc");
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    /**
     * Runs only a test control channel; all backend effects use actual authenticated D or H2 TCP.
     * @param args mode, generated class directory, fault phase, then H2 URL or three resource ports
     * @throws Exception for startup or protocol failure
     */
    public static void main(String[] args) throws Exception {
        phase = args[2]; boolean jdbc = args[0].equals("jdbc");
        ManagedInvocationPolicy policy = jdbc
                ? new ManagedJdbcTransactionPolicy(Map.of("default", new ManagedJdbcTransactionPolicy.Registration(() -> connection(args[3]), Duration.ofSeconds(2))))
                : new ManagedResourceTransactionPolicy(Map.of("default", new ManagedResourceTransactionPolicy.Registration(instrument(client(args[3]), "DECIDE"),
                        Map.of("left", instrument(client(args[4]), "PREPARE"), "right", client(args[5])), Duration.ofSeconds(2))));
        var factory = new ResourceBindingFactory(jdbc ? Map.of("jdbc", ResourceBindingFactory.jdbcConnection())
                : Map.of("transaction", ResourceBindingFactory.resourceTransactionContext()), Set.of("default"));
        try (var generated = new GeneratedTransactionService(Path.of(args[1])); var input = new BufferedReader(new InputStreamReader(System.in))) {
            generated.activate(policy, factory);
            try (var binding = policy.bind(generated.contract, generated.service, generated.business)) {
                System.out.println("READY"); System.out.flush(); String line;
                while ((line = input.readLine()) != null) {
                    String[] command = line.split(" ", 3);
                    try { System.out.println("RESULT " + generated.invoke(binding, command[1], command[2])); }
                    catch (ExecutionException unknown) { System.out.println("UNKNOWN"); }
                    System.out.flush();
                }
            }
        }
    }
}
