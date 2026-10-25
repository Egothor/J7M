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

package org.j7m.middleware.operations.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;

/**
 * Projects untrusted lifecycle provider error metadata into public diagnostics.
 */
final class LifecyclePublicErrorProjection {
    static final int MAX_PROVIDER_DETAILS_EXAMINED = 32;
    static final int MAX_PUBLIC_DETAILS = 15;
    static final int MAX_KEY_UTF8_BYTES = 48;
    static final int MAX_VALUE_UTF8_BYTES = 96;
    static final int MAX_AGGREGATE_UTF8_BYTES = 512;
    private static final String PUBLIC_MESSAGE = "application lifecycle operation failed";
    private static final String ACTION = "action";
    private static final String PHASE = "phase";
    private static final String STATE = "state";
    private static final String PREVIOUS_STATE = "previousState";
    private static final String CURRENT_STATE = "currentState";
    private static final String RESULTING_STATE = "resultingState";
    private static final String REASON = "reason";
    private static final String PROFILE = "profile";
    private static final String CATEGORY = "category";
    private static final String VERIFICATION_CATEGORY = "verificationCategory";
    private static final String POLICY_CATEGORY = "policyCategory";
    private static final String RETRYABLE = "retryable";
    private static final String RESULT = "result";
    private static final String DETAILS_OMITTED = "detailsOmitted";
    private static final String DETAILS_TRUNCATED = "detailsTruncated";
    private static final String[] PUBLIC_DETAIL_ORDER = {
            ACTION,
            PHASE,
            STATE,
            PREVIOUS_STATE,
            CURRENT_STATE,
            RESULTING_STATE,
            REASON,
            PROFILE,
            CATEGORY,
            VERIFICATION_CATEGORY,
            POLICY_CATEGORY,
            RETRYABLE,
            RESULT,
            DETAILS_OMITTED,
            DETAILS_TRUNCATED
    };

    private LifecyclePublicErrorProjection() {
    }

    static ProjectedError project(
            final RemoteError remote,
            final String fallbackReason,
            final String invocationId,
            final String runtimeId) {
        MiddlewareErrorCode code = remote == null ? MiddlewareErrorCode.INTERNAL : remote.code();
        DetailProjection details = projectDetails(remote == null ? Map.of() : remote.details(), fallbackReason);
        RemoteError error = new RemoteError(
                code,
                PUBLIC_MESSAGE,
                details.details(),
                safeToken(invocationId, ""),
                safeToken(runtimeId, ""),
                remote != null && remote.retryable(),
                remote != null && remote.dirtyTarget());
        return new ProjectedError(error, details.reason());
    }

    static DetailProjection projectDetails(final Map<?, ?> source, final String fallbackReason) {
        String reason = trustedPublicReason(fallbackReason);
        DetailBuilder builder = new DetailBuilder();
        builder.put(REASON, reason, true);
        boolean providerReasonAccepted = false;
        int examined = 0;
        boolean omitted = false;
        boolean truncated = false;
        if (source != null) {
            Iterator<? extends Map.Entry<?, ?>> iterator;
            try {
                iterator = source.entrySet().iterator();
            } catch (RuntimeException exception) {
                return projectedFailure(reason, true, true);
            }
            while (examined < MAX_PROVIDER_DETAILS_EXAMINED) {
                boolean hasNext;
                try {
                    hasNext = iterator.hasNext();
                } catch (RuntimeException exception) {
                    omitted = true;
                    break;
                }
                if (!hasNext) {
                    break;
                }
                Map.Entry<?, ?> entry;
                try {
                    entry = iterator.next();
                } catch (RuntimeException exception) {
                    omitted = true;
                    break;
                }
                examined++;
                Object rawKey;
                Object rawValue;
                try {
                    rawKey = entry.getKey();
                    rawValue = entry.getValue();
                } catch (RuntimeException exception) {
                    omitted = true;
                    continue;
                }
                if (!(rawKey instanceof String key) || !(rawValue instanceof String value)) {
                    omitted = true;
                    continue;
                }
                String normalizedKey = normalizedKey(key);
                if (normalizedKey.isEmpty()) {
                    omitted = true;
                    continue;
                }
                if ("reason".equals(normalizedKey)) {
                    String candidate = providerPublicReason(value);
                    if (!candidate.isEmpty() && !providerReasonAccepted) {
                        reason = candidate;
                        builder.put(REASON, reason, true);
                        providerReasonAccepted = true;
                    } else {
                        omitted = true;
                    }
                    continue;
                }
                if (sensitiveKey(normalizedKey)) {
                    omitted = true;
                    continue;
                }
                String publicKey = publicKey(normalizedKey);
                if (publicKey.isEmpty()) {
                    omitted = true;
                    continue;
                }
                String publicValue = publicValue(publicKey, value);
                if (publicValue.isEmpty()) {
                    omitted = true;
                    continue;
                }
                if (!builder.put(publicKey, publicValue, false)) {
                    truncated = true;
                }
            }
            truncated = truncated || examined == MAX_PROVIDER_DETAILS_EXAMINED;
        }
        builder.putFlag(DETAILS_OMITTED, omitted);
        builder.putFlag(DETAILS_TRUNCATED, truncated);
        return new DetailProjection(builder.toMap(), reason);
    }

