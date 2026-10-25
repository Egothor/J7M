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

package org.j7m.middleware.operations.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * UI-neutral nugget drain, shutdown, and self-termination contracts.
 *
 * <p>The records in this class are immutable public DTOs used by the
 * headless nugget administration service, CLI clients, deterministic tests,
 * demos, and future web/API adapters. They describe nugget-level control of
 * the current J7M runtime subject only; they are not an arbitrary operating
 * system process-control API. Implementations must enforce PIN admission,
 * authorization, exact self-target checks, confirmation, feature gates,
 * deadline checks, audit, redaction, and bounded retention before returning a
 * {@link Result}. For accepted asynchronous shutdown or termination actions,
 * the server owns the execution timeout and may retain a terminal degraded
 * operation status so replay of the same operation id reports
 * {@link Outcome#TIMED_OUT} or {@link Outcome#OUTCOME_UNKNOWN} instead of
 * executing the callback again.</p>
 *
 * <p>Confirmation challenges are sensitive operational credentials. A
 * challenge may be present in a direct {@link Result} only when
 * {@link Outcome#CONFIRMATION_REQUIRED} is returned so an operator can submit
 * it on the next request. Challenges must not be copied to audit records,
 * logs, demos, evidence reports, history, warnings, metadata, errors, or
 * string representations. {@link Request#toString()},
 * {@link Confirmation#toString()}, and {@link Result#toString()} redact
 * confirmation material deliberately.</p>
 *
 * <p>Instances are immutable and thread-safe. Construction performs bounded
 * defensive copying of public metadata but does not grant admission or
 * authorization.</p>
 */
public final class NuggetControl {
    /**
     * Maximum retained safe metadata entries.
     */
    public static final int MAX_METADATA = 16;
    /**
     * Maximum retained safe warning entries.
     */
    public static final int MAX_WARNINGS = 8;
    private static final int MAX_METADATA_KEY_UTF8_BYTES = 64;
    private static final int MAX_METADATA_VALUE_UTF8_BYTES = 256;
    private static final int MAX_ADMISSION_TOKEN_UTF8_BYTES = 128;
    private static final int MAX_WARNING_UTF8_BYTES = 160;

    private NuggetControl() {
    }

    /**
     * Nugget-level control action.
     */
    public enum Action {
        /**
         * Enter nugget draining mode and reject new mutating work where implemented.
         */
        DRAIN_NUGGET,
        /**
         * Request graceful shutdown through the owning {@code MiddlewareRuntime.close()} path.
         */
        SHUTDOWN_NUGGET,
        /**
         * Request explicitly enabled injected self-termination after shutdown gates.
         */
        KILL_NUGGET
    }

    /**
     * Requested control mode.
     */
    public enum Mode {
        /**
         * Prefer graceful behavior and report partial drain limits truthfully.
         */
        GRACEFUL,
        /**
         * Force only those steps explicitly enabled by server policy.
         */
        FORCED
    }

    /**
     * Stable public nugget-control outcome vocabulary.
     */
    public enum Outcome {
        /**
         * A server-issued confirmation challenge is required before execution.
         */
        CONFIRMATION_REQUIRED,
        /**
         * The operation was accepted for asynchronous execution.
         */
        ACCEPTED,
        /**
         * The operation is already in progress.
         */
        IN_PROGRESS,
        /**
         * The operation completed synchronously.
         */
        COMPLETED,
        /**
         * Admission, authorization, state, target, or confirmation policy denied the request.
         */
        DENIED,
        /**
         * The action is disabled by configuration.
         */
        DISABLED,
        /**
         * The selected nugget or provider cannot perform this action.
         */
        UNAVAILABLE,
        /**
         * The request or execution did not finish before its deadline.
         */
        TIMED_OUT,
        /**
         * The action completed only partially.
         */
        PARTIAL,
        /**
         * The final nugget state is not known.
         */
        OUTCOME_UNKNOWN
    }

    /**
     * Server-issued confirmation challenge metadata.
     *
     * <p>The challenge value is intentionally available only through the
     * accessor for the direct caller that must resubmit it. String rendering is
     * redacted because confirmations are credentials.</p>
     *
     * @param operationId operation identifier bound to the challenge
     * @param challenge challenge text to submit on the confirming request
     * @param expiresAt absolute expiration timestamp
     * @param action action bound to this challenge
     * @param nuggetId nugget id bound to this challenge
     * @param runtimeId runtime id bound to this challenge
     * @param generation runtime generation bound to this challenge
     * @param callerId caller principal or safe caller id bound to this challenge
     * @param state state observed when the challenge was issued
     */
    public record Confirmation(
            String operationId,
            String challenge,
            Instant expiresAt,
            Action action,
            String nuggetId,
            String runtimeId,
            long generation,
            String callerId,
            NuggetState state) {
        /**
         * Creates immutable confirmation metadata.
         */
        public Confirmation {
            operationId = safeToken(operationId);
            challenge = safeToken(challenge);
            expiresAt = expiresAt == null ? Instant.EPOCH : expiresAt;
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            nuggetId = safeToken(nuggetId);
            runtimeId = safeToken(runtimeId);
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            callerId = safeToken(callerId);
            state = state == null ? NuggetState.UNKNOWN : state;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return "Confirmation[operationId=" + this.operationId
                    + ", challenge=<redacted>, expiresAt=" + this.expiresAt
                    + ", action=" + this.action
                    + ", nuggetId=" + this.nuggetId
                    + ", runtimeId=" + this.runtimeId
                    + ", generation=" + this.generation
                    + ", callerId=" + this.callerId
                    + ", state=" + this.state + "]";
        }
    }

    /**
     * Nugget-control request metadata.
     *
     * <p>The admission id and confirmation value are sensitive operational
     * credentials. They are consumed by the server and must not be rendered or
     * audited. The target fields must identify the exact current nugget; an
     * all-target or ambiguous request is invalid at the presentation boundary
     * and must still be rejected by the service if submitted directly.</p>
     *
     * <p>{@code deadlineAt} is the caller's request deadline. A server may also
     * apply a shorter configured control timeout to accepted asynchronous
     * shutdown or termination callbacks. If that server-owned timeout expires,
     * replay with the same operation id reports the retained degraded outcome
     * and does not re-run the callback, even if the original callback later
     * returns.</p>
     *
     * @param admissionId accepted Phase 7.1 admission id
     * @param securityContext caller security context for authorization
     * @param operationId caller-supplied replay/idempotency operation id, or empty
     * @param correlationId safe correlation id
     * @param action requested action
     * @param mode requested mode
     * @param targetNuggetId exact target nugget id
     * @param targetRuntimeId exact target runtime id
     * @param targetGeneration expected runtime generation, or {@code 0}
     * @param confirmation server-issued confirmation value, or empty
     * @param requestedAt request creation timestamp
     * @param deadlineAt absolute deadline for the complete operation
     * @param metadata safe request metadata
     */
    public record Request(
            String admissionId,
            SecurityContext securityContext,
            String operationId,
            String correlationId,
            Action action,
            Mode mode,
            String targetNuggetId,
            String targetRuntimeId,
            long targetGeneration,
            String confirmation,
            Instant requestedAt,
            Instant deadlineAt,
            Map<String, String> metadata) {
        /**
         * Creates immutable request metadata.
         */
        public Request {
            admissionId = boundedToken(admissionId, MAX_ADMISSION_TOKEN_UTF8_BYTES);
            operationId = safeToken(operationId);
            correlationId = safeToken(correlationId);
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            mode = mode == null ? Mode.GRACEFUL : mode;
            targetNuggetId = safeToken(targetNuggetId);
            targetRuntimeId = safeToken(targetRuntimeId);
            if (targetGeneration < 0L) {
                throw new IllegalArgumentException("targetGeneration must not be negative");
            }
            confirmation = safeToken(confirmation);
            requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
            deadlineAt = deadlineAt == null ? requestedAt.plus(Duration.ofSeconds(30L)) : deadlineAt;
            metadata = metadata == null ? Map.of() : orderedCopy(metadata);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return "Request[admissionId=<redacted>, securityContext=<redacted>"
                    + ", operationId=" + this.operationId
                    + ", correlationId=" + this.correlationId
                    + ", action=" + this.action
                    + ", mode=" + this.mode
                    + ", targetNuggetId=" + this.targetNuggetId
                    + ", targetRuntimeId=" + this.targetRuntimeId
                    + ", targetGeneration=" + this.targetGeneration
                    + ", confirmation=<redacted>, requestedAt=" + this.requestedAt
                    + ", deadlineAt=" + this.deadlineAt
                    + ", metadata=" + this.metadata + "]";
        }
    }

    /**
     * Safe nugget-control result.
     *
     * <p>For accepted asynchronous controls, {@link #accepted()} records that
     * the server accepted responsibility for the operation, while
     * {@link #outcome()} records the latest known operation state. A retained
     * timeout or unknown-outcome status may have {@link #outcomeUnknown()} and
     * {@link #cleanupPartial()} set to {@code true}; clients must treat that as
     * a truthful degraded status and must not infer that repeating the request
     * will execute the operation again.</p>
     *
     * @param operationId server or caller operation id
     * @param correlationId safe correlation id
     * @param action requested action
     * @param mode requested mode
     * @param admitted whether PIN admission was accepted
     * @param authorized whether authorization allowed the action
     * @param targetNuggetId target nugget id
     * @param targetRuntimeId target runtime id
     * @param targetGeneration target runtime generation
     * @param previousState state observed before evaluation
     * @param currentState state observed while returning the result
     * @param resultingState expected or observed resulting state
     * @param outcome stable outcome enum
     * @param outcomeText safe operator-facing text
     * @param accepted whether the operation was accepted
     * @param inProgress whether work remains in progress
     * @param completed whether the operation completed synchronously
     * @param partial whether optional details are partial
     * @param outcomeUnknown whether the final state is uncertain
     * @param cleanupPartial whether cleanup is known to be partial
     * @param confirmation confirmation metadata, only for direct
     *        confirmation-required responses
     * @param warnings safe warnings
     * @param error normalized safe error, or {@code null}
     * @param requestedAt request timestamp
     * @param completedAt result timestamp
     * @param elapsed elapsed server-side duration
     * @param metadata safe bounded metadata
     */
    public record Result(
            String operationId,
            String correlationId,
            Action action,
            Mode mode,
            boolean admitted,
            boolean authorized,
            String targetNuggetId,
            String targetRuntimeId,
            long targetGeneration,
            NuggetState previousState,
            NuggetState currentState,
            NuggetState resultingState,
            Outcome outcome,
            String outcomeText,
            boolean accepted,
            boolean inProgress,
            boolean completed,
            boolean partial,
            boolean outcomeUnknown,
            boolean cleanupPartial,
            Confirmation confirmation,
            List<String> warnings,
            RemoteError error,
            Instant requestedAt,
            Instant completedAt,
            Duration elapsed,
            Map<String, String> metadata) {
        /**
         * Creates immutable safe result metadata.
         */
        public Result {
            operationId = safeToken(operationId);
            correlationId = safeToken(correlationId);
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            mode = mode == null ? Mode.GRACEFUL : mode;
            targetNuggetId = safeToken(targetNuggetId);
            targetRuntimeId = safeToken(targetRuntimeId);
            if (targetGeneration < 0L) {
                throw new IllegalArgumentException("targetGeneration must not be negative");
            }
            previousState = previousState == null ? NuggetState.UNKNOWN : previousState;
            currentState = currentState == null ? NuggetState.UNKNOWN : currentState;
            resultingState = resultingState == null ? NuggetState.UNKNOWN : resultingState;
            outcome = outcome == null ? Outcome.OUTCOME_UNKNOWN : outcome;
            outcomeText = outcomeText == null ? "" : outcomeText;
            warnings = warnings == null ? List.of() : safeWarnings(warnings);
            requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
            completedAt = completedAt == null ? requestedAt : completedAt;
            elapsed = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
            metadata = metadata == null ? Map.of() : orderedCopy(metadata);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return "Result[operationId=" + this.operationId
                    + ", correlationId=" + this.correlationId
                    + ", action=" + this.action
                    + ", mode=" + this.mode
                    + ", admitted=" + this.admitted
                    + ", authorized=" + this.authorized
                    + ", targetNuggetId=" + this.targetNuggetId
                    + ", targetRuntimeId=" + this.targetRuntimeId
                    + ", targetGeneration=" + this.targetGeneration
                    + ", previousState=" + this.previousState
                    + ", currentState=" + this.currentState
                    + ", resultingState=" + this.resultingState
                    + ", outcome=" + this.outcome
                    + ", outcomeText=" + this.outcomeText
                    + ", accepted=" + this.accepted
                    + ", inProgress=" + this.inProgress
                    + ", completed=" + this.completed
                    + ", partial=" + this.partial
                    + ", outcomeUnknown=" + this.outcomeUnknown
                    + ", cleanupPartial=" + this.cleanupPartial
                    + ", confirmation=<redacted>"
                    + ", warnings=" + this.warnings
                    + ", error=" + this.error
                    + ", requestedAt=" + this.requestedAt
                    + ", completedAt=" + this.completedAt
                    + ", elapsed=" + this.elapsed
                    + ", metadata=" + this.metadata + "]";
        }
    }

    private static List<String> safeWarnings(final List<String> source) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>(Math.min(source.size(), MAX_WARNINGS));
        boolean omitted = false;
        int examined = 0;
        for (String warning : source) {
            if (examined >= MAX_WARNINGS) {
                omitted = true;
                break;
            }
            examined++;
            String value = boundedToken(warning, MAX_WARNING_UTF8_BYTES);
            if (value.isEmpty() || sensitiveValue(value)) {
                omitted = true;
                continue;
            }
            values.add(value);
        }
        if (omitted && values.size() < MAX_WARNINGS) {
            values.add("details-omitted");
        }
        return List.copyOf(values);
    }

    private static Map<String, String> orderedCopy(final Map<String, String> source) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        boolean omitted = false;
        int examined = 0;
        try {
            java.util.Iterator<Map.Entry<String, String>> iterator = source.entrySet().iterator();
            while (examined < MAX_METADATA && iterator.hasNext()) {
                Map.Entry<String, String> entry = iterator.next();
                examined++;
                String key = boundedToken(entry.getKey(), MAX_METADATA_KEY_UTF8_BYTES);
                String value = boundedToken(entry.getValue(), MAX_METADATA_VALUE_UTF8_BYTES);
                if (key.isEmpty() || value.isEmpty() || sensitiveKey(key) || sensitiveValue(value)) {
                    omitted = true;
                    continue;
                }
                copy.putIfAbsent(key, value);
            }
            omitted = omitted || examined == MAX_METADATA;
        } catch (RuntimeException exception) {
            omitted = true;
        }
        if (omitted && copy.size() < MAX_METADATA) {
            copy.putIfAbsent("detailsOmitted", "true");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String safeToken(final String value) {
        return boundedToken(value, MAX_METADATA_VALUE_UTF8_BYTES);
    }

    private static String boundedToken(final String value, final int maxBytes) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxBytes));
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return "";
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!safeCodePoint(codePoint)) {
                return "";
            }
            int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > maxBytes) {
                return "";
            }
            builder.appendCodePoint(codePoint);
            bytes += nextBytes;
        }
        return builder.isEmpty() ? "" : builder.toString();
    }

    private static boolean safeCodePoint(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '.'
                || codePoint == '_'
                || codePoint == '-'
                || codePoint == ':';
    }

    private static int utf8Bytes(final int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private static boolean sensitiveKey(final String token) {
        String normalized = normalizedSensitivityText(token);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("confirmation")
                || normalized.contains("challenge")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("private")
                || normalized.contains("path")
                || normalized.contains("stack")
                || normalized.contains("exception")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || normalized.contains("certificate")
                || normalized.contains("signature")
                || normalized.contains("key");
    }

    private static boolean sensitiveValue(final String token) {
        String normalized = normalizedSensitivityText(token);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("confirmation")
                || normalized.contains("challenge")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("private")
                || normalized.contains("stack")
                || normalized.contains("exception")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || normalized.contains("beginprivatekey")
                || normalized.contains("begincertificate");
    }

    private static String normalizedSensitivityText(final String token) {
        StringBuilder builder = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value >= 'A' && value <= 'Z') {
                builder.append((char) (value + ('a' - 'A')));
            } else if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
                builder.append(value);
            }
        }
        return builder.toString();
    }
}
