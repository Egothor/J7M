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
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.j7m.middleware.distributed.*;

/**
 * Independent application JVM retaining actual owned USER work during a renewal network failure.
 */
public final class OwnershipWorkerProcess {
    private OwnershipWorkerProcess() { }
    private static synchronized void line(String text) { System.out.println(text); System.out.flush(); }
    /**
     * Runs a bounded stdin control protocol; all protected effects execute in the real USER scope.
     * @param args native voter endpoint ports and a TCP blackhole port for the renewal fault
     * @throws Exception on invalid fixture configuration
     */
    public static void main(String[] args) throws Exception {
        var addresses = Arrays.stream(args[0].split(",")).map(port -> new InetSocketAddress("127.0.0.1", Integer.parseInt(port))).toList();
        var actual = new RemoteResourceClient(addresses, "app", new byte[32], null);
        var blackhole = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1]))), "app", new byte[32], null);
        var blocked = new AtomicBoolean(); var current = new AtomicReference<ManagedOwnershipContext>();
        ResourceEndpoint fault = new ResourceEndpoint() {
            public ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
                return (blocked.get() && operation.equals("RENEW") ? blackhole : actual).execute(operation, request, arguments, deadline);
            }
            public ReplicatedResources.Value read(String key, Instant deadline) throws IOException { return actual.read(key, deadline); }
            public ReplicatedResources.Lease lease(String key, Instant deadline) throws IOException { return actual.lease(key, deadline); }
            public ReplicatedResources.Result transaction(String tx, Instant deadline) throws IOException { return actual.transaction(tx, deadline); }
            public List<String> prepared(Instant deadline) throws IOException { return actual.prepared(deadline); }
            public List<String> participants(String tx, Instant deadline) throws IOException { return actual.participants(tx, deadline); }
        };
        var profile = new ManagedOwnershipProfile(ManagedOwnershipBackend.group(fault), "inventory", Duration.ofSeconds(4),
                Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofMillis(500));
        var commands = new ArrayBlockingQueue<String>(16);
        try (var policy = new ManagedOwnershipPolicy(Map.of("default", profile), Set.of("alice"), context -> "alice");
                var binding = policy.bind(TransactionInvocation.contract(ManagedOwnershipContextTest.Service.class), ManagedOwnershipContextTest.Worker.class, ManagedOwnershipContextTest.Service.class);
                var worker = Executors.newSingleThreadExecutor(); var input = new BufferedReader(new InputStreamReader(System.in))) {
            line("READY"); String command; boolean started = false;
            while ((command = input.readLine()) != null) {
                if (command.equals("START")) {
                    if (started) throw new IllegalStateException("Only one owned call per fixture"); started = true; line("STARTED");
                    worker.submit(() -> {
                        try {
                            TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> {
                                var context = ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class); current.set(context);
                                line("ENTERED " + context.fence() + " " + context.leadershipEpoch());
                                while (true) {
                                    String operation = commands.poll(9, TimeUnit.SECONDS);
                                    if (operation == null) throw new IOException("Fixture work deadline");
                                    if (operation.equals("FINISH")) return "done";
                                    String[] fields = operation.split(" ", 3);
                                    try { line("WROTE " + context.write(fields[1], "key", fields[2])); }
                                    catch (RuntimeException denied) { line("DENIED " + denied.getClass().getSimpleName()); }
                                }
                            });
                            line("FINISHED " + policy.status().activeClaims());
                        } catch (Exception failure) { line("FAILED " + failure.getClass().getSimpleName()); }
                    });
                } else if (command.equals("BLOCK_RENEW")) { blocked.set(true); line("BLOCKED"); }
                else if (command.equals("STATUS")) { var context = current.get(); line("STATUS " + (context != null && context.revoked()) + " " + policy.status().activeClaims()); }
                else if (command.startsWith("WRITE ") || command.equals("FINISH")) {
                    if (!commands.offer(command)) throw new IllegalStateException("Fixture command capacity");
                } else throw new IllegalArgumentException("Unknown fixture command");
            }
        }
    }
}
