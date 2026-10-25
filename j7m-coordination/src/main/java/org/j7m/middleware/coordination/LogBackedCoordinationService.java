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

package org.j7m.middleware.coordination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.coordination.CasOptions;
import org.j7m.middleware.api.coordination.CasResult;
import org.j7m.middleware.api.coordination.CoordinateKey;
import org.j7m.middleware.api.coordination.CoordinateValue;
import org.j7m.middleware.api.coordination.CoordinateVersion;
import org.j7m.middleware.api.coordination.CoordinateWatcher;
import org.j7m.middleware.api.coordination.CoordinationAuditEvent;
import org.j7m.middleware.api.coordination.CoordinationException;
import org.j7m.middleware.api.coordination.CoordinationMetricsSnapshot;
import org.j7m.middleware.api.coordination.CoordinationRecoveryReport;
import org.j7m.middleware.api.coordination.CoordinationService;
import org.j7m.middleware.api.coordination.CoordinationStatus;
import org.j7m.middleware.api.coordination.Heartbeat;
import org.j7m.middleware.api.coordination.HeartbeatResult;
import org.j7m.middleware.api.coordination.JoinOptions;
import org.j7m.middleware.api.coordination.JoinResult;
import org.j7m.middleware.api.coordination.LeaseGrant;
import org.j7m.middleware.api.coordination.LeaseId;
import org.j7m.middleware.api.coordination.LeaseName;
import org.j7m.middleware.api.coordination.LeaseOptions;
import org.j7m.middleware.api.coordination.LeaseReleaseResult;
import org.j7m.middleware.api.coordination.LeaseRenewal;
import org.j7m.middleware.api.coordination.LeaseState;
import org.j7m.middleware.api.coordination.LeaseStatus;
import org.j7m.middleware.api.coordination.LeaveOptions;
import org.j7m.middleware.api.coordination.LeaveResult;
import org.j7m.middleware.api.coordination.MemberEndpoint;
import org.j7m.middleware.api.coordination.MemberIdentity;
import org.j7m.middleware.api.coordination.MemberId;
import org.j7m.middleware.api.coordination.MemberLiveness;
import org.j7m.middleware.api.coordination.MemberRole;
import org.j7m.middleware.api.coordination.MemberStatus;
import org.j7m.middleware.api.coordination.MembershipQuery;
import org.j7m.middleware.api.coordination.MembershipView;
import org.j7m.middleware.api.coordination.PrincipalRef;
import org.j7m.middleware.api.coordination.ReleaseLeaseOptions;
import org.j7m.middleware.api.coordination.SplitBrainStatus;
import org.j7m.middleware.api.coordination.SplitBrainSuspicion;
import org.j7m.middleware.api.coordination.WatchEvent;
import org.j7m.middleware.api.coordination.WatchEventType;
import org.j7m.middleware.api.coordination.WatchId;
import org.j7m.middleware.api.coordination.WatchRegistration;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogCorruptionAction;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.reliability.FencingToken;
import org.j7m.middleware.api.reliability.MonotonicEpoch;

/**
 * Durable-log-backed local coordination and membership service.
 *
 * <p>The implementation is thread-safe. It is local and non-consensus: leader
 * leases are exclusive local leases with fencing tokens, not Raft or quorum
 * leadership.</p>
 */
public final class LogBackedCoordinationService implements CoordinationService {
    private static final LogStreamId EVENT_STREAM = new LogStreamId("j7m-coordination-events");
    private static final String AUTHORITY = "j7m-coordination";
    private static final String EVENT_METADATA_KEY = "j7m-coordination-event";
    private static final String CHECKPOINT_EVENT_TYPE = "COORDINATION_CHECKPOINT";
    private static final String CHECKPOINT_VERSION = "1";
    private static final int MAX_RECOVERY_TAIL_EVENTS = 256;
    private static final int CHECKPOINT_LOOKUP_RECORD_LIMIT = 4096;
    private static final int MAX_QUEUED_WATCH_EVENTS = 1024;
    private static final int MAX_RETAINED_SUSPICIONS = 256;
    private static final int MAX_RETAINED_AUDIT_EVENTS = 1024;
    private final CoordinationConfig config;
    private final Map<MemberId, MemberRecord> members;
    private final Map<LeaseName, LeaseRecord> leases;
    private final Map<CoordinateKey, CoordinateRecord> coordinates;
    private final Map<WatchId, WatchRecord> watches;
    private final List<SplitBrainSuspicion> suspicions;
    private final List<CoordinationAuditEvent> auditEvents;
    private final BlockingQueue<WatchDelivery> watchDeliveries;
    private final Thread watchDispatcher;
    private final CoordinationMetrics metrics;
    private long idSequence;
    private long watchSequence;
    private long durableEventCount;
    private long eventsSinceCheckpoint;
    private boolean recovering;
    private boolean closed;
    private String lastError;
    private CoordinationRecoveryReport recoveryReport;

