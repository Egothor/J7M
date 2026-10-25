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
import org.j7m.middleware.api.RuntimeStatus;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationRepositoryStatus;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * Public UI-neutral contracts for read-only nugget introspection.
 *
 * <p>The nested records are immutable snapshots suitable for CLI, JSON
 * automation, tests, demos, and future web/API adapters. They deliberately do
 * not contain terminal rendering, HTTP, implementation object, classloader,
 * raw stack trace, environment-value, PIN, verifier, credential, token, private
 * key, or mutable backend state.</p>
 */
public final class NuggetIntrospection {
    /**
     * Default collection page size.
     */
    public static final int DEFAULT_LIMIT = 50;
    /**
     * Maximum collection page size.
     */
    public static final int MAX_LIMIT = 500;
    /**
     * Maximum accepted query filter count.
     */
    public static final int MAX_FILTERS = 16;
    /**
     * Maximum accepted filter key length.
     */
    public static final int MAX_FILTER_KEY_LENGTH = 64;
    /**
     * Maximum accepted filter value length.
     */
    public static final int MAX_FILTER_VALUE_LENGTH = 256;
    private static final int MAX_REQUEST_METADATA_EXAMINED = 32;
    private static final int MAX_REQUEST_METADATA_RETAINED = 16;
    private static final int MAX_REQUEST_METADATA_KEY_UTF8_BYTES = 64;
    private static final int MAX_REQUEST_METADATA_VALUE_UTF8_BYTES = 256;
    private static final int MAX_REQUEST_METADATA_AGGREGATE_UTF8_BYTES = 1_024;

    private NuggetIntrospection() {
    }

    /**
     * Bounded execution context passed to read-only introspection providers.
     *
     * <p>The context carries only safe request metadata. Providers must return
     * immutable snapshots and must not retain the context. The deadline is a
     * local best-effort deadline for provider work; the runtime may cancel or
     * ignore late provider results after it expires.</p>
     *
     * @param correlationId safe correlation id
     * @param requestedAt request timestamp
     * @param deadlineAt provider deadline
     */
    public record ProviderContext(String correlationId, Instant requestedAt, Instant deadlineAt) {
        /**
         * Creates a safe immutable context.
         */
        public ProviderContext {
            correlationId = correlationId == null ? "" : correlationId;
            requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
            deadlineAt = deadlineAt == null ? requestedAt : deadlineAt;
        }
    }

    /**
     * Narrow read-only provider SPI for trusted runtime assembly.
     *
     * <p>Implementations must be thread-safe, bounded, and side-effect free.
     * They must return immutable snapshots and must not construct public
     * response DTOs that bypass runtime authorization, redaction, projection,
     * filtering, sorting, or paging. Providers run behind the nugget runtime's
     * bounded execution policy; they must honor the supplied deadline and
     * tolerate interruption or cancellation. Exceptions, overload, timeout, and
     * late results are isolated by the runtime and represented as partial
     * unavailable component state.</p>
     */
    public interface Provider {
        /**
         * Returns a runtime status snapshot, or {@code null} when unavailable.
         *
         * @param context bounded provider context
         * @return runtime status snapshot, or {@code null}
         */
        default RuntimeStatus runtimeStatus(ProviderContext context) {
            return null;
        }

        /**
         * Returns hosted application snapshots.
         *
         * @param context bounded provider context
         * @return application snapshots
         */
        default List<ApplicationActivation> applications(ProviderContext context) {
            return List.of();
        }

        /**
         * Returns application repository status, or {@code null}.
         *
         * @param context bounded provider context
         * @return repository status, or {@code null}
         */
        default ApplicationRepositoryStatus repositoryStatus(ProviderContext context) {
            return null;
        }

        /**
         * Returns recent safe errors.
         *
         * @param context bounded provider context
         * @return bounded recent errors
         */
        default List<RecentErrorEntry> recentErrors(ProviderContext context) {
            return List.of();
        }

        /**
         * Returns stable safe metrics.
         *
         * @param context bounded provider context
         * @return bounded metrics
         */
        default List<MetricEntry> metrics(ProviderContext context) {
            return List.of();
        }

        /**
         * Returns component summaries.
         *
         * @param context bounded provider context
         * @return component summaries
         */
        default List<ComponentSummary> components(ProviderContext context) {
            return List.of();
        }
    }

