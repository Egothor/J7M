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

package org.j7m.middleware.registry.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryRecordVerifier;

/**
 * Shared registry-record trust admission logic for first-party registry
 * receivers.
 *
 * <p>The class is package-internal to the runtime module. It applies the same
 * structural, freshness, generation, context, replay, and verifier checks to
 * embedded, multicast, and bootstrap records before those records can enter
 * local candidate storage or result selection. Instances are thread-safe when
 * supplied with a thread-safe replay map.</p>
 */
public final class RegistryRecordTrust {
    private static final int MAX_REPLAY_ENTRIES = 4096;
    private static final long DEFAULT_FRESHNESS_MILLIS = 5L * 60L * 1000L;
    private final RegistryRecordVerifier verifier;
    private final boolean strictTrust;
    private final Clock clock;
    private final ConcurrentMap<String, ReplayEntry> replay;
    private final AtomicLong rejected;
    private final AtomicLong downgraded;

    /**
     * Creates shared trust admission logic.
     *
     * @param verifier optional record verifier
     * @param strictTrust whether unverifiable records are rejected
     * @param clock receiver clock
     * @param replay replay map owned by the receiving registry
     * @param rejected rejection counter
     * @param downgraded downgrade counter
     */
    public RegistryRecordTrust(
            final RegistryRecordVerifier verifier,
            final boolean strictTrust,
            final Clock clock,
            final ConcurrentMap<String, ReplayEntry> replay,
            final AtomicLong rejected,
            final AtomicLong downgraded) {
        this.verifier = verifier;
        this.strictTrust = strictTrust;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.replay = replay;
        this.rejected = rejected;
        this.downgraded = downgraded;
    }

