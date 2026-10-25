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
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * UI-neutral application lifecycle contracts for nugget administration.
 *
 * <p>The records in this type are immutable public DTOs used by the headless
 * nugget administration service, CLI clients, deterministic tests, demos, and
 * future web/API adapters. They carry only safe operational summaries and must
 * not contain PINs, admission identifiers in public summaries, repository host
 * paths, application configuration secrets, classloader details, raw exception
 * text, stack traces, private keys, certificates, credentials, or mutable
 * runtime implementation objects. Provider-origin error metadata is untrusted:
 * nugget admin implementations must project it through a bounded allowlist
 * before constructing {@link Result}, audit records, human output, or JSON
 * output. Unknown lifecycle failure details are omitted by default. Retained
 * details use explicit public value domains rather than provider free text, and
 * sensitive classification is based on the original provider key and value
 * before any public key normalization, fallback, or truncation. Implementations
 * that expose ordered public error details must preserve the canonical detail
 * order through defensive immutable copies.</p>
 *
 * <p>Instances are immutable and thread-safe. Implementations that create
 * these DTOs remain responsible for enforcing PIN admission, authorization,
 * read-only and feature gates, application verification, lifecycle state
 * checks, deadlines, redaction, audit, and bounded result construction before
 * returning them.</p>
 */
public final class NuggetApplicationLifecycle {
    private static final int MAX_METADATA = 16;
    private static final int MAX_METADATA_KEY_UTF8_BYTES = 64;
    private static final int MAX_METADATA_VALUE_UTF8_BYTES = 256;

    private NuggetApplicationLifecycle() {
    }

    /**
     * Application lifecycle action requested through nugget administration.
     */
    public enum Action {
        /**
         * Plan or activate an exact repository coordinate.
         */
        START,
        /**
         * Drain a running application instance without unloading it.
         */
        DRAIN,
        /**
         * Stop a running or draining application instance.
         */
        STOP,
        /**
         * Unload a stopped or failed application instance.
         */
        UNLOAD
    }

    /**
     * Stable public lifecycle outcome vocabulary.
     */
    public enum Outcome {
        /**
         * Operation completed successfully.
         */
        COMPLETED,
        /**
         * Operation produced a plan and made no runtime mutation.
         */
        DRY_RUN,
        /**
         * Operation was accepted but did not complete synchronously.
         */
        ACCEPTED,
        /**
         * Target was already in the requested terminal or idempotent state.
         */
        ALREADY_DONE,
        /**
         * Operation was rejected by admission, authorization, gate, or state policy.
         */
        REJECTED,
        /**
         * Operation is unsupported by the selected nugget or provider.
         */
        UNAVAILABLE,
        /**
         * Operation did not finish before its deadline.
         */
        DEADLINE_EXCEEDED,
        /**
         * Operation failed with a normalized safe error.
         */
        FAILED,
        /**
         * Operation outcome is not fully known.
         */
        UNKNOWN
    }

    /**
     * Headless lifecycle request metadata.
     *
     * <p>The admission id is required by the server but is not intended for
     * rendering, audit detail, or durable evidence. The deadline is absolute and
     * applies to server-side admission verification, authorization, provider
     * calls, lifecycle work, and result construction. Callers should compute it
     * once for the original command and reuse the remaining budget while
     * resolving targets, authenticating, connecting, and invoking this method.</p>
     *
     * @param admissionId accepted Phase 7.1 admission id
     * @param securityContext caller security context for authorization
     * @param correlationId safe correlation id
     * @param action lifecycle action
     * @param coordinate exact coordinate for {@link Action#START}
     * @param instanceId target instance for {@link Action#DRAIN},
     *        {@link Action#STOP}, or {@link Action#UNLOAD}
     * @param dryRun whether mutation must be planned but not executed
     * @param requestedAt request creation timestamp
     * @param deadlineAt absolute deadline for the complete operation
     * @param metadata safe request metadata
     */
    public record Request(
            String admissionId,
            SecurityContext securityContext,
            String correlationId,
            Action action,
            ApplicationCoordinate coordinate,
            ApplicationInstanceId instanceId,
            boolean dryRun,
            Instant requestedAt,
            Instant deadlineAt,
            Map<String, String> metadata) {
        /**
         * Creates immutable request metadata.
         */
        public Request {
            admissionId = admissionId == null ? "" : admissionId;
            correlationId = correlationId == null ? "" : correlationId;
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            if (action == Action.START && coordinate == null) {
                throw new IllegalArgumentException("start requires an application coordinate");
            }
            if (action != Action.START && instanceId == null) {
                throw new IllegalArgumentException("instance lifecycle operation requires an application instance id");
            }
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
                    + ", correlationId=" + this.correlationId
                    + ", action=" + this.action
                    + ", coordinate=" + this.coordinate
                    + ", instanceId=" + this.instanceId
                    + ", dryRun=" + this.dryRun
                    + ", requestedAt=" + this.requestedAt
                    + ", deadlineAt=" + this.deadlineAt
                    + ", metadata=" + this.metadata + "]";
        }
    }