    static DetailProjection projectReturnedDeploymentFailure(
            final NuggetApplicationLifecycle.Action action,
            final ApplicationRunState previousState,
            final ApplicationRunState currentState,
            final boolean detailsOmitted) {
        DetailBuilder builder = new DetailBuilder();
        builder.put(REASON, "deployer-result-failed", true);
        if (action != null) {
            builder.put(ACTION, publicAction(action.name()), false);
        }
        if (previousState != null) {
            builder.put(PREVIOUS_STATE, publicState(previousState.name()), false);
        }
        if (currentState != null) {
            String state = publicState(currentState.name());
            builder.put(CURRENT_STATE, state, false);
            builder.put(RESULTING_STATE, state, false);
            builder.put(RESULT, state, false);
        }
        builder.putFlag(DETAILS_OMITTED, detailsOmitted);
        return new DetailProjection(builder.toMap(), "deployer-result-failed");
    }

    private static DetailProjection projectedFailure(
            final String reason,
            final boolean omitted,
            final boolean truncated) {
        DetailBuilder builder = new DetailBuilder();
        builder.put(REASON, reason, true);
        builder.putFlag(DETAILS_OMITTED, omitted);
        builder.putFlag(DETAILS_TRUNCATED, truncated);
        return new DetailProjection(builder.toMap(), reason);
    }

    private static String publicKey(final String normalizedKey) {
        return switch (normalizedKey) {
            case "phase", "operationphase" -> "phase";
            case "profile", "verificationprofile" -> "profile";
            case "category", "errorcategory" -> "category";
            case "verificationcategory" -> "verificationCategory";
            case "policycategory" -> "policyCategory";
            case "action", "lifecycleaction" -> "action";
            case "state" -> "state";
            case "previousstate" -> "previousState";
            case "currentstate" -> "currentState";
            case "resultingstate" -> "resultingState";
            case "retryable" -> "retryable";
            case "result" -> "result";
            default -> "";
        };
    }

    private static String publicValue(final String publicKey, final String value) {
        String token = safeToken(value, "");
        if (token.isEmpty() || sensitiveValue(value, token)) {
            return "";
        }
        return switch (publicKey) {
            case ACTION -> publicAction(token);
            case PHASE -> publicPhase(token);
            case STATE, PREVIOUS_STATE, CURRENT_STATE, RESULTING_STATE, RESULT -> publicState(token);
            case PROFILE -> publicProfile(token);
            case CATEGORY -> publicCategory(token);
            case VERIFICATION_CATEGORY -> publicVerificationCategory(token);
            case POLICY_CATEGORY -> publicPolicyCategory(token);
            case RETRYABLE -> publicBoolean(token);
            default -> "";
        };
    }

    private static String trustedPublicReason(final String value) {
        String safe = safeToken(value, "");
        if (safe.isEmpty()) {
            return "lifecycle-failed";
        }
        String reason = publicReasonCode(safe);
        return reason.isEmpty() ? "lifecycle-failed" : reason;
    }

    private static String providerPublicReason(final String value) {
        String safe = safeToken(value, "");
        if (safe.isEmpty() || sensitiveValue(value, safe)) {
            return "";
        }
        return publicReasonCode(safe);
    }

    private static String publicReasonCode(final String safe) {
        return switch (safe) {
            case "not-found",
                    "invalid-lifecycle-state",
                    "permission-or-verification-denied",
                    "authentication-required",
                    "deadline-exceeded",
                    "cancelled",
                    "provider-unavailable",
                    "executor-overloaded",
                    "lifecycle-failed",
                    "plan-rejected",
                    "deployer-result-failed",
                    "verification-failed",
                    "authorization-denied",
                    "admission-required",
                    "read-only",
                    "start-disabled",
                    "interrupted",
                    "invalid-request",
                    "executor-unavailable" -> safe;
            default -> "";
        };
    }

