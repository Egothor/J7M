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
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.api.*;

/**
 * Separate test JVM hosting actual generated application dispatch against a real quorum backend.
 */
public final class DeduplicationFrontendProcess {
    /**
     * Runs the test control channel; application effects use a separate real TCP service.
     * @param args generated class directory, comma-separated backend ports and effect service port
     * @throws Exception on fixture startup or command failure
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("effect.port", args[2]);
        var endpoint = new PartitionClient(Arrays.stream(args[1].split(",")).map(port -> new InetSocketAddress("127.0.0.1", Integer.parseInt(port))).toList(), "app", new byte[32]);
        var execution = new ExecutionClient("execution", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(endpoint, 1));
        var schemas = new SchemaRegistry(List.of());
        var codec = new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas);
        var policy = new ManagedDeduplicationPolicy(Map.of("default", new ManagedDeduplicationPolicy.Registration(new DeduplicatedRpc(execution), codec, Duration.ofSeconds(30))));
        var resources = new ResourceBindingFactory(Map.of("deduplication", ResourceBindingFactory.deduplicationContext()), Set.of("default"));
        try (var generated = new GeneratedDeduplicationService(Path.of(args[0]), resources);
                var binding = policy.bind(generated.contract, generated.service, generated.business);
                var executor = Executors.newSingleThreadExecutor(); var input = new BufferedReader(new InputStreamReader(System.in))) {
            policy.validate(generated.contract, generated.service, generated.business);
            System.out.println("READY"); System.out.flush(); String line;
            while ((line = input.readLine()) != null) {
                var parts = line.split(" ", 3);
                try { System.out.println("RESULT " + invoke(generated, binding, executor, parts[1], parts[2])); }
                catch (ExecutionException expected) { System.out.println("UNKNOWN"); }
                System.out.flush();
            }
        }
    }
    static Object invoke(GeneratedDeduplicationService generated, ManagedInvocationPolicy.Binding binding,
            Executor executor, String key, String input) throws Exception {
        var context = new InvocationContext(new InvocationSecurityContext(new Principal("alice", "alice", Set.of(), Set.of()), Set.of(), new TrustDomain("qualified")), null, "frontend");
        var lifetime = new ManagedInvocationLifetime(generated.container.openCall(), context, null, Duration.ofSeconds(10), executor, 8, 8, () -> { });
        try {
            try {
                lifetime.call(() -> binding.invoke(J7mContext.currentInvocation(), new Object[]{key, input}, new ManagedInvocationPolicy.Proceed() {
                    @Override public boolean supportsResultFinalizers() { return true; }
                    @Override public void addResultFinalizer(InvocationResultFinalizer callback) { lifetime.addResultFinalizer(callback); }
                    @Override public <T> void putContext(Class<T> type, T value) { lifetime.putContext(type, value); }
                    @Override public Object invoke() throws Exception {
                        lifetime.markApplicationEntry(); return generated.adapter.invoke(generated.instance, generated.contract.method(), new Object[]{key, input});
                    }
                }));
            } catch (Exception failure) { /* The normalized logical result is authoritative, including failed finalization. */ }
            return lifetime.logicalResult().toCompletableFuture().get(12, TimeUnit.SECONDS);
        } finally { lifetime.close(); lifetime.physicalCompletion().toCompletableFuture().get(12, TimeUnit.SECONDS); }
    }

}
