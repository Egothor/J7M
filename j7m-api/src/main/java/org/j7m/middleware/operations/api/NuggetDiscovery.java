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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;

/**
 * Public UI-neutral nugget discovery contracts.
 *
 * <p>The records in this type describe unauthenticated discovery hints that can
 * later be used by a client to decide which nugget to contact. Discovery does
 * not prove endpoint reachability, does not authenticate a nugget, does not
 * admit a PIN, does not authorize a caller, and does not grant permission to use
 * an {@code ObjectRef} or any other runtime resource. All values are bounded
 * immutable snapshots suitable for CLI, JSON, and future web/API adapters.</p>
 */
public final class NuggetDiscovery {
    /**
     * Maximum public discovery result size.
     */
    public static final int MAX_RESULTS = 256;
    /**
     * Default public discovery result size.
     */
    public static final int DEFAULT_LIMIT = 64;
    /**
     * Maximum public discovery warning count.
     */
    public static final int MAX_WARNINGS = 16;
    /**
     * Maximum public discovery warning length.
     */
    public static final int MAX_WARNING_LENGTH = 128;
    private static final int MAX_CAPABILITIES = 32;
    private static final int MAX_CAPABILITY_SCAN = MAX_CAPABILITIES * 2;
    private static final int MAX_WARNING_SCAN = MAX_WARNINGS * 2;
    private static final List<String> SENSITIVE_NAME_WORDS = List.of(
            "password", "passwd", "secret", "token", "credential", "private", "bearer", "cookie", "session",
            "database", "cloud", "repository", "key");

    private NuggetDiscovery() {
    }

    /**
     * Discovery source category in descending trust priority.
     */
    public enum Source {
        /**
         * J7M-owned local protected discovery record.
         */
        LOCAL_RECORD,
        /**
         * Explicit configured endpoint target.
         */
        CONFIGURED_TARGET,
        /**
         * First-party registry or coordination source.
         */
        FIRST_PARTY_REGISTRY,
        /**
         * Explicitly enabled multicast or bootstrap source.
         */
        MULTICAST_BOOTSTRAP,
        /**
         * Source is not known.
         */
        UNKNOWN
    }

    /**
     * Public discovery record status.
     */
    public enum Status {
        /**
         * Record is active according to its own lease and current collection time.
         */
        ACTIVE,
        /**
         * Record is stale and must not resolve as active.
         */
        STALE,
        /**
         * Record conflicts with another source.
         */
        CONFLICTED,
        /**
         * Configured target has not yet disclosed a nugget identity.
         */
        UNRESOLVED,
        /**
         * Record could not be classified safely.
         */
        UNKNOWN
    }

    /**
     * Selector type for headless target resolution.
     */
    public enum SelectorType {
        /**
         * Select one nugget by exact immutable nugget id.
         */
        EXACT_ID,
        /**
         * Select by exact human-readable nugget name.
         */
        EXACT_NAME,
        /**
         * Select all active compatible nuggets.
         */
        ALL,
        /**
         * Select one configured endpoint identity.
         */
        ENDPOINT_ID
    }

    /**
     * Resolution outcome.
     */
    public enum ResolutionStatus {
        /**
         * Selector resolved to one or more active compatible records.
         */
        RESOLVED,
        /**
         * Selector matched no active compatible record.
         */
        NOT_FOUND,
        /**
         * Human-readable selector matched more than one active record.
         */
        AMBIGUOUS,
        /**
         * Matching record exists but is stale.
         */
        STALE,
        /**
         * Matching record has incompatible protocol information.
         */
        INCOMPATIBLE,
        /**
         * Matching source is configured but unresolved.
         */
        UNRESOLVED,
        /**
         * Matching records conflict and must not be selected automatically.
         */
        CONFLICTED
    }

    /**
     * Safe protocol compatibility summary.
     */
    public enum Compatibility {
        /**
         * Protocol version or range is compatible with this client.
         */
        COMPATIBLE,
        /**
         * Protocol version is known and incompatible.
         */
        INCOMPATIBLE,
        /**
         * Protocol compatibility cannot be determined from this hint.
         */
        UNKNOWN
    }

