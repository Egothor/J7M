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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.distributed.*;

/**
 * JDBC transactional outbox/inbox for H2 and PostgreSQL using application-supplied drivers.
 * Enqueue and consume methods never commit, roll back, close or replace the caller's connection.
 * Callers must use one non-autocommit transaction for their application writes and these methods,
 * and roll back that whole transaction on any exception. Relay owns its short transactions and
 * sends at least once; there is deliberately no atomic DB-plus-remote-queue commit claim.
 * Instances are immutable and thread-safe; connections must not be shared between threads.
 */
public final class JdbcDelivery {
    /**
     * Supported schema dialects; drivers are supplied by the hosting application.
     */
    public enum Dialect {
        /**
         * Reference embedded/server relational database.
         */ H2,
        /**
         * PostgreSQL with BYTEA payload storage.
         */ POSTGRESQL
    }
    /**
     * Application-managed connection factory; every returned connection is independently owned.
     */
    @FunctionalInterface public interface Connections {
        /**
         * Opens a fresh connection.
         * @return caller-owned JDBC connection
         * @throws SQLException for connection failure
         */
        Connection open() throws SQLException;
    }
    /**
     * Application mutation executed inside the same transaction as the inbox insertion.
     */
    @FunctionalInterface public interface Mutation {
        /**
         * Applies consumer effects on the exact caller connection.
         * @param connection active caller transaction
         * @param payload independent application bytes
         * @throws SQLException if application mutation fails
         */
        void apply(Connection connection, byte[] payload) throws SQLException;
    }
    /**
     * Immutable persisted relay row.
     * @param scope tenant/consumer identity
     * @param id stable message identity
     * @param aggregate ordered aggregate identity
     * @param sequence aggregate-local sequence
     * @param envelope persisted traced message bytes
     * @param digest SHA-256 of envelope
     */
    public record Pending(String scope, String id, String aggregate, long sequence, byte[] envelope, String digest) {
        /**
         * Copies message bytes.
         * @param scope scope identity
         * @param id message identity
         * @param aggregate ordering identity
         * @param sequence aggregate sequence
         * @param envelope encoded bytes
         * @param digest envelope hash
         */
        public Pending { envelope = envelope.clone(); }
        /**
         * Returns independent encoded message bytes.
         * @return copied envelope
         */
        @Override public byte[] envelope() { return envelope.clone(); }
    }
    private final String scope;
    private final Observability telemetry;
    private volatile java.util.function.Consumer<String> faultInjector;
    void faultInjector(java.util.function.Consumer<String> injector) { faultInjector = injector; }
    private void fault(String boundary) { var injector = faultInjector; if (injector != null) injector.accept(boundary); }

    /**
     * Binds DB identities to one authenticated queue/inbox scope.
     * @param scope scope identifier, at most 128 ASCII identifier characters
     * @param telemetry optional observability integration; null disables span export
     * @throws IllegalArgumentException for invalid scope
     */
    public JdbcDelivery(String scope, Observability telemetry) { identifier(scope); this.scope = scope; this.telemetry = telemetry; }

