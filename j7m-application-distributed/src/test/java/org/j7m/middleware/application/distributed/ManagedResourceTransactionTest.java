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
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

class ManagedResourceTransactionTest {
    public interface Business { @ResourceTransaction String apply(String input); }
    public static final class Implementation implements Business { public String apply(String input) { return input; } }
    public interface Other { @ResourceTransaction(policy="other") String apply(String input); }
    public interface Mixed { @ResourceTransaction @JdbcTransaction String apply(String input); }
    static final class Endpoint implements ResourceEndpoint {
        ReplicatedResources machine = new ReplicatedResources(); long index; boolean available = true; String loseReply = "";
        @Override public synchronized ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
            ready(); machine.apply(++index, new ResourceCommand(operation, "tenant", request, arguments).encode());
            if (loseReply.equals(operation)) { loseReply = ""; throw new IOException("committed response lost"); }
            return machine.result("tenant", request, index);
        }
        @Override public synchronized ReplicatedResources.Value read(String key, Instant deadline) throws IOException { ready(); return machine.queryAtReadIndex("tenant", key, index); }
        @Override public synchronized ReplicatedResources.Result transaction(String id, Instant deadline) throws IOException { ready(); return machine.transaction("tenant", id, index); }
        @Override public synchronized List<String> prepared(Instant deadline) throws IOException { ready(); return machine.prepared("tenant", index); }
        @Override public synchronized List<String> participants(String id, Instant deadline) throws IOException { ready(); return machine.participants("tenant", id, index); }
        private void ready() throws IOException { if (!available) throw new IOException("quorum unavailable"); }
        void restart() { byte[] snapshot = machine.snapshot(); machine = new ReplicatedResources(); machine.restore(snapshot); }
    }
    static final class Fixture {
        final Endpoint coordinator = new Endpoint(), left = new Endpoint(), right = new Endpoint();
        final ManagedResourceTransactionPolicy.Registration registration = new ManagedResourceTransactionPolicy.Registration(coordinator, Map.of("left", left, "right", right), Duration.ofSeconds(2));
        final ManagedResourceTransactionPolicy policy = new ManagedResourceTransactionPolicy(Map.of("default", registration, "other", registration));
        final RecoverableTransactions recovery = new RecoverableTransactions(coordinator, registration.participants());
        ManagedInvocationPolicy.Binding binding() { return policy.bind(TransactionInvocation.contract(Business.class), Implementation.class, Business.class); }
    }
    private static Instant deadline() { return Instant.now().plusSeconds(3); }
    private static ManagedResourceTransactionContext current() { return ManagedInvocationLifetime.currentContext(ManagedResourceTransactionContext.class); }
    @Test void twoParticipantWritesAndReadYourWritesPublishOnlyAfterUserSettlement() throws Exception {
        var fixture = new Fixture(); var retained = new ManagedResourceTransactionContext[1];
        try (var binding = fixture.binding()) {
            assertEquals("committed", TransactionInvocation.invoke(binding, frame -> {
                var transaction = current(); retained[0] = transaction; assertEquals(32, transaction.transactionId().length());
                assertNull(transaction.read("left", "account")); transaction.write("left", "account", "10");
                assertEquals("10", transaction.read("left", "account").value()); transaction.write("right", "counter", "20");
                assertNull(fixture.left.read("account", deadline())); assertNull(fixture.right.read("counter", deadline()));
                return "committed";
            }));
        }
        assertEquals("10", fixture.left.read("account", deadline()).value()); assertEquals("20", fixture.right.read("counter", deadline()).value());
        assertEquals("COMMITTED", fixture.recovery.status(retained[0].transactionId(), deadline()).status());
        assertThrows(IllegalStateException.class, () -> retained[0].write("left", "late", "bad"));
    }
    @Test void preparedLostReplyAndUnavailableCoordinatorRetainLocksWithoutHeuristicAbort() throws Exception {
        var fixture = new Fixture(); fixture.left.loseReply = "PREPARE"; var id = new AtomicReference<String>();
        try (var binding = fixture.binding()) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                id.set(current().transactionId()); current().write("left", "account", "1"); current().write("right", "account", "2"); return "uncertain";
            }));
        }
        assertEquals("PREPARED", fixture.left.transaction(id.get(), deadline()).status());
        fixture.coordinator.available = false;
        assertThrows(IOException.class, () -> fixture.recovery.recover(id.get(), deadline()));
        assertEquals("CONFLICT", fixture.left.execute("BATCH", "stale-writer", List.of("account", "0", "wrong"), deadline()).status());
        fixture.coordinator.available = true; fixture.coordinator.restart(); fixture.left.restart(); fixture.right.restart();
        assertEquals("IN_DOUBT", fixture.recovery.recover(id.get(), deadline()).status());
        assertEquals("PREPARED", fixture.left.transaction(id.get(), deadline()).status()); assertNull(fixture.left.read("account", deadline()));
        assertEquals("ABORTED", fixture.recovery.abort(id.get(), deadline()).status()); assertTrue(fixture.left.prepared(deadline()).isEmpty());
    }
    @Test void committedDecisionSurvivesAllSnapshotsAndRecoveryCannotDoubleApply() throws Exception {
        var fixture = new Fixture(); fixture.coordinator.loseReply = "DECIDE"; var id = new AtomicReference<String>();
        try (var binding = fixture.binding()) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> {
                id.set(current().transactionId()); current().write("left", "one", "left"); current().write("right", "two", "right"); return "reply-lost";
            }));
        }
        fixture.coordinator.restart(); fixture.left.restart(); fixture.right.restart();
        assertEquals("COMMITTED", fixture.recovery.recover(id.get(), deadline()).status());
        var first = fixture.left.read("one", deadline()); assertEquals("left", first.value());
        assertEquals("COMMITTED", fixture.recovery.recover(id.get(), deadline()).status()); assertEquals(first, fixture.left.read("one", deadline()));
        assertEquals("right", fixture.right.read("two", deadline()).value());
    }
    @Test void rangeLockAndBoundedContextPreventPhantomsAndInvalidParticipantEffects() throws Exception {
        var fixture = new Fixture();
        try (var binding = fixture.binding()) {
            assertEquals("range", TransactionInvocation.invoke(binding, frame -> {
                current().lockRange("left", "orders/"); current().lockRange("left", "orders/");
                assertEquals("CONFLICT", fixture.left.execute("BATCH", "phantom", List.of("orders/new", "0", "bad"), deadline()).status());
                assertThrows(MiddlewareException.class, () -> current().write("unknown", "key", "bad"));
                assertThrows(MiddlewareException.class, () -> current().write("left", "*", "bad"));
                assertThrows(MiddlewareException.class, () -> current().write("left", "value", "x".repeat(16385)));
                current().write("left", "orders/good", "safe"); return "range";
            }));
        }
        assertNull(fixture.left.read("orders/new", deadline())); assertEquals("safe", fixture.left.read("orders/good", deadline()).value());
    }
    @Test void finiteCapacityRejectsBeforeAdditionalLocksAndExplicitRollbackOnlyCannotPublishWrites() throws Exception {
        var fixture = new Fixture();
        try (var binding = fixture.binding()) {
            assertEquals("bounded", TransactionInvocation.invoke(binding, frame -> {
                for (int i = 0; i < 32; i++) current().write("left", "key-" + i, "value");
                assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(MiddlewareException.class, () -> current().write("left", "overflow", "bad")).code());
                return "bounded";
            }));
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(binding, frame -> { current().write("left", "discard", "bad"); current().setRollbackOnly(); return "rollback"; }));
        }
        assertEquals("value", fixture.left.read("key-31", deadline()).value()); assertNull(fixture.left.read("overflow", deadline())); assertNull(fixture.left.read("discard", deadline()));
        assertTrue(fixture.left.prepared(deadline()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ManagedResourceTransactionPolicy.Registration(fixture.coordinator, Map.of(), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedResourceTransactionPolicy.Registration(fixture.coordinator, Map.of("left", fixture.coordinator), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedResourceTransactionPolicy.Registration(fixture.coordinator, Map.of("left", fixture.left, "right", fixture.left), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedResourceTransactionPolicy.Registration(fixture.coordinator, Map.of("left", fixture.left), Duration.ZERO));
        try (var binding = fixture.binding()) { assertThrows(IllegalStateException.class, () -> binding.invoke(InvocationContext.empty(), new Object[]{"input"}, () -> fail("missing owned scope"))); }
    }
    @Test void runtimeJdbcMixRejectsBeforeOpeningConnectionAndNativeWritesRollBack() throws Exception {
        var fixture = new Fixture(); var opens = new AtomicInteger();
        var jdbc = new ManagedJdbcTransactionPolicy(Map.of("default", new ManagedJdbcTransactionPolicy.Registration(() -> { opens.incrementAndGet(); throw new java.sql.SQLException("must not open"); }, Duration.ofSeconds(1))));
        try (var outer = fixture.binding(); var nested = jdbc.bind(TransactionInvocation.contract(ManagedJdbcTransactionIT.Business.class), ManagedJdbcTransactionIT.Implementation.class, ManagedJdbcTransactionIT.Business.class)) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(outer, frame -> {
                current().write("left", "discard", "bad");
                assertThrows(IllegalStateException.class, () -> nested.invoke(J7mContext.currentInvocation(), new Object[]{"input"}, frame.next(() -> fail("mixed JDBC body"), false)));
                return "caught";
            }));
        }
        assertEquals(0, opens.get()); assertNull(fixture.left.read("discard", deadline())); assertTrue(fixture.left.prepared(deadline()).isEmpty());
    }
    @Test void expiredOriginalBudgetAbortsPreparingTransactionWithoutStartingCommit() throws Exception {
        var fixture = new Fixture(); var id = new AtomicReference<String>();
        try (var binding = fixture.binding()) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeWithin(Duration.ofMillis(100), binding, frame -> {
                id.set(current().transactionId()); current().write("left", "expired", "must-not-publish");
                while (!J7mContext.currentInvocation().control().remainingBudget().isZero()) Thread.onSpinWait(); return "expired";
            }));
        }
        assertEquals("ABORTED", fixture.recovery.status(id.get(), deadline()).status()); assertNull(fixture.left.read("expired", deadline())); assertTrue(fixture.left.prepared(deadline()).isEmpty());
    }
    @Test void requiredNestedFailureIsStickyAndProfileMismatchNeverStartsAnotherTransaction() throws Exception {
        var fixture = new Fixture();
        try (var outer = fixture.binding(); var nested = fixture.binding(); var other = fixture.policy.bind(TransactionInvocation.contract(Other.class), Other.class, Other.class)) {
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invoke(outer, frame -> {
                String id = current().transactionId(); current().write("left", "one", "discard");
                try { nested.invoke(J7mContext.currentInvocation(), new Object[]{"input"}, frame.next(() -> { assertEquals(id, current().transactionId()); throw new IllegalArgumentException("nested"); }, false)); }
                catch (IllegalArgumentException expected) { assertTrue(current().rollbackOnly()); }
                assertThrows(IllegalStateException.class, () -> other.invoke(J7mContext.currentInvocation(), new Object[]{"input"}, frame.next(() -> fail("wrong profile"), false)));
                return "caught";
            }));
        }
        assertNull(fixture.left.read("one", deadline())); assertTrue(fixture.left.prepared(deadline()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fixture.policy.validate(TransactionInvocation.contract(Mixed.class), Mixed.class, Mixed.class));
        assertEquals(ManagedInvocationPolicy.Phase.TRANSACTION, fixture.policy.phase());
        assertEquals(Set.of(ResourceTransaction.class), fixture.policy.annotationTypes());
    }
}
