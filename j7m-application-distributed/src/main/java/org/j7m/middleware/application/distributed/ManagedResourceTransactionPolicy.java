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
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.ResourceTransaction;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.transport.api.InvocationResult;

/**
 * Trusted REQUIRED adapter for existing native resource strict-2PL/2PC.
 * Participating endpoints are borrowed host capabilities; ordinary callers receive only a bounded
 * invocation context. A prepared participant never treats a timeout as an abort decision. JDBC,
 * queues, arbitrary external effects and the separate D12 protected map are not enlisted here.
 */
public final class ManagedResourceTransactionPolicy implements ManagedInvocationPolicy {
    /**
     * Borrowed finite participating resource configuration.
     * @param coordinator authorized durable coordinator endpoint
     * @param participants at most64 stable ASCII IDs mapped to distinct independent authorized groups; IDs have at most64 characters
     * @param cleanupBudget positive bounded abort/recovery cleanup duration at most30seconds
     */
    public record Registration(ResourceEndpoint coordinator, Map<String, ResourceEndpoint> participants, Duration cleanupBudget) {
        /**
         * Copies and validates capabilities without network operations.
         */
        public Registration {
            Objects.requireNonNull(coordinator); TransactionPolicySupport.names(participants); TransactionPolicySupport.cleanup(cleanupBudget);
            if (participants.isEmpty() || participants.size() > 64) throw new IllegalArgumentException("Invalid resource participant capacity");
            participants = Map.copyOf(participants);
            Set<ResourceEndpoint> distinct = Collections.newSetFromMap(new IdentityHashMap<>()); distinct.add(coordinator);
            for (ResourceEndpoint participant : participants.values()) if (!distinct.add(participant)) throw new IllegalArgumentException("Resource groups must have distinct endpoints");
        }
        /**
         * Returns only the finite participant count, never endpoint/factory diagnostics.
         * @return credential-free registration description
         */
        @Override public String toString() { return "ResourceTransactionRegistration[participants=" + participants.size() + "]"; }
    }
    private final Map<String, Registration> profiles;
    /**
     * Registers trusted policies without opening transactions or owning supplied endpoints.
     * @param profiles at most256 named immutable registrations
     * @throws IllegalArgumentException for invalid names/capacity
     */
    public ManagedResourceTransactionPolicy(Map<String, Registration> profiles) { TransactionPolicySupport.names(profiles); this.profiles = Map.copyOf(profiles); }
    /**
     * Returns the fixed transaction phase inside replay and outside application code.
     * @return transaction phase
     */
    @Override public Phase phase() { return Phase.TRANSACTION; }
    /**
     * Returns the exact implemented annotation behavior.
     * @return resource transaction marker
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(ResourceTransaction.class); }
    /**
     * Validates full lineage and rejects JDBC/native mixing before application construction.
     * @param contract complete verified method identity
     * @param implementation actual implementation
     * @param businessInterface declared business interface
     * @throws IllegalArgumentException for unsupported metadata/profile
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        TransactionPolicySupport.validateMix(contract, implementation, businessInterface);
        var annotation = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, ResourceTransaction.class);
        if (annotation != null && !profiles.containsKey(annotation.policy())) throw new IllegalArgumentException("Unknown resource transaction profile");
    }
    /**
     * Prepares a revocable immutable method binding; no transaction starts until actual owned admission.
     * @param contract verified method
     * @param implementation implementation class
     * @param businessInterface declared interface
     * @return owned binding; native clients remain borrowed
     */
    @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        validate(contract, implementation, businessInterface);
        var annotation = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, ResourceTransaction.class);
        var closed = new AtomicBoolean();
        return new Binding() {
            @Override public Object invoke(InvocationContext invocation, Object[] arguments, Proceed next) throws Exception {
                if (closed.get()) throw new IllegalStateException("Resource transaction binding closed");
                if (annotation == null) return next.invoke();
                TransactionPolicySupport.boundary(invocation, next);
                var otherProtocol = next.findContext(ManagedJdbcTransactionContext.class);
                if (otherProtocol.isPresent()) { otherProtocol.get().failNested(); throw new IllegalStateException("CROSS_PROTOCOL_TRANSACTION_UNSUPPORTED"); }
                var replay = next.findContext(ManagedDeduplicationContext.class);
                if (replay.isPresent()) replay.get().prohibitProtectedEffects();
                Registration registration = profiles.get(annotation.policy());
                var existing = next.findContext(ManagedResourceTransactionContext.class);
                if (existing.isPresent()) {
                    var context = existing.get();
                    if (!context.profile.equals(annotation.policy()) || context.registration != registration) { context.failNested(); throw new IllegalStateException("REQUIRED_PROFILE_MISMATCH"); }
                    try { return next.invoke(); } catch (Exception | Error failure) { context.failNested(); throw failure; }
                }
                var context = new ManagedResourceTransactionContext(annotation.policy(), registration, invocation);
                context.deduplication = replay.orElse(null);
                next.putContext(ManagedResourceTransactionContext.class, context);
                next.addResultFinalizer(result -> CompletableFuture.completedFuture(finish(context, result)));
                var begun = context.transactions.begin(context.transactionId(), TransactionPolicySupport.deadline(invocation));
                if (!begun.status().equals("OK")) throw TransactionPolicySupport.failure(MiddlewareErrorCode.UNAVAILABLE);
                context.begun = true;
                TransactionPolicySupport.deadline(invocation);
                return next.invoke();
            }
            @Override public void close() { closed.set(true); }
        };
    }
    private static InvocationResult finish(ManagedResourceTransactionContext context, InvocationResult result) {
        Map<String, List<String>> operations = context.seal();
        if (context.deduplication != null && context.deduplication.prohibitedEffectAttempt()) context.failNested();
        if (!context.begun) return TransactionPolicySupport.unknown("IN_DOUBT");
        if (result.failure() != null || context.rollbackOnly() || operations.isEmpty()) return abort(context, result, operations.isEmpty() && !context.rollbackOnly());
        try {
            TransactionPolicySupport.deadline(context.invocation);
        } catch (MiddlewareException expired) { return abort(context, InvocationResult.failure(expired), false); }
        try {
            for (var participant : new TreeMap<>(operations).entrySet()) {
                var prepared = context.transactions.prepare(context.transactionId(), participant.getKey(), participant.getValue(), TransactionPolicySupport.deadline(context.invocation));
                if (!prepared.status().equals("PREPARED")) return abort(context, InvocationResult.failure(TransactionPolicySupport.failure(MiddlewareErrorCode.UNAVAILABLE)), false);
            }
            var committed = context.transactions.commit(context.transactionId(), TransactionPolicySupport.deadline(context.invocation));
            return committed.status().equals("COMMITTED") ? TransactionPolicySupport.outcome(result, "COMMITTED") : TransactionPolicySupport.unknown("IN_DOUBT");
        } catch (Exception uncertain) { return TransactionPolicySupport.unknown("IN_DOUBT"); }
    }
    private static InvocationResult abort(ManagedResourceTransactionContext context, InvocationResult result, boolean noEffect) {
        try {
            var decision = context.transactions.abort(context.transactionId(), Instant.now().plus(context.registration.cleanupBudget()));
            if (!decision.status().equals("ABORTED")) return TransactionPolicySupport.unknown("IN_DOUBT");
            if (result.failure() != null) return TransactionPolicySupport.outcome(result, "ROLLED_BACK");
            return noEffect ? TransactionPolicySupport.outcome(result, "NO_EFFECT") : TransactionPolicySupport.unknown("ROLLED_BACK");
        } catch (Exception uncertain) { return TransactionPolicySupport.unknown("IN_DOUBT"); }
    }
}