    /**
     * Validates one received record for the supplied registry context.
     *
     * @param record received record
     * @param expectedContext expected registry context, or blank to use the record context
     * @return admitted record with trust metadata
     */
    public RegistryRecord admit(final RegistryRecord record, final String expectedContext) {
        if (record == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "registry record is required");
        }
        long nowMillis = this.clock.millis();
        TrustDecision structural = this.validateStructural(record, expectedContext, nowMillis);
        if (!structural.trusted()) {
            return this.rejectOrDowngrade(record, structural.reason());
        }
        TrustDecision signature = this.validateSignature(record, nowMillis);
        if (!signature.trusted()) {
            return this.rejectOrDowngrade(record, signature.reason());
        }
        TrustDecision replayDecision = this.validateReplay(record, nowMillis);
        if (!replayDecision.trusted()) {
            return this.rejectOrDowngrade(record, replayDecision.reason());
        }
        Map<String, String> metadata = new LinkedHashMap<>(record.authMetadata());
        metadata.put("trustStatus", "trusted");
        metadata.remove("trustReason");
        return record.withAuthentication(metadata);
    }

    /**
     * Returns whether a record is trusted for candidate selection.
     *
     * @param record record to test
     * @return true only when trust metadata marks the record trusted
     */
    public static boolean trustedCandidate(final RegistryRecord record) {
        return record != null && "trusted".equals(record.authMetadata().get("trustStatus"));
    }

    /**
     * Returns whether a record may be returned from candidate lookup/list APIs.
     *
     * <p>Records explicitly downgraded to {@code trustStatus=untrusted} and
     * records without a trust marker are retained only as local diagnostics and
     * must not become invocation candidates.</p>
     *
     * @param record record to test
     * @return true when the record is not explicitly downgraded
     */
    public static boolean candidateEligible(final RegistryRecord record) {
        return trustedCandidate(record);
    }

    /**
     * Marks an authoritative local publication as trusted without treating a
     * received discovery record as self-authenticating.
     *
     * <p>This method is intended only for the local registry publication path
     * owned by the exporting runtime. Remote registry receivers must use
     * {@link #admit(RegistryRecord, String)} so verifierless, unsigned, or
     * unverifiable records are rejected or downgraded according to trust mode.</p>
     *
     * @param record locally owned record
     * @return trusted local publication copy
     */
    public static RegistryRecord localTrustedPublication(final RegistryRecord record) {
        if (record == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "registry record is required");
        }
        Map<String, String> metadata = new LinkedHashMap<>(record.authMetadata());
        metadata.put("trustStatus", "trusted");
        metadata.remove("trustReason");
        return record.withAuthentication(metadata);
    }

    private TrustDecision validateStructural(final RegistryRecord record, final String expectedContext, final long nowMillis) {
        String context = expectedContext == null || expectedContext.isBlank() ? record.ref().registryContext() : expectedContext;
        if (!record.ref().registryContext().equals(context)) {
            return new TrustDecision(false, "context-mismatch");
        }
        if (!record.activeAt(nowMillis)) {
            return new TrustDecision(false, "stale-or-expired");
        }
        if (record.ref().generation() <= 0L) {
            return new TrustDecision(false, "stale-generation");
        }
        if (record.createdAtMillis() <= 0L || record.lastHeartbeatMillis() <= 0L) {
            return new TrustDecision(false, "missing-record-time");
        }
        if (record.lastHeartbeatMillis() < record.createdAtMillis()) {
            return new TrustDecision(false, "heartbeat-before-created");
        }
        if (record.createdAtMillis() > nowMillis + DEFAULT_FRESHNESS_MILLIS
                || record.lastHeartbeatMillis() > nowMillis + DEFAULT_FRESHNESS_MILLIS) {
            return new TrustDecision(false, "future-record-time");
        }
        if (record.leaseUntilMillis() != record.ref().leaseUntil()) {
            return new TrustDecision(false, "lease-mismatch");
        }
        return new TrustDecision(true, "accepted");
    }

    private TrustDecision validateReplay(final RegistryRecord record, final long nowMillis) {
        if (this.replay == null) {
            return new TrustDecision(true, "accepted");
        }
        this.evictReplay(nowMillis);
        String key = record.ref().registryContext() + "|" + record.ref().runtimeId() + "|" + record.ref().objectId();
        ReplayEntry next = new ReplayEntry(record.ref().generation(), record.createdAtMillis(), record.lastHeartbeatMillis(), record.leaseUntilMillis(), key);
        for (;;) {
            ReplayEntry previous = this.replay.get(key);
            if (previous == null) {
                if (this.replay.size() >= MAX_REPLAY_ENTRIES) {
                    this.evictReplay(nowMillis);
                    if (this.replay.size() >= MAX_REPLAY_ENTRIES) {
                        return new TrustDecision(false, "replay-window-full");
                    }
                }
                if (this.replay.putIfAbsent(key, next) == null) {
                    return new TrustDecision(true, "accepted");
                }
                continue;
            }
            if (!this.replayAdvances(previous, next)) {
                return new TrustDecision(false, "replayed-or-stale-record");
            }
            if (this.replay.replace(key, previous, next)) {
                return new TrustDecision(true, "accepted");
            }
        }
    }

    private boolean replayAdvances(final ReplayEntry previous, final ReplayEntry next) {
        if (next.generation() < previous.generation()) {
            return false;
        }
        if (next.generation() > previous.generation()) {
            return true;
        }
        return next.createdAtMillis() >= previous.createdAtMillis()
                && next.lastHeartbeatMillis() > previous.lastHeartbeatMillis()
                && next.leaseUntilMillis() >= previous.leaseUntilMillis();
    }

    private TrustDecision validateSignature(final RegistryRecord record, final long nowMillis) {
        if (this.verifier == null) {
            return new TrustDecision(false, "missing-verifier");
        }
        if (record.authMetadata().isEmpty()) {
            return new TrustDecision(false, "unsigned");
        }
        String issuedAt = record.authMetadata().get("issuedAt");
        if (issuedAt != null && !issuedAt.isBlank()) {
            try {
                long issuedAtMillis = Instant.parse(issuedAt).toEpochMilli();
                if (issuedAtMillis > nowMillis + DEFAULT_FRESHNESS_MILLIS) {
                    return new TrustDecision(false, "future-signature");
                }
            } catch (DateTimeParseException exception) {
                return new TrustDecision(false, "invalid-issued-at");
            }
        }
        try {
            return this.verifier.verify(record, record.authMetadata())
                    ? new TrustDecision(true, "trusted")
                    : new TrustDecision(false, "invalid-signature");
        } catch (RuntimeException exception) {
            return new TrustDecision(false, "verification-error");
        }
    }

    private RegistryRecord rejectOrDowngrade(final RegistryRecord record, final String reason) {
        if (this.strictTrust) {
            if (this.rejected != null) {
                this.rejected.incrementAndGet();
            }
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "registry record trust verification failed");
        }
        if (this.downgraded != null) {
            this.downgraded.incrementAndGet();
        }
        Map<String, String> metadata = new LinkedHashMap<>(record.authMetadata());
        metadata.put("trustStatus", "untrusted");
        metadata.put("trustReason", reason);
        return record.withAuthentication(metadata);
    }

    private void evictReplay(final long nowMillis) {
        for (Map.Entry<String, ReplayEntry> entry : this.replay.entrySet()) {
            if (entry.getValue().leaseUntilMillis() > 0L && entry.getValue().leaseUntilMillis() < nowMillis) {
                this.replay.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Replay cache entry for registry record admission.
     *
     * @param generation record generation
     * @param createdAtMillis record creation time
     * @param lastHeartbeatMillis last heartbeat time
     * @param leaseUntilMillis lease expiration
     * @param stableId stable cache key
     */
    public record ReplayEntry(
            long generation,
            long createdAtMillis,
            long lastHeartbeatMillis,
            long leaseUntilMillis,
            String stableId) {
    }

    private record TrustDecision(boolean trusted, String reason) {
    }
}
