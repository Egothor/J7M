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

import java.sql.*;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;

/**
 * Invocation-bound local H2 JDBC unit of work. The host owns its one physical connection.
 * Application SQL and JdbcDelivery share one stable guarded Connection. The guard supports only
 * the declared finite H2 data subset, not transaction control, DDL, procedures, vendor unwrapping,
 * savepoints or arbitrary SQL sandboxing. USER work is serial; results must be detached data before
 * settlement. JDBC/LOB/stream handles cannot be used by a codec or after USER seal.
 */
@NoRemoteExport
public final class ManagedJdbcTransactionContext {
    ManagedDeduplicationContext deduplication;
    final String profile;
    final ManagedJdbcTransactionPolicy.Registration registration;
    final InvocationContext invocation;
    private Connection physical;
    private GuardedJdbc guarded;
    private volatile boolean rollbackOnly;
    private boolean sealed;
    private volatile boolean closed;
    private long cleanupStarted;
    private boolean cleanupActive;

    ManagedJdbcTransactionContext(String profile, ManagedJdbcTransactionPolicy.Registration registration, InvocationContext invocation) {
        this.profile = profile; this.registration = registration; this.invocation = invocation;
    }
    /**
     * Returns the same guarded connection for every access in this actual transaction.
     * Application code may close its statements/results, but cannot commit, roll back, configure or
     * close this connection. Inbox/outbox calls use this exact object together with application SQL.
     * @return borrowed stable guarded connection
     * @throws SQLException outside the owning open USER scope or before successful admission
     */
    public Connection connection() throws SQLException { user(); return guarded.connection(); }
    /**
     * Marks the entire REQUIRED unit of work rollback-only; no nested caller can clear the flag.
     * @throws SQLException outside the owning open USER phase
     */
    public void setRollbackOnly() throws SQLException { user(); rollbackOnly = true; }
    /**
     * Returns whether the transaction must roll back rather than commit.
     * @return sticky rollback-only state
     */
    public boolean rollbackOnly() { return rollbackOnly; }
    void user() throws SQLException { userCleanup(); TransactionPolicySupport.deadline(invocation); }
    void userCleanup() throws SQLException {
        if (!ManagedInvocationLifetime.currentUserWorkOpen()) throw unavailable();
        if (ManagedInvocationLifetime.currentContext(ManagedJdbcTransactionContext.class) != this
                || !ManagedInvocationLifetime.currentUserWorkOpen() || sealed || closed || guarded == null) throw unavailable();
    }
    void open() throws SQLException {
        physical = registration.connections().open();
        if (physical == null) throw unavailable();
        physical.setNetworkTimeout(Runnable::run, milliseconds(invocation.control().remainingBudget()));
        var metadata = physical.getMetaData();
        if (!metadata.getDatabaseProductName().equals("H2") || !metadata.getDatabaseProductVersion().startsWith("2.3.232")
                || !metadata.getURL().startsWith("jdbc:h2:tcp://") || !physical.getAutoCommit()) throw unavailable();
        physical.setReadOnly(false); physical.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); physical.setAutoCommit(false);
        guarded = new GuardedJdbc(this, physical);
    }
    void seal() { sealed = true; }
    boolean opened() { return guarded != null; }
    boolean closed() { return closed; }
    void failNested() { rollbackOnly = true; }
    void commit() throws SQLException {
        physical.setNetworkTimeout(Runnable::run, milliseconds(invocation.control().remainingBudget()));
        TransactionPolicySupport.deadline(invocation); physical.commit();
    }
    void rollback() throws SQLException {
        if (physical != null) { cleanupTimeout(); if (!physical.getAutoCommit()) physical.rollback(); }
    }
    private void cleanupTimeout() throws SQLException {
        if (!cleanupActive) { cleanupStarted = System.nanoTime(); cleanupActive = true; }
        long remaining = Math.max(1, registration.cleanupBudget().toNanos() - Math.max(0, System.nanoTime() - cleanupStarted));
        physical.setNetworkTimeout(Runnable::run, milliseconds(java.time.Duration.ofNanos(remaining)));
    }
    private static int milliseconds(java.time.Duration value) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, value.toMillis() + (value.getNano() % 1000000 == 0 ? 0 : 1)));
    }
    void dispose() throws SQLException {
        if (closed) return; closed = true;
        SQLException failure = null;
        try { if (guarded != null) guarded.dispose(); } catch (Exception cleanup) { failure = unavailable(); }
        if (physical != null) {
            try { cleanupTimeout(); } catch (Exception cleanup) { if (failure == null) failure = unavailable(); }
            try { physical.close(); } catch (Exception cleanup) { if (failure == null) failure = unavailable(); }
        }
        if (failure != null) throw unavailable();
    }
    static SQLException unavailable() { return new SQLException("Managed JDBC transaction unavailable", "25000"); }
}
