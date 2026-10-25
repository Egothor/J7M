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

package org.j7m.middleware.transport.grpc;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.*;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.transport.api.*;
import org.junit.jupiter.api.Test;

class GrpcLogicalAsyncIT {
    interface Service { CompletionStage<String> call(); }
    private static final String SERVICE = "org.j7m.middleware.transport.grpc.RemoteObjectService";
    private static final io.grpc.MethodDescriptor.Marshaller<byte[]> BYTES = new io.grpc.MethodDescriptor.Marshaller<>() {
        public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
        public byte[] parse(InputStream input) { try { return input.readAllBytes(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }
    };

    @Test void missingV2NeverFallsBackToLegacyBusinessMethod() throws Exception {
        var legacyEffects = new AtomicInteger();
        var legacy = io.grpc.MethodDescriptor.<byte[], byte[]>newBuilder().setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(SERVICE + "/Invoke").setRequestMarshaller(BYTES).setResponseMarshaller(BYTES).build();
        var server = io.grpc.Grpc.newServerBuilderForPort(0, io.grpc.InsecureServerCredentials.create())
                .addService(io.grpc.ServerServiceDefinition.builder(SERVICE).addMethod(legacy,
                        io.grpc.stub.ServerCalls.asyncUnaryCall((request, observer) -> {
                            legacyEffects.incrementAndGet(); observer.onNext(new byte[0]); observer.onCompleted();
                        })).build()).build().start();
        try (var client = new GrpcTransportProvider()) {
            client.start(context(null, null));
            var failure = assertThrows(ExecutionException.class, () -> invoke(client, target("grpc://127.0.0.1:" + server.getPort())).get(3, TimeUnit.SECONDS));
            assertEquals("UNIMPLEMENTED", ((MiddlewareException) failure.getCause()).remoteError().details().get("status"));
            assertEquals(0, legacyEffects.get());
        } finally { server.shutdownNow(); assertTrue(server.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void physicalWorkersAndQueueAreBoundedAndServerObservedCancellationPreventsEntry() throws Exception {
        var entered = new CountDownLatch(16); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var server = new GrpcTransportProvider(); var client = new GrpcTransportProvider()) {
            var endpoint = server.start(context(entered, new Object[] {release, calls}));
            client.start(context(null, null));
            var target = target(endpoint.endpoint());
            List<CompletableFuture<Object>> replies = new ArrayList<>();
            try {
                for (int i = 0; i < 16; i++) replies.add(invoke(client, target));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 64; i++) replies.add(invoke(client, target));
                var field = GrpcTransportProvider.class.getDeclaredField("asyncWorkers"); field.setAccessible(true);
                var workers = (ThreadPoolExecutor) field.get(server);
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (workers.getQueue().size() != 64 && System.nanoTime() < limit) java.util.concurrent.locks.LockSupport.parkNanos(1000000);
                assertEquals(64, workers.getQueue().size()); assertEquals(16, calls.get());
                var overloaded = assertThrows(ExecutionException.class, () -> invoke(client, target).get(3, TimeUnit.SECONDS));
                assertEquals(MiddlewareErrorCode.OVERLOADED, ((MiddlewareException) overloaded.getCause()).code());
                List<io.grpc.Context> queued = queuedContexts(workers);
                assertEquals(64, queued.size());
                assertTrue(replies.get(16).cancel(false));
                // Client cancellation is asynchronous: only server observation can prevent entry.
                // Network arrival order need not match the client submission index.
                long cancelLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (queued.stream().noneMatch(io.grpc.Context::isCancelled) && System.nanoTime() < cancelLimit) {
                    java.util.concurrent.locks.LockSupport.parkNanos(1000000);
                }
                assertTrue(queued.stream().anyMatch(io.grpc.Context::isCancelled), "Server did not observe queued cancellation");
                release.countDown();
                for (int i = 0; i < replies.size(); i++) if (i != 16) assertEquals("value", replies.get(i).get(3, TimeUnit.SECONDS));
                assertEquals(79, calls.get());
            } finally { release.countDown(); }
        }
    }

    private static List<io.grpc.Context> queuedContexts(ThreadPoolExecutor workers) throws IllegalAccessException {
        List<io.grpc.Context> contexts = new ArrayList<>();
        for (Runnable task : workers.getQueue()) {
            boolean found = false;
            for (var field : task.getClass().getDeclaredFields()) {
                if (io.grpc.Context.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true); contexts.add((io.grpc.Context) field.get(task)); found = true; break;
                }
            }
            assertTrue(found, "Expected actual Context.wrap task while physical workers are held");
        }
        return contexts;
    }
    private static CompletableFuture<Object> invoke(GrpcTransportProvider client, ObjectRef target) throws Exception {
        return client.invokeAsync(target, MethodDescriptor.fromMethod(Service.class.getMethod("call")), List.of(),
                new InvocationOptions(null, Duration.ofSeconds(10), Map.of()), new AsyncMethodDescriptor(String.class));
    }
    private static ObjectRef target(String endpoint) {
        return new ObjectRef("object", "logical", "service", "remote-runtime", "default", List.of(Service.class.getName()),
                "grpc", endpoint, Long.MAX_VALUE, 1, Map.of());
    }
    private static RuntimeTransportContext context(CountDownLatch entered, Object[] state) {
        return (RuntimeTransportContext) Proxy.newProxyInstance(RuntimeTransportContext.class.getClassLoader(),
                new Class<?>[] {RuntimeTransportContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("identity")) return new RuntimeIdentity("local-runtime", "default", Map.of());
                    if (method.getName().equals("decodeValue")) return "value";
                    if (method.getName().equals("dispatchLogicalAsyncTrusted")) {
                        ((AtomicInteger) state[1]).incrementAndGet(); entered.countDown();
                        assertTrue(((CountDownLatch) state[0]).await(5, TimeUnit.SECONDS));
                        return new EncodedValue("scalar", "java.lang.String", "value", Map.of());
                    }
                    throw new AssertionError("Unexpected runtime edge " + method.getName());
                });
    }
}
