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

package org.j7m.middleware.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.CredentialProvider;
import org.j7m.middleware.security.api.CredentialRotationListener;
import org.j7m.middleware.security.api.CredentialSnapshot;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Redactor;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityAuditSink;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityMode;

/**
 * Immutable options for {@link InMemoryWorkloadScheduler}.
 *
 * @param schedulerId scheduler id
 * @param runtimeId hosting runtime id
 * @param registryContext registry context
 * @param clock scheduler clock
 * @param security security configuration used for authorization and resource policy
 * @param maxTasks maximum retained tasks
 * @param maxConcurrentExecutions maximum concurrently executing tasks
 * @param streamBufferSize bounded stream buffer size
 * @param maxStreamItemBytes maximum approximate stream item size
 * @param defaultLeaseDuration default lease duration
 */
public record InMemorySchedulerOptions(
        String schedulerId,
        String runtimeId,
        String registryContext,
        Clock clock,
        SecurityConfiguration security,
        int maxTasks,
        int maxConcurrentExecutions,
        int streamBufferSize,
        int maxStreamItemBytes,
        Duration defaultLeaseDuration) {
    private static final int DEFAULT_MAX_METADATA_ENTRIES = 64;
    private static final int DEFAULT_MAX_METADATA_VALUE_BYTES = 1024;
    private static final int DEFAULT_MAX_PROGRESS_MESSAGE_BYTES = 512;
    private static final int DEFAULT_MAX_CHECKPOINT_METADATA_BYTES = 16 * 1024;
    private static final int DEFAULT_MAX_RETAINED_FAN_OUT_RESULTS = 64;
    private static final int DEFAULT_MAX_AGGREGATE_RESULT_BYTES = 1024 * 1024;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;

    /**
     * Creates validated options.
     */
    public InMemorySchedulerOptions {
        schedulerId = schedulerId == null || schedulerId.isBlank() ? "scheduler" : schedulerId;
        runtimeId = runtimeId == null || runtimeId.isBlank() ? "runtime" : runtimeId;
        registryContext = registryContext == null || registryContext.isBlank() ? "default" : registryContext;
        clock = clock == null ? Clock.systemUTC() : clock;
        security = security == null ? developmentSecurity() : security;
        maxTasks = maxTasks <= 0 ? 1024 : maxTasks;
        maxConcurrentExecutions = maxConcurrentExecutions <= 0 ? 64 : maxConcurrentExecutions;
        streamBufferSize = streamBufferSize <= 0 ? 64 : streamBufferSize;
        maxStreamItemBytes = maxStreamItemBytes <= 0 ? 1024 * 1024 : maxStreamItemBytes;
        defaultLeaseDuration = defaultLeaseDuration == null ? Duration.ofSeconds(30L) : defaultLeaseDuration;
    }

    /**
     * Returns conservative development defaults.
     *
     * @param schedulerId scheduler id
     * @param runtimeId runtime id
     * @return options
     */
    public static InMemorySchedulerOptions defaults(final String schedulerId, final String runtimeId) {
        return new InMemorySchedulerOptions(schedulerId, runtimeId, "default", Clock.systemUTC(), developmentSecurity(),
                1024, 64, 64, 1024 * 1024, Duration.ofSeconds(30L));
    }

    /**
     * Returns the maximum number of metadata entries accepted by scheduler
     * admission, progress, checkpoint, result, and fan-out paths.
     *
     * @return metadata entry limit
     */
    public int maxMetadataEntries() {
        return DEFAULT_MAX_METADATA_ENTRIES;
    }

    /**
     * Returns the maximum UTF-8 byte length accepted for one metadata key or
     * value.
     *
     * @return per-string metadata byte limit
     */
    public int maxMetadataValueBytes() {
        return DEFAULT_MAX_METADATA_VALUE_BYTES;
    }

    /**
     * Returns the maximum UTF-8 byte length accepted for a progress message.
     *
     * @return progress message byte limit
     */
    public int maxProgressMessageBytes() {
        return DEFAULT_MAX_PROGRESS_MESSAGE_BYTES;
    }

    /**
     * Returns the maximum aggregate UTF-8 byte length retained for checkpoint
     * metadata.
     *
     * @return checkpoint metadata byte limit
     */
    public int maxCheckpointMetadataBytes() {
        return DEFAULT_MAX_CHECKPOINT_METADATA_BYTES;
    }

    /**
     * Returns the maximum number of fan-out or scatter-gather targets accepted
     * in one request.
     *
     * @return target count limit
     */
    public int maxFanOutTargets() {
        return Math.min(this.maxTasks, DEFAULT_MAX_RETAINED_FAN_OUT_RESULTS);
    }

    /**
     * Returns the maximum number of successful or failed fan-out results
     * retained in one response.
     *
     * @return retained result limit
     */
    public int maxRetainedFanOutResults() {
        return DEFAULT_MAX_RETAINED_FAN_OUT_RESULTS;
    }

    /**
     * Returns the maximum aggregate UTF-8 byte length retained for fan-out and
     * scatter-gather response values and failure metadata.
     *
     * @return aggregate result byte limit
     */
    public int maxAggregateResultBytes() {
        return DEFAULT_MAX_AGGREGATE_RESULT_BYTES;
    }

    /**
     * Returns the finite payload limit applied when a task submission leaves
     * {@link org.j7m.middleware.workload.api.TaskOptions#maxPayloadBytes()} at
     * zero.
     *
     * <p>The API default remains source compatible with earlier callers, but
     * scheduler admission treats zero as this bounded implementation default
     * rather than as an unbounded payload allowance.</p>
     *
     * @return default payload byte limit
     */
    public int defaultMaxPayloadBytes() {
        return DEFAULT_MAX_PAYLOAD_BYTES;
    }

    private static SecurityConfiguration developmentSecurity() {
        return new SecurityConfiguration(SecurityMode.DEV, new AllowPolicy(), new NoopAudit(), new NoopRedactor(), false, true,
                1024 * 1024, 1024 * 1024, 64, Duration.ofSeconds(2L), new StaticCredentialProvider(), false,
                Duration.ofSeconds(30L), 4096, false, null);
    }

    private static final class AllowPolicy implements AuthorizationPolicy {
        @Override
        public String id() {
            return "scheduler-allow";
        }

        @Override
        public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
            return AuthorizationDecision.allow(this.id());
        }
    }

    private static final class NoopAudit implements SecurityAuditSink {
        @Override
        public void record(final AuditEvent event) {
        }

        @Override
        public java.util.List<AuditEvent> recentEvents() {
            return java.util.List.of();
        }

        @Override
        public long eventCount() {
            return 0L;
        }
    }

    private static final class NoopRedactor implements Redactor {
        @Override
        public String redact(final String key, final String value) {
            return value == null ? "" : value;
        }

        @Override
        public Map<String, String> redactMetadata(final Map<String, String> metadata) {
            return metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private static final class StaticCredentialProvider implements CredentialProvider {
        @Override
        public CredentialSnapshot current() {
            return new CredentialSnapshot(new Credential("dev", "1", Map.of()), Instant.now());
        }

        @Override
        public void addRotationListener(final CredentialRotationListener listener) {
        }
    }
}
