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
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.tools.Server;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.distributed.TraceCarrier;
import org.j7m.middleware.integrations.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real H2 TCP qualification of the transaction policy and its GuardedJdbc/JdbcDataSql boundary.
 */
class ManagedJdbcTransactionIT {
    public interface Business { @JdbcTransaction String apply(String input); }
    public static final class Implementation implements Business { public String apply(String input) { return input; } }
    public interface AsyncBusiness { @Async @JdbcTransaction CompletionStage<String> apply(String input); }
    public static final class AsyncImplementation implements AsyncBusiness { public CompletionStage<String> apply(String input) { return CompletableFuture.completedFuture(input); } }
    public interface Cached { @Deduplicated @JdbcTransaction String apply(@IdempotencyKey String input); }
    public static final class CachedImplementation implements Cached { public String apply(String input) { return input; } }
    public interface Other { @JdbcTransaction(policy="other") String apply(String input); }
    public interface Mixed { @JdbcTransaction @ResourceTransaction String apply(String input); }
    public interface LiveResult { @JdbcTransaction Connection apply(String input); }
    @TempDir Path directory;
    Server server; String url;
    final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger(), commits = new AtomicInteger(), rollbacks = new AtomicInteger();
    volatile boolean loseCommitReply, failClose, failMetadata;
    @BeforeEach void database() throws Exception {
        server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + directory.resolve("transactions").toAbsolutePath();
        try (var connection = raw(); var statement = connection.createStatement()) {
            JdbcDelivery.initialize(connection, JdbcDelivery.Dialect.H2);
            statement.executeUpdate("CREATE TABLE business_value(id VARCHAR(128) PRIMARY KEY, amount INTEGER)");
            statement.executeUpdate("INSERT INTO business_value VALUES('account',0)");
        }
    }
    @AfterEach void stop() { if (server != null) server.stop(); }
    Connection raw() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    Connection owned() throws SQLException {
        Connection actual = raw(); opens.incrementAndGet(); var closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (self, method, args) -> {
            if (method.getName().equals("commit")) commits.incrementAndGet();
            if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
            if (method.getName().equals("close") && closed.compareAndSet(false, true)) closes.incrementAndGet();
            try {
                if (method.getName().equals("getMetaData") && failMetadata) throw new SQLException("SECRET metadata failure", "08006");
                Object result = method.invoke(actual, args);
                if (method.getName().equals("commit") && loseCommitReply) throw new SQLException("SECRET commit reply lost", "08006");
                if (method.getName().equals("close") && failClose) throw new SQLException("SECRET close failure", "08006");
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    ManagedJdbcTransactionPolicy policy() {
        var registration = new ManagedJdbcTransactionPolicy.Registration(this::owned, Duration.ofSeconds(2));
        return new ManagedJdbcTransactionPolicy(Map.of("default", registration, "other", registration));
    }
    ManagedInvocationPolicy.Binding binding(ManagedJdbcTransactionPolicy policy) { return policy.bind(TransactionInvocation.contract(Business.class), Implementation.class, Business.class); }
    static ManagedJdbcTransactionContext current() { return ManagedInvocationLifetime.currentContext(ManagedJdbcTransactionContext.class); }
    int amount() throws SQLException {
        try (var connection = raw(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT amount FROM business_value WHERE id='account'")) { assertTrue(rows.next()); return rows.getInt(1); }
    }
    static void update(Connection connection) throws SQLException { try (var statement = connection.prepareStatement("UPDATE business_value SET amount=amount+1 WHERE id=?")) { statement.setString(1, "account"); assertEquals(1, statement.executeUpdate()); } }
    @Test void applicationAndJdbcDeliveryUseOneGuardedConnectionAndCommitTogether() throws Exception {
        var retained = new Connection[1];
        try (var binding = binding(policy())) {
            assertEquals("done", TransactionInvocation.invoke(binding, frame -> {
                var connection = current().connection(); retained[0] = connection; assertSame(connection, current().connection());
                assertSame(connection, connection.getMetaData().getConnection()); assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
                update(connection); assertEquals(0, amount());
                var delivery = new JdbcDelivery("tenant", null);
                delivery.enqueue(connection, "message", "account", new TracedQueue.Message(TraceCarrier.empty(), new byte[]{1}), Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
                byte[] envelope = TracedQueue.encode(new TracedQueue.Message(TraceCarrier.empty(), new byte[]{2}));
                assertTrue(delivery.consume(connection, "inbox", envelope, (same, payload) -> { assertSame(connection, same); update(same); }, Instant.EPOCH));
                assertFalse(delivery.consume(connection, "inbox", envelope, (same, payload) -> fail("duplicate inbox effect"), Instant.EPOCH));
                return "done";
            }));
        }
        assertEquals(2, amount()); assertEquals(1, opens.get()); assertEquals(1, commits.get()); assertEquals(1, closes.get());
        assertThrows(SQLException.class, () -> retained[0].createStatement());
        try (var connection = raw(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM j7m_outbox")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
    }
    @Test void nestedRequiredJoinsExactConnectionAndCaughtFailureMarksOuterRollbackOnly() throws Exception {
        var policy = policy();
        try (var outer = binding(policy); var nested = binding(policy)) {
            var failure = assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(outer, frame -> {
                var first = current().connection(); update(first);
                try { nested.invoke(J7mContext.currentInvocation(), new Object[]{"nested"}, frame.next(() -> { assertSame(first, current().connection()); update(first); throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "private"); }, false)); }
                catch (MiddlewareException expected) { assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, expected.code()); }
                assertTrue(current().rollbackOnly()); return "caught";
            }));
            assertEquals("ROLLED_BACK", ((MiddlewareException) failure.getCause()).remoteError().details().get("status"));
        }
        assertEquals(0, amount()); assertEquals(1, opens.get()); assertEquals(0, commits.get()); assertEquals(1, rollbacks.get()); assertEquals(1, closes.get());
    }
    @Test void commitReplyLossNeverBecomesRollbackProofAndPhysicalConnectionStillCloses() throws Exception {
        loseCommitReply = true;
        try (var binding = binding(policy())) {
            var failure = assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> { update(current().connection()); return "unconfirmed"; }));
            assertEquals("UNKNOWN", ((MiddlewareException) failure.getCause()).remoteError().details().get("status")); assertFalse(failure.toString().contains("SECRET"));
        }
        assertEquals(1, amount()); assertEquals(1, commits.get()); assertEquals(0, rollbacks.get()); assertEquals(1, closes.get());
    }
    @Test void guardedApiAndBacklinksCannotCommitConfigureUnwrapOrEscapeThroughPreparedOverloads() throws Exception {
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                Connection connection = current().connection(); update(connection);
                assertThrows(SQLException.class, connection::commit); assertThrows(SQLException.class, connection::rollback);
                assertThrows(SQLException.class, connection::close); assertThrows(SQLException.class, () -> connection.abort(Runnable::run));
                assertThrows(SQLException.class, () -> connection.setAutoCommit(true));
                assertThrows(SQLException.class, () -> connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED));
                assertThrows(SQLException.class, () -> connection.setReadOnly(true)); assertThrows(SQLException.class, () -> connection.setSchema("PUBLIC"));
                assertThrows(SQLException.class, () -> connection.setClientInfo("unsafe", "value"));
                assertThrows(SQLException.class, connection::setSavepoint); assertThrows(SQLException.class, () -> connection.unwrap(Connection.class));
                var metadata = connection.getMetaData(); assertSame(connection, metadata.getConnection()); assertEquals("managed", metadata.getURL());
                assertThrows(SQLException.class, () -> metadata.unwrap(DatabaseMetaData.class));
                try (var statement = connection.prepareStatement("SELECT amount FROM business_value WHERE id=?")) {
                    assertSame(connection, statement.getConnection()); statement.setString(1, "account");
                    assertThrows(SQLException.class, () -> statement.execute("UPDATE business_value SET amount=99; COMMIT"));
                    assertThrows(SQLException.class, () -> statement.getParameterMetaData().unwrap(ParameterMetaData.class));
                    try (var rows = statement.executeQuery()) {
                        assertSame(statement, rows.getStatement()); assertSame(connection, rows.getStatement().getConnection());
                        assertThrows(SQLException.class, () -> rows.getMetaData().unwrap(ResultSetMetaData.class));
                        assertThrows(SQLException.class, () -> rows.unwrap(ResultSet.class)); assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                        assertThrows(SQLException.class, () -> rows.updateInt(1, 99));
                    }
                    statement.close(); statement.close();
                }
                assertTrue(current().rollbackOnly()); return "must-rollback";
            }));
        }
        assertEquals(0, amount()); assertEquals(0, commits.get()); assertEquals(1, rollbacks.get()); assertEquals(1, closes.get());
    }
    @Test void ddlProcedureAndMultistatementRejectBeforeDriverCanImplicitlyCommit() throws Exception {
        for (String sql : List.of("CREATE TABLE forbidden(id INT)", "SET AUTOCOMMIT TRUE", "UPDATE business_value SET amount=99; COMMIT", "CALL H2VERSION()", "SELECT H2VERSION()", "SELECT \"ABS\"(1)", "SELECT /*comment*/ 1", "{call dangerous()}")) {
            try (var binding = binding(policy())) {
                assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                    var connection = current().connection(); update(connection);
                    try (var statement = connection.createStatement()) { assertThrows(SQLException.class, () -> statement.execute(sql), sql); }
                    return "caught-but-rollback-only";
                }));
            }
            assertEquals(0, amount(), sql);
        }
        assertEquals(0, commits.get()); assertEquals(opens.get(), closes.get());
    }
    @Test void unsupportedScopeAttachmentCannotOpenGhostConnectionAndProfileMismatchCannotOpenSecond() throws Exception {
        var policy = policy();
        try (var binding = binding(policy)) {
            var original = InvocationContext.empty();
            try (var container = ManagedContainer.activate(List.of()); var actor = Executors.newSingleThreadExecutor()) {
                var lifetime = new ManagedInvocationLifetime(container.openCall(), original, null, Duration.ofSeconds(5), actor, 8, 8, () -> { });
                try {
                    assertThrows(IllegalStateException.class, () -> lifetime.call(() -> binding.invoke(J7mContext.currentInvocation(), new Object[]{"input"}, new ManagedInvocationPolicy.Proceed() {
                        @Override public boolean supportsResultFinalizers() { return true; }
                        @Override public <T> Optional<T> findContext(Class<T> type) { return Optional.empty(); }
                        @Override public Object invoke() { return fail("unsupported host body"); }
                    })));
                } finally { lifetime.close(); lifetime.physicalCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS); }
            }
        }
        assertEquals(0, opens.get());
        try (var outer = binding(policy); var nested = policy.bind(TransactionInvocation.contract(Other.class), Other.class, Other.class)) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(outer, frame -> {
                update(current().connection());
                assertThrows(IllegalStateException.class, () -> nested.invoke(J7mContext.currentInvocation(), new Object[]{"input"}, frame.next(() -> fail("wrong profile body"), false)));
                return "caught";
            }));
        }
        assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertEquals(0, amount());
    }
    @Test void handleBoundFailsExplicitlyAndCleanupSurvivesConnectionCloseFailure() throws Exception {
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                var connection = current().connection(); update(connection);
                for (int i = 0; i < 255; i++) connection.createStatement().close();
                assertThrows(SQLException.class, connection::createStatement); return "overflow";
            }));
        }
        assertEquals(0, amount());
        failClose = true;
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> { update(current().connection()); return "committed-close-unknown"; }));
        }
        assertEquals(1, amount()); assertEquals(opens.get(), closes.get());
    }
    @Test void controlledAsyncChildFinallyPrecedesRollbackAndCancellationCannotCommitOrReleaseConnectionEarly() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finallyDone = new CountDownLatch(1);
        var active = new AtomicReference<TransactionInvocation>();
        try (var binding = policy().bind(TransactionInvocation.contract(AsyncBusiness.class), AsyncImplementation.class, AsyncBusiness.class);
                var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> TransactionInvocation.invoke(binding, frame -> {
                active.set(frame);
                return J7mContext.currentInvocation().control().submit(() -> {
                    try (var statement = current().connection().createStatement()) {
                        statement.executeUpdate("UPDATE business_value SET amount=amount+1 WHERE id='account'"); entered.countDown();
                        assertTrue(release.await(3, TimeUnit.SECONDS)); return "done";
                    } finally { finallyDone.countDown(); }
                });
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS)); assertEquals(0, amount()); active.get().lifetime.requestCancellation();
                assertEquals(0, closes.get()); assertEquals(0, commits.get()); assertEquals(1, finallyDone.getCount());
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(finallyDone.await(1, TimeUnit.SECONDS));
        }
        assertEquals(0, amount()); assertEquals(0, commits.get()); assertEquals(1, rollbacks.get()); assertEquals(1, closes.get());
    }
    @Test void explicitRollbackOnlyAndActualRowOverflowNeverReportSuccessfulCommit() throws Exception {
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                update(current().connection()); current().setRollbackOnly(); assertTrue(current().rollbackOnly()); return "rollback";
            }));
        }
        try (var connection = raw(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE row_limit(id INT)");
            for (int i = 0; i < 1025; i++) statement.addBatch("INSERT INTO row_limit VALUES(" + i + ")"); statement.executeBatch();
        }
        var seen = new AtomicInteger();
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                try (var statement = current().connection().createStatement(); var rows = statement.executeQuery("SELECT id FROM row_limit")) {
                    assertThrows(SQLException.class, () -> { while (rows.next()) { rows.getInt(1); seen.incrementAndGet(); } });
                }
                return "must-not-silently-truncate";
            }));
        }
        assertEquals(1024, seen.get()); assertEquals(0, amount()); assertEquals(0, commits.get()); assertEquals(opens.get(), closes.get());
    }
    @Test void generatedAnnotatedServiceInjectsExactCurrentConnectionAndUnknownPolicyRejectsBeforeConstructor() throws Exception {
        Path classes = GeneratedTransactionService.compile(directory.resolve("generated"), true);
        try (var generated = new GeneratedTransactionService(classes)) {
            var factory = new ResourceBindingFactory(Map.of("jdbc", ResourceBindingFactory.jdbcConnection()), Set.of("default"));
            assertThrows(IllegalArgumentException.class, () -> generated.activate(new ManagedJdbcTransactionPolicy(Map.of()), factory));
            assertEquals(0, generated.service.getField("constructed").getInt(null)); assertEquals(0, opens.get());
            var policy = policy(); generated.activate(policy, factory);
            assertEquals(1, generated.service.getField("constructed").getInt(null));
            try (var binding = policy.bind(generated.contract, generated.service, generated.business)) { assertEquals("result-5", generated.invoke(binding, "generated", "5")); }
        }
        assertEquals(1, opens.get()); assertEquals(1, commits.get()); assertEquals(1, closes.get());
        try (var connection = raw(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT amount FROM business_value WHERE id='generated'")) { assertTrue(rows.next()); assertEquals(5, rows.getInt(1)); }
    }
    @Test void openedConnectionIsClosedWhenAdmissionConfigurationFailsBeforeBody() throws Exception {
        failMetadata = true;
        try (var binding = binding(policy())) { assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> fail("configuration failed before body"))); }
        assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertEquals(0, commits.get()); assertEquals(0, amount());
    }
    @Test void cacheOnlyJdbcCompositionReplaysWithoutOpeningAnotherConnectionAndCasMixRollsBack() throws Exception {
        var execution = new ExecutionTransactionFixture(); var contract = TransactionInvocation.contract(Cached.class); var jdbc = policy();
        try (var transaction = jdbc.bind(contract, CachedImplementation.class, Cached.class); var cache = execution.policy.bind(contract, CachedImplementation.class, Cached.class)) {
            var binding = TransactionInvocation.compose(cache, transaction);
            assertEquals("cached", TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { update(current().connection()); return "cached"; }));
            assertEquals("cached", TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> fail("cached JDBC body")));
        }
        assertEquals(1, amount()); assertEquals(1, opens.get());
        var otherExecution = new ExecutionTransactionFixture();
        try (var transaction = jdbc.bind(contract, CachedImplementation.class, Cached.class); var cache = otherExecution.policy.bind(contract, CachedImplementation.class, Cached.class)) {
            var binding = TransactionInvocation.compose(cache, transaction);
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> {
                update(current().connection());
                var dedup = ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class);
                assertThrows(IllegalStateException.class, () -> dedup.compareAndSet("forbidden", 0, "value")); return "caught";
            }));
        }
        assertEquals(1, amount()); assertEquals(1, commits.get()); assertEquals(1, rollbacks.get());
        assertEquals("NOT_FOUND", otherExecution.client.query("E_VALUE", "forbidden", Instant.now().plusSeconds(1)).status());
    }
    @Test void jdbcCommitBeforeFailedCacheWriteIsUnknownWithoutAutomaticDatabaseReexecution() throws Exception {
        var execution = new ExecutionTransactionFixture(); var contract = TransactionInvocation.contract(Cached.class); var jdbc = policy(); var calls = new AtomicInteger();
        try (var transaction = jdbc.bind(contract, CachedImplementation.class, Cached.class); var cache = execution.policy.bind(contract, CachedImplementation.class, Cached.class)) {
            var binding = TransactionInvocation.compose(cache, transaction);
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { calls.incrementAndGet(); update(current().connection()); return "x".repeat(13000); }));
            assertEquals(1, amount());
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { calls.incrementAndGet(); update(current().connection()); return "bad"; }));
        }
        assertEquals(1, calls.get()); assertEquals(1, amount()); assertEquals(1, commits.get()); assertEquals(1, opens.get()); assertEquals(1, closes.get());
    }
    @Test void runtimeNativeProtocolMixRejectsBeforeSecondBeginAndMarksJdbcRollbackOnly() throws Exception {
        var resources = new ManagedResourceTransactionTest.Fixture();
        try (var outer = binding(policy()); var nativeBinding = resources.binding()) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(outer, frame -> {
                update(current().connection());
                assertThrows(IllegalStateException.class, () -> nativeBinding.invoke(J7mContext.currentInvocation(), new Object[]{"input"}, frame.next(() -> fail("second protocol body"), false)));
                return "caught";
            }));
        }
        assertEquals(0, amount()); assertEquals(0, resources.coordinator.index); assertEquals(0, commits.get()); assertEquals(1, rollbacks.get());
    }
    @Test void hostRegistryOrdersReplayBeforeTransactionAndUnsupportedEmbeddedProfileIsClosedWithoutBody() throws Exception {
        var jdbc = policy(); var execution = new ExecutionTransactionFixture();
        var options = new ManagedHostingOptions(new ManagedHostBindings(Map.of()), List.of(jdbc, execution.policy));
        assertEquals(List.of(ManagedInvocationPolicy.Phase.REPLAY, ManagedInvocationPolicy.Phase.TRANSACTION), options.policies().stream().map(ManagedInvocationPolicy::phase).toList());
        assertEquals(Set.of(JdbcTransaction.class), jdbc.annotationTypes());
        assertThrows(IllegalArgumentException.class, () -> new ManagedJdbcTransactionPolicy.Registration(this::owned, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ManagedJdbcTransactionPolicy(Map.of("bad name", new ManagedJdbcTransactionPolicy.Registration(this::owned, Duration.ofSeconds(1)))));
        Connection embedded = DriverManager.getConnection("jdbc:h2:mem:unqualified", "sa", "");
        var unqualified = new ManagedJdbcTransactionPolicy(Map.of("default", new ManagedJdbcTransactionPolicy.Registration(() -> embedded, Duration.ofSeconds(1))));
        try (var binding = binding(unqualified)) { assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> fail("unqualified profile body"))); }
        assertTrue(embedded.isClosed()); assertEquals(0, opens.get());
    }
    @Test void statementExecutionCannotExtendExplicitShorterQueryTimeout() throws Exception {
        try (var raw = raw(); var statement = raw.createStatement()) {
            statement.setQueryTimeout(1); try (var rows = statement.executeQuery("SELECT 1")) { assertTrue(rows.next()); }
            assertEquals(1, statement.getQueryTimeout(), "Raw qualified H2 preserves the application timeout");
        }
        try (var binding = binding(policy())) {
            assertEquals("bounded", TransactionInvocation.invoke(binding, frame -> {
                try (var statement = current().connection().createStatement()) {
                    statement.setQueryTimeout(1); try (var rows = statement.executeQuery("SELECT 1")) { assertTrue(rows.next()); }
                    assertEquals(1, statement.getQueryTimeout(), "Execution must only clamp, never extend a stricter timeout");
                }
                return "bounded";
            }));
        }
    }
    @Test void negativeJdbcTimeoutsAreInvalidBeforeDriverHealthProbeOrExecution() throws Exception {
        try (var raw = raw(); var statement = raw.createStatement()) { assertThrows(SQLException.class, () -> statement.setQueryTimeout(-1)); assertTrue(raw.isValid(1)); }
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                var connection = current().connection();
                try (var statement = connection.createStatement()) {
                    assertAll(() -> assertThrows(SQLException.class, () -> statement.setQueryTimeout(-1)), () -> assertThrows(SQLException.class, () -> connection.isValid(-1)));
                }
                return "invalid";
            }));
        }
    }
    @Test void expiredOriginalBudgetCannotStartCommitFromCleanupBudget() throws Exception {
        var updated = new AtomicBoolean();
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeWithin(Duration.ofMillis(200), binding, frame -> {
                update(current().connection()); updated.set(true);
                while (!J7mContext.currentInvocation().control().remainingBudget().isZero()) Thread.onSpinWait();
                return "expired";
            }));
        }
        assertTrue(updated.get()); assertEquals(0, commits.get()); assertEquals(1, rollbacks.get()); assertEquals(0, amount()); assertEquals(1, closes.get());
    }
    @Test void callerFailureRollsBackApplicationInboxAndOutboxOnTheSameConnection() throws Exception {
        try (var binding = binding(policy())) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                var connection = current().connection(); update(connection); var delivery = new JdbcDelivery("tenant", null);
                delivery.enqueue(connection, "rollback", "account", new TracedQueue.Message(TraceCarrier.empty(), new byte[]{1}), Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
                delivery.consume(connection, "rollback-inbox", TracedQueue.encode(new TracedQueue.Message(TraceCarrier.empty(), new byte[]{2})), (same, payload) -> update(same), Instant.EPOCH);
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "private rollback reason");
            }));
        }
        assertEquals(0, amount());
        try (var connection = raw(); var statement = connection.createStatement()) {
            for (String table : List.of("j7m_inbox", "j7m_outbox", "j7m_outbox_aggregate")) try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
        assertEquals(0, commits.get()); assertEquals(1, rollbacks.get()); assertEquals(1, closes.get());
    }
    @Test void missingScopeUnknownProfileMixedProtocolAndLiveReturnRejectBeforeOpening() throws Exception {
        var policy = policy();
        assertThrows(IllegalArgumentException.class, () -> policy.validate(TransactionInvocation.contract(Mixed.class), Mixed.class, Mixed.class));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(TransactionInvocation.contract(LiveResult.class), LiveResult.class, LiveResult.class));
        var other = new ManagedJdbcTransactionPolicy(Map.of("default", new ManagedJdbcTransactionPolicy.Registration(this::owned, Duration.ofSeconds(1))));
        assertThrows(IllegalArgumentException.class, () -> other.validate(TransactionInvocation.contract(Other.class), Other.class, Other.class));
        try (var binding = binding(policy)) { assertThrows(IllegalStateException.class, () -> binding.invoke(InvocationContext.empty(), new Object[]{"input"}, () -> fail("body entered"))); }
        assertEquals(0, opens.get());
    }
}
