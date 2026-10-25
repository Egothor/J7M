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
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.tools.Server;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real H2 TCP marker/effect atomicity against the exact production A11 guarded connection.
 */
class ManagedInboxPolicyIT {
    public static class Handler {
        @DurableQueueConsumer(value="handler", queue="one") @JdbcTransaction @InboxHandler public void on(String value) { }
    }
    public static class AsyncHandler {
        @DurableQueueConsumer(value="handler", queue="one") @JdbcTransaction @InboxHandler @Async
        public CompletionStage<Void> on(String value) { return CompletableFuture.completedFuture(null); }
    }
    @TempDir Path directory;
    Server server; String url;
    final AtomicInteger opened = new AtomicInteger();
    ManagedJdbcTransactionPolicy.Registration registration;
    ManagedJdbcTransactionPolicy transactions;
    ManagedInboxPolicy inbox;
    QueueConsumerProfile source;
    @BeforeEach void openDatabase() throws Exception {
        server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + directory.resolve("inbox").toAbsolutePath();
        try (var c = raw(); var s = c.createStatement()) { JdbcDelivery.initialize(c, JdbcDelivery.Dialect.H2); s.executeUpdate("CREATE TABLE effect(value_count INT)"); s.executeUpdate("INSERT INTO effect VALUES(0)"); }
        registration = new ManagedJdbcTransactionPolicy.Registration(() -> { opened.incrementAndGet(); return raw(); }, Duration.ofSeconds(2));
        transactions = new ManagedJdbcTransactionPolicy(Map.of("default", registration));
        inbox = new ManagedInboxPolicy(Map.of("default", new ManagedInboxPolicy.Registration("inbox", "tenant", "default", registration, null)));
        var schemas = new SchemaRegistry(List.of());
        var queue = new ReplicatedQueue("queue", "tenant", (p, d) -> { throw new AssertionError("Inbox uses only actual owned Connection"); });
        source = new QueueConsumerProfile(queue, null, new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas), "tenant", "logical", "g", "m", Set.of("one", "two"),
                Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofHours(1), QueueConsumerProfile.Strategy.INBOX);
    }
    @AfterEach void closeDatabase() { if (server != null) server.stop(); }
    Connection raw() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    int count(String table) throws SQLException {
        try (var c = raw(); var s = c.createStatement(); var r = s.executeQuery("SELECT " + (table.equals("effect") ? "value_count" : "COUNT(*)") + " FROM " + table)) { assertTrue(r.next()); return r.getInt(1); }
    }
    static ManagedMethod method(Class<?> type) { return new ManagedMethod(type.getName(), "on", List.of(String.class.getName()), type == AsyncHandler.class ? CompletionStage.class.getName() : "void"); }
    ManagedInvocationPolicy.Binding binding(Class<?> type, String queue, String handler, long receipt, byte[] content) throws Exception {
        var contract = new ManagedServiceContract("hosted.queue-consumer.handler", 1, method(type));
        var tx = transactions.bindHosted(contract, type, Set.of(method(type))); var marker = inbox.bindHosted(contract, type, Set.of(method(type)));
        var chain = TransactionInvocation.compose(tx, marker);
        byte[] envelope = TracedQueue.encode(new TracedQueue.Message(TraceCarrier.empty(), content));
        return new ManagedInvocationPolicy.Binding() {
            @Override public Object invoke(InvocationContext context, Object[] values, ManagedInvocationPolicy.Proceed next) throws Exception {
                var grant = new QueueDelivery(queue, "g", "m", 1, "same-id", envelope, 1, receipt, (int) receipt);
                next.putContext(ManagedQueueDeliveryContext.class, new ManagedQueueDeliveryContext(source, handler, 1, grant));
                return chain.invoke(context, values, next);
            }
            @Override public void close() throws Exception { try { marker.close(); } finally { tx.close(); } }
        };
    }
    static void effect() throws Exception {
        var owner = ManagedInvocationLifetime.currentContext(ManagedJdbcTransactionContext.class);
        Connection connection = owner.connection(); assertSame(connection, owner.connection());
        try (var statement = connection.prepareStatement("UPDATE effect SET value_count=value_count+1")) { assertEquals(1, statement.executeUpdate()); }
    }
    @Test void identitiesSeparateQueuesAndLogicalHandlersWhileNewReceiptsReplayWithoutAnotherEffect() throws Exception {
        for (String queue : List.of("one", "two")) for (String handler : List.of("first", "second")) {
            try (var fresh = binding(Handler.class, queue, handler, 1, new byte[]{1})) { TransactionInvocation.invokeAs(TransactionInvocation.caller(), fresh, frame -> { effect(); return null; }); }
            try (var replay = binding(Handler.class, queue, handler, 2, new byte[]{1})) { TransactionInvocation.invokeAs(TransactionInvocation.caller(), replay, frame -> fail("Committed inbox must skip body")); }
        }
        assertEquals(4, count("effect")); assertEquals(4, count("j7m_inbox")); assertEquals(8, opened.get());
    }
    @Test void rollbackRemovesBothMarkerAndApplicationMutationAndConflictNeverChangesCommittedEffects() throws Exception {
        try (var binding = binding(Handler.class, "one", "handler", 1, new byte[]{1})) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { effect(); throw new SQLException("private application failure"); }));
        }
        assertEquals(0, count("effect")); assertEquals(0, count("j7m_inbox"));
        try (var binding = binding(Handler.class, "one", "handler", 2, new byte[]{1})) { TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { effect(); return null; }); }
        try (var conflict = binding(Handler.class, "one", "handler", 3, new byte[]{2})) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), conflict, frame -> fail("Conflicting inbox payload")));
        }
        assertEquals(1, count("effect")); assertEquals(1, count("j7m_inbox"));
    }
    @Test void trackedAsyncMutationSettlesBeforeSingleCommitAndReplayCannotRunTheStageAgain() throws Exception {
        try (var binding = binding(AsyncHandler.class, "one", "async", 1, new byte[]{1})) {
            TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> J7mContext.currentInvocation().control().submit(() -> { effect(); return null; }));
        }
        assertEquals(1, count("effect")); assertEquals(1, count("j7m_inbox"));
        try (var binding = binding(AsyncHandler.class, "one", "async", 2, new byte[]{1})) { TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> fail("Async replay body")); }
        assertEquals(1, count("effect"));
    }
}