    /**
     * Safe endpoint descriptor.
     *
     * <p>The descriptor contains only minimal addressing data. It rejects URI
     * user information, query strings, fragments, blank schemes, invalid ports,
     * and overlong text. Possession of a descriptor is not an admission or
     * authorization credential.</p>
     *
     * @param transport logical administration transport or connector kind
     * @param scheme safe endpoint scheme or connector scheme
     * @param host safe host name or classification, empty for local endpoints
     * @param port TCP/UDP port, or {@code -1} when not applicable
     * @param localId safe local endpoint identifier, empty for remote endpoints
     */
    public record EndpointDescriptor(String transport, String scheme, String host, int port, String localId) {
        /**
         * Creates a bounded safe endpoint descriptor.
         */
        public EndpointDescriptor {
            transport = safeToken(transport, "local", 32);
            scheme = safeToken(scheme, transport, 32);
            host = host == null ? "" : safeText(host, 128);
            localId = safeLocalId(localId);
            if (port < -1 || port > 65_535) {
                throw new IllegalArgumentException("port outside endpoint range");
            }
            if (host.contains("@") || host.contains("?") || host.contains("#") || host.contains("/")) {
                throw new IllegalArgumentException("endpoint host is not safe");
            }
        }

        /**
         * Creates a local connector descriptor.
         *
         * @param localId local connector identity
         * @return endpoint descriptor
         */
        public static EndpointDescriptor local(final String localId) {
            return new EndpointDescriptor("local", "j7m-local", "", -1, localId);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            if (!this.localId.isBlank()) {
                return this.scheme + ":" + this.localId;
            }
            String suffix = this.port >= 0 ? ":" + this.port : "";
            return this.scheme + "://" + this.host + suffix;
        }
    }

