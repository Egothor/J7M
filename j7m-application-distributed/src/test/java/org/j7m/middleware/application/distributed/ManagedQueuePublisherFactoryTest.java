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
import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.TracedQueue;
import org.junit.jupiter.api.Test;

class ManagedQueuePublisherFactoryTest {
    public interface Producer { @QueuePublisher(queue="orders") long publish(String id, String value); }
    public interface Invalid { @QueuePublisher(queue="orders") String publish(String id, String value); }
    private static final ManagedInvocationPolicy.Binding DIRECT = new ManagedInvocationPolicy.Binding() {
        @Override public Object invoke(InvocationContext context, Object[] arguments, ManagedInvocationPolicy.Proceed next) throws Exception { return next.invoke(); }
        @Override public void close() { }
    };
    private static final class QueueEndpoint implements PartitionEndpoint {
        final QueueStateMachine machine = new QueueStateMachine(); long index;
        @Override public synchronized PartitionReply execute(long epoch, String id, byte[] command, byte[] query, Instant deadline) {
            machine.validate(command, "tenant", false); machine.apply(++index, command); return new PartitionReply("OK", epoch, machine.query(query));
        }
        @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) { return new PartitionReply("OK", epoch, machine.query(query)); }
        @Override public PartitionStateMachine.Status status(Instant deadline) { throw new UnsupportedOperationException(); }
    }
    private static ManagedDependency edge(Class<?> type) {
        return new ManagedDependency(type, "producer", List.of(), true, Map.of("resource.name", "producer", "binding.refresh", "NEVER", "binding.policy", "default"));
    }
    @Test void firstIntentPreservesPayloadAndTraceAcrossFreshPhysicalRetryAndRejectsConflict() throws Exception {
        var endpoint = new QueueEndpoint(); var queue = new ReplicatedQueue("queue", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
        var execution = new ExecutionTransactionFixture(); var trace = new AtomicReference<>(Map.of("traceparent", "00-11111111111111111111111111111111-1111111111111111-01"));
        var intents = new DeduplicatedRpc(new ExecutionClient("execution", "tenant", (p, d) -> new ReplicatedQueue.Route(execution, 1), trace::get));
        var schemas = new SchemaRegistry(List.of()); var codec = new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas);
        var factory = new ManagedQueuePublisherFactory(Map.of("producer", new ManagedQueuePublisherFactory.Registration(
                Producer.class.getName(), "sales", "tenant", queue, intents, codec, Duration.ofMinutes(1), 3, Set.of("orders"))));
        try (var binding = factory.bind(edge(Producer.class))) {
            Producer producer = (Producer) binding.get();
            assertEquals(1L, TransactionInvocation.invokeAs(TransactionInvocation.caller(), DIRECT, frame -> producer.publish("stable", "value")));
            trace.set(Map.of("traceparent", "00-22222222222222222222222222222222-2222222222222222-01"));
            assertEquals(1L, TransactionInvocation.invokeAs(TransactionInvocation.caller(), DIRECT, frame -> producer.publish("stable", "value")));
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), DIRECT, frame -> producer.publish("stable", "changed")));
            queue.join("join", "g", "m", 10000, Instant.now().plusSeconds(5));
            String[] delivered = queue.claim("claim", "orders", "g", "m", 1, 1000, Instant.now().plusSeconds(5)).split("\t");
            var message = TracedQueue.decode(Base64.getDecoder().decode(delivered[2]));
            assertEquals("value", codec.decode(message.payload(), String.class));
            assertEquals("00-11111111111111111111111111111111-1111111111111111-01", message.context().traceparent());
            assertThrows(IllegalStateException.class, () -> producer.publish("outside", "value"));
            assertSame(producer, binding.get()); assertEquals(producer, producer); assertNotEquals(producer, new Object());
            assertFalse(producer.toString().contains("tenant")); binding.close(); assertThrows(IllegalStateException.class, binding::get);
        }
    }
    @Test void producerValidationRejectsInvalidShapeRefreshDirectInjectionAndUnknownResource() {
        var execution = new ExecutionTransactionFixture(); var schemas = new SchemaRegistry(List.of());
        var queue = new ReplicatedQueue("queue", "tenant", (p, d) -> { throw new AssertionError("No setup network operation"); });
        var codec = new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas); var backend = new DeduplicatedRpc(execution.client);
        assertThrows(IllegalArgumentException.class, () -> new ManagedQueuePublisherFactory(Map.of("producer",
                new ManagedQueuePublisherFactory.Registration(Invalid.class, "sales", "tenant", queue, backend, codec, Duration.ofSeconds(1), 1, Set.of("orders")))));
        var registration = new ManagedQueuePublisherFactory.Registration(Producer.class, "sales", "tenant", queue, backend, codec, Duration.ofSeconds(1), 1, Set.of("orders"));
        var factory = new ManagedQueuePublisherFactory(Map.of("producer", registration));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(new ManagedDependency(Producer.class, "producer", List.of(), false, edge(Producer.class).attributes()), Scope.Kind.ACTIVATION));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(edge(String.class), Scope.Kind.ACTIVATION));
        var attrs = new HashMap<>(edge(Producer.class).attributes()); attrs.put("binding.refresh", "ON_CHANGE");
        assertThrows(IllegalArgumentException.class, () -> factory.validate(new ManagedDependency(Producer.class, "producer", List.of(), true, attrs), Scope.Kind.ACTIVATION));
        assertThrows(IllegalArgumentException.class, () -> new ManagedQueuePublisherFactory.Registration(Producer.class, "sales", "tenant", queue, backend, codec, Duration.ZERO, 1, Set.of("orders")));
        assertThrows(IllegalArgumentException.class, () -> new ManagedQueuePublisherFactory.Registration("bad/name", "sales", "tenant", queue, backend, codec, Duration.ofSeconds(1), 1, Set.of("orders")));
        var symbolicInvalid = new ManagedQueuePublisherFactory(Map.of("producer", new ManagedQueuePublisherFactory.Registration(Invalid.class.getName(), "sales", "tenant", queue, backend, codec, Duration.ofSeconds(1), 1, Set.of("orders"))));
        assertThrows(IllegalArgumentException.class, () -> symbolicInvalid.validate(edge(Invalid.class), Scope.Kind.ACTIVATION));
        assertThrows(IllegalArgumentException.class, () -> symbolicInvalid.validate(edge(Producer.class), Scope.Kind.ACTIVATION));
    }
}
