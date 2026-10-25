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
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedInvocationPolicy;
import org.j7m.middleware.application.components.ManagedPolicyMetadata;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.transport.api.*;

/**
 * Trusted durable replay adapter for authenticated managed method bodies.
 * The borrowed D12 client fixes the authenticated tenant partition; replay identities additionally
 * bind the actual principal, trust domain and full versioned method contract. Security precedes
 * this REPLAY phase on every attempt. Decoding and pure canonical codec functions precede claims;
 * this adapter does not promise exactly-once external effects or suppress all application code.
 * Retained native identities are finite, never recycled automatically, and expiry never reexecutes.
 */
public final class ManagedDeduplicationPolicy implements ManagedInvocationPolicy {
    /**
     * Finite host policy configuration; backend and codec are borrowed, never closed here.
     * @param backend scope-authorized D12 facade
     * @param codec explicit bounded data-only codec
     * @param retention retained outcome duration, positive milliseconds at most one day
     * @param cleanupBudget bounded UNKNOWN-only cleanup budget, positive milliseconds at most30seconds
     */
    public record Registration(DeduplicatedRpc backend, ManagedPayloadCodec codec, Duration retention, Duration cleanupBudget) {
        /**
         * Validates native duration limits without silently truncating submillisecond durations.
         */
        public Registration {
            Objects.requireNonNull(backend); Objects.requireNonNull(codec);
            duration(retention, Duration.ofDays(1)); duration(cleanupBudget, Duration.ofSeconds(30));
        }
        /**
         * Uses a two-second UNKNOWN-only cleanup budget.
         * @param backend scope-authorized backend
         * @param codec bounded data-only codec
         * @param retention retained outcome duration
         */
        public Registration(DeduplicatedRpc backend, ManagedPayloadCodec codec, Duration retention) {
            this(backend, codec, retention, Duration.ofSeconds(2));
        }
        private static void duration(Duration value, Duration maximum) {
            if (value == null || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(maximum) > 0
                    || value.getNano() % 1000000 != 0) throw new IllegalArgumentException("Invalid deduplication duration");
        }
    }
    private record Shape(Registration policy, int key, Method method, Class<?> result) { }
    private static final Set<MiddlewareErrorCode> KNOWN_ERRORS = Set.of(MiddlewareErrorCode.INVALID_ARGUMENT,
            MiddlewareErrorCode.NOT_FOUND, MiddlewareErrorCode.PERMISSION_DENIED, MiddlewareErrorCode.UNAUTHENTICATED);
    private static final Set<String> STATUSES = Set.of("COMPLETED", "ERROR", "CONFLICT", "EXPIRED", "UNKNOWN",
            "OVERLOADED", "FENCED", "EXTERNAL_IN_PROGRESS", "IN_PROGRESS");
    private final Map<String, Registration> policies;

