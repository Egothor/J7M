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
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.transport.api.*;

/**
 * Separate generated queue application JVM, with a test-only gate before native ACK submission.
 */
public final class QueueFrontendProcess {
    private static PartitionClient endpoint(String ports) {
        return new PartitionClient(Arrays.stream(ports.split(",")).map(port -> new InetSocketAddress("127.0.0.1", Integer.parseInt(port))).toList(), "app", new byte[32]);
    }
    /**
     * Hosts actual private activation and owned callback dispatch.
     * @param args generated classes, queue ports, execution ports, effect port, gate ACK boolean
     * @throws Exception on startup or control failure
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("queue.effect.port", args[3]);
        var broker = endpoint(args[1]); var execution = endpoint(args[2]);
        var release = new CountDownLatch(1); boolean gated = Boolean.parseBoolean(args[4]);
        PartitionEndpoint source = new PartitionEndpoint() {
            @Override public PartitionReply execute(long epoch, String id, byte[] command, byte[] query, Instant end) throws IOException {
                if (gated && ResourceCommand.decode(command).operation().equals("Q_ACK")) {
                    System.out.println("BEFORE_ACK"); System.out.flush();
                    try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException(stopped); }
                }
                return broker.execute(epoch, id, command, query, end);
            }
            @Override public PartitionReply query(long epoch, byte[] query, Instant end) throws IOException { return broker.query(epoch, query, end); }
            @Override public PartitionStateMachine.Status status(Instant end) throws IOException { return broker.status(end); }
            @Override public QueueSession subscribeQueue(long epoch, String queue, String group, String member, long visibility, boolean observational, Instant end) throws IOException {
                return broker.subscribeQueue(epoch, queue, group, member, visibility, observational, end);
            }
        };
        var queue = new ReplicatedQueue("queue-partition", "tenant", (p,d) -> new ReplicatedQueue.Route(source, 1));
        var client = new ExecutionClient("execution", "tenant", (p,d) -> new ReplicatedQueue.Route(execution, 1));
        var schemas = new SchemaRegistry(List.of());
        var profile = new QueueConsumerProfile(queue, new DeduplicatedRpc(client), new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas),
                "tenant", "orders", "workers", "member", Set.of("orders"), Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ofSeconds(10), Duration.ofHours(1), QueueConsumerProfile.Strategy.EXTERNAL_GUARD);
        var owner = new AtomicReference<Object>(); var helper = new ManagedQueueGeneratedIT();
        try (var loader = new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()}, QueueFrontendProcess.class.getClassLoader());
                var actor = Executors.newFixedThreadPool(2); var factory = new ManagedQueueConsumerFactory(Map.of("default", profile), 1);
                var input = new BufferedReader(new InputStreamReader(System.in))) {
            InvocationCompletionFactory completion = boundary -> {
                try {
                    var scope = (ManagedContainer.CallScope) ManagedQueueGeneratedIT.call(owner.get(), "openInvocationCall", new Class<?>[]{});
                    var context = new InvocationContext(new InvocationSecurityContext(boundary.security().principal(), Set.of(), boundary.security().trustDomain()), null, boundary.options().invocationId());
                    var lifetime = new ManagedInvocationLifetime(scope, context, boundary.security(), boundary.options().deadline(), actor, 16, 16, () -> {});
                    return (InvocationCompletionScope) ManagedQueueGeneratedIT.call(owner.get(), "ownedScope", new Class<?>[]{InvocationCompletionScope.class}, lifetime);
                } catch (Exception failure) { throw new IllegalStateException("Fixture ownership", failure); }
            };
            Object host = helper.activate(loader, ManagedQueueGeneratedIT.options(factory, factory), completion); owner.set(host);
            try {
                for (String action : List.of("start", "commitHostedAdmission", "openHostedAdmission")) ManagedQueueGeneratedIT.call(host, action, new Class<?>[]{});
                System.out.println("READY"); System.out.flush(); String command;
                while ((command = input.readLine()) != null) {
                    if (command.equals("RELEASE")) { release.countDown(); System.out.println("RELEASED"); }
                    else if (command.equals("STATUS")) { var status = factory.status("orders", "orders-handler"); System.out.println("STATUS " + status.phase() + " " + status.reason() + " " + status.acknowledged()); }
                    else throw new IllegalArgumentException("Unknown fixture control");
                    System.out.flush();
                }
            } finally {
                release.countDown(); ManagedQueueGeneratedIT.call(host, "stopHostedAdmission", new Class<?>[]{});
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (!(boolean) ManagedQueueGeneratedIT.call(host, "drainHosted", new Class<?>[]{})) {
                    if (System.nanoTime() >= end) throw new IllegalStateException("Actual application owner did not drain");
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                }
                ManagedQueueGeneratedIT.call(host, "close", new Class<?>[]{});
            }
        }
    }
}