    /**
     * Creates additive integration tables; execute as an explicit migration on a dedicated connection.
     * Never call during an application transaction: DDL transaction semantics differ by database.
     * Tables and tombstones remain on rollback/uninstall to preserve delivery identity.
     * @param connection dedicated migration connection
     * @param dialect supported database dialect
     * @throws SQLException for schema creation failure
     */
    public static void initialize(Connection connection, Dialect dialect) throws SQLException {
        String binary = dialect == Dialect.POSTGRESQL ? "BYTEA" : "BINARY LARGE OBJECT";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS j7m_outbox_aggregate (scope VARCHAR(128) NOT NULL, aggregate_id VARCHAR(128) NOT NULL, next_sequence BIGINT NOT NULL, PRIMARY KEY(scope,aggregate_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS j7m_outbox (scope VARCHAR(128) NOT NULL, message_id VARCHAR(128) NOT NULL, aggregate_id VARCHAR(128) NOT NULL, sequence_no BIGINT NOT NULL, content_hash VARCHAR(64) NOT NULL, payload " + binary + ", created_at BIGINT NOT NULL, retain_until BIGINT NOT NULL, sent INTEGER NOT NULL, PRIMARY KEY(scope,message_id), UNIQUE(scope,aggregate_id,sequence_no))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS j7m_outbox_pending ON j7m_outbox(scope,sent,aggregate_id,sequence_no)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS j7m_inbox (scope VARCHAR(128) NOT NULL, message_id VARCHAR(128) NOT NULL, content_hash VARCHAR(64) NOT NULL, consumed_at BIGINT NOT NULL, PRIMARY KEY(scope,message_id))");
        }
    }

    /**
     * Appends an ordered message in the caller's application transaction.
     * Existing IDs with identical envelope/aggregate return their original sequence; conflicting IDs
     * fail. Concurrent first insertion of an aggregate can require retry of the whole transaction.
     * @param connection exact non-autocommit application transaction
     * @param id stable message identity
     * @param aggregate aggregate ordering identity
     * @param message immutable application payload and retry-stable context
     * @param createdAt application-selected timestamp used only for retention metadata
     * @param retainUntil earliest time payload may be removed after successful publication
     * @return aggregate-local sequence
     * @throws SQLException for inactive transaction, conflicts or database errors
     * @throws IOException for invalid message envelope
     */
    public long enqueue(Connection connection, String id, String aggregate, TracedQueue.Message message, Instant createdAt, Instant retainUntil) throws SQLException, IOException {
        transaction(connection); identifier(id); identifier(aggregate);
        if (retainUntil.isBefore(createdAt)) throw new IllegalArgumentException("Retention precedes creation");
        byte[] envelope = TracedQueue.encode(message); String digest = digest(envelope);
        try (PreparedStatement prior = connection.prepareStatement("SELECT aggregate_id,sequence_no,content_hash FROM j7m_outbox WHERE scope=? AND message_id=?")) {
            prior.setString(1, scope); prior.setString(2, id);
            try (ResultSet row = prior.executeQuery()) {
                if (row.next()) {
                    if (!row.getString(1).equals(aggregate) || !row.getString(3).equals(digest)) throw conflict();
                    return row.getLong(2);
                }
            }
        }
        Long sequence = null;
        try (PreparedStatement lock = connection.prepareStatement("SELECT next_sequence FROM j7m_outbox_aggregate WHERE scope=? AND aggregate_id=? FOR UPDATE")) {
            lock.setString(1, scope); lock.setString(2, aggregate);
            try (ResultSet row = lock.executeQuery()) { if (row.next()) sequence = row.getLong(1); }
        }
        if (sequence == null) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO j7m_outbox_aggregate(scope,aggregate_id,next_sequence) VALUES(?,?,?)")) {
                insert.setString(1, scope); insert.setString(2, aggregate); insert.setLong(3, 1); insert.executeUpdate(); sequence = 1L;
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE j7m_outbox_aggregate SET next_sequence=? WHERE scope=? AND aggregate_id=?")) {
            update.setLong(1, Math.addExact(sequence, 1)); update.setString(2, scope); update.setString(3, aggregate); update.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO j7m_outbox(scope,message_id,aggregate_id,sequence_no,content_hash,payload,created_at,retain_until,sent) VALUES(?,?,?,?,?,?,?,?,0)")) {
            insert.setString(1, scope); insert.setString(2, id); insert.setString(3, aggregate); insert.setLong(4, sequence); insert.setString(5, digest);
            insert.setBytes(6, envelope); insert.setLong(7, createdAt.toEpochMilli()); insert.setLong(8, retainUntil.toEpochMilli()); insert.executeUpdate();
        }
        return sequence;
    }