    private static String safeToken(final String value, final String fallback) {
        if (value == null) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder(MAX_VALUE_UTF8_BYTES);
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return fallback;
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!safeTokenCodePoint(codePoint)) {
                return fallback;
            }
            int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > MAX_VALUE_UTF8_BYTES) {
                return fallback;
            }
            builder.appendCodePoint(codePoint);
            bytes += nextBytes;
        }
        return builder.isEmpty() ? fallback : builder.toString();
    }

    private static boolean safeTokenCodePoint(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '.'
                || codePoint == '_'
                || codePoint == '-'
                || codePoint == ':';
    }

    private static boolean sensitiveKey(final String normalizedKey) {
        return contains(normalizedKey, "pin")
                || contains(normalizedKey, "admission")
                || contains(normalizedKey, "credential")
                || contains(normalizedKey, "password")
                || contains(normalizedKey, "passwd")
                || contains(normalizedKey, "secret")
                || contains(normalizedKey, "token")
                || contains(normalizedKey, "bearer")
                || contains(normalizedKey, "cookie")
                || contains(normalizedKey, "session")
                || contains(normalizedKey, "auth")
                || contains(normalizedKey, "caller")
                || contains(normalizedKey, "private")
                || contains(normalizedKey, "path")
                || contains(normalizedKey, "uri")
                || contains(normalizedKey, "url")
                || contains(normalizedKey, "endpoint")
                || contains(normalizedKey, "command")
                || contains(normalizedKey, "environment")
                || contains(normalizedKey, "manifest")
                || contains(normalizedKey, "config")
                || contains(normalizedKey, "payload")
                || contains(normalizedKey, "body")
                || contains(normalizedKey, "argument")
                || contains(normalizedKey, "stack")
                || contains(normalizedKey, "exception")
                || contains(normalizedKey, "throwable")
                || contains(normalizedKey, "cause")
                || contains(normalizedKey, "suppressed")
                || contains(normalizedKey, "implementation")
                || contains(normalizedKey, "classloader")
                || contains(normalizedKey, "modulelayer")
                || contains(normalizedKey, "process")
                || contains(normalizedKey, "certificate")
                || contains(normalizedKey, "cert")
                || contains(normalizedKey, "signature")
                || contains(normalizedKey, "key")
                || contains(normalizedKey, "digest")
                || contains(normalizedKey, "sbom")
                || contains(normalizedKey, "hook");
    }

    private static boolean sensitiveValue(final String rawValue, final String token) {
        String normalized = normalizedKey(rawValue);
        if (normalized.isEmpty() && !token.isEmpty()) {
            return true;
        }
        return contains(normalized, "pin")
                || contains(normalized, "credential")
                || contains(normalized, "password")
                || contains(normalized, "passwd")
                || contains(normalized, "secret")
                || contains(normalized, "token")
                || contains(normalized, "bearer")
                || contains(normalized, "cookie")
                || contains(normalized, "session")
                || contains(normalized, "privatekey")
                || contains(normalized, "beginprivatekey")
                || contains(normalized, "begincertificate")
                || contains(normalized, "http")
                || contains(normalized, "https")
                || contains(normalized, "javalang")
                || contains(normalized, "atsun")
                || contains(normalized, "atjava")
                || contains(normalized, "dotdot")
                || contains(normalized, "stacktrace")
                || contains(normalized, "exception")
                || contains(normalized, "throwable")
                || contains(normalized, "payload")
                || contains(normalized, "configuration")
                || contains(normalized, "repositorypath")
                || contains(token, "..")
                || contains(token, "://")
                || contains(token, "\\\\")
                || windowsDrive(token);
    }

    private static boolean windowsDrive(final String token) {
        return token.length() >= 2 && ((token.charAt(0) >= 'A' && token.charAt(0) <= 'Z')
                || (token.charAt(0) >= 'a' && token.charAt(0) <= 'z')) && token.charAt(1) == ':';
    }

    private static boolean contains(final String text, final String token) {
        return text.contains(token);
    }

    private static String normalizedKey(final String key) {
        if (key == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(MAX_KEY_UTF8_BYTES);
        int bytes = 0;
        for (int index = 0; index < key.length();) {
            char current = key.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= key.length() || !Character.isLowSurrogate(key.charAt(index + 1))))) {
                return "";
            }
            int codePoint = key.codePointAt(index);
            index += Character.charCount(codePoint);
            int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > MAX_KEY_UTF8_BYTES) {
                return "";
            }
            bytes += nextBytes;
            if (isAsciiLetterOrDigit(codePoint)) {
                int lower = asciiLower(codePoint);
                builder.appendCodePoint(lower);
            }
        }
        return builder.toString();
    }

    private static boolean isAsciiLetterOrDigit(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9');
    }

    private static int asciiLower(final int codePoint) {
        if (codePoint >= 'A' && codePoint <= 'Z') {
            return codePoint + ('a' - 'A');
        }
        return codePoint;
    }

    private static String publicAction(final String token) {
        String normalized = upperAscii(token);
        return switch (normalized) {
            case "START", "DRAIN", "STOP", "UNLOAD" -> normalized;
            default -> "";
        };
    }

    private static String publicPhase(final String token) {
        String normalized = lowerAscii(token);
        return switch (normalized) {
            case "admission",
                    "authorization",
                    "repository",
                    "verify",
                    "verification",
                    "compatibility",
                    "security",
                    "resource",
                    "plan",
                    "activate",
                    "activation",
                    "start",
                    "startup",
                    "drain",
                    "stop",
                    "unload",
                    "cleanup",
                    "provider",
                    "lifecycle" -> normalized;
            default -> "";
        };
    }

    private static String publicState(final String token) {
        String normalized = upperAscii(token);
        return switch (normalized) {
            case "PACKAGED",
                    "VERIFIED",
                    "DEPLOYED",
                    "PLANNED",
                    "LOADING",
                    "LOADED",
                    "STARTING",
                    "RUNNING",
                    "UPGRADING",
                    "ROLLING_BACK",
                    "DRAINING",
                    "STOPPING",
                    "STOPPED",
                    "UNLOADING",
                    "UNLOADED",
                    "FAILED",
                    "REJECTED" -> normalized;
            default -> "";
        };
    }

    private static String publicProfile(final String token) {
        String normalized = lowerAscii(token);
        return switch (normalized) {
            case "default", "dev", "development", "strict", "production", "regulated" -> normalized;
            default -> "";
        };
    }

    private static String publicCategory(final String token) {
        String normalized = lowerAscii(token);
        return switch (normalized) {
            case "lifecycle",
                    "verification",
                    "authorization",
                    "policy",
                    "repository",
                    "compatibility",
                    "resource",
                    "provider",
                    "timeout",
                    "invalid-state",
                    "cleanup",
                    "security" -> normalized;
            default -> "";
        };
    }

    private static String publicVerificationCategory(final String token) {
        String normalized = lowerAscii(token);
        return switch (normalized) {
            case "checksum",
                    "signature",
                    "provenance",
                    "sbom",
                    "manifest",
                    "compatibility",
                    "repository",
                    "strict",
                    "policy",
                    "verification" -> normalized;
            default -> "";
        };
    }

    private static String publicPolicyCategory(final String token) {
        String normalized = lowerAscii(token);
        return switch (normalized) {
            case "authorization",
                    "security",
                    "resource",
                    "read-only",
                    "feature-gate",
                    "lifecycle",
                    "admission" -> normalized;
            default -> "";
        };
    }

    private static String publicBoolean(final String token) {
        if ("true".equalsIgnoreCase(token)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(token)) {
            return "false";
        }
        return "";
    }

    private static String lowerAscii(final String token) {
        StringBuilder builder = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            builder.append((char) asciiLower(token.charAt(index)));
        }
        return builder.toString();
    }

    private static String upperAscii(final String token) {
        StringBuilder builder = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value >= 'a' && value <= 'z') {
                builder.append((char) (value - ('a' - 'A')));
            } else {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static Map<String, String> orderedCopy(final LinkedHashMap<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static int publicKeyIndex(final String key) {
        for (int index = 0; index < PUBLIC_DETAIL_ORDER.length; index++) {
            if (PUBLIC_DETAIL_ORDER[index].equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private static final class DetailBuilder {
        private final String[] values = new String[PUBLIC_DETAIL_ORDER.length];
        private int entries;
        private int aggregateBytes;

        private boolean put(final String key, final String value, final boolean replace) {
            int index = publicKeyIndex(key);
            if (index < 0 || value == null || value.isEmpty()) {
                return false;
            }
            String previous = this.values[index];
            if (previous != null && !replace) {
                return true;
            }
            int previousBytes = previous == null ? 0 : entryBytes(key, previous);
            int entryBytes = entryBytes(key, value);
            int nextEntries = previous == null ? this.entries + 1 : this.entries;
            long nextAggregate = (long) this.aggregateBytes - previousBytes + entryBytes;
            if (nextEntries > MAX_PUBLIC_DETAILS || nextAggregate > MAX_AGGREGATE_UTF8_BYTES) {
                return false;
            }
            this.values[index] = value;
            this.entries = nextEntries;
            this.aggregateBytes = (int) nextAggregate;
            return true;
        }

        private void putFlag(final String key, final boolean value) {
            if (value) {
                put(key, "true", false);
            }
        }

        private Map<String, String> toMap() {
            LinkedHashMap<String, String> result = new LinkedHashMap<>(MAX_PUBLIC_DETAILS);
            for (int index = 0; index < PUBLIC_DETAIL_ORDER.length; index++) {
                String value = this.values[index];
                if (value != null) {
                    result.put(PUBLIC_DETAIL_ORDER[index], value);
                }
            }
            return orderedCopy(result);
        }
    }

    private static int entryBytes(final String key, final String value) {
        return utf8Bytes(key) + utf8Bytes(value);
    }

    private static int utf8Bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
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

    record ProjectedError(RemoteError error, String reason) {
    }

    record DetailProjection(Map<String, String> details, String reason) {
    }
}