    /**
     * Creates and recovers a coordination service.
     *
     * @param config service configuration
     */
    public LogBackedCoordinationService(final CoordinationConfig config) {
        this.config = config;
        this.members = new LinkedHashMap<>();
        this.leases = new LinkedHashMap<>();
        this.coordinates = new LinkedHashMap<>();
        this.watches = new LinkedHashMap<>();
        this.suspicions = new ArrayList<>();
        this.auditEvents = new ArrayList<>();
        this.watchDeliveries = new ArrayBlockingQueue<>(MAX_QUEUED_WATCH_EVENTS);
        this.watchDispatcher = new Thread(this::runWatchDispatcher, "j7m-coordination-watch-dispatcher");
        this.watchDispatcher.setDaemon(true);
        this.metrics = new CoordinationMetrics();
        this.idSequence = 0L;
        this.watchSequence = 0L;
        this.durableEventCount = 0L;
        this.eventsSinceCheckpoint = 0L;
        this.recovering = false;
        this.closed = false;
        this.lastError = "";
        this.recoveryReport = this.recover();
        this.watchDispatcher.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MembershipView membership(final MembershipQuery query) {
        List<WatchEvent> events;
        MembershipView result;
        synchronized (this) {
            this.ensureOpen();
            events = this.evaluateLiveness(false);
            MembershipQuery safeQuery = query == null ? MembershipQuery.activeOnly() : query;
            List<MemberStatus> statuses = this.members.values().stream()
                    .filter(record -> safeQuery.includeLeft() || record.liveness != MemberLiveness.LEFT)
                    .filter(record -> safeQuery.includeDead() || record.liveness != MemberLiveness.DEAD)
                    .map(MemberRecord::status)
                    .sorted(Comparator.comparing(status -> status.identity().memberId().value()))
                    .toList();
            result = new MembershipView(this.config.clock().instant(), statuses);
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JoinResult join(final MemberIdentity member, final JoinOptions options) {
        List<WatchEvent> events;
        JoinResult result;
        synchronized (this) {
            this.ensureOpen();
            if (member == null) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "member identity is required", null);
            }
            JoinOptions safeOptions = options == null ? JoinOptions.defaults() : options;
            MemberRecord existing = this.members.get(member.memberId());
            if (existing != null) {
                if (safeOptions.allowDuplicate() && existing.identity.equals(member)) {
                    return new JoinResult(member.memberId(), existing.epoch, true);
                }
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "member already joined", null);
            }
            this.ensureRecordCapacity(this.members.size(), this.config.maxMembers(), "member limit exceeded");
            List<WatchEvent> splitBrainEvents = new ArrayList<>();
            for (MemberRecord record : this.members.values()) {
                if (record.liveness != MemberLiveness.LEFT && record.identity.endpoint().equals(member.endpoint())) {
                    splitBrainEvents.addAll(this.recordSplitBrain("CONFLICTING_ENDPOINT"));
                }
            }
            MonotonicEpoch epoch = new MonotonicEpoch(AUTHORITY, "member:" + member.memberId().value(), 1L);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("memberId", member.memberId().value());
            fields.put("endpoint", member.endpoint().value());
            fields.put("role", member.role().name());
            fields.put("metadata", CoordinationEventCodec.encodeStringMap(member.metadata()));
            fields.put("epoch", Long.toString(epoch.value()));
            fields.put("lastHeartbeat", Long.toString(this.config.clock().instant().toEpochMilli()));
            this.appendEvent(new CoordinationEvent("MEMBER_JOINED", fields));
            this.apply(new CoordinationEvent("MEMBER_JOINED", fields), true);
            result = new JoinResult(member.memberId(), epoch, false);
            events = new ArrayList<>(splitBrainEvents);
            events.add(new WatchEvent(WatchEventType.MEMBERSHIP_CHANGED, membershipKey(), this.config.clock().instant(), "member-joined"));
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LeaveResult leave(final MemberId memberId, final LeaveOptions options) {
        List<WatchEvent> events;
        LeaveResult result;
        synchronized (this) {
            this.ensureOpen();
            MemberRecord record = this.requireMember(memberId);
            LeaveOptions safeOptions = options == null ? LeaveOptions.defaults() : options;
            if (safeOptions.expectedEpoch() > 0L && safeOptions.expectedEpoch() != record.epoch.value()) {
                this.metrics.staleRejections++;
                this.audit("EPOCH_MISMATCH_REJECTED", Map.of(
                        "decision", "rejected",
                        "reasonCode", MiddlewareErrorCode.STALE_FENCING_TOKEN.name(),
                        "operation", "leave",
                        "memberId", memberId.value()));
                throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "member epoch is stale", null);
            }
            Map<String, String> fields = Map.of("memberId", memberId.value());
            this.appendEvent(new CoordinationEvent("MEMBER_LEFT", fields));
            this.apply(new CoordinationEvent("MEMBER_LEFT", fields), true);
            result = new LeaveResult(memberId, MemberLiveness.LEFT);
            events = List.of(new WatchEvent(WatchEventType.MEMBERSHIP_CHANGED, membershipKey(), this.config.clock().instant(), "member-left"));
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HeartbeatResult heartbeat(final MemberId memberId, final Heartbeat heartbeat) {
        List<WatchEvent> events;
        HeartbeatResult result;
        synchronized (this) {
            this.ensureOpen();
            MemberRecord record = this.requireHeartbeatMember(memberId);
            if (record.liveness == MemberLiveness.LEFT || record.liveness == MemberLiveness.DEAD) {
                this.metrics.staleRejections++;
                this.audit("HEARTBEAT_REJECTED", Map.of(
                        "decision", "rejected",
                        "reasonCode", MiddlewareErrorCode.INVALID_ARGUMENT.name(),
                        "memberId", record.identity.memberId().value(),
                        "liveness", record.liveness.name()));
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "terminal member cannot heartbeat", null);
            }
            Heartbeat safeHeartbeat = heartbeat == null ? new Heartbeat(this.config.clock().instant(), Map.of()) : heartbeat;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("memberId", memberId.value());
            fields.put("observedAt", Long.toString(safeHeartbeat.observedAt().toEpochMilli()));
            this.appendEvent(new CoordinationEvent("HEARTBEAT_RECORDED", fields));
            this.apply(new CoordinationEvent("HEARTBEAT_RECORDED", fields), true);
            result = new HeartbeatResult(memberId, MemberLiveness.ALIVE, record.epoch);
            events = List.of(new WatchEvent(WatchEventType.MEMBERSHIP_CHANGED, membershipKey(), this.config.clock().instant(), "heartbeat"));
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LeaseGrant acquireLease(final LeaseName name, final PrincipalRef owner, final LeaseOptions options) {
        List<WatchEvent> events;
        LeaseGrant result;
        synchronized (this) {
            this.ensureOpen();
            if (name == null || owner == null) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "lease name and owner are required", null);
            }
            LeaseOptions safeOptions = options == null ? LeaseOptions.defaults() : options;
            this.expireLeases(false);
            LeaseRecord existing = this.leases.get(name);
            if (existing != null && existing.state == LeaseState.ACTIVE) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "lease is already active", null);
            }
            if (existing == null) {
                this.ensureRecordCapacity(this.leases.size(), this.config.maxLeases(), "lease limit exceeded");
            }
            long epochValue = existing == null ? 1L : existing.epoch.value() + 1L;
            LeaseId leaseId = new LeaseId("lease-" + (++this.idSequence));
            MonotonicEpoch epoch = new MonotonicEpoch(AUTHORITY, "lease:" + name.value(), epochValue);
            FencingToken token = new FencingToken(AUTHORITY, "lease:" + name.value(), epochValue);
            Instant expiresAt = this.config.clock().instant().plus(safeOptions.ttl());
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("leaseName", name.value());
            fields.put("leaseId", leaseId.value());
            fields.put("owner", owner.value());
            fields.put("epoch", Long.toString(epoch.value()));
            fields.put("token", Long.toString(token.value()));
            fields.put("ttlMillis", Long.toString(safeOptions.ttl().toMillis()));
            fields.put("expiresAt", Long.toString(expiresAt.toEpochMilli()));
            fields.put("leaderLease", Boolean.toString(safeOptions.leaderLease()));
            this.appendEvent(new CoordinationEvent("LEASE_GRANTED", fields));
            this.apply(new CoordinationEvent("LEASE_GRANTED", fields), true);
            result = new LeaseGrant(name, leaseId, owner, epoch, token, expiresAt);
            events = List.of(new WatchEvent(WatchEventType.LEASE_CHANGED, leaseKey(name), this.config.clock().instant(), "lease-granted"));
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LeaseRenewal renewLease(final LeaseId leaseId, final long expectedEpoch) {
        List<WatchEvent> events;
        LeaseRenewal result;
        synchronized (this) {
            this.ensureOpen();
            LeaseRecord record = this.requireLeaseById(leaseId);
            this.requireActiveLease(record, "renew-lease");
            if (record.epoch.value() != expectedEpoch) {
                this.metrics.staleRejections++;
                this.audit("EPOCH_MISMATCH_REJECTED", Map.of(
                        "decision", "rejected",
                        "reasonCode", MiddlewareErrorCode.STALE_FENCING_TOKEN.name(),
                        "operation", "renew-lease",
                        "leaseName", record.name.value()));
                throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "lease epoch is stale", null);
            }
            Instant expiresAt = this.config.clock().instant().plusMillis(record.ttlMillis);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("leaseName", record.name.value());
            fields.put("leaseId", record.leaseId.value());
            fields.put("expectedEpoch", Long.toString(expectedEpoch));
            fields.put("expiresAt", Long.toString(expiresAt.toEpochMilli()));
            this.appendEvent(new CoordinationEvent("LEASE_RENEWED", fields));
            this.apply(new CoordinationEvent("LEASE_RENEWED", fields), true);
            result = new LeaseRenewal(leaseId, record.epoch, record.fencingToken, expiresAt);
            events = List.of(new WatchEvent(WatchEventType.LEASE_CHANGED, leaseKey(record.name), this.config.clock().instant(), "lease-renewed"));
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FencingToken fencingToken(final LeaseId leaseId) {
        synchronized (this) {
            this.ensureOpen();
            LeaseRecord record = this.requireLeaseById(leaseId);
            this.requireActiveLease(record, "fencing-token");
            return record.fencingToken;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LeaseReleaseResult releaseLease(final LeaseId leaseId, final ReleaseLeaseOptions options) {
        List<WatchEvent> events;
        LeaseReleaseResult result;
        synchronized (this) {
            this.ensureOpen();
            LeaseRecord record = this.requireLeaseById(leaseId);
            this.requireActiveLease(record, "release-lease");
            ReleaseLeaseOptions safeOptions = options == null ? new ReleaseLeaseOptions(0L) : options;
            if (safeOptions.expectedEpoch() > 0L && safeOptions.expectedEpoch() != record.epoch.value()) {
                this.metrics.staleRejections++;
                this.audit("EPOCH_MISMATCH_REJECTED", Map.of(
                        "decision", "rejected",
                        "reasonCode", MiddlewareErrorCode.STALE_FENCING_TOKEN.name(),
                        "operation", "release-lease",
                        "leaseName", record.name.value()));
                throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "lease release epoch is stale", null);
            }
            Map<String, String> fields = Map.of("leaseName", record.name.value(), "leaseId", leaseId.value());
            this.appendEvent(new CoordinationEvent("LEASE_RELEASED", fields));
            this.apply(new CoordinationEvent("LEASE_RELEASED", fields), true);
            result = new LeaseReleaseResult(leaseId, LeaseState.RELEASED);
            events = List.of(new WatchEvent(WatchEventType.LEASE_CHANGED, leaseKey(record.name), this.config.clock().instant(), "lease-released"));
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CasResult compareAndSet(
            final CoordinateKey key,
            final CoordinateValue expected,
            final CoordinateValue update,
            final CasOptions options) {
        List<WatchEvent> events = List.of();
        CasResult result;
        synchronized (this) {
            this.ensureOpen();
            if (key == null || update == null) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "coordinate key and update value are required", null);
            }
            this.ensureCoordinateValueWithinLimit(update);
            CasOptions safeOptions = options == null ? CasOptions.defaults() : options;
            LeaseRecord fencedLease = this.validateCasFence(safeOptions);
            CoordinateRecord current = this.coordinates.get(key);
            if (current == null) {
                this.ensureRecordCapacity(this.coordinates.size(), this.config.maxCoordinates(), "coordinate limit exceeded");
            }
            boolean valueMatches = expected == null ? current == null : current != null && expected.equals(current.value);
            boolean versionMatches = safeOptions.expectedVersion() == null
                    || (current != null && current.version.equals(safeOptions.expectedVersion()));
            if (!valueMatches || !versionMatches) {
                this.metrics.casFailures++;
                this.audit("CAS_MISMATCH", Map.of(
                        "decision", "mismatch",
                        "reasonCode", !valueMatches ? "VALUE_MISMATCH" : "VERSION_MISMATCH",
                        "key", key.value()));
                return new CasResult(key, false, current == null ? new CoordinateVersion(0L) : current.version, "MISMATCH");
            }
            CoordinateVersion newVersion = new CoordinateVersion(current == null ? 1L : current.version.value() + 1L);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("key", key.value());
            fields.put("value", CoordinationEventCodec.encodeBytes(update.bytes()));
            fields.put("coordinateVersion", Long.toString(newVersion.value()));
            if (fencedLease != null) {
                fields.put("leaseName", fencedLease.name.value());
                fields.put("leaseId", fencedLease.leaseId.value());
                fields.put("fencingAuthority", fencedLease.fencingToken.authority());
                fields.put("fencingScope", fencedLease.fencingToken.scope());
                fields.put("fencingToken", Long.toString(fencedLease.fencingToken.value()));
            }
            this.appendEvent(new CoordinationEvent("CAS_APPLIED", fields));
            this.apply(new CoordinationEvent("CAS_APPLIED", fields), true);
            result = new CasResult(key, true, newVersion, "APPLIED");
            events = List.of(new WatchEvent(WatchEventType.CAS_CHANGED, key, this.config.clock().instant(), "cas-applied"));
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CoordinateValue get(final CoordinateKey key) {
        synchronized (this) {
            this.ensureOpen();
            CoordinateRecord record = this.coordinates.get(key);
            return record == null ? null : record.value;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WatchRegistration watch(final CoordinateKey key, final CoordinateWatcher watcher) {
        synchronized (this) {
            this.ensureOpen();
            if (key == null || watcher == null) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "watch key and watcher are required", null);
            }
            if (this.watches.size() >= this.config.maxWatches()) {
                throw this.failure(MiddlewareErrorCode.OVERLOADED, "watch limit exceeded", null);
            }
            WatchId watchId = new WatchId("watch-" + (++this.watchSequence));
            WatchRecord record = new WatchRecord(watchId, key, watcher);
            this.watches.put(watchId, record);
            this.audit("WATCH_REGISTERED", Map.of("watchId", watchId.value(), "key", key.value()));
            return new RuntimeWatchRegistration(this, watchId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CoordinationStatus status() {
        List<WatchEvent> events;
        CoordinationStatus result;
        synchronized (this) {
            events = new ArrayList<>(this.evaluateLiveness(false));
            events.addAll(this.expireLeases(false));
            MembershipView membershipView = this.membershipSnapshot(MembershipQuery.all());
            result = new CoordinationStatus(!this.closed, membershipView, this.leaseStatuses(),
                    this.coordinates.size(), new SplitBrainStatus(!this.suspicions.isEmpty(), this.suspicions),
                    this.metrics.snapshot(), this.lastError);
        }
        this.dispatch(events);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized CoordinationRecoveryReport recoveryReport() {
        return this.recoveryReport;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        boolean shouldJoin = false;
        synchronized (this) {
            if (!this.closed) {
                this.closed = true;
                this.watches.clear();
                this.watchDeliveries.clear();
                this.watchDispatcher.interrupt();
                this.config.durableLog().close();
                shouldJoin = true;
            }
        }
        if (shouldJoin && Thread.currentThread() != this.watchDispatcher) {
            try {
                this.watchDispatcher.join(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns retained safe audit events.
     *
     * @return audit snapshot
     */
    public synchronized List<CoordinationAuditEvent> auditEvents() {
        return List.copyOf(this.auditEvents);
    }

    static long recoveryRecordScanLimit() {
        return (long) CHECKPOINT_LOOKUP_RECORD_LIMIT + (long) MAX_RECOVERY_TAIL_EVENTS + 1L;
    }

    private synchronized void closeWatch(final WatchId watchId) {
        WatchRecord removed = this.watches.remove(watchId);
        if (removed != null) {
            this.audit("WATCH_CLOSED", Map.of("watchId", watchId.value(), "key", removed.key.value()));
        }
    }

    private CoordinationRecoveryReport recover() {
        this.members.clear();
        this.leases.clear();
        this.coordinates.clear();
        this.suspicions.clear();
        this.durableEventCount = 0L;
        this.eventsSinceCheckpoint = 0L;
        long events = 0L;
        long tailEvents = 0L;
        String stage = "durable-log-recover";
        String eventType = "unknown";
        String eventVersion = "unknown";
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            this.recovering = true;
            this.config.durableLog().recover(this.recoveryOptions());
            stage = "open-cursor";
            RecoveryStart recoveryStart = this.recoveryStart();
            long maxRecordsToReplay = recoveryStart.checkpointExpected()
                    ? (long) CHECKPOINT_LOOKUP_RECORD_LIMIT
                    : (long) MAX_RECOVERY_TAIL_EVENTS;
            boolean firstRecord = true;
            try (LogCursor cursor = this.config.durableLog().openCursor(EVENT_STREAM, recoveryStart.offset(), new CursorOptions(true))) {
                Optional<LogRecord> next = cursor.next();
                while (next.isPresent()) {
                    if (events >= maxRecordsToReplay) {
                        throw new IllegalStateException("coordination recovery tail bound exceeded");
                    }
                    LogRecord record = next.orElseThrow();
                    stage = "decode";
                    CoordinationEventCodec.EventMetadata metadata = CoordinationEventCodec.metadata(record.payload());
                    eventType = metadata.type();
                    eventVersion = metadata.version();
                    CoordinationEvent event = CoordinationEventCodec.decode(record.payload());
                    eventType = event.type();
                    eventVersion = CoordinationEventCodec.SCHEMA_VERSION;
                    stage = "apply";
                    if (firstRecord && recoveryStart.checkpointExpected() && !CHECKPOINT_EVENT_TYPE.equals(event.type())) {
                        throw new IllegalStateException("coordination checkpoint recovery start is invalid");
                    }
                    this.apply(event, false);
                    if (!CHECKPOINT_EVENT_TYPE.equals(event.type())) {
                        this.durableEventCount++;
                        tailEvents++;
                        if (tailEvents > MAX_RECOVERY_TAIL_EVENTS) {
                            throw new IllegalStateException("coordination recovery tail bound exceeded");
                        }
                    } else {
                        tailEvents = 0L;
                    }
                    events++;
                    firstRecord = false;
                    next = cursor.next();
                }
            }
        } catch (DurableLogException exception) {
            this.recovering = false;
            CoordinationRecoveryReport report = this.failedRecoveryReport(events, warnings, errors, stage, eventType, eventVersion, exception.code());
            throw new CoordinationException(exception.code(), "coordination recovery failed", exception,
                    recoveryDetails(stage, eventType, eventVersion, exception.code()), report);
        } catch (RuntimeException exception) {
            this.recovering = false;
            CoordinationRecoveryReport report = this.failedRecoveryReport(events, warnings, errors, stage, eventType, eventVersion, MiddlewareErrorCode.RECOVERY_FAILED);
            throw new CoordinationException(MiddlewareErrorCode.RECOVERY_FAILED, "coordination recovery failed", exception,
                    recoveryDetails(stage, eventType, eventVersion, MiddlewareErrorCode.RECOVERY_FAILED), report);
        }
        this.recovering = false;
        this.eventsSinceCheckpoint = tailEvents;
        this.evaluateLiveness(false);
        this.expireLeases(false);
        this.metrics.recoveries++;
        CoordinationRecoveryReport report = new CoordinationRecoveryReport(events, this.members.size(), this.leases.size(), this.coordinates.size(), warnings, errors);
        this.recoveryReport = report;
        return report;
    }

    private CoordinationRecoveryReport failedRecoveryReport(
            final long events,
            final List<String> warnings,
            final List<String> errors,
            final String stage,
            final String eventType,
            final String eventVersion,
            final MiddlewareErrorCode code) {
        List<String> safeErrors = new ArrayList<>(errors);
        safeErrors.add("stage=" + safeDiagnostic(stage) + ",eventType=" + safeDiagnostic(eventType)
                + ",eventVersion=" + safeDiagnostic(eventVersion) + ",errorCode=" + code.name());
        CoordinationRecoveryReport report = new CoordinationRecoveryReport(events, this.members.size(), this.leases.size(), this.coordinates.size(), warnings, safeErrors);
        this.recoveryReport = report;
        this.lastError = code.name();
        this.audit("RECOVERY_EVENT_REJECTED", Map.of(
                "decision", "rejected",
                "reasonCode", code.name(),
                "stage", safeDiagnostic(stage),
                "eventType", safeDiagnostic(eventType),
                "eventVersion", safeDiagnostic(eventVersion)));
        return report;
    }

    private static Map<String, String> recoveryDetails(
            final String stage,
            final String eventType,
            final String eventVersion,
            final MiddlewareErrorCode code) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("recoveryStage", safeDiagnostic(stage));
        details.put("eventType", safeDiagnostic(eventType));
        details.put("eventVersion", safeDiagnostic(eventVersion));
        details.put("errorCode", code.name());
        return details;
    }

    private void appendEvent(final CoordinationEvent event) {
        this.ensureEventCanAppend();
        this.appendEncodedEvent(event);
        if (!CHECKPOINT_EVENT_TYPE.equals(event.type())) {
            this.durableEventCount++;
            this.eventsSinceCheckpoint++;
        }
    }

    private void appendEncodedEvent(final CoordinationEvent event) {
        try {
            LogRecord record = new LogRecord(LogRecordHeader.data(Map.of(EVENT_METADATA_KEY, event.type())),
                    CoordinationEventCodec.encode(event));
            this.config.durableLog().append(EVENT_STREAM, List.of(record), AppendOptions.defaults());
        } catch (RuntimeException exception) {
            throw this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "coordination event append failed", exception);
        }
    }

    private void ensureEventCanAppend() {
        if (!this.recovering && this.eventsSinceCheckpoint >= MAX_RECOVERY_TAIL_EVENTS) {
            this.appendCheckpoint();
        }
    }

    private void appendCheckpoint() {
        String body = this.checkpointBody();
        long checksum = checksum(body);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("checkpointVersion", CHECKPOINT_VERSION);
        fields.put("eventCount", Long.toString(this.durableEventCount));
        fields.put("checksum", Long.toUnsignedString(checksum));
        fields.put("body", body);
        this.appendEncodedEvent(new CoordinationEvent(CHECKPOINT_EVENT_TYPE, fields));
        this.eventsSinceCheckpoint = 0L;
    }

    private void apply(final CoordinationEvent event, final boolean countMetrics) {
        Map<String, String> fields = event.fields();
        if ("MEMBER_JOINED".equals(event.type())) {
            MemberId memberId = new MemberId(required(fields, "memberId"));
            MemberIdentity identity = new MemberIdentity(memberId, new MemberEndpoint(required(fields, "endpoint")),
                    MemberRole.valueOf(required(fields, "role")), CoordinationEventCodec.decodeStringMap(fields.get("metadata")));
            MonotonicEpoch epoch = new MonotonicEpoch(AUTHORITY, "member:" + memberId.value(), parseLong(required(fields, "epoch")));
            Instant lastHeartbeat = Instant.ofEpochMilli(parseLong(required(fields, "lastHeartbeat")));
            this.members.put(memberId, new MemberRecord(identity, MemberLiveness.JOINING, epoch, lastHeartbeat));
            if (countMetrics) {
                this.metrics.joins++;
                this.audit("MEMBER_JOINED", Map.of("memberId", memberId.value()));
            }
        } else if ("MEMBER_LEFT".equals(event.type())) {
            MemberRecord record = this.requireMember(new MemberId(required(fields, "memberId")));
            record.liveness = MemberLiveness.LEFT;
            if (countMetrics) {
                this.metrics.leaves++;
                this.audit("MEMBER_LEFT", Map.of("memberId", record.identity.memberId().value()));
            }
        } else if ("HEARTBEAT_RECORDED".equals(event.type())) {
            MemberRecord record = this.requireMember(new MemberId(required(fields, "memberId")));
            if (record.liveness == MemberLiveness.LEFT || record.liveness == MemberLiveness.DEAD) {
                if (countMetrics) {
                    this.metrics.staleRejections++;
                    this.audit("HEARTBEAT_REJECTED", Map.of(
                            "decision", "rejected",
                            "reasonCode", MiddlewareErrorCode.INVALID_ARGUMENT.name(),
                            "memberId", record.identity.memberId().value(),
                            "liveness", record.liveness.name()));
                }
                return;
            }
            record.lastHeartbeat = Instant.ofEpochMilli(parseLong(required(fields, "observedAt")));
            record.liveness = MemberLiveness.ALIVE;
            if (countMetrics) {
                this.metrics.heartbeats++;
                this.audit("HEARTBEAT_RECORDED", Map.of("memberId", record.identity.memberId().value()));
            }
        } else if ("MEMBER_LIVENESS_CHANGED".equals(event.type())) {
            MemberRecord record = this.requireMember(new MemberId(required(fields, "memberId")));
            record.liveness = MemberLiveness.valueOf(required(fields, "liveness"));
        } else if ("LEASE_GRANTED".equals(event.type())) {
            LeaseName name = new LeaseName(required(fields, "leaseName"));
            long epochValue = parseLong(required(fields, "epoch"));
            LeaseRecord record = new LeaseRecord(name, new LeaseId(required(fields, "leaseId")), new PrincipalRef(required(fields, "owner")),
                    new MonotonicEpoch(AUTHORITY, "lease:" + name.value(), epochValue),
                    new FencingToken(AUTHORITY, "lease:" + name.value(), parseLong(required(fields, "token"))),
                    Instant.ofEpochMilli(parseLong(required(fields, "expiresAt"))),
                    parseLong(required(fields, "ttlMillis")), Boolean.parseBoolean(required(fields, "leaderLease")), LeaseState.ACTIVE);
            this.leases.put(name, record);
            this.observeId(record.leaseId.value());
            if (countMetrics) {
                this.metrics.leaseGrants++;
                this.audit("LEASE_GRANTED", Map.of("leaseName", name.value(), "owner", record.owner.value()));
            }
        } else if ("LEASE_RENEWED".equals(event.type())) {
            LeaseRecord record = this.requireLeaseByName(new LeaseName(required(fields, "leaseName")));
            if (!record.leaseId.value().equals(required(fields, "leaseId"))) {
                throw new IllegalArgumentException("lease id mismatch");
            }
            record.expiresAt = Instant.ofEpochMilli(parseLong(required(fields, "expiresAt")));
            record.state = LeaseState.ACTIVE;
            if (countMetrics) {
                this.metrics.leaseRenewals++;
                this.audit("LEASE_RENEWED", Map.of("leaseName", record.name.value()));
            }
        } else if ("LEASE_RELEASED".equals(event.type())) {
            LeaseRecord record = this.requireLeaseByName(new LeaseName(required(fields, "leaseName")));
            if (!record.leaseId.value().equals(required(fields, "leaseId"))) {
                throw new IllegalArgumentException("lease id mismatch");
            }
            record.state = LeaseState.RELEASED;
            if (countMetrics) {
                this.metrics.leaseReleases++;
                this.audit("LEASE_RELEASED", Map.of("leaseName", record.name.value()));
            }
        } else if ("LEASE_EXPIRED".equals(event.type())) {
            LeaseRecord record = this.requireLeaseByName(new LeaseName(required(fields, "leaseName")));
            record.state = LeaseState.EXPIRED;
        } else if ("CAS_APPLIED".equals(event.type())) {
            CoordinateKey key = new CoordinateKey(required(fields, "key"));
            CoordinateVersion version = new CoordinateVersion(parseLong(required(fields, "coordinateVersion")));
            this.coordinates.put(key, new CoordinateRecord(key, new CoordinateValue(CoordinationEventCodec.decodeBytes(required(fields, "value"))), version));
            if (countMetrics) {
                this.metrics.casSuccesses++;
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("decision", "applied");
                metadata.put("reasonCode", "CAS_APPLIED");
                metadata.put("resourceType", "coordinate");
                metadata.put("key", key.value());
                metadata.put("version", Long.toString(version.value()));
                copyIfPresent(fields, metadata, "leaseName");
                copyIfPresent(fields, metadata, "leaseId");
                copyIfPresent(fields, metadata, "fencingAuthority");
                copyIfPresent(fields, metadata, "fencingScope");
                copyIfPresent(fields, metadata, "fencingToken");
                this.audit("CAS_APPLIED", metadata);
            }
        } else if ("SPLIT_BRAIN_SUSPECTED".equals(event.type())) {
            SplitBrainSuspicion suspicion = new SplitBrainSuspicion(Instant.ofEpochMilli(parseLong(required(fields, "observedAt"))),
                    required(fields, "reason"), required(fields, "severity"));
            this.suspicions.add(suspicion);
            while (this.suspicions.size() > MAX_RETAINED_SUSPICIONS) {
                this.suspicions.remove(0);
            }
            if (countMetrics) {
                this.metrics.splitBrainSuspicions++;
                this.audit("SPLIT_BRAIN_SUSPECTED", Map.of(
                        "decision", "suspected",
                        "reasonCode", "SPLIT_BRAIN_SUSPECTED",
                        "resourceType", "split-brain",
                        "reason", suspicion.reason(),
                        "severity", suspicion.severity()));
            }
        } else if (CHECKPOINT_EVENT_TYPE.equals(event.type())) {
            this.restoreCheckpoint(event);
        } else {
            throw new IllegalArgumentException("unknown coordination event type");
        }
    }

    private RecoveryStart recoveryStart() {
        Optional<LogOffset> checkpointOffset = this.config.durableLog().findLatestOffsetByMetadata(
                EVENT_STREAM, EVENT_METADATA_KEY, CHECKPOINT_EVENT_TYPE, CHECKPOINT_LOOKUP_RECORD_LIMIT);
        if (checkpointOffset.isPresent()) {
            return new RecoveryStart(checkpointOffset.orElseThrow(), true);
        }
        return new RecoveryStart(LogOffset.beginning(), false);
    }

    private RecoveryOptions recoveryOptions() {
        return RecoveryOptions.bounded(
                LogCorruptionAction.FAIL,
                true,
                RecoveryOptions.DEFAULT_MAX_SEGMENTS_SCANNED,
                recoveryRecordScanLimit());
    }

    private String checkpointBody() {
        StringBuilder builder = new StringBuilder(1024);
        for (MemberRecord record : this.members.values()) {
            appendFields(builder, "M", record.identity.memberId().value(), record.identity.endpoint().value(),
                    record.identity.role().name(), CoordinationEventCodec.encodeStringMap(record.identity.metadata()),
                    record.liveness.name(), Long.toString(record.epoch.value()),
                    Long.toString(record.lastHeartbeat.toEpochMilli()));
            this.ensureCheckpointWithinLimit(builder);
        }
        for (LeaseRecord record : this.leases.values()) {
            appendFields(builder, "L", record.name.value(), record.leaseId.value(), record.owner.value(),
                    Long.toString(record.epoch.value()), Long.toString(record.fencingToken.value()),
                    Long.toString(record.expiresAt.toEpochMilli()), Long.toString(record.ttlMillis),
                    Boolean.toString(record.leaderLease), record.state.name());
            this.ensureCheckpointWithinLimit(builder);
        }
        for (CoordinateRecord record : this.coordinates.values()) {
            appendFields(builder, "C", record.key.value(), CoordinationEventCodec.encodeBytes(record.value.bytes()),
                    Long.toString(record.version.value()));
            this.ensureCheckpointWithinLimit(builder);
        }
        for (SplitBrainSuspicion suspicion : this.suspicions) {
            appendFields(builder, "S", Long.toString(suspicion.observedAt().toEpochMilli()),
                    suspicion.reason(), suspicion.severity());
            this.ensureCheckpointWithinLimit(builder);
        }
        appendFields(builder, "Q", Long.toString(this.idSequence), Long.toString(this.durableEventCount));
        this.ensureCheckpointWithinLimit(builder);
        return builder.toString();
    }

    private void restoreCheckpoint(final CoordinationEvent event) {
        Map<String, String> fields = event.fields();
        String checkpointVersion = required(fields, "checkpointVersion");
        if (!CHECKPOINT_VERSION.equals(checkpointVersion)) {
            throw new IllegalArgumentException("unsupported coordination checkpoint version");
        }
        String body = required(fields, "body");
        if (body.getBytes(StandardCharsets.UTF_8).length > this.config.maxCheckpointBytes()) {
            throw new IllegalArgumentException("coordination checkpoint body exceeds bounds");
        }
        long expectedChecksum = Long.parseUnsignedLong(required(fields, "checksum"));
        if (expectedChecksum != checksum(body)) {
            throw new IllegalArgumentException("coordination checkpoint checksum mismatch");
        }
        this.members.clear();
        this.leases.clear();
        this.coordinates.clear();
        this.suspicions.clear();
        this.idSequence = 0L;
        this.durableEventCount = parseLong(required(fields, "eventCount"));
        this.parseCheckpointBody(body);
    }

    private void parseCheckpointBody(final String body) {
        int position = 0;
        while (position < body.length()) {
            int lineEnd = body.indexOf('\n', position);
            if (lineEnd < 0) {
                throw new IllegalArgumentException("coordination checkpoint line is truncated");
            }
            if (lineEnd > position) {
                this.applyCheckpointLine(decodeCheckpointFields(body, position, lineEnd));
            }
            position = lineEnd + 1;
        }
    }

    private void applyCheckpointLine(final List<String> fields) {
        String kind = fields.getFirst();
        if ("M".equals(kind)) {
            requireFieldCount(fields, 8);
            MemberId memberId = new MemberId(fields.get(1));
            MemberIdentity identity = new MemberIdentity(memberId, new MemberEndpoint(fields.get(2)),
                    MemberRole.valueOf(fields.get(3)), CoordinationEventCodec.decodeStringMap(fields.get(4)));
            MemberLiveness liveness = MemberLiveness.valueOf(fields.get(5));
            MonotonicEpoch epoch = new MonotonicEpoch(AUTHORITY, "member:" + memberId.value(), parseLong(fields.get(6)));
            this.ensureRecordCapacity(this.members.size(), this.config.maxMembers(), "member limit exceeded");
            this.members.put(memberId, new MemberRecord(identity, liveness, epoch, Instant.ofEpochMilli(parseLong(fields.get(7)))));
        } else if ("L".equals(kind)) {
            requireFieldCount(fields, 10);
            LeaseName name = new LeaseName(fields.get(1));
            long epoch = parseLong(fields.get(4));
            LeaseRecord record = new LeaseRecord(name, new LeaseId(fields.get(2)), new PrincipalRef(fields.get(3)),
                    new MonotonicEpoch(AUTHORITY, "lease:" + name.value(), epoch),
                    new FencingToken(AUTHORITY, "lease:" + name.value(), parseLong(fields.get(5))),
                    Instant.ofEpochMilli(parseLong(fields.get(6))), parseLong(fields.get(7)),
                    Boolean.parseBoolean(fields.get(8)), LeaseState.valueOf(fields.get(9)));
            this.ensureRecordCapacity(this.leases.size(), this.config.maxLeases(), "lease limit exceeded");
            this.leases.put(name, record);
            this.observeId(record.leaseId.value());
        } else if ("C".equals(kind)) {
            requireFieldCount(fields, 4);
            CoordinateKey key = new CoordinateKey(fields.get(1));
            CoordinateValue value = new CoordinateValue(CoordinationEventCodec.decodeBytes(fields.get(2)));
            this.ensureCoordinateValueWithinLimit(value);
            this.ensureRecordCapacity(this.coordinates.size(), this.config.maxCoordinates(), "coordinate limit exceeded");
            this.coordinates.put(key, new CoordinateRecord(key, value, new CoordinateVersion(parseLong(fields.get(3)))));
        } else if ("S".equals(kind)) {
            requireFieldCount(fields, 4);
            this.suspicions.add(new SplitBrainSuspicion(Instant.ofEpochMilli(parseLong(fields.get(1))),
                    fields.get(2), fields.get(3)));
            while (this.suspicions.size() > MAX_RETAINED_SUSPICIONS) {
                this.suspicions.remove(0);
            }
        } else if ("Q".equals(kind)) {
            requireFieldCount(fields, 3);
            this.idSequence = Math.max(this.idSequence, parseLong(fields.get(1)));
            this.durableEventCount = Math.max(this.durableEventCount, parseLong(fields.get(2)));
        } else {
            throw new IllegalArgumentException("unknown coordination checkpoint line");
        }
    }

    private List<WatchEvent> evaluateLiveness(final boolean durable) {
        List<WatchEvent> events = new ArrayList<>();
        Instant now = this.config.clock().instant();
        for (MemberRecord record : this.members.values()) {
            if (record.liveness == MemberLiveness.LEFT || record.liveness == MemberLiveness.DEAD) {
                continue;
            }
            MemberLiveness target = record.liveness;
            if (!record.lastHeartbeat.plus(this.config.heartbeatOptions().deadAfter()).isAfter(now)) {
                target = MemberLiveness.DEAD;
            } else if (!record.lastHeartbeat.plus(this.config.heartbeatOptions().suspectAfter()).isAfter(now)) {
                target = MemberLiveness.SUSPECT;
            }
            if (target != record.liveness) {
                record.liveness = target;
                this.audit("MEMBER_LIVENESS_CHANGED", Map.of("memberId", record.identity.memberId().value(), "liveness", target.name()));
                if (durable) {
                    this.appendEvent(new CoordinationEvent("MEMBER_LIVENESS_CHANGED", Map.of(
                            "memberId", record.identity.memberId().value(), "liveness", target.name())));
                }
                events.add(new WatchEvent(WatchEventType.MEMBERSHIP_CHANGED, membershipKey(), now, "member-liveness-" + target.name()));
            }
        }
        return events;
    }

    private List<WatchEvent> expireLeases(final boolean durable) {
        List<WatchEvent> events = new ArrayList<>();
        Instant now = this.config.clock().instant();
        for (LeaseRecord record : this.leases.values()) {
            if (record.state == LeaseState.ACTIVE && !record.expiresAt.isAfter(now)) {
                record.state = LeaseState.EXPIRED;
                this.audit("LEASE_EXPIRED", Map.of("leaseName", record.name.value()));
                if (durable) {
                    this.appendEvent(new CoordinationEvent("LEASE_EXPIRED", Map.of("leaseName", record.name.value())));
                }
                events.add(new WatchEvent(WatchEventType.LEASE_CHANGED, leaseKey(record.name), now, "lease-expired"));
            }
        }
        return events;
    }

    private LeaseRecord validateCasFence(final CasOptions options) {
        if (options.fencingToken() == null) {
            return null;
        }
        LeaseRecord record = options.requiredLeaseId() == null ? this.findLeaseByToken(options.fencingToken()) : this.requireLeaseById(options.requiredLeaseId());
        this.requireActiveLease(record, "cas");
        if (!record.fencingToken.authority().equals(options.fencingToken().authority())
                || !record.fencingToken.scope().equals(options.fencingToken().scope())
                || record.fencingToken.value() != options.fencingToken().value()) {
            this.metrics.staleRejections++;
            this.audit("FENCING_TOKEN_REJECTED", Map.of(
                    "decision", "rejected",
                    "reasonCode", MiddlewareErrorCode.STALE_FENCING_TOKEN.name(),
                    "operation", "cas",
                    "leaseName", record.name.value()));
            throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "fencing token is stale", null);
        }
        return record;
    }

    private LeaseRecord findLeaseByToken(final FencingToken token) {
        for (LeaseRecord record : this.leases.values()) {
            if (record.fencingToken.authority().equals(token.authority())
                    && record.fencingToken.scope().equals(token.scope())
                    && record.fencingToken.value() == token.value()) {
                return record;
            }
        }
        this.metrics.staleRejections++;
        this.audit("FENCING_TOKEN_REJECTED", Map.of(
                "decision", "rejected",
                "reasonCode", MiddlewareErrorCode.STALE_FENCING_TOKEN.name(),
                "operation", "cas"));
        throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "fencing token is stale", null);
    }

    private List<WatchEvent> recordSplitBrain(final String reason) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("observedAt", Long.toString(this.config.clock().instant().toEpochMilli()));
        fields.put("reason", reason);
        fields.put("severity", "diagnostic");
        this.appendEvent(new CoordinationEvent("SPLIT_BRAIN_SUSPECTED", fields));
        this.apply(new CoordinationEvent("SPLIT_BRAIN_SUSPECTED", fields), true);
        return List.of(new WatchEvent(WatchEventType.SPLIT_BRAIN_SUSPECTED, splitBrainKey(), this.config.clock().instant(), "split-brain-suspected"));
    }

    private void dispatch(final List<WatchEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<WatchDelivery> deliveries = new ArrayList<>();
        synchronized (this) {
            if (this.closed) {
                return;
            }
            for (WatchEvent event : events) {
                for (WatchRecord record : this.watches.values()) {
                    if (record.key.equals(event.key())) {
                        deliveries.add(new WatchDelivery(record.watchId, event));
                    }
                }
            }
        }
        for (WatchDelivery delivery : deliveries) {
            if (!this.watchDeliveries.offer(delivery)) {
                synchronized (this) {
                    this.lastError = MiddlewareErrorCode.OVERLOADED.name();
                    this.audit("WATCH_EVENT_DROPPED", Map.of(
                            "decision", "dropped",
                            "reasonCode", MiddlewareErrorCode.OVERLOADED.name(),
                            "watchId", delivery.watchId.value(),
                            "eventType", delivery.event.type().name()));
                }
            }
        }
    }

    private void runWatchDispatcher() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchDelivery delivery = this.watchDeliveries.take();
                WatchRecord record;
                synchronized (this) {
                    if (this.closed) {
                        return;
                    }
                    record = this.watches.get(delivery.watchId);
                }
                if (record != null) {
                    this.invokeWatcher(record, delivery.event);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void invokeWatcher(final WatchRecord record, final WatchEvent event) {
        try {
            record.watcher.onEvent(event);
            synchronized (this) {
                this.metrics.watchNotifications++;
            }
        } catch (RuntimeException exception) {
            synchronized (this) {
                this.lastError = MiddlewareErrorCode.INTERNAL.name();
                this.audit("WATCH_CALLBACK_FAILED", Map.of(
                        "decision", "failed",
                        "reasonCode", MiddlewareErrorCode.INTERNAL.name(),
                        "watchId", record.watchId.value(),
                        "eventType", event.type().name()));
            }
        }
    }

    private MembershipView membershipSnapshot(final MembershipQuery query) {
        MembershipQuery safeQuery = query == null ? MembershipQuery.activeOnly() : query;
        List<MemberStatus> statuses = this.members.values().stream()
                .filter(record -> safeQuery.includeLeft() || record.liveness != MemberLiveness.LEFT)
                .filter(record -> safeQuery.includeDead() || record.liveness != MemberLiveness.DEAD)
                .map(MemberRecord::status)
                .sorted(Comparator.comparing(status -> status.identity().memberId().value()))
                .toList();
        return new MembershipView(this.config.clock().instant(), statuses);
    }

    private List<LeaseStatus> leaseStatuses() {
        return this.leases.values().stream()
                .map(LeaseRecord::status)
                .sorted(Comparator.comparing(status -> status.leaseName().value()))
                .toList();
    }

    private void requireActiveLease(final LeaseRecord record, final String operation) {
        if (record.state != LeaseState.ACTIVE || !record.expiresAt.isAfter(this.config.clock().instant())) {
            this.metrics.staleRejections++;
            this.audit("STALE_LEASE_REJECTED", Map.of(
                    "decision", "rejected",
                    "reasonCode", MiddlewareErrorCode.STALE_FENCING_TOKEN.name(),
                    "operation", safeDiagnostic(operation),
                    "leaseName", record.name.value()));
            throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "lease is stale", null);
        }
    }

    private MemberRecord requireMember(final MemberId memberId) {
        if (memberId == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "member id is required", null);
        }
        MemberRecord record = this.members.get(memberId);
        if (record == null) {
            throw this.failure(MiddlewareErrorCode.NOT_FOUND, "member not found", null);
        }
        return record;
    }

    private MemberRecord requireHeartbeatMember(final MemberId memberId) {
        if (memberId == null) {
            this.audit("HEARTBEAT_REJECTED", Map.of(
                    "decision", "rejected",
                    "reasonCode", MiddlewareErrorCode.INVALID_ARGUMENT.name()));
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "member id is required", null);
        }
        MemberRecord record = this.members.get(memberId);
        if (record == null) {
            this.audit("HEARTBEAT_REJECTED", Map.of(
                    "decision", "rejected",
                    "reasonCode", MiddlewareErrorCode.NOT_FOUND.name(),
                    "memberId", memberId.value()));
            throw this.failure(MiddlewareErrorCode.NOT_FOUND, "member not found", null);
        }
        return record;
    }

    private LeaseRecord requireLeaseById(final LeaseId leaseId) {
        if (leaseId == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "lease id is required", null);
        }
        for (LeaseRecord record : this.leases.values()) {
            if (record.leaseId.equals(leaseId)) {
                return record;
            }
        }
        throw this.failure(MiddlewareErrorCode.NOT_FOUND, "lease not found", null);
    }

    private LeaseRecord requireLeaseByName(final LeaseName name) {
        LeaseRecord record = this.leases.get(name);
        if (record == null) {
            throw this.failure(MiddlewareErrorCode.NOT_FOUND, "lease not found", null);
        }
        return record;
    }

    private void ensureOpen() {
        if (this.closed) {
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "coordination service is closed", null);
        }
    }

    private CoordinationException failure(final MiddlewareErrorCode code, final String message, final Throwable cause) {
        this.lastError = code.name();
        return new CoordinationException(code, message, cause, Map.of("errorCode", code.name()), null);
    }

    private void audit(final String action, final Map<String, String> metadata) {
        this.auditEvents.add(new CoordinationAuditEvent(this.config.clock().instant(), action, metadata));
        while (this.auditEvents.size() > MAX_RETAINED_AUDIT_EVENTS) {
            this.auditEvents.remove(0);
        }
    }

    private static void copyIfPresent(final Map<String, String> source, final Map<String, String> target, final String key) {
        String value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void observeId(final String id) {
        if (id != null && id.startsWith("lease-")) {
            this.idSequence = Math.max(this.idSequence, parseLong(id.substring("lease-".length())));
        }
    }

    private void ensureRecordCapacity(final int currentSize, final int limit, final String message) {
        if (currentSize >= limit) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, message, null);
        }
    }

    private void ensureCoordinateValueWithinLimit(final CoordinateValue value) {
        if (value.bytes().length > this.config.maxCoordinateValueBytes()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "coordinate value limit exceeded", null);
        }
    }

    private void ensureCheckpointWithinLimit(final StringBuilder builder) {
        if (builder.toString().getBytes(StandardCharsets.UTF_8).length > this.config.maxCheckpointBytes()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "coordination checkpoint size limit exceeded", null);
        }
    }

    private static String required(final Map<String, String> fields, final String key) {
        return CoordinationEventCodec.required(key, fields.get(key));
    }

    private static long parseLong(final String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid coordination event number", exception);
        }
    }