    /**
     * Selects only the first unsent message of each aggregate, preserving send order across relays.
     * Sending/marking an older row more than once is safe only because the real queue retains the
     * same message identity and content fingerprint. This method does not claim rows or commit.
     * @param connection caller-owned connection
     * @param maximum bounded head count in [1,256]
     * @return immutable ordered head rows
     * @throws SQLException for database access failure
     */
    public List<Pending> pending(Connection connection, int maximum) throws SQLException {
        committedReads(connection);
        if (maximum < 1 || maximum > 256) throw new IllegalArgumentException("Invalid relay batch size");
        String sql = "SELECT o.message_id,o.aggregate_id,o.sequence_no,o.payload,o.content_hash,OCTET_LENGTH(o.payload) FROM j7m_outbox o WHERE o.scope=? AND o.sent=0 AND NOT EXISTS (SELECT 1 FROM j7m_outbox p WHERE p.scope=o.scope AND p.aggregate_id=o.aggregate_id AND p.sent=0 AND p.sequence_no<o.sequence_no) ORDER BY o.aggregate_id,o.sequence_no";
        List<Pending> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, scope); select.setMaxRows(maximum);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    long size = result.getLong(6); if (result.wasNull() || size < 12 || size > 32768) throw new SQLException("Outbox payload bounds/corruption");
                    byte[] bytes = result.getBytes(4); String storedHash = result.getString(5);
                    if (!digest(bytes).equals(storedHash)) throw new SQLException("Outbox content hash mismatch");
                    rows.add(new Pending(scope, result.getString(1), result.getString(2), result.getLong(3), bytes, storedHash));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Publishes head rows at least once and marks only confirmed queue publications in short DB transactions.
     * Stable queue requests bind both DB scope and message ID. A timeout preserves the pending row.
     * Concurrent relays may resend the same head but cannot publish a successor before confirmed send.
     * @param connections independent relay connection factory
     * @param queue authenticated queue bound to this scope
     * @param name destination queue name
     * @param maximum at most 256 aggregate heads
     * @param deadline absolute queue deadline
     * @return number of confirmed/marked rows
     * @throws SQLException for DB failures; rows remain recoverable on retry
     * @throws IOException for queue failure/conflict or unknown outcome
     */
    public int relayOnce(Connections connections, ReplicatedQueue queue, String name, int maximum, Instant deadline) throws SQLException, IOException {
        List<Pending> rows;
        try (Connection read = connections.open()) { rows = pending(read, maximum); }
        int marked = 0;
        for (Pending row : rows) {
            String request = "outbox-" + digest((scope + ":" + row.id()).getBytes(StandardCharsets.UTF_8));
            Observability.SpanScope span = telemetry == null ? null : telemetry.start(Observability.Operation.DB_OUTBOX, TracedQueue.decode(row.envelope()).context());
            try {
                fault("after-db-commit");
                String result = queue.send(request, name, row.id(), row.envelope(), 1000, deadline);
                if (!result.startsWith("SENT")) throw new IOException("Outbox publication rejected: " + result);
                fault("after-send"); fault("before-mark");
                try (Connection connection = connections.open()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement update = connection.prepareStatement("UPDATE j7m_outbox SET sent=1 WHERE scope=? AND message_id=? AND content_hash=?")) {
                        update.setString(1, scope); update.setString(2, row.id()); update.setString(3, row.digest());
                        if (update.executeUpdate() != 1) throw new SQLException("Outbox row changed unexpectedly");
                        connection.commit(); marked++;
                    } catch (SQLException failure) { connection.rollback(); throw failure; }
                }
            } catch (SQLException | IOException failure) { if (span != null) span.failed(); throw failure; }
            finally { if (span != null) span.close(); }
        }
        return marked;
    }

    /**
     * Inserts the inbox identity and applies consumer mutation in the caller's single transaction.
     * The database uniqueness constraint serializes concurrent duplicates. After a uniqueness error,
     * roll back and retry the whole transaction so the committed digest can be checked. A returned
     * false means an identical previously committed consumption and performs no application mutation.
     * @param connection exact non-autocommit consumer transaction
     * @param id queue message identity
     * @param envelope received traced message bytes
     * @param mutation application mutation using this connection
     * @param consumedAt audit timestamp, not an expiry authority
     * @return true for a newly applied mutation, false for a verified replay
     * @throws SQLException on conflict, application failure or DB failure
     * @throws IOException on malformed envelope
     */
    public boolean consume(Connection connection, String id, byte[] envelope, Mutation mutation, Instant consumedAt) throws SQLException, IOException {
        transaction(connection); identifier(id); Objects.requireNonNull(mutation);
        TracedQueue.Message message = TracedQueue.decode(envelope); String digest = digest(envelope);
        try (PreparedStatement prior = connection.prepareStatement("SELECT content_hash FROM j7m_inbox WHERE scope=? AND message_id=?")) {
            prior.setString(1, scope); prior.setString(2, id);
            try (ResultSet row = prior.executeQuery()) {
                if (row.next()) { if (!row.getString(1).equals(digest)) throw conflict(); return false; }
            }
        }
        Observability.SpanScope span = telemetry == null ? null : telemetry.start(Observability.Operation.DB_INBOX, message.context());
        try {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO j7m_inbox(scope,message_id,content_hash,consumed_at) VALUES(?,?,?,?)")) {
                insert.setString(1, scope); insert.setString(2, id); insert.setString(3, digest); insert.setLong(4, consumedAt.toEpochMilli()); insert.executeUpdate();
            }
            mutation.apply(connection, message.payload()); return true;
        } catch (SQLException failure) { if (span != null) span.failed(); throw failure; }
        finally { if (span != null) span.close(); }
    }

    /**
     * Removes only payloads already confirmed sent and past their retention time.
     * Hash/identity/order tombstones are retained indefinitely so expired payloads cannot permit
     * conflicting ID reuse. Operators must provision/monitor tombstone storage explicitly.
     * @param connection caller-managed retention transaction
     * @param now retention cutoff timestamp
     * @return number of payloads removed
     * @throws SQLException for inactive transaction or database error
     */
    public int pruneSentPayloads(Connection connection, Instant now) throws SQLException {
        transaction(connection);
        try (PreparedStatement update = connection.prepareStatement("UPDATE j7m_outbox SET payload=NULL WHERE scope=? AND sent=1 AND retain_until<=? AND payload IS NOT NULL")) {
            update.setString(1, scope); update.setLong(2, now.toEpochMilli()); return update.executeUpdate();
        }
    }
    private static void transaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || connection.isClosed()) throw new SQLException("Caller transaction required", "25000");
        committedReads(connection);
    }
    private static void committedReads(Connection connection) throws SQLException {
        if (connection.getTransactionIsolation() < Connection.TRANSACTION_READ_COMMITTED) throw new SQLException("READ_COMMITTED or stronger isolation required", "25000");
    }
    private static void identifier(String id) { if (id == null || !id.matches("[a-zA-Z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid delivery identifier"); }
    private static SQLException conflict() { return new SQLException("MESSAGE_CONFLICT: identity has different content", "23000"); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
