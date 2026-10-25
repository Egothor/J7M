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
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.tools.Server;
import org.j7m.middleware.distributed.TraceCarrier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(90)
class JdbcDeliveryIT {
    @TempDir Path directory;
    Server server; String url;
    final JdbcDelivery delivery = new JdbcDelivery("tenant", null);
    @BeforeEach void database() throws Exception {
        server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + directory.resolve("delivery").toAbsolutePath();
        try (Connection connection = open()) {
            JdbcDelivery.initialize(connection, JdbcDelivery.Dialect.H2);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE application_effect (id VARCHAR(32) PRIMARY KEY, amount INTEGER)");
                statement.executeUpdate("INSERT INTO application_effect VALUES('producer',0),('consumer',0)");
            }
        }
    }
    @AfterEach void close() { if (server != null) server.stop(); }
    Connection open() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    static TracedQueue.Message message(String text) { return new TracedQueue.Message(TraceCarrier.empty(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    void produce(String id) throws Exception {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (Statement update = connection.createStatement()) { update.executeUpdate("UPDATE application_effect SET amount=amount+1 WHERE id='producer'"); }
            delivery.enqueue(connection, id, "aggregate", message(id), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)); connection.commit();
        }
    }
    int amount(String id) throws Exception {
        try (Connection connection = open(); PreparedStatement select = connection.prepareStatement("SELECT amount FROM application_effect WHERE id=?")) {
            select.setString(1, id); try (ResultSet row = select.executeQuery()) { assertTrue(row.next()); return row.getInt(1); }
        }
    }
    @Test void callerRollbackPreventsPublicationAndHashTombstonesRejectConflictingIdentity() throws Exception {
        try (Connection connection = open()) {
            assertThrows(SQLException.class, () -> delivery.enqueue(connection, "invalid", "aggregate", message("x"), Instant.EPOCH, Instant.EPOCH));
            connection.setAutoCommit(false);
            JdbcDelivery.Mutation ownedMutation = (c,p) -> { try (Statement statement = c.createStatement()) { statement.executeUpdate("UPDATE application_effect SET amount=amount+1 WHERE id='producer'"); } };
            ownedMutation.apply(connection, new byte[0]);
            assertEquals(0, amount("producer"), "Mutation remains invisible before caller commits"); connection.rollback();
            try (Statement statement = connection.createStatement()) { statement.executeUpdate("UPDATE application_effect SET amount=1 WHERE id='producer'"); }
            delivery.enqueue(connection, "rolled-back", "aggregate", message("x"), Instant.EPOCH, Instant.EPOCH); connection.rollback();
            assertEquals(List.of(), delivery.pending(connection, 10));
            assertEquals(0, amount("producer"));
            delivery.enqueue(connection, "kept", "aggregate", message("first"), Instant.EPOCH, Instant.EPOCH); connection.commit();
            assertEquals(1, delivery.enqueue(connection, "kept", "aggregate", message("first"), Instant.EPOCH, Instant.EPOCH));
            assertThrows(SQLException.class, () -> delivery.enqueue(connection, "kept", "aggregate", message("different"), Instant.EPOCH, Instant.EPOCH)); connection.rollback();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            assertThrows(SQLException.class, () -> delivery.pending(connection, 1));
        }
    }
    @Test void concurrentInboxTransactionsApplyExactlyOneConsumerChangeAndRollbackRetries() throws Exception {
        byte[] envelope = TracedQueue.encode(message("effect"));
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> delivery.consume(connection, "rollback", envelope, (c, p) -> { throw new SQLException("expected application failure"); }, Instant.EPOCH));
            connection.rollback();
        }
        try (var tasks = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 32; i++) attempts.add(tasks.submit(() -> {
                for (int retry = 0; retry < 5; retry++) try (Connection connection = open()) {
                    connection.setAutoCommit(false);
                    try {
                        boolean applied = delivery.consume(connection, "same", envelope, (c, p) -> {
                            try (Statement mutation = c.createStatement()) { mutation.executeUpdate("UPDATE application_effect SET amount=amount+1 WHERE id='consumer'"); }
                        }, Instant.EPOCH);
                        connection.commit(); return applied;
                    } catch (SQLException conflict) { connection.rollback(); if (!"23505".equals(conflict.getSQLState()) && !"40001".equals(conflict.getSQLState())) throw conflict; }
                }
                throw new AssertionError("Concurrent inbox retries exhausted");
            }));
            int newlyApplied = 0; for (Future<Boolean> attempt : attempts) if (attempt.get(20, TimeUnit.SECONDS)) newlyApplied++;
            assertEquals(1, newlyApplied); assertEquals(1, amount("consumer"));
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> delivery.consume(connection, "same", TracedQueue.encode(message("other")), (c,p) -> fail("conflict mutated application"), Instant.EPOCH)); connection.rollback();
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"after-db-commit", "after-send", "before-mark"})
    void killedRelayRecoversCommittedOutboxAndConsumerCommitsBeforeAck(String boundary) throws Exception {
        try (var cluster = new IntegrationCluster(directory.resolve("brokers"))) {
            produce("message"); assertEquals(1, amount("producer"));
            Process relay = IntegrationCluster.child(directory, "RELAY", url, cluster.ports, boundary);
            try { assertEquals("FAULT " + boundary, IntegrationCluster.read(relay.inputReader())); } finally { IntegrationCluster.kill(relay); }
            try (Connection connection = open()) { assertEquals(1, delivery.pending(connection, 10).size()); }
            Process recovered = IntegrationCluster.child(directory, "RELAY", url, cluster.ports, "none");
            assertEquals("MARKED 1", IntegrationCluster.read(recovered.inputReader())); assertTrue(recovered.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, recovered.exitValue());
            Process consumer = IntegrationCluster.child(directory, "CONSUME", url, cluster.ports);
            try { assertEquals("FAULT after-consumer-commit", IntegrationCluster.read(consumer.inputReader())); } finally { IntegrationCluster.kill(consumer); }
            assertEquals(1, amount("consumer"));
            String claim = cluster.queue.claim("claim-after-consumer-crash", "outbox", "consumer", "member", 1, 100000, IntegrationProcess.deadline()); assertTrue(claim.startsWith("MESSAGE\t"), claim);
            String[] fields = claim.split("\t"); byte[] envelope = Base64.getDecoder().decode(fields[2]);
            for (int duplicate = 0; duplicate < 2; duplicate++) try (Connection connection = open()) {
                connection.setAutoCommit(false);
                boolean first = delivery.consume(connection, fields[1], envelope, (c,p) -> {
                    try (Statement mutation = c.createStatement()) { mutation.executeUpdate("UPDATE application_effect SET amount=amount+1 WHERE id='consumer'"); }
                }, Instant.EPOCH);
                assertFalse(first, "Committed inbox must survive consumer JVM death"); connection.commit();
            }
            assertEquals(1, amount("consumer"));
            assertEquals("ACKED", cluster.queue.ack("ack", "outbox", "consumer", "member", 1, fields[1], Long.parseLong(fields[4]), IntegrationProcess.deadline()));
            assertEquals("EMPTY", cluster.queue.claim("empty", "outbox", "consumer", "member", 1, 100, IntegrationProcess.deadline()));
            try (Connection connection = open()) {
                connection.setAutoCommit(false); assertEquals(1, delivery.pruneSentPayloads(connection, Instant.EPOCH.plusSeconds(3601))); connection.commit();
                assertThrows(SQLException.class, () -> delivery.enqueue(connection, "message", "aggregate", message("changed"), Instant.EPOCH, Instant.EPOCH)); connection.rollback();
                assertEquals(List.of(), delivery.pending(connection, 1));
            }
            Path evidence = Path.of("build/reports/integrations/relay-" + boundary + ".txt"); Files.createDirectories(evidence.getParent()); Files.writeString(evidence, "realH2TCP;real3JVMqueue;relayKilled=" + boundary + ";producer=1;consumer=1;pending=0;conflictTombstone=verified\n");
        }
    }
    @Test void concurrentRelaysPublishOnlyAggregateHeadsAndMaintainSequence() throws Exception {
        try (var cluster = new IntegrationCluster(directory.resolve("brokers"))) {
            for (int i = 0; i < 12; i++) produce("m" + i);
            try (var tasks = Executors.newFixedThreadPool(2)) {
                for (int round = 0; round < 12; round++) {
                    var one = tasks.submit(() -> delivery.relayOnce(this::open, cluster.queue, "outbox", 256, IntegrationProcess.deadline()));
                    var two = tasks.submit(() -> delivery.relayOnce(this::open, cluster.queue, "outbox", 256, IntegrationProcess.deadline()));
                    one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
                }
            }
            cluster.queue.join("join", "consumer", "member", 100000, IntegrationProcess.deadline());
            long previous = 0;
            for (int i = 0; i < 12; i++) {
                String[] claim = cluster.queue.claim("claim-" + i, "outbox", "consumer", "member", 1, 100000, IntegrationProcess.deadline()).split("\t");
                assertEquals("m" + i, claim[1]); long sequence = Long.parseLong(claim[3]); assertTrue(sequence > previous); previous = sequence;
                assertEquals("ACKED", cluster.queue.ack("ack-" + i, "outbox", "consumer", "member", 1, claim[1], Long.parseLong(claim[4]), IntegrationProcess.deadline()));
            }
        }
    }
}
