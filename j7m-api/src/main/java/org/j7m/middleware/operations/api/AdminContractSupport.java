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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdminContractSupport {
    static final int MAX_TOKEN_UTF8_BYTES = NuggetAdminContract.MAX_TOKEN_UTF8_BYTES;
    static final int MAX_TEXT_UTF8_BYTES = NuggetAdminContract.MAX_TEXT_UTF8_BYTES;
    static final int MAX_METADATA_KEY_UTF8_BYTES = NuggetAdminContract.MAX_METADATA_KEY_UTF8_BYTES;
    static final int MAX_METADATA_VALUE_UTF8_BYTES = NuggetAdminContract.MAX_METADATA_VALUE_UTF8_BYTES;
    private static final String DETAILS_OMITTED = "details-omitted";

    private AdminContractSupport() {
    }

    static String token(final String value, final String name) {
        final String token = boundedToken(value, MAX_TOKEN_UTF8_BYTES);
        if (token.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty safe token");
        }
        return token;
    }

    static String optionalToken(final String value) {
        return optionalBounded(value, MAX_TOKEN_UTF8_BYTES, true);
    }

    static String optionalText(final String value) {
        return optionalBounded(value, MAX_TEXT_UTF8_BYTES, false);
    }

    static Duration boundedDuration(final Duration value, final Duration defaultValue, final Duration maximum) {
        final Duration candidate = value == null ? defaultValue : value;
        if (candidate.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (candidate.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("duration outside admin contract bounds");
        }
        return candidate;
    }

    static Map<String, String> orderedStringMap(final Map<String, String> source, final int maxEntries) {
        if (source == null) {
            return Map.of();
        }
        final LinkedHashMap<String, String> copy = new LinkedHashMap<>(maxEntries);
        boolean omitted = false;
        int examined = 0;
        int aggregateBytes = 0;
        try {
            final Iterator<Map.Entry<String, String>> iterator = source.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<String, String> entry = iterator.next();
                examined++;
                if (examined > maxEntries) {
                    omitted = true;
                    break;
                }
                final String key = boundedToken(entry.getKey(), MAX_METADATA_KEY_UTF8_BYTES);
                final String value = boundedText(entry.getValue(), MAX_METADATA_VALUE_UTF8_BYTES);
                if (key.isBlank() || value.isBlank() || sensitive(key) || sensitive(value)) {
                    omitted = true;
                    continue;
                }
                final int nextAggregateBytes = utf8Length(key) + utf8Length(value);
                if (aggregateBytes + nextAggregateBytes > NuggetAdminContract.MAX_METADATA_AGGREGATE_UTF8_BYTES) {
                    omitted = true;
                    break;
                }
                copy.putIfAbsent(key, value);
                aggregateBytes += nextAggregateBytes;
            }
        } catch (RuntimeException exception) {
            omitted = true;
        }
        if (omitted
                && copy.size() < maxEntries
                && aggregateBytes + utf8Length("detailsOmitted") + utf8Length("true")
                <= NuggetAdminContract.MAX_METADATA_AGGREGATE_UTF8_BYTES) {
            copy.putIfAbsent("detailsOmitted", "true");
        }
        if (copy.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(copy);
    }

    static List<String> safeStringList(final List<String> source, final int maxEntries, final int maxBytes) {
        if (source == null || maxEntries <= 0) {
            return List.of();
        }
        final ArrayList<String> copy = new ArrayList<>(Math.min(maxEntries, 8));
        boolean omitted = false;
        int examined = 0;
        try {
            final Iterator<String> iterator = source.iterator();
            while (iterator.hasNext()) {
                final String value = iterator.next();
                examined++;
                if (examined > maxEntries) {
                    omitted = true;
                    break;
                }
                final String text = boundedText(value, maxBytes);
                if (text.isBlank() || sensitive(text)) {
                    omitted = true;
                    continue;
                }
                copy.add(text);
            }
        } catch (RuntimeException exception) {
            omitted = true;
        }
        if (omitted) {
            if (copy.size() < maxEntries) {
                copy.add(DETAILS_OMITTED);
            } else if (!copy.isEmpty()) {
                copy.set(copy.size() - 1, DETAILS_OMITTED);
            }
        }
        if (copy.isEmpty()) {
            return List.of();
        }
        return List.copyOf(copy);
    }

    static void validateRequestTargetConsistency(final NuggetAdminTarget target, final Object payload) {
        if (payload instanceof NuggetControl.Request request) {
            requireTarget(target, "request");
            requireEquals(target.nuggetId(), request.targetNuggetId(), "target nugget id");
            requireEquals(target.runtimeId(), request.targetRuntimeId(), "target runtime id");
            if (target.generation() != request.targetGeneration()) {
                throw new IllegalArgumentException("request target generation does not match context target generation");
            }
        }
    }

    static void validateResultTargetConsistency(final NuggetAdminTarget target, final Object payload) {
        if (payload instanceof NuggetControl.Result result) {
            requireTarget(target, "result");
            requireEquals(target.nuggetId(), result.targetNuggetId(), "target nugget id");
            requireEquals(target.runtimeId(), result.targetRuntimeId(), "target runtime id");
            if (target.generation() != result.targetGeneration()) {
                throw new IllegalArgumentException("result target generation does not match envelope target generation");
            }
            return;
        }
        if (payload instanceof NuggetApplicationLifecycle.Result result) {
            requireTarget(target, "result");
            requireEquals(target.nuggetId(), result.nuggetId(), "nugget id");
            requireEquals(target.runtimeId(), result.runtimeId(), "runtime id");
        }
    }

    static void validateResultOperationIdConsistency(final NuggetAdminResultHeader header, final Object payload) {
        if (payload instanceof NuggetControl.Result result) {
            requireOperationIdConsistency(header, result.operationId());
            return;
        }
        if (payload instanceof NuggetApplicationLifecycle.Result result) {
            requireOperationIdConsistency(header, result.operationId());
        }
    }

    private static void requireTarget(final NuggetAdminTarget target, final String role) {
        if (target == null) {
            throw new IllegalArgumentException("admin command " + role + " target is required for target-bearing payload");
        }
    }

    private static void requireEquals(final String expected, final String actual, final String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("admin command repeated " + field + " does not match envelope target");
        }
    }

    private static void requireOperationIdConsistency(
            final NuggetAdminResultHeader header,
            final String payloadOperationId) {
        final String headerOperationId = header.operationId();
        if (!headerOperationId.isBlank()
                && !payloadOperationId.isBlank()
                && !headerOperationId.equals(payloadOperationId)) {
            throw new IllegalArgumentException("admin command payload operation id does not match header operation id");
        }
        if (mutationOperationMayExist(header) && !headerOperationId.isBlank() && payloadOperationId.isBlank()) {
            throw new IllegalArgumentException("admin command mutation payload operation id is missing");
        }
    }

    private static boolean mutationOperationMayExist(final NuggetAdminResultHeader header) {
        return header.accepted()
                || header.inProgress()
                || header.timedOut()
                || header.outcomeUnknown()
                || header.outcome() == NuggetAdminOutcome.ACCEPTED
                || header.outcome() == NuggetAdminOutcome.IN_PROGRESS
                || header.outcome() == NuggetAdminOutcome.TIMED_OUT
                || header.outcome() == NuggetAdminOutcome.OUTCOME_UNKNOWN;
    }

    static String boundedToken(final String value, final int maxBytes) {
        return bounded(value, maxBytes, true);
    }

    static String boundedText(final String value, final int maxBytes) {
        return bounded(value, maxBytes, false);
    }

    private static String optionalBounded(final String value, final int maxBytes, final boolean tokenOnly) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder(Math.min(value.length(), maxBytes));
        int bytes = 0;
        boolean blankPrefix = false;
        boolean unsafeBlankPrefix = false;
        boolean nonBlank = false;
        for (int index = 0; index < value.length();) {
            final char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return "";
            }
            final int codePoint = value.codePointAt(index);
            final int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > maxBytes) {
                return "";
            }
            final boolean blank = Character.isWhitespace(codePoint);
            final boolean safe = tokenOnly ? safeTokenCodePoint(codePoint) : safeTextCodePoint(codePoint);
            if (!safe) {
                if (blank && !nonBlank) {
                    blankPrefix = true;
                    unsafeBlankPrefix = true;
                    bytes += nextBytes;
                    index += Character.charCount(codePoint);
                    continue;
                }
                return "";
            }
            if (blank) {
                blankPrefix = true;
            } else {
                nonBlank = true;
                if (unsafeBlankPrefix || (tokenOnly && blankPrefix)) {
                    return "";
                }
            }
            builder.appendCodePoint(codePoint);
            bytes += nextBytes;
            index += Character.charCount(codePoint);
        }
        return nonBlank ? builder.toString() : "";
    }

    private static String bounded(final String value, final int maxBytes, final boolean tokenOnly) {
        if (value == null) {
            return "";
        }
        final StringBuilder builder = new StringBuilder(Math.min(value.length(), maxBytes));
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            final char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return "";
            }
            final int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if ((tokenOnly && !safeTokenCodePoint(codePoint)) || (!tokenOnly && !safeTextCodePoint(codePoint))) {
                return "";
            }
            final int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > maxBytes) {
                return "";
            }
            builder.appendCodePoint(codePoint);
            bytes += nextBytes;
        }
        return builder.toString();
    }

    private static boolean safeTokenCodePoint(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '.'
                || codePoint == '_'
                || codePoint == '-'
                || codePoint == ':'
                || codePoint == '/';
    }

    private static boolean safeTextCodePoint(final int codePoint) {
        return codePoint >= 0x20
                && codePoint != 0x7F
                && codePoint != '<'
                && codePoint != '>'
                && !bidirectionalControl(codePoint);
    }

    private static boolean bidirectionalControl(final int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
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

    private static int utf8Length(final String value) {
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            final int codePoint = value.codePointAt(index);
            bytes += utf8Bytes(codePoint);
            index += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static boolean sensitive(final String text) {
        final String normalized = normalized(text);
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
                || normalized.contains("challenge")
                || normalized.contains("oauth")
                || normalized.contains("path")
                || normalized.contains("manifest")
                || normalized.contains("certificate")
                || normalized.contains("signature")
                || normalized.contains("key")
                || normalized.contains("implementation")
                || looksLikePath(text);
    }

    private static boolean looksLikePath(final String text) {
        return text.indexOf('/') >= 0
                || text.indexOf('\\') >= 0
                || (text.length() >= 3
                && ((text.charAt(0) >= 'A' && text.charAt(0) <= 'Z')
                || (text.charAt(0) >= 'a' && text.charAt(0) <= 'z'))
                && text.charAt(1) == ':'
                && (text.charAt(2) == '\\' || text.charAt(2) == '/'));
    }

    private static String normalized(final String text) {
        final StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char value = text.charAt(index);
            if (value >= 'A' && value <= 'Z') {
                builder.append((char) (value + ('a' - 'A')));
            } else if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
                builder.append(value);
            }
        }
        return builder.toString();
    }
}
