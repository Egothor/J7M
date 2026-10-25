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
import java.time.Duration;
import java.util.*;
import org.h2.tools.Server;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.JdbcDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxDefinitionTest {
    @TempDir Path directory;
    private OutboxRelayProfile profile(ManagedJdbcTransactionPolicy.Registration jdbc, String queueName) {
        var execution = new ExecutionTransactionFixture(); var schemas = new SchemaRegistry(List.of());
        var publication = new OutboxProfile("sales", "tenant", "orders", 1, "default", jdbc, new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas),
                new DeduplicatedRpc(execution.client), Duration.ofHours(1), Duration.ofDays(1), null);
        var queue = new ReplicatedQueue("queue", "tenant", (p, d) -> { throw new AssertionError("Metadata cannot publish a message"); });
        return new OutboxRelayProfile(publication, queue, queueName, 1, Duration.ofMillis(10), Duration.ofSeconds(1));
    }
    @Test void definitionIsAtomicReservedNonpublishableAndDestinationConflictSurvivesFreshProfileObjects() throws Exception {
        Server server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + directory.resolve("definition").toAbsolutePath();
        var jdbc = new ManagedJdbcTransactionPolicy.Registration(() -> DriverManager.getConnection(url, "sa", ""), Duration.ofSeconds(1));
        var profile = profile(jdbc, "orders");
        try (var connection = jdbc.connections().open()) {
            JdbcDelivery.initialize(connection, JdbcDelivery.Dialect.H2);
            assertThrows(SQLException.class, () -> OutboxDefinition.ensure(connection, profile));
            connection.setAutoCommit(false); OutboxDefinition.ensure(connection, profile); OutboxDefinition.ensure(connection, profile); connection.rollback();
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM j7m_outbox")) { assertTrue(r.next()); assertEquals(0, r.getInt(1)); }
            OutboxDefinition.ensure(connection, profile); connection.commit(); connection.setAutoCommit(true);
            assertTrue(profile.publication().delivery().pending(connection, 256).isEmpty(), "Reserved definition must never be a business head");
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM j7m_outbox")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
            connection.setAutoCommit(false); OutboxDefinition.ensure(connection, profile(jdbc, "orders")); connection.commit();
            var changed = profile(jdbc, "different-queue");
            assertThrows(SQLException.class, () -> OutboxDefinition.ensure(connection, changed)); connection.rollback();
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM j7m_outbox")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
        } finally { server.stop(); }
    }
    @Test void reservedDefinitionsCannotBypassFiniteSchemaQuotaOrItsIntegrityCounter() throws Exception {
        Server server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + directory.resolve("quota").toAbsolutePath();
        var jdbc = new ManagedJdbcTransactionPolicy.Registration(() -> DriverManager.getConnection(url, "sa", ""), Duration.ofSeconds(1));
        try (var connection = jdbc.connections().open()) {
            JdbcDelivery.initialize(connection, JdbcDelivery.Dialect.H2); connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO j7m_outbox(scope,message_id,aggregate_id,sequence_no,content_hash,payload,created_at,retain_until,sent) VALUES(?,?,?,?,?,NULL,?,?,1)")) {
                for (int i = 0; i < 256; i++) {
                    insert.setString(1, "outbox-definition-" + String.format("%064x", i)); insert.setString(2, "definition"); insert.setString(3, "definition"); insert.setLong(4, 1);
                    insert.setString(5, "a".repeat(64)); insert.setLong(6, 0); insert.setLong(7, Long.MAX_VALUE); insert.addBatch();
                }
                assertEquals(256, insert.executeBatch().length);
            }
            try (var s = connection.createStatement()) { s.executeUpdate("INSERT INTO j7m_outbox_aggregate VALUES('outbox-definition-quota','definitions',256)"); }
            connection.commit();
            assertThrows(SQLException.class, () -> OutboxDefinition.ensure(connection, profile(jdbc, "orders"))); connection.rollback();
            try (var s = connection.createStatement()) { s.executeUpdate("UPDATE j7m_outbox_aggregate SET next_sequence=255 WHERE scope='outbox-definition-quota'"); } connection.commit();
            assertThrows(SQLException.class, () -> OutboxDefinition.ensure(connection, profile(jdbc, "orders"))); connection.rollback();
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM j7m_outbox")) { assertTrue(r.next()); assertEquals(256, r.getInt(1)); }
        } finally { server.stop(); }
    }
}