    /**
     * Read-only operation category used for authorization and audit.
     */
    public enum Category {
        /**
         * Nugget overview and identity.
         */
        OVERVIEW,
        /**
         * JVM, process, and environment summary.
         */
        ENVIRONMENT,
        /**
         * Disk and storage-root summary.
         */
        DISK,
        /**
         * Health summary.
         */
        HEALTH,
        /**
         * Readiness summary.
         */
        READINESS,
        /**
         * Runtime summary.
         */
        RUNTIME,
        /**
         * Transport summary.
         */
        TRANSPORTS,
        /**
         * Registry summary.
         */
        REGISTRY,
        /**
         * Application repository summary.
         */
        REPOSITORY,
        /**
         * Hosted application summary/detail.
         */
        APPLICATIONS,
        /**
         * Hosted object/service summary/detail.
         */
        OBJECTS,
        /**
         * Metrics summary.
         */
        METRICS,
        /**
         * Recent safe errors.
         */
        ERRORS
    }

    /**
     * Stable component availability categories.
     */
    public enum Availability {
        /**
         * Component data was collected.
         */
        AVAILABLE,
        /**
         * Component data was partially collected.
         */
        PARTIAL,
        /**
         * Component exists but is unavailable.
         */
        UNAVAILABLE,
        /**
         * Component is not configured for this nugget.
         */
        NOT_CONFIGURED,
        /**
         * Component state is unknown.
         */
        UNKNOWN
    }

    /**
     * Stable health vocabulary.
     */
    public enum HealthState {
        /**
         * Component is healthy.
         */
        HEALTHY,
        /**
         * Component is degraded but still partially useful.
         */
        DEGRADED,
        /**
         * Component is unhealthy.
         */
        UNHEALTHY,
        /**
         * Component health is unknown.
         */
        UNKNOWN
    }

    /**
     * Stable readiness vocabulary.
     */
    public enum ReadinessState {
        /**
         * Component is ready to serve its expected responsibility.
         */
        READY,
        /**
         * Component is not ready.
         */
        NOT_READY,
        /**
         * Component is ready with degraded behavior.
         */
        DEGRADED,
        /**
         * Component readiness is unknown.
         */
        UNKNOWN
    }

    /**
     * Disk health vocabulary.
     */
    public enum DiskHealth {
        /**
         * Disk root is usable.
         */
        HEALTHY,
        /**
         * Disk root is near limits or partially unavailable.
         */
        DEGRADED,
        /**
         * Disk root is unavailable or unusable.
         */
        UNHEALTHY,
        /**
         * Disk root health is unknown.
         */
        UNKNOWN
    }

    /**
     * Operations-plane object visibility categories.
     */
    public enum ObjectVisibility {
        /**
         * Object is exported through supported J7M lookup/transport mechanisms.
         */
        EXPORTED,
        /**
         * Object is runtime-internal and requires stronger admin-read authorization.
         */
        INTERNAL,
        /**
         * Object is private and requires explicit private-object inspection authorization.
         */
        PRIVATE
    }

    /**
     * Sort direction.
     */
    public enum SortDirection {
        /**
         * Ascending order.
         */
        ASC,
        /**
         * Descending order.
         */
        DESC
    }

    /**
     * Per-operation request metadata.
     *
     * <p>Admission id, correlation id, and metadata are bounded and copied
     * before authorization and audit. Metadata count, key size, value size, and
     * aggregate UTF-8 size are capped, and sensitive names or values are
     * replaced with stable safe markers.</p>
     *
     * @param admissionId accepted Phase 7.1 admission id
     * @param securityContext existing caller security context
     * @param correlationId audit correlation id
     * @param requestedAt request creation time
     * @param metadata safe request metadata
     */
    public record Request(
            String admissionId,
            SecurityContext securityContext,
            String correlationId,
            Instant requestedAt,
            Map<String, String> metadata) {
        /**
         * Creates immutable bounded and redacted request metadata.
         */
        public Request {
            admissionId = boundedRequestIdentifier(admissionId, 64);
            correlationId = boundedRequestIdentifier(correlationId, 64);
            requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
            metadata = boundedRequestMetadata(metadata);
        }
    }

    /**
     * Page, filter, and sort request for bounded collections.
     *
     * @param offset zero-based offset
     * @param limit maximum returned items
     * @param sortBy stable sort field
     * @param direction sort direction
     * @param filters safe exact-match filters
     */
    public record Query(
            int offset,
            int limit,
            String sortBy,
            SortDirection direction,
            Map<String, String> filters) {
        /**
         * Creates bounded immutable query metadata.
         */
        public Query {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            if (limit < 0 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit outside nugget introspection bounds");
            }
            limit = limit == 0 ? DEFAULT_LIMIT : limit;
            sortBy = sortBy == null || sortBy.isBlank() ? "id" : sortBy;
            direction = direction == null ? SortDirection.ASC : direction;
            filters = validateFilters(filters);
        }

        /**
         * Creates the default query.
         *
         * @return default query
         */
        public static Query all() {
            return new Query(0, DEFAULT_LIMIT, "id", SortDirection.ASC, Map.of());
        }
    }