    private static void appendFields(final StringBuilder builder, final String... fields) {
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                builder.append('|');
            }
            builder.append(java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((fields[index] == null ? "" : fields[index]).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        builder.append('\n');
    }

    private static List<String> decodeCheckpointFields(final String body, final int start, final int end) {
        List<String> fields = new ArrayList<>();
        int position = start;
        while (position <= end) {
            int separator = body.indexOf('|', position);
            int fieldEnd = separator < 0 || separator > end ? end : separator;
            String encoded = body.substring(position, fieldEnd);
            fields.add(new String(java.util.Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8));
            if (separator < 0 || separator > end) {
                break;
            }
            position = separator + 1;
        }
        return fields;
    }

    private static void requireFieldCount(final List<String> fields, final int expected) {
        if (fields.size() != expected) {
            throw new IllegalArgumentException("coordination checkpoint field count is invalid");
        }
    }

    private static long checksum(final String body) {
        CRC32 crc32 = new CRC32();
        crc32.update(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    private static CoordinateKey membershipKey() {
        return new CoordinateKey("membership");
    }

    private static CoordinateKey splitBrainKey() {
        return new CoordinateKey("split-brain");
    }

    private static CoordinateKey leaseKey(final LeaseName name) {
        return new CoordinateKey("lease:" + name.value());
    }

    private static String safeDiagnostic(final String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 128));
        int limit = Math.min(value.length(), 128);
        for (int index = 0; index < limit; index++) {
            char current = value.charAt(index);
            if ((current >= 'A' && current <= 'Z') || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9') || current == '_' || current == '-' || current == '.') {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private static final class MemberRecord {
        private final MemberIdentity identity;
        private MemberLiveness liveness;
        private final MonotonicEpoch epoch;
        private Instant lastHeartbeat;

        private MemberRecord(
                final MemberIdentity identity,
                final MemberLiveness liveness,
                final MonotonicEpoch epoch,
                final Instant lastHeartbeat) {
            this.identity = identity;
            this.liveness = liveness;
            this.epoch = epoch;
            this.lastHeartbeat = lastHeartbeat;
        }

        private MemberStatus status() {
            return new MemberStatus(this.identity, this.liveness, this.epoch, this.lastHeartbeat);
        }
    }

    private static final class LeaseRecord {
        private final LeaseName name;
        private final LeaseId leaseId;
        private final PrincipalRef owner;
        private final MonotonicEpoch epoch;
        private final FencingToken fencingToken;
        private Instant expiresAt;
        private final long ttlMillis;
        private final boolean leaderLease;
        private LeaseState state;

        private LeaseRecord(
                final LeaseName name,
                final LeaseId leaseId,
                final PrincipalRef owner,
                final MonotonicEpoch epoch,
                final FencingToken fencingToken,
                final Instant expiresAt,
                final long ttlMillis,
                final boolean leaderLease,
                final LeaseState state) {
            this.name = name;
            this.leaseId = leaseId;
            this.owner = owner;
            this.epoch = epoch;
            this.fencingToken = fencingToken;
            this.expiresAt = expiresAt;
            this.ttlMillis = ttlMillis;
            this.leaderLease = leaderLease;
            this.state = state;
        }

        private LeaseStatus status() {
            return new LeaseStatus(this.name, this.leaseId, this.owner, this.state, this.epoch, this.fencingToken, this.expiresAt);
        }
    }

    private record CoordinateRecord(CoordinateKey key, CoordinateValue value, CoordinateVersion version) {
    }

    private record WatchRecord(WatchId watchId, CoordinateKey key, CoordinateWatcher watcher) {
    }

    private record WatchDelivery(WatchId watchId, WatchEvent event) {
    }

    private record RecoveryStart(LogOffset offset, boolean checkpointExpected) {
    }

    private static final class RuntimeWatchRegistration implements WatchRegistration {
        private final LogBackedCoordinationService service;
        private final WatchId watchId;
        private final AtomicBoolean closed;

        private RuntimeWatchRegistration(final LogBackedCoordinationService service, final WatchId watchId) {
            this.service = service;
            this.watchId = watchId;
            this.closed = new AtomicBoolean(false);
        }

        @Override
        public WatchId watchId() {
            return this.watchId;
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.service.closeWatch(this.watchId);
            }
        }
    }

    private static final class CoordinationMetrics {
        private long joins;
        private long leaves;
        private long heartbeats;
        private long leaseGrants;
        private long leaseRenewals;
        private long leaseReleases;
        private long staleRejections;
        private long casSuccesses;
        private long casFailures;
        private long watchNotifications;
        private long splitBrainSuspicions;
        private long recoveries;

        private CoordinationMetricsSnapshot snapshot() {
            return new CoordinationMetricsSnapshot(this.joins, this.leaves, this.heartbeats, this.leaseGrants,
                    this.leaseRenewals, this.leaseReleases, this.staleRejections, this.casSuccesses, this.casFailures,
                    this.watchNotifications, this.splitBrainSuspicions, this.recoveries);
        }
    }
}
