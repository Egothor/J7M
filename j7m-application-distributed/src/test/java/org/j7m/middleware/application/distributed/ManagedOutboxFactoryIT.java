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
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.tools.Server;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.JdbcDelivery;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual H2 TCP rollback/relay and OutboxDefinition boundary; native queue state is real behind its protocol edge.
 */
class ManagedOutboxFactoryIT {
    public interface Producer { @OutboxPublisher("sales") long enqueue(String id, String aggregate, String value); }
    public interface Second { @OutboxPublisher("sales") long enqueue(String id, String aggregate, String value); }
    public interface Business { @JdbcTransaction String apply(String input); }
    public static class Service implements Business { public String apply(String input) { return input; } }
    public static class FirstOwner { }
    public static class SecondOwner { }
    static final class Endpoint implements PartitionEndpoint {
        final QueueStateMachine state = new QueueStateMachine(); long index; final AtomicInteger sends = new AtomicInteger();
        final AtomicBoolean loseReply = new AtomicBoolean(); volatile CountDownLatch entered, release;
        @Override public PartitionReply execute(long epoch, String request, byte[] bytes, byte[] query, Instant deadline) throws java.io.IOException {
            byte[] response; synchronized (this) { state.validate(bytes, "tenant", false); state.apply(++index, bytes); response = state.query(query); }
            if (ResourceCommand.decode(bytes).operation().equals("Q_SEND")) {
                sends.incrementAndGet();
                if (entered != null) { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new java.io.IOException(stopped); } }
                if (loseReply.compareAndSet(true, false)) throw new java.io.IOException("Lost native send response after real commit");
            }
            return new PartitionReply("OK", epoch, response);
        }
        @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) { return new PartitionReply("OK", epoch, state.query(query)); }
        @Override public PartitionStateMachine.Status status(Instant deadline) { throw new UnsupportedOperationException(); }
    }
    @TempDir Path directory;
    Server server; String url; final AtomicInteger opens = new AtomicInteger();
    ManagedJdbcTransactionPolicy.Registration jdbc;
    ManagedJdbcTransactionPolicy transactions;
    Endpoint endpoint; ReplicatedQueue queue; OutboxRelayProfile relay;
    ManagedOutboxFactory factory;
    @BeforeEach void database() throws Exception {
        server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + directory.resolve("outbox").toAbsolutePath();
        try (var c = raw(); var s = c.createStatement()) { JdbcDelivery.initialize(c, JdbcDelivery.Dialect.H2); s.executeUpdate("CREATE TABLE effect(value_count INT)"); s.executeUpdate("INSERT INTO effect VALUES(0)"); }
        jdbc = new ManagedJdbcTransactionPolicy.Registration(() -> { opens.incrementAndGet(); return raw(); }, Duration.ofSeconds(2));
        transactions = new ManagedJdbcTransactionPolicy(Map.of("default", jdbc)); endpoint = new Endpoint();
        queue = new ReplicatedQueue("queue", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
        var execution = new ExecutionTransactionFixture(); var schemas = new SchemaRegistry(List.of());
        var publication = new OutboxProfile("sales", "tenant", "orders", 1, "default", jdbc, new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas),
                new DeduplicatedRpc(execution.client), Duration.ofHours(1), Duration.ofDays(1), null);
        relay = new OutboxRelayProfile(publication, queue, "orders", 16, Duration.ofMillis(10), Duration.ofSeconds(1));
        factory = new ManagedOutboxFactory(Map.of("first", new ManagedOutboxFactory.Registration(Producer.class.getName(), Map.of("default", relay)),
                "second", new ManagedOutboxFactory.Registration(Second.class, Map.of("default", relay))), 1);
    }
    @AfterEach void close() { if (server != null) server.stop(); }
    Connection raw() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    int count(String sql) throws SQLException {
        if (sql.contains("FROM j7m_outbox")) sql += (sql.contains("WHERE") ? " AND" : " WHERE") + " scope='" + relay.publication().scope() + "'";
        try (var c = raw(); var s = c.createStatement(); var r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getInt(1); } }
    static ManagedDependency edge(String name, Class<?> type) { return new ManagedDependency(type, name, List.of(), true, Map.of("resource.name", name, "binding.refresh", "NEVER", "binding.policy", "default")); }
    static ManagedComponentDefinition<?> owner(Class<?> type, ManagedDependency dependency) {
        return new ManagedComponentDefinition<Object>() {
            @SuppressWarnings("unchecked") public Class<Object> type() { return (Class<Object>) type; }
            public String name() { return ""; }
            public List<ManagedQualifier> qualifiers() { return List.of(); }
            public Scope.Kind scope() { return Scope.Kind.CALL; }
            public List<ManagedDependency> dependencies() { return List.of(dependency); }
            public Object create(ManagedResolver resolver) { throw new AssertionError("Methodless relay must not construct its owner"); }
        };
    }
    ManagedHostedResources.Binding lease(String resource, Class<?> type, Class<?> producer, AtomicBoolean admission) throws Exception {
        var definition = owner(type, edge(resource, producer));
        var feature = new ManagedHostedFeature("outbox-relay", type.getName() + '#' + resource + "#sales#1", 1,
                Map.of("resource", resource, "outbox", "sales", "policy", "default", "version", "1"), List.of());
        var invoker = new ManagedHostedResources.Invoker() {
            @Override public boolean admissionOpen() { return admission.get(); }
            @Override public CompletionStage<Object> invokeDecoded(ManagedMethod method, Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions options) { throw new AssertionError("Relay has no application callback"); }
        };
        var binding = factory.bind(feature, definition, null, new ManagedHostedResources.Bundle("a".repeat(64), Map.of()), invoker);
        binding.prepare(); binding.start(); return binding;
    }
    Object transaction(TransactionInvocation.Work work) throws Exception {
        try (var binding = transactions.bind(TransactionInvocation.contract(Business.class), Service.class, Business.class)) { return TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, work); }
    }
    static void effect() throws SQLException {
        try (var s = ManagedInvocationLifetime.currentContext(ManagedJdbcTransactionContext.class).connection().createStatement()) { assertEquals(1, s.executeUpdate("UPDATE effect SET value_count=value_count+1")); }
    }
    private static void eventually(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call()) { if (System.nanoTime() >= end) fail("Actual bounded relay completion"); java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); }
    }
    @Test void producerStagesOnlyOnSameConnectionRollbackLeavesNoOutboxAndCaughtFailureIsSticky() throws Exception {
        try (var bound = factory.bind(edge("first", Producer.class))) {
            Producer producer = (Producer) bound.get();
            assertThrows(IllegalStateException.class, () -> producer.enqueue("outside", "a", "v"));
            assertThrows(ExecutionException.class, () -> transaction(frame -> { effect(); producer.enqueue("rolled-back", "a", "v"); throw new SQLException("handler failed"); }));
            assertEquals(0, count("SELECT value_count FROM effect")); assertEquals(0, count("SELECT COUNT(*) FROM j7m_outbox"));
            int before = opens.get();
            assertEquals("done", transaction(frame -> { effect(); assertEquals(1, producer.enqueue("rolled-back", "a", "v")); assertEquals(1, producer.enqueue("rolled-back", "a", "v")); return "done"; }));
            assertEquals(before + 1, opens.get(), "Producer must not open another DB connection");
            assertEquals(1, count("SELECT value_count FROM effect")); assertEquals(1, count("SELECT COUNT(*) FROM j7m_outbox")); assertEquals(0, endpoint.sends.get());
            assertThrows(ExecutionException.class, () -> transaction(frame -> {
                effect(); assertThrows(RuntimeException.class, () -> producer.enqueue("oversized", "a", "x".repeat(13000))); return "caught";
            }));
            assertEquals(1, count("SELECT value_count FROM effect")); assertEquals(1, count("SELECT COUNT(*) FROM j7m_outbox"));
            bound.close(); assertThrows(IllegalStateException.class, bound::get);
        } finally { factory.close(); }
    }
    @Test void twoProducerOwnersShareOneRelayAndFirstDepartureCannotCloseTheSecondOrReleaseLastPhysicalWork() throws Exception {
        var firstAdmission = new AtomicBoolean(true); var secondAdmission = new AtomicBoolean(true);
        var first = lease("first", FirstOwner.class, Producer.class, firstAdmission);
        var second = lease("second", SecondOwner.class, Second.class, secondAdmission);
        try (var a = factory.bind(edge("first", Producer.class)); var b = factory.bind(edge("second", Second.class))) {
            transaction(frame -> { ((Producer) a.get()).enqueue("same", "aggregate", "one"); ((Second) b.get()).enqueue("same", "aggregate", "two"); return "done"; });
            assertEquals(2, count("SELECT COUNT(*) FROM j7m_outbox"));
            endpoint.entered = new CountDownLatch(1); endpoint.release = new CountDownLatch(1);
            first.commitAdmission(); second.commitAdmission(); assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
            firstAdmission.set(false); first.stopAdmission(); assertTrue(first.drain(Duration.ZERO)); first.close();
            assertEquals(1, factory.status("second", "default").bindings()); assertTrue(factory.status("second", "default").working());
            second.stopAdmission(); assertFalse(second.drain(Duration.ZERO)); assertThrows(IllegalStateException.class, second::close);
            endpoint.release.countDown(); assertTrue(second.drain(Duration.ofSeconds(5))); second.close();
            var third = lease("second", SecondOwner.class, Second.class, new AtomicBoolean(true));
            try { third.commitAdmission(); eventually(() -> count("SELECT COUNT(*) FROM j7m_outbox WHERE sent=1") == 2); }
            finally { assertTrue(third.drain(Duration.ofSeconds(5))); third.close(); }
            assertEquals(2, endpoint.sends.get());
        } finally {
            endpoint.release.countDown(); if (factory.status("first", "default").bindings() > 0) { first.drain(Duration.ofSeconds(5)); first.close(); second.drain(Duration.ofSeconds(5)); second.close(); }
            factory.close();
        }
    }
    @Test void lostQueueReplyRetainsPendingRowAndRelayRetriesExactPublicationWithoutAnotherMessage() throws Exception {
        try (var producer = factory.bind(edge("first", Producer.class))) { transaction(frame -> { ((Producer) producer.get()).enqueue("stable", "aggregate", "payload"); return "done"; }); }
        endpoint.loseReply.set(true); var owner = lease("first", FirstOwner.class, Producer.class, new AtomicBoolean(true));
        try {
            owner.commitAdmission(); eventually(() -> count("SELECT COUNT(*) FROM j7m_outbox WHERE sent=1") == 1);
            assertEquals(2, endpoint.sends.get()); assertTrue(factory.status("first", "default").confirmed() >= 1);
            var end = Instant.now().plusSeconds(5); queue.join("join", "g", "m", 10000, end);
            String[] grant = queue.claim("claim", "orders", "g", "m", 1, 1000, end).split("\t"); assertEquals("1", grant[3]);
            queue.ack("ack", "orders", "g", "m", 1, grant[1], Long.parseLong(grant[4]), end);
            assertEquals("EMPTY", queue.claim("empty", "orders", "g", "m", 1, 1000, end));
        } finally { assertTrue(owner.drain(Duration.ofSeconds(5))); owner.close(); factory.close(); }
    }
    @Test void reopeningFactoryCannotReroutePersistedPendingPublicationToAnotherDestination() throws Exception {
        try (var bound = factory.bind(edge("first", Producer.class))) {
            transaction(frame -> { ((Producer) bound.get()).enqueue("pending", "aggregate", "original"); return "done"; });
        }
        factory.close();
        var changed = new OutboxRelayProfile(relay.publication(), queue, "another-queue", relay.maximumBatch(), relay.interval(), relay.operationBudget());
        try (var replacement = new ManagedOutboxFactory(Map.of("first", new ManagedOutboxFactory.Registration(Producer.class, Map.of("default", changed))), 1)) {
            var dependency = edge("first", Producer.class);
            var definition = owner(FirstOwner.class, dependency);
            var feature = new ManagedHostedFeature("outbox-relay", FirstOwner.class.getName() + "#first#sales#1", 1,
                    Map.of("resource", "first", "outbox", "sales", "policy", "default", "version", "1"), List.of());
            var binding = replacement.bind(feature, definition, null, new ManagedHostedResources.Bundle("b".repeat(64), Map.of()),
                    (method, decoder, options) -> { throw new AssertionError("Backend relay has no user callback"); });
            try { assertThrows(SQLException.class, binding::prepare); }
            finally { assertTrue(binding.drain(Duration.ZERO)); binding.close(); }
        }
        assertEquals(1, count("SELECT COUNT(*) FROM j7m_outbox WHERE sent=0"));
        assertEquals(0, endpoint.sends.get());
    }
}