    private static Map<String, String> validateFilters(final Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("too many nugget introspection filters");
        }
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || key.length() > MAX_FILTER_KEY_LENGTH) {
                throw new IllegalArgumentException("nugget introspection filter key outside bounds");
            }
            if (value == null || value.length() > MAX_FILTER_VALUE_LENGTH) {
                throw new IllegalArgumentException("nugget introspection filter value outside bounds");
            }
        }
        return Map.copyOf(filters);
    }

    private static Map<String, String> boundedRequestMetadata(final Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        boolean omitted = false;
        int examined = 0;
        int aggregateBytes = 0;
        try {
            java.util.Iterator<Map.Entry<String, String>> iterator = metadata.entrySet().iterator();
            while (examined < MAX_REQUEST_METADATA_EXAMINED) {
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
                Map.Entry<String, String> entry;
                try {
                    entry = iterator.next();
                } catch (RuntimeException exception) {
                    omitted = true;
                    break;
                }
                examined++;
                String key = boundedRequestToken(entry.getKey(), MAX_REQUEST_METADATA_KEY_UTF8_BYTES);
                String value = boundedRequestToken(entry.getValue(), MAX_REQUEST_METADATA_VALUE_UTF8_BYTES);
                if (key.isEmpty() || value.isEmpty() || sensitiveRequestText(key) || sensitiveRequestText(value)) {
                    omitted = true;
                    continue;
                }
                int entryBytes = utf8Length(key) + utf8Length(value);
                if (copy.size() >= MAX_REQUEST_METADATA_RETAINED - 1
                        || aggregateBytes + entryBytes > MAX_REQUEST_METADATA_AGGREGATE_UTF8_BYTES) {
                    omitted = true;
                    continue;
                }
                copy.putIfAbsent(key, value);
                aggregateBytes += entryBytes;
            }
            omitted = omitted || examined == MAX_REQUEST_METADATA_EXAMINED;
        } catch (RuntimeException exception) {
            omitted = true;
        }
        if (omitted && copy.size() < MAX_REQUEST_METADATA_RETAINED) {
            copy.putIfAbsent("detailsOmitted", "true");
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }

    private static String boundedRequestIdentifier(final String value, final int maxBytes) {
        String token = boundedRequestToken(value, maxBytes);
        return token.isEmpty() || sensitiveRequestText(token) ? "" : token;
    }

    private static String boundedRequestToken(final String value, final int maxBytes) {
        if (value == null || value.isBlank()) {
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
            if (!safeRequestCodePoint(codePoint)) {
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

    private static boolean safeRequestCodePoint(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '.'
                || codePoint == '_'
                || codePoint == '-'
                || codePoint == ':';
    }

    private static boolean sensitiveRequestText(final String value) {
        String normalized = normalizedRequestSensitivityText(value);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("privatekey")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || value.contains("://")
                || value.contains("..")
                || value.contains("\\\\")
                || windowsDrive(value);
    }

    private static String normalizedRequestSensitivityText(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= 'A' && current <= 'Z') {
                builder.append((char) (current + ('a' - 'A')));
            } else if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')) {
                builder.append(current);
            }
        }
        return builder.toString();
    }

    private static int utf8Length(final String value) {
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            bytes += utf8Bytes(codePoint);
        }
        return bytes;
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

    private static boolean windowsDrive(final String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }

    /**
     * Safe operation result envelope.
     *
     * @param admitted whether Phase 7.1 admission was accepted
     * @param authorized whether authorization allowed this read
     * @param category operation category
     * @param collectedAt collection timestamp
     * @param partial whether optional component data is partial
     * @param value response value, or {@code null} on failure
     * @param error normalized safe error, or {@code null}
     * @param warnings safe warnings
     * @param metadata safe metadata
     * @param <T> response value type
     */
    public record Result<T>(
            boolean admitted,
            boolean authorized,
            Category category,
            Instant collectedAt,
            boolean partial,
            T value,
            RemoteError error,
            List<String> warnings,
            Map<String, String> metadata) {
        /**
         * Creates immutable result metadata.
         */
        public Result {
            category = category == null ? Category.OVERVIEW : category;
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Bounded collection result.
     *
     * @param items returned items
     * @param offset applied offset
     * @param limit applied limit
     * @param returned returned item count
     * @param totalAvailable total authorized count, or {@code -1} when hidden
     * @param totalAvailableAuthorized whether totalAvailable is authorized
     * @param sortedBy applied sort field
     * @param direction applied sort direction
     * @param filters applied filters
     * @param collectedAt collection timestamp
     * @param partial whether provider data was partial
     * @param metadata safe metadata
     * @param <T> item type
     */
    public record CollectionResult<T>(
            List<T> items,
            int offset,
            int limit,
            int returned,
            int totalAvailable,
            boolean totalAvailableAuthorized,
            String sortedBy,
            SortDirection direction,
            Map<String, String> filters,
            Instant collectedAt,
            boolean partial,
            Map<String, String> metadata) {
        /**
         * Creates immutable collection metadata.
         */
        public CollectionResult {
            items = items == null ? List.of() : List.copyOf(items);
            if (offset < 0 || limit < 0 || returned < 0) {
                throw new IllegalArgumentException("collection bounds must not be negative");
            }
            sortedBy = sortedBy == null ? "id" : sortedBy;
            direction = direction == null ? SortDirection.ASC : direction;
            filters = filters == null ? Map.of() : Map.copyOf(filters);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Component status summary.
     *
     * @param component stable component id
     * @param availability component availability
     * @param health health state
     * @param readiness readiness state
     * @param required whether component is required for this nugget
     * @param safeReason safe status reason
     * @param metadata redacted metadata
     */
    public record ComponentSummary(
            String component,
            Availability availability,
            HealthState health,
            ReadinessState readiness,
            boolean required,
            String safeReason,
            Map<String, String> metadata) {
        /**
         * Creates immutable component metadata.
         */
        public ComponentSummary {
            component = component == null || component.isBlank() ? "unknown" : component;
            availability = availability == null ? Availability.UNKNOWN : availability;
            health = health == null ? HealthState.UNKNOWN : health;
            readiness = readiness == null ? ReadinessState.UNKNOWN : readiness;
            safeReason = safeReason == null ? "" : safeReason;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Nugget overview.
     *
     * @param status safe Phase 7.1 nugget status
     * @param runtime runtime summary
     * @param health health summary
     * @param readiness readiness summary
     * @param partial whether optional data is partial
     * @param warnings safe warnings
     */
    public record Overview(
            NuggetStatus status,
            RuntimeSummary runtime,
            HealthSummary health,
            ReadinessSummary readiness,
            boolean partial,
            List<String> warnings) {
        /**
         * Creates immutable overview metadata.
         */
        public Overview {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * JVM and process environment summary.
     *
     * @param jvmVersion JVM version
     * @param jvmVendor JVM vendor
     * @param vmName VM name
     * @param osName OS name
     * @param osVersion OS version
     * @param architecture CPU architecture
     * @param processId process id or empty string
     * @param nuggetStartTime nugget start time
     * @param processUptime uptime duration
     * @param availableProcessors processor count
     * @param heapUsedBytes heap used bytes
     * @param heapCommittedBytes heap committed bytes
     * @param heapMaxBytes heap max bytes, or {@code -1}
     * @param nonHeapUsedBytes non-heap used bytes
     * @param nonHeapCommittedBytes non-heap committed bytes
     * @param liveThreadCount live thread count
     * @param daemonThreadCount daemon thread count
     * @param peakThreadCount peak thread count
     * @param totalStartedThreadCount total started thread count
     * @param loadedClassCount loaded class count
     * @param totalLoadedClassCount total loaded class count
     * @param garbageCollectors garbage collector summaries
     * @param processCpuLoad process CPU load, or {@code -1}
     * @param systemCpuLoad system CPU load, or {@code -1}
     * @param processCpuTimeNanos process CPU time, or {@code -1}
     * @param workingDirectorySummary safe working-directory summary
     * @param environmentVariableNames environment variable names only
     * @param jvmArgumentSummaries redacted JVM argument summaries
     * @param capabilities capability availability markers
     * @param collectedAt collection timestamp
     */
    public record EnvironmentSummary(
            String jvmVersion,
            String jvmVendor,
            String vmName,
            String osName,
            String osVersion,
            String architecture,
            String processId,
            Instant nuggetStartTime,
            Duration processUptime,
            int availableProcessors,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long nonHeapCommittedBytes,
            int liveThreadCount,
            int daemonThreadCount,
            int peakThreadCount,
            long totalStartedThreadCount,
            int loadedClassCount,
            long totalLoadedClassCount,
            List<KeyValue> garbageCollectors,
            double processCpuLoad,
            double systemCpuLoad,
            long processCpuTimeNanos,
            String workingDirectorySummary,
            List<String> environmentVariableNames,
            List<KeyValue> jvmArgumentSummaries,
            Map<String, String> capabilities,
            Instant collectedAt) {
        /**
         * Creates immutable environment metadata.
         */
        public EnvironmentSummary {
            jvmVersion = jvmVersion == null ? "" : jvmVersion;
            jvmVendor = jvmVendor == null ? "" : jvmVendor;
            vmName = vmName == null ? "" : vmName;
            osName = osName == null ? "" : osName;
            osVersion = osVersion == null ? "" : osVersion;
            architecture = architecture == null ? "" : architecture;
            processId = processId == null ? "" : processId;
            nuggetStartTime = nuggetStartTime == null ? Instant.EPOCH : nuggetStartTime;
            processUptime = processUptime == null || processUptime.isNegative() ? Duration.ZERO : processUptime;
            garbageCollectors = garbageCollectors == null ? List.of() : List.copyOf(garbageCollectors);
            workingDirectorySummary = workingDirectorySummary == null ? "" : workingDirectorySummary;
            environmentVariableNames = environmentVariableNames == null ? List.of() : List.copyOf(environmentVariableNames);
            jvmArgumentSummaries = jvmArgumentSummaries == null ? List.of() : List.copyOf(jvmArgumentSummaries);
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        }
    }

    /**
     * Disk/storage root query.
     *
     * @param role optional role filter
     * @param page collection query
     */
    public record DiskQuery(String role, Query page) {
        /**
         * Creates immutable disk query metadata.
         */
        public DiskQuery {
            role = role == null ? "" : role;
            page = page == null ? Query.all() : page;
        }
    }

    /**
     * Disk/storage root summary.
     *
     * @param rootId safe root id
     * @param role primary storage role
     * @param roles deterministic set of storage roles for this physical root
     * @param pathSummary redacted path summary
     * @param totalBytes total bytes, or {@code -1}
     * @param usableBytes usable bytes, or {@code -1}
     * @param availableBytes available bytes, or {@code -1}
     * @param usedBytes used bytes, or {@code -1}
     * @param percentUsed percent used, or {@code -1}
     * @param health disk health
     * @param readOnly whether root is read-only
     * @param availability availability
     * @param collectedAt collection timestamp
     * @param safeErrorCode safe unavailable error code
     * @param metadata redacted metadata
     */
    public record DiskVolumeSummary(
            String rootId,
            String role,
            List<String> roles,
            String pathSummary,
            long totalBytes,
            long usableBytes,
            long availableBytes,
            long usedBytes,
            double percentUsed,
            DiskHealth health,
            boolean readOnly,
            Availability availability,
            Instant collectedAt,
            String safeErrorCode,
            Map<String, String> metadata) {
        /**
         * Creates immutable disk root metadata.
         */
        public DiskVolumeSummary {
            rootId = rootId == null || rootId.isBlank() ? role : rootId;
            role = role == null || role.isBlank() ? "unknown" : role;
            roles = roles == null || roles.isEmpty() ? List.of(role) : List.copyOf(roles);
            pathSummary = pathSummary == null ? "" : pathSummary;
            health = health == null ? DiskHealth.UNKNOWN : health;
            availability = availability == null ? Availability.UNKNOWN : availability;
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
            safeErrorCode = safeErrorCode == null ? "" : safeErrorCode;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Health response.
     *
     * @param state overall health
     * @param components component summaries
     * @param collectedAt collection timestamp
     * @param partial whether optional providers failed
     */
    public record HealthSummary(
            HealthState state,
            List<ComponentSummary> components,
            Instant collectedAt,
            boolean partial) {
        /**
         * Creates immutable health metadata.
         */
        public HealthSummary {
            state = state == null ? HealthState.UNKNOWN : state;
            components = components == null ? List.of() : List.copyOf(components);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        }
    }

    /**
     * Readiness response.
     *
     * @param state overall readiness
     * @param components component summaries
     * @param collectedAt collection timestamp
     * @param partial whether optional providers failed
     */
    public record ReadinessSummary(
            ReadinessState state,
            List<ComponentSummary> components,
            Instant collectedAt,
            boolean partial) {
        /**
         * Creates immutable readiness metadata.
         */
        public ReadinessSummary {
            state = state == null ? ReadinessState.UNKNOWN : state;
            components = components == null ? List.of() : List.copyOf(components);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        }
    }

    /**
     * Runtime status projection.
     *
     * @param runtimeId runtime id
     * @param nuggetId nugget id
     * @param lifecycleState lifecycle state
     * @param startTime start time
     * @param uptime uptime
     * @param activeObjectCount active object count
     * @param exportedObjectCount exported object count
     * @param hostedApplicationCount hosted application count
     * @param invocationCount invocation count
     * @param invocationFailureCount invocation failure count
     * @param recentFailureCount recent failure count
     * @param security safe security metadata
     * @param collectedAt collection timestamp
     */
    public record RuntimeSummary(
            String runtimeId,
            String nuggetId,
            NuggetState lifecycleState,
            Instant startTime,
            Duration uptime,
            int activeObjectCount,
            int exportedObjectCount,
            int hostedApplicationCount,
            long invocationCount,
            long invocationFailureCount,
            int recentFailureCount,
            Map<String, String> security,
            Instant collectedAt) {
        /**
         * Creates immutable runtime metadata.
         */
        public RuntimeSummary {
            runtimeId = runtimeId == null ? "" : runtimeId;
            nuggetId = nuggetId == null ? "" : nuggetId;
            lifecycleState = lifecycleState == null ? NuggetState.UNKNOWN : lifecycleState;
            startTime = startTime == null ? Instant.EPOCH : startTime;
            uptime = uptime == null || uptime.isNegative() ? Duration.ZERO : uptime;
            security = security == null ? Map.of() : Map.copyOf(security);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        }
    }

    /**
     * Transport endpoint summary.
     *
     * @param transportId transport id
     * @param endpointSummary redacted endpoint summary
     * @param running whether transport is running
     * @param metadata safe metadata
     */
    public record TransportSummary(String transportId, String endpointSummary, boolean running, Map<String, String> metadata) {
        /**
         * Creates immutable transport metadata.
         */
        public TransportSummary {
            transportId = transportId == null ? "" : transportId;
            endpointSummary = endpointSummary == null ? "" : endpointSummary;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Registry summary.
     *
     * @param recordCount safe record count
     * @param peerCount peer count
     * @param multicastListenerRunning multicast listener state
     * @param bootstrapHintCount bootstrap hint count
     * @param lookupCacheSize lookup cache size
     * @param metrics safe registry metrics
     * @param peerIds safe peer ids
     * @param recentEvents bounded safe events
     * @param collectedAt collection timestamp
     */
    public record RegistrySummary(
            int recordCount,
            int peerCount,
            boolean multicastListenerRunning,
            int bootstrapHintCount,
            int lookupCacheSize,
            Map<String, Long> metrics,
            List<String> peerIds,
            List<String> recentEvents,
            Instant collectedAt) {
        /**
         * Creates immutable registry metadata.
         */
        public RegistrySummary {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            peerIds = peerIds == null ? List.of() : List.copyOf(peerIds);
            recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        }
    }

    /**
     * Application repository summary.
     *
     * @param rootSummary redacted repository root summary
     * @param artifactCount artifact count
     * @param promotionCount promotion count
     * @param metadata redacted repository metadata
     * @param collectedAt collection timestamp
     */
    public record RepositorySummary(
            String rootSummary,
            int artifactCount,
            int promotionCount,
            Map<String, String> metadata,
            Instant collectedAt) {
        /**
         * Creates immutable repository metadata.
         */
        public RepositorySummary {
            rootSummary = rootSummary == null ? "" : rootSummary;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        }
    }

    /**
     * Application summary projection.
     *
     * @param applicationId application id
     * @param coordinate coordinate summary
     * @param instanceId instance id
     * @param displayName display name
     * @param version application version
     * @param state lifecycle state
     * @param activationId activation id
     * @param exportedServiceCount exported service count
     * @param health health state
     * @param readiness readiness state
     * @param lastSafeErrorCode last safe error code
     * @param metadata redacted metadata
     */
    public record ApplicationSummary(
            String applicationId,
            String coordinate,
            String instanceId,
            String displayName,
            String version,
            String state,
            String activationId,
            int exportedServiceCount,
            HealthState health,
            ReadinessState readiness,
            String lastSafeErrorCode,
            Map<String, String> metadata) {
        /**
         * Creates immutable application summary metadata.
         */
        public ApplicationSummary {
            applicationId = applicationId == null ? "" : applicationId;
            coordinate = coordinate == null ? "" : coordinate;
            instanceId = instanceId == null ? "" : instanceId;
            displayName = displayName == null ? "" : displayName;
            version = version == null ? "" : version;
            state = state == null ? "" : state;
            activationId = activationId == null ? "" : activationId;
            health = health == null ? HealthState.UNKNOWN : health;
            readiness = readiness == null ? ReadinessState.UNKNOWN : readiness;
            lastSafeErrorCode = lastSafeErrorCode == null ? "" : lastSafeErrorCode;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Application detail projection.
     *
     * @param summary base summary
     * @param declaredServices safe service summaries
     * @param moduleNames module names
     * @param capabilities safe capabilities
     * @param verificationStatus verification status
     * @param signatureStatus signature status
     * @param sbomStatus SBOM presence/status
     * @param provenanceStatus provenance status
     * @param auditCounters safe audit counters
     */
    public record ApplicationDetail(
            ApplicationSummary summary,
            List<ServiceSummary> declaredServices,
            List<String> moduleNames,
            Map<String, String> capabilities,
            String verificationStatus,
            String signatureStatus,
            String sbomStatus,
            String provenanceStatus,
            Map<String, Long> auditCounters) {
        /**
         * Creates immutable application detail metadata.
         */
        public ApplicationDetail {
            if (summary == null) {
                throw new IllegalArgumentException("summary must not be null");
            }
            declaredServices = declaredServices == null ? List.of() : List.copyOf(declaredServices);
            moduleNames = moduleNames == null ? List.of() : List.copyOf(moduleNames);
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
            verificationStatus = verificationStatus == null ? "unknown" : verificationStatus;
            signatureStatus = signatureStatus == null ? "unknown" : signatureStatus;
            sbomStatus = sbomStatus == null ? "unknown" : sbomStatus;
            provenanceStatus = provenanceStatus == null ? "unknown" : provenanceStatus;
            auditCounters = auditCounters == null ? Map.of() : Map.copyOf(auditCounters);
        }
    }

    /**
     * Object/service summary projection.
     *
     * @param nuggetId nugget id
     * @param objectId object id summary
     * @param logicalName logical name
     * @param serviceName service name
     * @param kind object kind
     * @param visibility visibility category
     * @param lifecycleState object lifecycle state
     * @param registrationState registry state
     * @param ownerApplication owner application id
     * @param interfaces exposed interface names
     * @param registryContext registry context
     * @param local whether object is local to this runtime
     * @param endpointSummary redacted endpoint summary
     * @param transportId transport id
     * @param health health state
     * @param readiness readiness state
     * @param leaseExpiryMillis lease expiry timestamp in milliseconds
     * @param generation object generation
     * @param invocationCount invocation count
     * @param failureCount failure count
     * @param lastInvocationTime last invocation timestamp
     * @param lastSafeErrorCode last safe error code
     * @param sensitive whether metadata was redacted for sensitivity
     * @param metadata redacted metadata
     */
    public record ObjectSummary(
            String nuggetId,
            String objectId,
            String logicalName,
            String serviceName,
            String kind,
            ObjectVisibility visibility,
            String lifecycleState,
            String registrationState,
            String ownerApplication,
            List<String> interfaces,
            String registryContext,
            boolean local,
            String endpointSummary,
            String transportId,
            HealthState health,
            ReadinessState readiness,
            long leaseExpiryMillis,
            long generation,
            long invocationCount,
            long failureCount,
            Instant lastInvocationTime,
            String lastSafeErrorCode,
            boolean sensitive,
            Map<String, String> metadata) {
        /**
         * Creates immutable object summary metadata.
         */
        public ObjectSummary {
            nuggetId = nuggetId == null ? "" : nuggetId;
            objectId = objectId == null ? "" : objectId;
            logicalName = logicalName == null ? "" : logicalName;
            serviceName = serviceName == null ? "" : serviceName;
            kind = kind == null ? "" : kind;
            visibility = visibility == null ? ObjectVisibility.EXPORTED : visibility;
            lifecycleState = lifecycleState == null ? "" : lifecycleState;
            registrationState = registrationState == null ? "" : registrationState;
            ownerApplication = ownerApplication == null ? "" : ownerApplication;
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
            registryContext = registryContext == null ? "default" : registryContext;
            endpointSummary = endpointSummary == null ? "" : endpointSummary;
            transportId = transportId == null ? "" : transportId;
            health = health == null ? HealthState.UNKNOWN : health;
            readiness = readiness == null ? ReadinessState.UNKNOWN : readiness;
            lastInvocationTime = lastInvocationTime == null ? Instant.EPOCH : lastInvocationTime;
            lastSafeErrorCode = lastSafeErrorCode == null ? "" : lastSafeErrorCode;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Object/service detail projection.
     *
     * @param summary base summary
     * @param descriptorMetadata supported descriptor metadata
     * @param annotationMetadata redacted supported annotation metadata
     */
    public record ObjectDetail(
            ObjectSummary summary,
            Map<String, String> descriptorMetadata,
            Map<String, String> annotationMetadata) {
        /**
         * Creates immutable object detail metadata.
         */
        public ObjectDetail {
            if (summary == null) {
                throw new IllegalArgumentException("summary must not be null");
            }
            descriptorMetadata = descriptorMetadata == null ? Map.of() : Map.copyOf(descriptorMetadata);
            annotationMetadata = annotationMetadata == null ? Map.of() : Map.copyOf(annotationMetadata);
        }
    }

    /**
     * Safe service summary.
     *
     * @param serviceName service name
     * @param interfaceName public interface name
     * @param implementationSummary redacted implementation summary
     */
    public record ServiceSummary(String serviceName, String interfaceName, String implementationSummary) {
        /**
         * Creates immutable service metadata.
         */
        public ServiceSummary {
            serviceName = serviceName == null ? "" : serviceName;
            interfaceName = interfaceName == null ? "" : interfaceName;
            implementationSummary = implementationSummary == null ? "" : implementationSummary;
        }
    }

    /**
     * Recent safe error query.
     *
     * @param component optional component filter
     * @param page collection query
     */
    public record RecentErrorQuery(String component, Query page) {
        /**
         * Creates immutable recent-error query metadata.
         */
        public RecentErrorQuery {
            component = component == null ? "" : component;
            page = page == null ? Query.all() : page;
        }
    }

    /**
     * Safe recent error entry.
     *
     * @param timestamp error timestamp
     * @param correlationId correlation id
     * @param component component id
     * @param resourceSummary safe resource summary
     * @param errorCode safe error code
     * @param safeMessage redacted safe message
     * @param retryable whether retry is safe
     * @param remoteRuntimeId safe remote runtime id
     * @param recurrenceCount recurrence count
     * @param metadata redacted metadata
     */
    public record RecentErrorEntry(
            Instant timestamp,
            String correlationId,
            String component,
            String resourceSummary,
            MiddlewareErrorCode errorCode,
            String safeMessage,
            boolean retryable,
            String remoteRuntimeId,
            long recurrenceCount,
            Map<String, String> metadata) {
        /**
         * Creates immutable recent-error metadata.
         */
        public RecentErrorEntry {
            timestamp = timestamp == null ? Instant.EPOCH : timestamp;
            correlationId = correlationId == null ? "" : correlationId;
            component = component == null ? "" : component;
            resourceSummary = resourceSummary == null ? "" : resourceSummary;
            errorCode = errorCode == null ? MiddlewareErrorCode.INTERNAL : errorCode;
            safeMessage = safeMessage == null ? "" : safeMessage;
            remoteRuntimeId = remoteRuntimeId == null ? "" : remoteRuntimeId;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Metrics summary.
     *
     * @param metrics bounded stable metric entries
     * @param collectedAt collection timestamp
     * @param partial whether optional metrics failed
     */
    public record MetricsSummary(List<MetricEntry> metrics, Instant collectedAt, boolean partial) {
        /**
         * Creates immutable metrics metadata.
         */
        public MetricsSummary {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        }
    }

    /**
     * Stable metric entry.
     *
     * @param id stable metric id
     * @param unit explicit unit
     * @param value numeric value
     * @param labels bounded redacted labels
     */
    public record MetricEntry(String id, String unit, double value, Map<String, String> labels) {
        /**
         * Creates immutable metric metadata.
         */
        public MetricEntry {
            id = id == null || id.isBlank() ? "unknown" : id;
            unit = unit == null || unit.isBlank() ? "count" : unit;
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }

    /**
     * Safe key/value pair.
     *
     * @param key key
     * @param value value
     */
    public record KeyValue(String key, String value) {
        /**
         * Creates immutable key/value metadata.
         */
        public KeyValue {
            key = key == null ? "" : key;
            value = value == null ? "" : value;
        }
    }
}
