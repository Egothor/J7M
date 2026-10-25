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
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.integrations.*;
import org.j7m.middleware.transport.api.AsyncMethodDescriptor;

/**
 * Local SQL inbox policy inside the actual A11 JDBC transaction. The marker and application SQL
 * effect use exactly the same guarded Connection. An identical committed delivery skips only the
 * handler body after current authorization; receipt/generation changes never create a new SQL ID.
 * Async work is finalized by the existing lifetime/JDBC policy, never by blocking inside Mutation.
 * External non-DB effects are outside this protocol and must not be claimed as exactly once.
 */
public final class ManagedInboxPolicy implements ManagedInvocationPolicy {
    /**
     * Trusted SQL namespace and exact participating JDBC capability.
     * @param namespace stable logical inbox namespace
     * @param tenant authorized source tenant
     * @param jdbcPolicy exact A11 profile name
     * @param jdbcRegistration exact borrowed registration instance installed in the JDBC policy
     * @param telemetry borrowed optional tracing integration
     */
    public record Registration(String namespace, String tenant, String jdbcPolicy,
            ManagedJdbcTransactionPolicy.Registration jdbcRegistration, Observability telemetry) {
        /**
         * Validates identity without opening connections.
         */
        public Registration {
            DeliveryIdentities.name(namespace); DeliveryIdentities.scope(tenant); DeliveryIdentities.name(jdbcPolicy); Objects.requireNonNull(jdbcRegistration);
        }
        /**
         * Omits factory and tenant diagnostics.
         * @return safe fixed registration identity
         */
        @Override public String toString() { return "InboxRegistration[H2_TCP_2_3_232]"; }
    }
    private final Map<String, Registration> profiles;
    /**
     * Registers at most256 finite explicitly trusted inbox profiles.
     * @param profiles named immutable SQL capabilities
     * @throws IllegalArgumentException for invalid registry names/capacity
     */
    public ManagedInboxPolicy(Map<String, Registration> profiles) { TransactionPolicySupport.names(profiles); this.profiles = Map.copyOf(profiles); }
    /**
     * Runs inside the participating JDBC transaction and all earlier security/replay policies.
     * @return APPLICATION phase; no new global phase is introduced
     */
    @Override public Phase phase() { return Phase.APPLICATION; }
    /**
     * Declares only the actual SQL inbox capability.
     * @return exact immutable marker set
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(InboxHandler.class); }
    /**
     * Rejects unsupported callback shapes and missing/mismatched JDBC metadata before constructors.
     * @param contract complete generated hosted contract
     * @param implementation actual component
     * @param businessInterface actual hosted component or ordinary RPC interface
     * @throws IllegalArgumentException for unsupported shape, profile or nonhosted entry
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        var marker = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, InboxHandler.class);
        if (marker == null) return;
        Registration profile = profiles.get(marker.policy());
        var transaction = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, JdbcTransaction.class);
        var consumer = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, DurableQueueConsumer.class);
        var dlq = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, DeadLetterHandler.class);
        if (profile == null || transaction == null || !transaction.policy().equals(profile.jdbcPolicy()) || (consumer == null) == (dlq == null)
                || businessInterface != implementation || !contract.id().startsWith("hosted.")) throw new IllegalArgumentException("Inbox requires actual hosted JDBC queue entry");
        var method = ManagedPolicyMetadata.method(contract, businessInterface);
        if (method.getReturnType() != void.class && (!CompletionStage.class.isAssignableFrom(method.getReturnType())
                || AsyncMethodDescriptor.fromMethod(method).payloadType() != Void.class)) throw new IllegalArgumentException("Inbox replay has no arbitrary application result");
    }
    /**
     * Binds SQL duplicate suppression without opening a second connection.
     * @param contract verified full contract
     * @param implementation concrete component
     * @param businessInterface declared business surface
     * @return owned revocable inbox policy
     */
    @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        validate(contract, implementation, businessInterface);
        var marker = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, InboxHandler.class);
        Registration profile = marker == null ? null : profiles.get(marker.policy()); var closed = new AtomicBoolean();
        return new Binding() {
            @Override public Object invoke(InvocationContext invocation, Object[] arguments, Proceed next) throws Exception {
                if (closed.get()) throw new IllegalStateException("Inbox binding closed");
                if (profile == null) return next.invoke();
                TransactionPolicySupport.boundary(invocation, next);
                var jdbc = next.findContext(ManagedJdbcTransactionContext.class).orElseThrow(() -> new IllegalStateException("Actual JDBC context required"));
                var delivery = next.findContext(ManagedQueueDeliveryContext.class).orElseThrow(() -> new IllegalStateException("Actual queue delivery required"));
                if (!jdbc.profile.equals(profile.jdbcPolicy()) || jdbc.registration != profile.jdbcRegistration() || !delivery.profile.tenant().equals(profile.tenant())
                        || delivery.profile.strategy() != QueueConsumerProfile.Strategy.INBOX) { jdbc.failNested(); throw new IllegalStateException("Inbox ownership profile mismatch"); }
                var source = delivery.delivery();
                String scope = "inbox-" + DeliveryIdentities.digest(profile.namespace(), profile.tenant(), delivery.profile.source().partitionId(), source.queue(), delivery.handler, Integer.toString(delivery.version));
                JdbcDelivery inbox = new JdbcDelivery(scope, profile.telemetry()); Object[] value = new Object[1]; Exception[] bodyFailure = new Exception[1];
                try {
                    boolean fresh = inbox.consume(jdbc.connection(), delivery.inboxId(), delivery.envelope(), (sameConnection, payload) -> {
                        try { value[0] = next.invoke(); }
                        catch (Exception failure) { bodyFailure[0] = failure; throw new SQLException("Inbox handler failed"); }
                    }, Instant.now());
                    return fresh ? value[0] : null;
                } catch (SQLException failed) {
                    jdbc.failNested(); if (bodyFailure[0] != null) throw bodyFailure[0];
                    throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Inbox transaction outcome unavailable");
                } catch (RuntimeException failed) { jdbc.failNested(); throw failed; }
            }
            @Override public void close() { closed.set(true); }
        };
    }
}