    /**
     * Installs at most256 named trusted registrations; applications cannot create capabilities by annotation.
     * @param policies immutable finite policy registry, IDs matching letters/digits/underscore/dot/hyphen
     * @throws IllegalArgumentException for invalid names or capacity
     */
    public ManagedDeduplicationPolicy(Map<String, Registration> policies) {
        if (policies == null || policies.size() > 256) throw new IllegalArgumentException("Invalid deduplication registry capacity");
        for (String name : policies.keySet()) if (!name.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid deduplication policy name");
        this.policies = Map.copyOf(policies);
    }
    /**
     * Returns the fixed replay phase after authorization and ownership.
     * @return replay phase
     */
    @Override public Phase phase() { return Phase.REPLAY; }
    /**
     * Returns exactly the behaviors implemented by this trusted adapter.
     * @return immutable deduplication and key marker types
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(Deduplicated.class, IdempotencyKey.class); }
    /**
     * Validates complete effective annotation lineage before application construction.
     * @param contract full public identity
     * @param implementation actual implementation
     * @param businessInterface declared public contract
     * @throws IllegalArgumentException for missing/ambiguous keys, unknown policies or unsupported OneWay
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        shape(contract, implementation, businessInterface);
    }
    private Shape shape(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        var annotation = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, Deduplicated.class);
        Method method = ManagedPolicyMetadata.method(contract, businessInterface);
        Set<Integer> keys = new HashSet<>();
        for (var declaration : ManagedPolicyMetadata.declarations(contract, implementation, businessInterface)) {
            if (declaration instanceof Method candidate) {
                var parameters = candidate.getParameters();
                for (int i = 0; i < parameters.length; i++) if (parameters[i].isAnnotationPresent(IdempotencyKey.class)) keys.add(i);
            }
        }
        if (annotation == null) {
            if (!keys.isEmpty()) throw new IllegalArgumentException("Idempotency key requires deduplication");
            return null;
        }
        Registration policy = policies.get(annotation.policy());
        if (policy == null || keys.size() != 1 || method.getParameterTypes()[keys.iterator().next()] != String.class
                || ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, OneWay.class) != null) {
            throw new IllegalArgumentException("Unsupported deduplication contract");
        }
        Class<?> result = CompletionStage.class.isAssignableFrom(method.getReturnType())
                ? AsyncMethodDescriptor.fromMethod(method).payloadType() : method.getReturnType();
        for (Class<?> parameter : method.getParameterTypes()) policy.codec.validateDeclaredType(parameter);
        policy.codec.validateDeclaredType(result);
        return new Shape(policy, keys.iterator().next(), method, result);
    }
    /**
     * Binds an immutable export policy; closing revokes later invocations but does not delete journals.
     * @param contract verified versioned method
     * @param implementation actual implementation
     * @param businessInterface declared interface
     * @return owned revocable policy binding
     */
    @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        Shape shape = shape(contract, implementation, businessInterface); var closed = new AtomicBoolean();
        return new Binding() {
            @Override public Object invoke(InvocationContext context, Object[] arguments, Proceed next) throws Exception {
                if (closed.get()) throw new IllegalStateException("Deduplication binding closed");
                if (shape == null) return next.invoke();
                if (!next.supportsResultFinalizers()) throw new IllegalStateException("RESULT_FINALIZATION_UNAVAILABLE");
                if (context.security().principal().id().equals("anonymous")) throw safe(MiddlewareErrorCode.UNAUTHENTICATED);
                if (arguments == null || arguments.length != shape.method.getParameterCount()
                        || !(arguments[shape.key] instanceof String key) || key.isBlank() || key.length() > 128 || key.indexOf(0) >= 0) throw safe(MiddlewareErrorCode.INVALID_ARGUMENT);
                String request = identity(contract, context, key);
                String hash = shape.policy.codec.inputHash(contract, List.of(shape.method.getParameterTypes()), arguments);
                var state = new Completion(shape, context, request, hash);
                next.addResultFinalizer(state::finish);
                ExecutionResult claim;
                try { claim = shape.policy.backend.claim("claim-" + request, request, hash, shape.policy.retention.toMillis(), true, deadline(context)); }
                catch (Exception failure) { state.status = "UNKNOWN"; throw safe(MiddlewareErrorCode.UNAVAILABLE); }
                state.status = nativeStatus(claim.status());
                if (claim.status().equals("COMPLETED")) {
                    try { return shape.policy.codec.decode(decode(claim.fields().get("result")), shape.result); }
                    catch (RuntimeException corrupt) { state.status = "UNKNOWN"; throw safe(MiddlewareErrorCode.CODEC_ERROR); }
                }
                if (claim.status().equals("ERROR")) {
                    MiddlewareException error;
                    try { error = replayError(shape.policy.codec, claim.fields().get("error")); }
                    catch (RuntimeException corrupt) { state.status = "UNKNOWN"; throw safe(MiddlewareErrorCode.CODEC_ERROR); }
                    throw error;
                }
                if (!claim.status().equals("EXTERNAL_IN_PROGRESS") || !"true".equals(claim.fields().get("acquired"))) throw safe(MiddlewareErrorCode.UNAVAILABLE);
                try { state.fence = Long.parseLong(claim.fields().get("fence")); if (state.fence <= 0) throw new NumberFormatException("invalid fence"); }
                catch (RuntimeException malformed) { state.status = "UNKNOWN"; throw safe(MiddlewareErrorCode.UNAVAILABLE); }
                state.acquired = true;
                state.context = new ManagedDeduplicationContext(request, state.fence, context.control());
                next.putContext(ManagedDeduplicationContext.class, state.context);
                deadline(context);
                return next.invoke();
            }
            @Override public void close() { closed.set(true); }
        };
    }
    private static final class Completion {
        private final Shape shape; private final InvocationContext invocation; private final String request, hash;
        private String status = "UNKNOWN"; private boolean acquired; private long fence;
        private ManagedDeduplicationContext context;
        Completion(Shape shape, InvocationContext invocation, String request, String hash) {
            this.shape = shape; this.invocation = invocation; this.request = request; this.hash = hash;
        }
        CompletionStage<InvocationResult> finish(InvocationResult result) {
            if (!acquired) return CompletableFuture.completedFuture(new InvocationResult(result.value(), result.failure(), Map.of("status", status)));
            List<String> effects = context.sealAndSnapshot();
            try {
                Throwable failure = result.failure();
                if (failure != null && (failure.getClass() != MiddlewareException.class || !KNOWN_ERRORS.contains(((MiddlewareException) failure).code()))) return unknown();
                Instant end = deadline(invocation);
                String error = failure == null ? "" : Base64.getEncoder().encodeToString(shape.policy.codec.encode(((MiddlewareException) failure).code().name(), String.class));
                String value = failure == null ? Base64.getEncoder().encodeToString(shape.policy.codec.encode(result.value(), shape.result)) : "";
                deadline(invocation);
                ExecutionResult completed = shape.policy.backend.complete("complete-" + request, request, hash, fence,
                        value, error, failure == null ? effects : List.of(), end);
                String completedStatus = nativeStatus(completed.status());
                if (!completedStatus.equals(failure == null ? "COMPLETED" : "ERROR")) return unknown();
                Throwable safeFailure = failure == null ? null : safe(((MiddlewareException) failure).code());
                Object materialized = failure == null
                        ? shape.policy.codec.decode(decode(completed.fields().get("result")), shape.result) : null;
                return CompletableFuture.completedFuture(new InvocationResult(materialized, safeFailure, Map.of("status", completedStatus)));
            } catch (Exception failure) { return unknown(); }
        }
        private CompletionStage<InvocationResult> unknown() {
            try { shape.policy.backend.unknown("unknown-" + request, request, hash, fence, Instant.now().plus(shape.policy.cleanupBudget)); }
            catch (Exception ignored) { /* An unresolved external claim remains publicly UNKNOWN without this best-effort marker. */ }
            return CompletableFuture.completedFuture(new InvocationResult(null, safe(MiddlewareErrorCode.UNAVAILABLE), Map.of("status", "UNKNOWN")));
        }
    }
    private static Instant deadline(InvocationContext invocation) {
        Duration remaining = invocation.control().remainingBudget();
        if (invocation.control().cancellationRequested() || remaining.isZero() || remaining.isNegative()) throw safe(MiddlewareErrorCode.DEADLINE_EXCEEDED);
        return Instant.now().plus(remaining.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : remaining);
    }
    private static MiddlewareException safe(MiddlewareErrorCode code) { return MiddlewareException.of(code, "Managed durable invocation " + code.name()); }
    private static MiddlewareException replayError(ManagedPayloadCodec codec, String frame) {
        try {
            MiddlewareErrorCode code = MiddlewareErrorCode.valueOf((String) codec.decode(decode(frame), String.class));
            if (!KNOWN_ERRORS.contains(code)) throw safe(MiddlewareErrorCode.CODEC_ERROR);
            return safe(code);
        } catch (RuntimeException invalid) { throw safe(MiddlewareErrorCode.CODEC_ERROR); }
    }
    private static byte[] decode(String frame) {
        if (frame == null || frame.length() > 16384) throw safe(MiddlewareErrorCode.CODEC_ERROR);
        return Base64.getDecoder().decode(frame);
    }
    private static String nativeStatus(String value) { return STATUSES.contains(value) ? value : "UNKNOWN"; }
    private static String identity(ManagedServiceContract contract, InvocationContext context, String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of("j7m-managed-dedup-v1", context.security().trustDomain().id(), context.security().principal().id(),
                    contract.id(), Integer.toString(contract.version()), contract.method().declaringType(), contract.method().name(),
                    contract.method().parameterTypes().toString(), contract.method().returnType(), key)) {
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(part.length()).array());
                for (int i = 0; i < part.length(); i++) { char character = part.charAt(i); digest.update((byte) (character >>> 8)); digest.update((byte) character); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
}
