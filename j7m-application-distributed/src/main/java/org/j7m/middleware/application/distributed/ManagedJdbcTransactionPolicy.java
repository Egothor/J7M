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

import java.lang.annotation.Annotation;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.JdbcTransaction;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.integrations.JdbcDelivery;
import org.j7m.middleware.transport.api.*;

/**
 * Trusted same-invocation REQUIRED local H2 TCP transaction policy.
 * Exactly one fresh physical connection belongs to the outer invocation and remains owned through
 * actual tracked USER completion. Commit reply loss is UNKNOWN, not proof of rollback. This adapter
 * neither enlists a database in native resource 2PC nor provides automatic DB-plus-queue atomicity.
 */
public final class ManagedJdbcTransactionPolicy implements ManagedInvocationPolicy {
    /**
     * Qualified H2 TCP connection ownership configuration.
     * The factory supplies a fresh independently owned autocommit connection. The supported profile
     * is H2 TCP2.3.232 with SERIALIZABLE isolation; connection/socket timeouts remain host driver
     * configuration. A noncooperative driver retains physical ownership until its operation exits.
     * @param connections borrowed fresh-connection factory, never closed by the policy
     * @param cleanupBudget positive cleanup budget at most30seconds; never authorizes a new commit
     */
    public record Registration(JdbcDelivery.Connections connections, Duration cleanupBudget) {
        /**
         * Validates finite settings without opening a connection.
         */
        public Registration { Objects.requireNonNull(connections); TransactionPolicySupport.cleanup(cleanupBudget); }
        /**
         * Returns a credential-free diagnostic form.
         * @return fixed profile identity without factory/connection strings
         */
        @Override public String toString() { return "JdbcTransactionRegistration[H2_TCP_2_3_232]"; }
    }
    private final Map<String, Registration> profiles;
    /**
     * Registers at most256 finite named local transaction policies.
     * @param profiles borrowed connection configurations
     * @throws IllegalArgumentException for invalid profile names/capacity
     */
    public ManagedJdbcTransactionPolicy(Map<String, Registration> profiles) { TransactionPolicySupport.names(profiles); this.profiles = Map.copyOf(profiles); }
    /**
     * Returns the transaction phase inside durable replay and outside application work.
     * @return transaction phase
     */
    @Override public Phase phase() { return Phase.TRANSACTION; }
    /**
     * Returns the precise annotation capability implemented by this host policy.
     * @return local JDBC marker
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(JdbcTransaction.class); }
    /**
     * Rejects unsupported profile/mixed protocol and live JDBC return contracts before constructors.
     * @param contract complete verified method identity
     * @param implementation actual implementation
     * @param businessInterface declared public interface
     * @throws IllegalArgumentException for unknown profile or unsupported shape
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        TransactionPolicySupport.validateMix(contract, implementation, businessInterface);
        var annotation = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, JdbcTransaction.class);
        if (annotation == null) return;
        if (!profiles.containsKey(annotation.policy())) throw new IllegalArgumentException("Unknown JDBC transaction profile");
        var method = ManagedPolicyMetadata.method(contract, businessInterface);
        Class<?> result = CompletionStage.class.isAssignableFrom(method.getReturnType()) ? AsyncMethodDescriptor.fromMethod(method).payloadType() : method.getReturnType();
        if (result.getPackageName().equals("java.sql") || result == ManagedJdbcTransactionContext.class) throw new IllegalArgumentException("JDBC result must be detached data");
    }
    /**
     * Binds immutable policy state; connection opening waits for actual admitted owned scope.
     * @param contract complete method identity
     * @param implementation actual implementation
     * @param businessInterface declared interface
     * @return revocable binding; closing never commits or deletes durable data
     */
    @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        validate(contract, implementation, businessInterface);
        var annotation = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, JdbcTransaction.class);
        var closed = new AtomicBoolean();
        return new Binding() {
            @Override public Object invoke(InvocationContext invocation, Object[] arguments, Proceed next) throws Exception {
                if (closed.get()) throw new IllegalStateException("JDBC transaction binding closed");
                if (annotation == null) return next.invoke();
                TransactionPolicySupport.boundary(invocation, next);
                var otherProtocol = next.findContext(ManagedResourceTransactionContext.class);
                if (otherProtocol.isPresent()) { otherProtocol.get().failNested(); throw new IllegalStateException("CROSS_PROTOCOL_TRANSACTION_UNSUPPORTED"); }
                var replay = next.findContext(ManagedDeduplicationContext.class);
                if (replay.isPresent()) replay.get().prohibitProtectedEffects();
                Registration registration = profiles.get(annotation.policy());
                var existing = next.findContext(ManagedJdbcTransactionContext.class);
                if (existing.isPresent()) {
                    var context = existing.get();
                    if (!context.profile.equals(annotation.policy()) || context.registration != registration) { context.failNested(); throw new IllegalStateException("REQUIRED_PROFILE_MISMATCH"); }
                    try { return next.invoke(); } catch (Exception | Error failure) { context.failNested(); throw failure; }
                }
                var context = new ManagedJdbcTransactionContext(annotation.policy(), registration, invocation);
                context.deduplication = replay.orElse(null);
                next.putContext(ManagedJdbcTransactionContext.class, context);
                next.addResultFinalizer(result -> CompletableFuture.completedFuture(finish(context, result)));
                context.open(); TransactionPolicySupport.deadline(invocation);
                return next.invoke();
            }
            @Override public void close() { closed.set(true); }
        };
    }
    private static InvocationResult finish(ManagedJdbcTransactionContext context, InvocationResult result) {
        context.seal(); InvocationResult outcome = result;
        if (context.deduplication != null && context.deduplication.prohibitedEffectAttempt()) context.failNested();
        try {
            if (!context.opened()) return TransactionPolicySupport.unknown("UNKNOWN");
            boolean rollback = result.failure() != null || context.rollbackOnly();
            if (!rollback) {
                try { TransactionPolicySupport.deadline(context.invocation); }
                catch (RuntimeException cancelled) { rollback = true; outcome = InvocationResult.failure(cancelled); }
            }
            if (rollback) {
                context.rollback();
                outcome = outcome.failure() == null ? TransactionPolicySupport.unknown("ROLLED_BACK") : TransactionPolicySupport.outcome(outcome, "ROLLED_BACK");
            } else {
                // Once commit has begun, a failed reply cannot establish whether the database committed.
                context.commit(); outcome = TransactionPolicySupport.outcome(outcome, "COMMITTED");
            }
        } catch (Exception uncertain) { outcome = TransactionPolicySupport.unknown("UNKNOWN"); }
        finally {
            try { context.dispose(); } catch (SQLException cleanup) { outcome = TransactionPolicySupport.unknown("UNKNOWN"); }
        }
        return outcome;
    }
}