    /**
     * Safe lifecycle operation result.
     *
     * @param operationId server-created operation id
     * @param correlationId safe correlation id
     * @param action lifecycle action
     * @param admitted whether PIN admission was accepted
     * @param authorized whether authorization allowed the requested action
     * @param dryRun whether mutation was intentionally skipped
     * @param nuggetId target nugget id
     * @param runtimeId target runtime id
     * @param coordinate target application coordinate, when applicable
     * @param instanceId target or resulting application instance id, when known
     * @param previousState state observed before the operation, when known
     * @param currentState state observed after the operation, when known
     * @param resultingState resulting lifecycle state, when known
     * @param outcome stable outcome enum
     * @param outcomeText safe operator-facing outcome text
     * @param accepted whether the server accepted the operation
     * @param inProgress whether work remains in progress
     * @param completed whether the operation completed synchronously
     * @param partial whether optional details are partial
     * @param outcomeUnknown whether the final application state is uncertain
     * @param verificationSummary safe verification summary
     * @param authorizationSummary safe authorization summary
     * @param plan activation plan for dry-run or start planning, when available
     * @param deploymentResult safe deployer result, when available; returned
     *        failed deployer results are omitted or reduced to a public
     *        projection and must not expose provider-supplied message or detail
     *        text
     * @param activation activation snapshot, when available
     * @param warnings safe warnings
     * @param error normalized safe error, or {@code null}
     * @param requestedAt request timestamp
     * @param completedAt result timestamp
     * @param elapsed elapsed server-side duration
     * @param metadata safe bounded metadata produced from the same public
     *        projection used for audit and rendering
     */
    public record Result(
            String operationId,
            String correlationId,
            Action action,
            boolean admitted,
            boolean authorized,
            boolean dryRun,
            String nuggetId,
            String runtimeId,
            ApplicationCoordinate coordinate,
            ApplicationInstanceId instanceId,
            ApplicationRunState previousState,
            ApplicationRunState currentState,
            ApplicationRunState resultingState,
            Outcome outcome,
            String outcomeText,
            boolean accepted,
            boolean inProgress,
            boolean completed,
            boolean partial,
            boolean outcomeUnknown,
            String verificationSummary,
            String authorizationSummary,
            ApplicationDeploymentPlan plan,
            ApplicationDeploymentResult deploymentResult,
            ApplicationActivation activation,
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
            operationId = operationId == null ? "" : operationId;
            correlationId = correlationId == null ? "" : correlationId;
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            nuggetId = nuggetId == null ? "" : nuggetId;
            runtimeId = runtimeId == null ? "" : runtimeId;
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            outcomeText = outcomeText == null ? "" : outcomeText;
            verificationSummary = verificationSummary == null ? "" : verificationSummary;
            authorizationSummary = authorizationSummary == null ? "" : authorizationSummary;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
            completedAt = completedAt == null ? requestedAt : completedAt;
            elapsed = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
            metadata = metadata == null ? Map.of() : orderedCopy(metadata);
        }
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
                String rawKey = entry.getKey();
                String rawValue = entry.getValue();
                String key = boundedToken(rawKey, MAX_METADATA_KEY_UTF8_BYTES);
                String value = boundedToken(rawValue, MAX_METADATA_VALUE_UTF8_BYTES);
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

    private static String boundedToken(final String value, final int maxBytes) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(maxBytes);
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
                || normalized.contains("throwable")
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
                || normalized.contains("throwable")
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