    /**
     * Explicit configured target.
     *
     * @param endpoint endpoint descriptor
     * @param expectedNuggetId optional exact nugget id expected later
     * @param expectedName optional expected human-readable name
     * @param protocolMinimum expected minimum admin protocol version
     * @param protocolMaximum expected maximum admin protocol version
     */
    public record ConfiguredTarget(
            EndpointDescriptor endpoint,
            String expectedNuggetId,
            String expectedName,
            int protocolMinimum,
            int protocolMaximum) {
        /**
         * Creates a configured target using the current baseline protocol range.
         *
         * @param endpoint endpoint descriptor
         * @param expectedNuggetId optional exact nugget id expected later
         * @param expectedName optional expected human-readable name
         */
        public ConfiguredTarget(
                final EndpointDescriptor endpoint,
                final String expectedNuggetId,
                final String expectedName) {
            this(endpoint, expectedNuggetId, expectedName, 1, 1);
        }

        /**
         * Creates a configured target.
         */
        public ConfiguredTarget {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null");
            }
            if (protocolMinimum < 1 || protocolMaximum < protocolMinimum || protocolMaximum > 1000) {
                throw new IllegalArgumentException("protocol range outside policy");
            }
            expectedNuggetId = expectedNuggetId == null ? "" : safeToken(expectedNuggetId, "", 128);
            expectedName = safePublicName(expectedName);
        }
    }

    /**
     * Discovery query.
     *
     * @param includeStale whether stale records may be returned as stale hints
     * @param limit maximum result count, from 1 through {@link #MAX_RESULTS}
     * @param correlationId safe audit correlation id
     */
    public record Query(boolean includeStale, int limit, String correlationId) {
        /**
         * Creates a bounded discovery query.
         */
        public Query {
            if (limit <= 0 || limit > MAX_RESULTS) {
                throw new IllegalArgumentException("discovery limit outside policy");
            }
            correlationId = correlationId == null ? "" : safeToken(correlationId, "", 128);
        }

        /**
         * Returns the default active-record query.
         *
         * @return default query
         */
        public static Query active() {
            return new Query(false, DEFAULT_LIMIT, "");
        }
    }

    /**
     * Target selector for future CLI and automation use.
     *
     * @param type selector type
     * @param value exact id, exact name, or endpoint identity
     * @param correlationId safe audit correlation id
     */
    public record Selector(SelectorType type, String value, String correlationId) {
        /**
         * Creates a selector.
         */
        public Selector {
            type = type == null ? SelectorType.ALL : type;
            value = value == null ? "" : safeText(value, 128);
            correlationId = correlationId == null ? "" : safeToken(correlationId, "", 128);
            if (type != SelectorType.ALL && value.isBlank()) {
                throw new IllegalArgumentException("selector value must not be blank");
            }
        }
    }

    /**
     * Safe minimal discovery record.
     *
     * @param nuggetId immutable nugget id when known
     * @param nuggetName human-readable non-unique nugget name
     * @param runtimeId runtime id when safely available
     * @param endpoint safe endpoint descriptor
     * @param protocolMinimum minimum admin protocol version
     * @param protocolMaximum maximum admin protocol version
     * @param compatibility client compatibility summary
     * @param capabilities bounded safe capabilities
     * @param processId safe local process id for trusted local records
     * @param hostSummary safe host classification
     * @param startedAt nugget start time
     * @param lastSeenAt last observation time
     * @param expiresAt expiry time
     * @param source discovery source
     * @param status record status
     * @param warnings bounded safe warnings
     */
    public record Record(
            String nuggetId,
            String nuggetName,
            String runtimeId,
            EndpointDescriptor endpoint,
            int protocolMinimum,
            int protocolMaximum,
            Compatibility compatibility,
            List<String> capabilities,
            String processId,
            String hostSummary,
            Instant startedAt,
            Instant lastSeenAt,
            Instant expiresAt,
            Source source,
            Status status,
            List<String> warnings) {
        /**
         * Creates an immutable safe discovery record.
         */
        public Record {
            nuggetId = nuggetId == null ? "" : safeToken(nuggetId, "", 128);
            nuggetName = safePublicName(nuggetName);
            runtimeId = runtimeId == null ? "" : safeToken(runtimeId, "", 128);
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null");
            }
            if (protocolMinimum < 1 || protocolMaximum < protocolMinimum || protocolMaximum > 1000) {
                throw new IllegalArgumentException("protocol range outside policy");
            }
            compatibility = compatibility == null ? Compatibility.UNKNOWN : compatibility;
            capabilities = safeCapabilities(capabilities);
            processId = processId == null ? "" : safeToken(processId, "", 32);
            hostSummary = hostSummary == null ? "" : safeToken(hostSummary, "UNKNOWN", 64);
            startedAt = startedAt == null ? Instant.EPOCH : startedAt;
            lastSeenAt = lastSeenAt == null ? Instant.EPOCH : lastSeenAt;
            expiresAt = expiresAt == null ? Instant.EPOCH : expiresAt;
            source = source == null ? Source.UNKNOWN : source;
            status = status == null ? Status.UNKNOWN : status;
            warnings = safeWarnings(warnings);
        }

        /**
         * Returns whether the record is active at {@code now}.
         *
         * @param now collection timestamp
         * @return true when active
         */
        public boolean activeAt(final Instant now) {
            Instant value = now == null ? Instant.EPOCH : now;
            return this.status == Status.ACTIVE && this.expiresAt.isAfter(value);
        }
    }

    /**
     * Discovery collection result.
     *
     * @param records visible records
     * @param returned returned record count
     * @param totalAvailable visible total before the applied limit
     * @param collectedAt collection timestamp
     * @param partial whether any source failed safely
     * @param warnings bounded safe warnings
     */
    public record Result(
            List<Record> records,
            int returned,
            int totalAvailable,
            Instant collectedAt,
            boolean partial,
            List<String> warnings) {
        /**
         * Creates an immutable discovery result.
         */
        public Result {
            List<Record> safeRecords = records == null ? List.of() : records.stream()
                    .limit(MAX_RESULTS)
                    .toList();
            boolean truncatedRecords = records != null && records.size() > MAX_RESULTS;
            records = List.copyOf(safeRecords);
            returned = records.size();
            totalAvailable = Math.max(returned, totalAvailable);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
            warnings = safeWarnings(warnings);
            partial = partial || truncatedRecords;
        }
    }

    /**
     * Selector resolution result.
     *
     * @param status resolution status
     * @param records selected records
     * @param safeReason safe reason code
     * @param errorCode normalized safe error code
     * @param resolvedAt resolution timestamp
     * @param warnings bounded safe warnings
     */
    public record Resolution(
            ResolutionStatus status,
            List<Record> records,
            String safeReason,
            MiddlewareErrorCode errorCode,
            Instant resolvedAt,
            List<String> warnings) {
        /**
         * Creates an immutable resolution result.
         */
        public Resolution {
            status = status == null ? ResolutionStatus.NOT_FOUND : status;
            records = records == null ? List.of() : records.stream()
                    .limit(MAX_RESULTS)
                    .toList();
            safeReason = safeReason == null ? "" : safeToken(safeReason, "", 64);
            errorCode = errorCode;
            resolvedAt = resolvedAt == null ? Instant.EPOCH : resolvedAt;
            warnings = safeWarnings(warnings);
        }
    }

    /**
     * Safe discovery service status.
     *
     * @param localPublicationEnabled whether local publication is configured
     * @param configuredTargetCount configured target count
     * @param lastPublicationAt last successful local publication time
     * @param lastScanAt last scan time
     * @param activeRecordCount active visible record count from the last scan
     * @param metadata safe status metadata
     */
    public record StatusSummary(
            boolean localPublicationEnabled,
            int configuredTargetCount,
            Instant lastPublicationAt,
            Instant lastScanAt,
            int activeRecordCount,
            Map<String, String> metadata) {
        /**
         * Creates an immutable safe status summary.
         */
        public StatusSummary {
            configuredTargetCount = Math.max(0, configuredTargetCount);
            lastPublicationAt = lastPublicationAt == null ? Instant.EPOCH : lastPublicationAt;
            lastScanAt = lastScanAt == null ? Instant.EPOCH : lastScanAt;
            activeRecordCount = Math.max(0, activeRecordCount);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private static String safeToken(final String value, final String fallback, final int maxLength) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && builder.length() < maxLength; index++) {
            char c = value.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ':') {
                builder.append(c);
            }
        }
        return builder.isEmpty() ? fallback : builder.toString();
    }

    private static String safeText(final String value, final int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
        int inspected = 0;
        for (int index = 0; index < value.length() && inspected < maxLength && builder.length() < maxLength; index++) {
            inspected++;
            char c = value.charAt(index);
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String safePublicName(final String value) {
        if (containsIsoControl(value)) {
            return "";
        }
        String text = safeText(value, 128).trim();
        if (text.isBlank() || text.contains("/") || text.contains("\\") || text.contains(":") || text.contains("?")
                || text.contains("#") || text.contains("@")) {
            return "";
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String word : SENSITIVE_NAME_WORDS) {
            if (lower.contains(word)) {
                return "";
            }
        }
        if (looksOpaque(text)) {
            return "";
        }
        return text;
    }

    private static String safeLocalId(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String candidate = safeToken(value, "", 128);
        if (candidate.isBlank()) {
            return "";
        }
        String lower = candidate.toLowerCase(java.util.Locale.ROOT);
        for (String word : SENSITIVE_NAME_WORDS) {
            if (lower.contains(word)) {
                return "";
            }
        }
        return looksOpaque(candidate) ? "" : candidate;
    }

    private static List<String> safeCapabilities(final List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(MAX_CAPABILITIES);
        int inspected = 0;
        for (String capability : capabilities) {
            if (inspected >= MAX_CAPABILITY_SCAN) {
                break;
            }
            inspected++;
            String value = safeToken(capability, "", 64);
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
                if (values.size() >= MAX_CAPABILITIES) {
                    break;
                }
            }
        }
        values.sort(String::compareTo);
        return List.copyOf(values);
    }

    private static List<String> safeWarnings(final List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(MAX_WARNINGS);
        int inspected = 0;
        for (String warning : warnings) {
            if (inspected >= MAX_WARNING_SCAN) {
                break;
            }
            inspected++;
            String value = safeText(warning, MAX_WARNING_LENGTH).trim();
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
                if (values.size() >= MAX_WARNINGS) {
                    break;
                }
            }
        }
        values.sort(String::compareTo);
        return List.copyOf(values);
    }

    private static boolean containsIsoControl(final String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean looksOpaque(final String value) {
        int alphaNumeric = 0;
        int letters = 0;
        int digits = 0;
        int punctuation = 0;
        int longestAlphaNumericRun = 0;
        int currentAlphaNumericRun = 0;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isLetterOrDigit(c)) {
                alphaNumeric++;
                if (Character.isLetter(c)) {
                    letters++;
                } else {
                    digits++;
                }
                currentAlphaNumericRun++;
                longestAlphaNumericRun = Math.max(longestAlphaNumericRun, currentAlphaNumericRun);
            } else if (!Character.isWhitespace(c) && c != '-' && c != '_' && c != '.') {
                punctuation++;
                currentAlphaNumericRun = 0;
            } else {
                currentAlphaNumericRun = 0;
            }
        }
        if (longestAlphaNumericRun >= 24) {
            return true;
        }
        if (looksUuid(value)) {
            return true;
        }
        if (looksBase64Token(value)) {
            return true;
        }
        if (value.length() >= 20 && letters > 0 && digits > 0
                && alphaNumeric * 100 / Math.max(1, value.length()) >= 80) {
            return true;
        }
        return value.length() >= 32 && alphaNumeric >= 24 && punctuation > 2;
    }

    private static boolean looksUuid(final String value) {
        if (value.length() != 36) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (!isHex(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksBase64Token(final String value) {
        if (value.length() < 24) {
            return false;
        }
        int encodedCharacters = 0;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '+' || c == '/' || c == '=' || c == '-' || c == '_') {
                encodedCharacters++;
            } else {
                return false;
            }
        }
        return encodedCharacters >= 24;
    }

    private static boolean isHex(final char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }
}
