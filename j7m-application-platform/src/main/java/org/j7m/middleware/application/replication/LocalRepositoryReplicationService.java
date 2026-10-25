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

package org.j7m.middleware.application.replication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.LogAppendResult;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.LogStatus;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.api.replication.RepositoryArtifactChunk;
import org.j7m.middleware.application.api.replication.RepositoryArtifactDigest;
import org.j7m.middleware.application.api.replication.RepositoryArtifactTransferStatus;
import org.j7m.middleware.application.api.replication.RepositoryConflictResolution;
import org.j7m.middleware.application.api.replication.RepositoryIndexDelta;
import org.j7m.middleware.application.api.replication.RepositoryIndexSnapshot;
import org.j7m.middleware.application.api.replication.RepositoryPromotionRecord;
import org.j7m.middleware.application.api.replication.RepositoryReconciliationPlan;
import org.j7m.middleware.application.api.replication.RepositoryRepairPlan;
import org.j7m.middleware.application.api.replication.RepositoryReplicationConflict;
import org.j7m.middleware.application.api.replication.RepositoryReplicationEventType;
import org.j7m.middleware.application.api.replication.RepositoryReplicationEvidence;
import org.j7m.middleware.application.api.replication.RepositoryReplicationException;
import org.j7m.middleware.application.api.replication.RepositoryReplicationMetricsSnapshot;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.api.replication.RepositoryReplicationResult;
import org.j7m.middleware.application.api.replication.RepositoryReplicationService;
import org.j7m.middleware.application.api.replication.RepositoryReplicationStatus;
import org.j7m.middleware.application.api.replication.RepositoryRollbackRecord;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.internal.BoundedBundleIO;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SignaturePolicy;
import org.j7m.middleware.application.repository.StrictMetadataJson;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;

/**
 * Local durable-log-backed repository replication service.
 *
 * <p>The service is synchronized per instance. It replicates immutable
 * artifacts by SHA-256 digest and publishes them through the existing local
 * repository verification path before any promotion state is accepted.</p>
 */
public final class LocalRepositoryReplicationService implements RepositoryReplicationService {
    private static final LogStreamId EVENT_STREAM = new LogStreamId("repository-replication-events");
    private static final String METADATA_SCHEMA_VERSION = "1";
    private static final String CHECKPOINT_SCHEMA_VERSION = "j7m.repository.replication-checkpoint/v1";
    private static final String AUDIT_REPLICATION_CHECKPOINT_SCHEMA_VERSION = "j7m.repository.audit-replication-checkpoint/v1";
    private static final Set<String> PROMOTION_METADATA_FIELDS = Set.of("schemaVersion", "coordinate", "state");
    private static final Set<String> ROLLBACK_METADATA_FIELDS = Set.of("schemaVersion", "environment", "from", "to", "reason", "recordedAt");
    private static final Set<String> AUDIT_METADATA_FIELDS = Set.of("timestamp", "action", "coordinate", "result", "previousHash", "currentHash");
    private static final Set<String> AUDIT_REQUIRED_METADATA_FIELDS = Set.of("timestamp", "action", "coordinate", "result", "currentHash");
    private static final List<String> CHECKPOINT_FIELDS = List.of("schemaVersion", "lastOffset", "indexSequence",
            "artifactsTransferred", "bytesTransferred", "repairsCompleted", "completedTransfers", "pendingTransfers",
            "promotionState", "rollbackState", "replicatedAuditHashes", "indexEntries", "checksum");
    private static final Set<String> CHECKPOINT_FIELD_SET = Set.copyOf(CHECKPOINT_FIELDS);
    private static final Set<String> CHECKPOINT_REQUIRED_FIELDS = Set.of("schemaVersion", "lastOffset", "indexSequence",
            "artifactsTransferred", "bytesTransferred", "repairsCompleted", "checksum");
    private static final List<String> AUDIT_REPLICATION_CHECKPOINT_FIELDS = List.of("schemaVersion", "cursor",
            "previousHash", "replicated", "page", "stagedBytes", "stagedDigest", "checksum");
    private static final Set<String> AUDIT_REPLICATION_CHECKPOINT_FIELD_SET = Set.copyOf(AUDIT_REPLICATION_CHECKPOINT_FIELDS);
    private static final Set<String> AUDIT_REPLICATION_CHECKPOINT_REQUIRED_FIELDS = Set.copyOf(AUDIT_REPLICATION_CHECKPOINT_FIELDS);
    private final RepositoryReplicationConfig config;
    private final ApplicationPlatformLimits limits;
    private final FileDurableLog log;
    private final RepositoryReplicationEventCodec codec;
    private final AtomicBoolean closed;
    private final List<RepositoryReplicationEvidence> evidence;
    private final List<RepositoryReplicationConflict> conflicts;
    private final Map<String, IndexEntry> indexEntries;
    private final Map<String, String> promotionState;
    private final Map<String, String> rollbackState;
    private final Set<String> completedTransfers;
    private final Set<String> pendingTransfers;
    private final List<String> replicatedAuditHashes;
    private final int retainedStatusItems;
    private boolean recoveryDegraded;
    private long indexSequence;
    private long artifactsTransferred;
    private long bytesTransferred;
    private long repairsCompleted;
    private long rejectedOperations;
    private long lastAppliedLogOffset;
    private long recoveryReadCount;

    /**
     * Creates a service and recovers its durable replication event state.
     *
     * @param config replication configuration
     */
    public LocalRepositoryReplicationService(final RepositoryReplicationConfig config) {
        this.config = config;
        this.limits = ApplicationPlatformLimits.defaults();
        this.log = new FileDurableLog(DurableLogConfig.defaults(config.eventLogDirectory()));
        this.codec = new RepositoryReplicationEventCodec();
        this.closed = new AtomicBoolean(false);
        this.evidence = new ArrayList<>();
        this.conflicts = new ArrayList<>();
        this.indexEntries = new LinkedHashMap<>();
        this.promotionState = new LinkedHashMap<>();
        this.rollbackState = new LinkedHashMap<>();
        this.completedTransfers = new LinkedHashSet<>();
        this.pendingTransfers = new HashSet<>();
        this.replicatedAuditHashes = new ArrayList<>();
        this.retainedStatusItems = this.limits.maxAuditEventsPerPage();
        this.recoveryDegraded = false;
        this.indexSequence = 0L;
        this.lastAppliedLogOffset = -1L;
        this.recoveryReadCount = 0L;
        this.recover();
        this.append(RepositoryReplicationEventType.REPLICA_JOINED, Map.of("replica", config.localReplica().value()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryArtifactDigest advertiseDigest(final ApplicationCoordinate coordinate) {
        this.ensureOpen();
        RepositoryArtifactDigest digest = this.digest(this.config.source(), coordinate);
        this.append(RepositoryReplicationEventType.ARTIFACT_DIGEST_ADVERTISED, Map.of(
                "coordinate", coordinate.toString(),
                "digest", digest.value(),
                "sizeBytes", Long.toString(digest.sizeBytes())));
        return digest;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryReplicationResult replicate(final RepositoryReplicationPlan plan) {
        this.ensureOpen();
        RepositoryReplicationPlan effective = plan == null ? new RepositoryReplicationPlan(List.of(), true, true, true, true) : plan;
        List<RepositoryReplicationEvidence> resultEvidence = new ArrayList<>();
        List<RepositoryReplicationConflict> resultConflicts = new ArrayList<>();
        if (effective.coordinates().size() > this.config.maxPendingTransferTasks()) {
            this.rejectedOperations++;
            throw failure(MiddlewareErrorCode.OVERLOADED, "pending transfer task limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxPendingTransferTasks())));
        }
        if (effective.coordinates().size() > this.config.maxInFlightTransfers()) {
            this.rejectedOperations++;
            throw failure(MiddlewareErrorCode.OVERLOADED, "in-flight transfer limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxInFlightTransfers())));
        }
        for (ApplicationCoordinate coordinate : effective.coordinates()) {
            RepositoryReplicationResult result = this.replicateArtifact(coordinate);
            resultEvidence.addAll(result.evidence());
            resultConflicts.addAll(result.conflicts());
        }
        if (effective.includeIndex()) {
            RepositoryIndexSnapshot snapshot = this.snapshot(this.config.source());
            RepositoryReplicationEvidence item = this.writeIndexSnapshot(this.config.target(), snapshot);
            resultEvidence.add(item);
        }
        if (effective.includeAuditChain()) {
            int conflictsBeforeAudit = this.conflicts.size();
            resultEvidence.addAll(this.replicateAuditChain());
            if (this.conflicts.size() > conflictsBeforeAudit) {
                resultConflicts.addAll(this.conflicts.subList(conflictsBeforeAudit, this.conflicts.size()));
            }
        }
        if (effective.includePromotions()) {
            for (RepositoryPromotionRecord record : this.sourcePromotionRecords()) {
                RepositoryReplicationResult result = this.replicatePromotion(record);
                resultEvidence.addAll(result.evidence());
                resultConflicts.addAll(result.conflicts());
            }
        }
        if (effective.includeRollback()) {
            for (RepositoryRollbackRecord record : this.sourceRollbackRecords()) {
                RepositoryReplicationResult result = this.replicateRollback(record);
                resultEvidence.addAll(result.evidence());
                resultConflicts.addAll(result.conflicts());
            }
        }
        return new RepositoryReplicationResult(resultConflicts.isEmpty(), resultConflicts.isEmpty()
                ? RepositoryArtifactTransferStatus.COMPLETED : RepositoryArtifactTransferStatus.FAILED,
                resultEvidence, resultConflicts);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryReplicationResult replicatePromotion(final RepositoryPromotionRecord record) {
        this.ensureOpen();
        RepositoryArtifactDigest targetDigest = this.digestOrNull(this.config.target(), record.coordinate());
        SignaturePolicy policy = this.effectivePolicy(record.coordinate());
        if (targetDigest == null || !this.config.target().verifiedForActivation(record.coordinate(), policy)) {
            RepositoryReplicationConflict conflict = this.conflict("PROMOTION_TARGET_UNVERIFIED",
                    record.coordinate().toString(), Map.of("environment", record.environment(), "policy", policy.name()));
            return new RepositoryReplicationResult(false, RepositoryArtifactTransferStatus.FAILED, List.of(), List.of(conflict));
        }
        if (!record.digest().isBlank() && !record.digest().equals(targetDigest.value())) {
            RepositoryReplicationConflict conflict = this.conflict("PROMOTION_DIGEST_MISMATCH",
                    record.coordinate().toString(), Map.of("environment", record.environment()));
            return new RepositoryReplicationResult(false, RepositoryArtifactTransferStatus.FAILED, List.of(), List.of(conflict));
        }
        ApplicationDeploymentResult result = "DEMOTED".equalsIgnoreCase(record.state())
                ? this.config.target().demote(record.coordinate(), record.environment())
                : this.config.target().promote(record.coordinate(), record.environment());
        RepositoryReplicationEventType type = "DEMOTED".equalsIgnoreCase(record.state())
                ? RepositoryReplicationEventType.DEMOTION_RECORDED : RepositoryReplicationEventType.PROMOTION_RECORDED;
        RepositoryReplicationEvidence item = this.evidence(type, record.coordinate().toString(), Map.of(
                "environment", record.environment(), "coordinate", record.coordinate().toString(),
                "state", record.state(), "result", Boolean.toString(result.success()), "policy", policy.name()));
        this.append(type, item.metadata());
        this.promotionState.put(record.environment(), record.coordinate() + "|" + record.state() + "|" + record.digest());
        this.publishCheckpoint();
        return new RepositoryReplicationResult(result.success(), RepositoryArtifactTransferStatus.COMPLETED, List.of(item), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryReplicationResult replicateRollback(final RepositoryRollbackRecord record) {
        this.ensureOpen();
        SignaturePolicy policy = this.effectivePolicy(record.toCoordinate());
        if (this.digestOrNull(this.config.target(), record.toCoordinate()) == null
                || !this.config.target().verifiedForActivation(record.toCoordinate(), policy)) {
            RepositoryReplicationConflict conflict = this.conflict("ROLLBACK_TARGET_MISSING",
                    record.toCoordinate().toString(), Map.of("environment", record.environment(), "policy", policy.name()));
            return new RepositoryReplicationResult(false, RepositoryArtifactTransferStatus.FAILED, List.of(), List.of(conflict));
        }
        try {
            Path rollback = this.config.target().repositoryRoot().resolve("rollback").resolve(record.environment() + ".json");
            BoundedBundleIO.writeSmallFileAtomically(rollback, json(Map.of(
                    "schemaVersion", METADATA_SCHEMA_VERSION,
                    "environment", record.environment(),
                    "from", record.fromCoordinate().toString(),
                    "to", record.toCoordinate().toString(),
                    "reason", safeText(record.reason()),
                    "recordedAt", record.recordedAt().toString())) + "\n",
                    this.limits.maxMetadataBytes());
            RepositoryReplicationEvidence item = this.evidence(RepositoryReplicationEventType.ROLLBACK_RECORDED,
                    record.toCoordinate().toString(), Map.of("from", record.fromCoordinate().toString(),
                            "to", record.toCoordinate().toString(), "environment", record.environment(), "policy", policy.name()));
            this.append(RepositoryReplicationEventType.ROLLBACK_RECORDED, item.metadata());
            this.rollbackState.put(record.environment(), record.fromCoordinate() + "|" + record.toCoordinate());
            this.publishCheckpoint();
            return new RepositoryReplicationResult(true, RepositoryArtifactTransferStatus.COMPLETED, List.of(item), List.of());
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to replicate rollback metadata", Map.of());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryReconciliationPlan reconcile() {
        this.ensureOpen();
        this.append(RepositoryReplicationEventType.RECONCILIATION_STARTED, Map.of("replica", this.config.localReplica().value()));
        Map<String, String> source = this.snapshot(this.config.source()).entries();
        Map<String, String> target = this.snapshot(this.config.target()).entries();
        if (source.size() > this.config.maxReconciliationBatchSize()) {
            this.rejectedOperations++;
            throw failure(MiddlewareErrorCode.OVERLOADED, "reconciliation batch limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxReconciliationBatchSize())));
        }
        List<String> missing = new ArrayList<>();
        List<String> corrupted = new ArrayList<>();
        List<RepositoryReplicationConflict> found = new ArrayList<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String targetDigest = target.get(entry.getKey());
            if (targetDigest == null) {
                missing.add(entry.getKey());
            } else if (!targetDigest.equals(entry.getValue())) {
                corrupted.add(entry.getKey());
                found.add(this.conflict("ARTIFACT_DIGEST_MISMATCH", entry.getKey(), Map.of("sourceDigest", entry.getValue(),
                        "targetDigest", targetDigest)));
            }
        }
        this.append(RepositoryReplicationEventType.RECONCILIATION_COMPLETED, Map.of(
                "missing", Integer.toString(missing.size()), "corrupted", Integer.toString(corrupted.size())));
        return new RepositoryReconciliationPlan(missing, corrupted, found);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryReplicationResult repair(final RepositoryRepairPlan plan) {
        this.ensureOpen();
        RepositoryRepairPlan effective = plan == null ? new RepositoryRepairPlan(List.of(), true, true) : plan;
        this.append(RepositoryReplicationEventType.REPAIR_REQUESTED, Map.of("artifacts", Integer.toString(effective.artifacts().size())));
        List<RepositoryReplicationEvidence> repaired = new ArrayList<>();
        List<RepositoryReplicationConflict> repairConflicts = new ArrayList<>();
        for (ApplicationCoordinate coordinate : effective.artifacts()) {
            this.discardTargetArtifactForRepair(coordinate);
            RepositoryReplicationResult result = this.replicateArtifact(coordinate);
            repaired.addAll(result.evidence());
            repairConflicts.addAll(result.conflicts());
        }
        if (effective.repairIndex()) {
            RepositoryIndexSnapshot snapshot = this.snapshot(this.config.source());
            repaired.add(this.writeIndexSnapshot(this.config.target(), snapshot));
        }
        if (effective.repairAuditChain()) {
            repaired.addAll(this.replicateAuditChain());
        }
        this.repairsCompleted++;
        this.append(RepositoryReplicationEventType.REPAIR_COMPLETED, Map.of("artifacts", Integer.toString(effective.artifacts().size())));
        if (repairConflicts.isEmpty() && (effective.repairIndex() || effective.repairAuditChain() || !effective.artifacts().isEmpty())) {
            this.recoveryDegraded = false;
            this.publishCheckpoint();
        }
        return new RepositoryReplicationResult(repairConflicts.isEmpty(), repairConflicts.isEmpty()
                ? RepositoryArtifactTransferStatus.COMPLETED : RepositoryArtifactTransferStatus.FAILED, repaired, repairConflicts);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryReplicationStatus status() {
        return new RepositoryReplicationStatus(this.config.localReplica(), this.config.peerReplica(),
                new RepositoryReplicationMetricsSnapshot(this.artifactsTransferred, this.bytesTransferred,
                        this.conflicts.size(), this.repairsCompleted, this.rejectedOperations),
                List.copyOf(this.conflicts), List.copyOf(this.evidence));
    }

    synchronized long recoveryReadCount() {
        return this.recoveryReadCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.log.close();
        }
    }

    private RepositoryReplicationResult replicateArtifact(final ApplicationCoordinate coordinate) {
        RepositoryArtifactDigest sourceDigest = this.digest(this.config.source(), coordinate);
        if (sourceDigest.sizeBytes() > this.config.maxArtifactBytes()) {
            this.rejectedOperations++;
            throw failure(MiddlewareErrorCode.OVERLOADED, "artifact size limit exceeded",
                    Map.of("coordinate", coordinate.toString(), "limit", Long.toString(this.config.maxArtifactBytes())));
        }
        RepositoryArtifactDigest existing = this.digestOrNull(this.config.target(), coordinate);
        if (existing != null && !existing.value().equals(sourceDigest.value())) {
            RepositoryReplicationConflict conflict = this.conflict("COORDINATE_DIGEST_CONFLICT", coordinate.toString(),
                    Map.of("sourceDigest", sourceDigest.value(), "targetDigest", existing.value()));
            return new RepositoryReplicationResult(false, RepositoryArtifactTransferStatus.FAILED, List.of(), List.of(conflict));
        }
        if (existing != null) {
            RepositoryReplicationEvidence item = this.evidence(RepositoryReplicationEventType.ARTIFACT_TRANSFER_COMPLETED,
                    coordinate.toString(), Map.of("coordinate", coordinate.toString(), "digest", existing.value(), "alreadyPresent", "true"));
            return new RepositoryReplicationResult(true, RepositoryArtifactTransferStatus.COMPLETED, List.of(item), List.of());
        }
        Path sourcePath = this.config.source().fetch(coordinate).path();
        String transferId = "transfer-" + coordinate + "-" + sourceDigest.value();
        this.pendingTransfers.add(transferId);
        this.append(RepositoryReplicationEventType.ARTIFACT_TRANSFER_STARTED, Map.of(
                "transferId", transferId, "coordinate", coordinate.toString(), "digest", sourceDigest.value(),
                "sizeBytes", Long.toString(sourceDigest.sizeBytes())));
        Path temporary = null;
        try {
            temporary = Files.createTempFile(this.config.target().repositoryRoot(), "replication-", ".j7m");
            MessageDigest rollingDigest = sha256Digest();
            long copied;
            try (InputStream input = Files.newInputStream(sourcePath);
                    OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                copied = BoundedBundleIO.copy(input, output, this.config.maxArtifactBytes(), this.limits.ioBufferBytes(), rollingDigest);
            }
            String digest = hex(rollingDigest.digest());
            if (!sourceDigest.value().equals(digest)) {
                Files.deleteIfExists(temporary);
                RepositoryReplicationConflict conflict = this.conflict("ARTIFACT_DIGEST_VERIFICATION_FAILED",
                        coordinate.toString(), Map.of("expectedDigest", sourceDigest.value(), "actualDigest", digest));
                return new RepositoryReplicationResult(false, RepositoryArtifactTransferStatus.FAILED, List.of(), List.of(conflict));
            }
            ApplicationBundle bundle = new ApplicationBundle(temporary, coordinate, digest);
            SignaturePolicy policy = this.effectivePolicy(coordinate);
            ApplicationVerificationResult verification = new ApplicationBundleVerifier().verify(bundle, policy);
            ApplicationDeploymentResult publish = this.config.target().publish(bundle,
                    new RepositoryPublishOptions("replication", policy.signatureRequired(), policy.name()));
            Files.deleteIfExists(temporary);
            temporary = null;
            if (!publish.success()) {
                RepositoryReplicationConflict conflict = this.conflict("ARTIFACT_VERIFICATION_REJECTED",
                        coordinate.toString(), Map.of("coordinate", coordinate.toString(), "policy", policy.name(),
                                "signature", verification.messages().stream().anyMatch(message -> message.contains("valid=true")) ? "verified" : "not-verified",
                                "sbom", verification.messages().stream().anyMatch(message -> message.contains("sbom present=true")) ? "present" : "missing",
                                "provenance", verification.messages().stream().anyMatch(message -> message.contains("provenance present=true")) ? "present" : "missing"));
                this.pendingTransfers.remove(transferId);
                return new RepositoryReplicationResult(false, RepositoryArtifactTransferStatus.FAILED, List.of(), List.of(conflict));
            }
            this.artifactsTransferred++;
            this.bytesTransferred += copied;
            this.pendingTransfers.remove(transferId);
            this.addCompletedTransfer(transferId);
            RepositoryReplicationEvidence item = this.evidence(RepositoryReplicationEventType.ARTIFACT_TRANSFER_COMPLETED,
                    coordinate.toString(), Map.of("coordinate", coordinate.toString(), "digest", digest,
                            "sizeBytes", Long.toString(copied), "transferId", transferId, "policy", policy.name()));
            this.append(RepositoryReplicationEventType.ARTIFACT_TRANSFER_COMPLETED, item.metadata());
            this.publishIndexDelta("ADD", coordinate, digest, copied);
            return new RepositoryReplicationResult(true, RepositoryArtifactTransferStatus.COMPLETED, List.of(item), List.of());
        } catch (IOException exception) {
            this.append(RepositoryReplicationEventType.ARTIFACT_TRANSFER_FAILED, Map.of("coordinate", coordinate.toString()));
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to replicate artifact", Map.of("coordinate", coordinate.toString()));
        } finally {
            this.pendingTransfers.remove(transferId);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an incomplete replication transfer.
                }
            }
        }
    }

    List<RepositoryArtifactChunk> createChunks(
            final Path source,
            final RepositoryArtifactDigest digest,
            final String transferId) throws IOException {
        long sourceBytes = Files.size(source);
        if (sourceBytes > this.config.maxArtifactBytes()) {
            throw failure(MiddlewareErrorCode.OVERLOADED, "artifact size limit exceeded",
                    Map.of("coordinate", digest.coordinate().toString(), "limit", Long.toString(this.config.maxArtifactBytes())));
        }
        int max = this.config.maxChunkBytes();
        long totalLong = Math.max(1L, (sourceBytes + max - 1L) / max);
        if (totalLong > this.limits.maxReplicationChunks() || totalLong > Integer.MAX_VALUE) {
            throw failure(MiddlewareErrorCode.OVERLOADED, "artifact chunk count limit exceeded",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        int total = (int) totalLong;
        List<RepositoryArtifactChunk> chunks = new ArrayList<>(total);
        try (InputStream input = Files.newInputStream(source)) {
            for (int index = 0; index < total; index++) {
                int expected = (int) Math.min(max, sourceBytes - ((long) index * max));
                byte[] part = input.readNBytes(expected);
                if (part.length != expected) {
                    throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact source changed during chunking",
                            Map.of("coordinate", digest.coordinate().toString()));
                }
                String chunkDigest = sha256(part);
                chunks.add(new RepositoryArtifactChunk(transferId, digest, index, total, chunkDigest, part));
                this.append(RepositoryReplicationEventType.ARTIFACT_CHUNK_TRANSFERRED, Map.of(
                        "transferId", transferId,
                        "coordinate", digest.coordinate().toString(),
                        "sequence", Integer.toString(index),
                        "totalChunks", Integer.toString(total),
                        "chunkDigest", chunkDigest,
                        "bytes", Integer.toString(part.length)));
            }
        }
        return chunks;
    }

    byte[] assembleChunks(
            final List<RepositoryArtifactChunk> chunks,
            final RepositoryArtifactDigest digest) {
        this.validateArtifactDigestDescriptor(digest);
        if (digest.sizeBytes() > this.compatibilityHeapAssemblyLimit()) {
            throw failure(MiddlewareErrorCode.OVERLOADED, "compatibility chunk assembly size limit exceeded",
                    Map.of("coordinate", digest.coordinate().toString(),
                            "limit", Long.toString(this.compatibilityHeapAssemblyLimit())));
        }
        Path staged = null;
        try {
            staged = this.stageChunks(chunks, digest);
            long stagedBytes = Files.size(staged);
            if (stagedBytes > this.compatibilityHeapAssemblyLimit()) {
                throw failure(MiddlewareErrorCode.OVERLOADED, "compatibility chunk assembly size limit exceeded",
                        Map.of("coordinate", digest.coordinate().toString(),
                                "limit", Long.toString(this.compatibilityHeapAssemblyLimit())));
            }
            return Files.readAllBytes(staged);
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to assemble artifact chunks",
                    Map.of("coordinate", digest == null ? "" : digest.coordinate().toString()));
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a compatibility staging artifact.
                }
            }
        }
    }

    Path stageChunks(
            final List<RepositoryArtifactChunk> chunks,
            final RepositoryArtifactDigest digest) throws IOException {
        this.validateArtifactDigestDescriptor(digest);
        if (chunks == null || chunks.isEmpty()) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact transfer is missing chunks",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        for (RepositoryArtifactChunk chunk : chunks) {
            if (chunk == null) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk descriptor is missing",
                        Map.of("coordinate", digest.coordinate().toString()));
            }
        }
        int total = chunks.getFirst().totalChunks();
        if (total < 1) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk total is invalid",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        if (total > this.limits.maxReplicationChunks() || chunks.size() > this.limits.maxReplicationChunks()) {
            throw failure(MiddlewareErrorCode.OVERLOADED, "artifact chunk count limit exceeded",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        if (chunks.size() != total) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk count mismatch",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        if (digest.sizeBytes() > this.config.maxArtifactBytes()) {
            throw failure(MiddlewareErrorCode.OVERLOADED, "artifact size limit exceeded",
                    Map.of("coordinate", digest.coordinate().toString(), "limit", Long.toString(this.config.maxArtifactBytes())));
        }
        long assembledBytes = 0L;
        String transferId = "";
        RepositoryArtifactChunk[] ordered = new RepositoryArtifactChunk[total];
        for (int index = 0; index < chunks.size(); index++) {
            RepositoryArtifactChunk chunk = chunks.get(index);
            if (!digest.equals(chunk.digest())) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk digest descriptor mismatch",
                        Map.of("coordinate", digest.coordinate().toString()));
            }
            if (chunk.totalChunks() != total) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk total mismatch",
                        Map.of("coordinate", digest.coordinate().toString()));
            }
            if (chunk.sequence() < 0 || chunk.sequence() >= total) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk sequence is invalid",
                        Map.of("coordinate", digest.coordinate().toString()));
            }
            if (chunk.chunkDigest() != null && !chunk.chunkDigest().isBlank() && !validSha256Hex(chunk.chunkDigest())) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk digest value is invalid",
                        Map.of("coordinate", digest.coordinate().toString()));
            }
            if (transferId.isBlank()) {
                transferId = chunk.transferId();
            } else if (!transferId.equals(chunk.transferId())) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk transfer mismatch",
                        Map.of("coordinate", digest.coordinate().toString()));
            }
            if (ordered[chunk.sequence()] != null) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate artifact chunk",
                        Map.of("coordinate", digest.coordinate().toString(), "sequence", Integer.toString(chunk.sequence())));
            }
            byte[] bytes = chunk.bytes();
            if (bytes.length > this.config.maxChunkBytes()) {
                this.rejectedOperations++;
                throw failure(MiddlewareErrorCode.OVERLOADED, "artifact chunk size limit exceeded",
                        Map.of("coordinate", digest.coordinate().toString(), "limit", Integer.toString(this.config.maxChunkBytes())));
            }
            assembledBytes += bytes.length;
            if (assembledBytes > this.config.maxArtifactBytes()) {
                throw failure(MiddlewareErrorCode.OVERLOADED, "artifact size limit exceeded",
                        Map.of("coordinate", digest.coordinate().toString(), "limit", Long.toString(this.config.maxArtifactBytes())));
            }
            String actualChunkDigest = sha256(bytes);
            if (!chunk.chunkDigest().isBlank() && !chunk.chunkDigest().equals(actualChunkDigest)) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk checksum mismatch",
                        Map.of("coordinate", digest.coordinate().toString(), "sequence", Integer.toString(chunk.sequence())));
            }
            ordered[chunk.sequence()] = chunk;
        }
        if (assembledBytes != digest.sizeBytes()) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact chunk size descriptor mismatch",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        for (int sequence = 0; sequence < ordered.length; sequence++) {
            if (ordered[sequence] == null) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "missing artifact chunk",
                        Map.of("coordinate", digest.coordinate().toString(), "sequence", Integer.toString(sequence)));
            }
        }
        Path staged = Files.createTempFile(this.config.eventLogDirectory(), "replication-chunks-", ".j7m");
        MessageDigest aggregateDigest = sha256Digest();
        try (OutputStream output = Files.newOutputStream(staged, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (RepositoryArtifactChunk chunk : ordered) {
                byte[] bytes = chunk.bytes();
                output.write(bytes);
                aggregateDigest.update(bytes);
            }
        }
        String actualDigest = hex(aggregateDigest.digest());
        if (!digest.value().equals(actualDigest)) {
            Files.deleteIfExists(staged);
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "assembled artifact digest mismatch",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        return staged;
    }

    private long compatibilityHeapAssemblyLimit() {
        return Math.min(this.config.maxArtifactBytes(), this.limits.maxMetadataBytes());
    }

    private void validateArtifactDigestDescriptor(final RepositoryArtifactDigest digest) {
        if (digest == null) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact digest descriptor is missing", Map.of());
        }
        if (!"SHA-256".equals(digest.algorithm())) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact digest algorithm is unsupported",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        if (!validSha256Hex(digest.value())) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact digest value is invalid",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
        if (digest.sizeBytes() < 0L) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "artifact size descriptor is invalid",
                    Map.of("coordinate", digest.coordinate().toString()));
        }
    }

    private static boolean validSha256Hex(final String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private List<RepositoryReplicationEvidence> replicateAuditChain() {
        int recordsPerPage = Math.min(this.config.maxAuditReplicationBatchSize(), this.limits.maxAuditEventsPerPage());
        long maxPageBytes = Math.min(this.limits.maxMetadataBytes(), (long) this.limits.maxAuditLineBytes() * recordsPerPage);
        long cursor = 0L;
        String previousHash = "";
        int replicated = 0;
        int pageNumber = 0;
        Path targetAudit = this.replicatedAuditPath();
        Path staged = targetAudit.resolveSibling(targetAudit.getFileName() + ".part");
        Path stagedCheckpoint = targetAudit.resolveSibling(targetAudit.getFileName() + ".checkpoint.json");
        AuditReplicationProgress progress = this.restoreAuditReplicationProgress(staged, stagedCheckpoint);
        cursor = progress.cursor();
        previousHash = progress.previousHash();
        replicated = progress.replicated();
        pageNumber = progress.page();
        long stagedBytes = progress.stagedBytes();
        while (true) {
            int remaining = this.config.maxAuditReplicationBatchSize() - replicated;
            if (remaining <= 0) {
                throw failure(MiddlewareErrorCode.OVERLOADED, "audit replication batch limit exceeded",
                        Map.of("limit", Integer.toString(this.config.maxAuditReplicationBatchSize())));
            }
            int pageLimit = Math.min(recordsPerPage, remaining);
            LocalApplicationRepository.AuditPage page = this.config.source().auditEventsPage(cursor, pageLimit);
            if (page.events().isEmpty() && !page.end()) {
                throw failure(MiddlewareErrorCode.INTERNAL, "audit replication continuation did not advance", Map.of());
            }
            int pageRecords = 0;
            long pageBytes = 0L;
            for (String line : page.events()) {
                pageBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (pageBytes > maxPageBytes) {
                    throw failure(MiddlewareErrorCode.OVERLOADED, "audit replication page byte limit exceeded",
                            Map.of("limit", Long.toString(maxPageBytes)));
                }
                String currentHash = verifyAuditLine(line, previousHash);
                if (currentHash == null) {
                    RepositoryReplicationConflict conflict = this.conflict("AUDIT_CHAIN_HASH_MISMATCH", "repository-audit", Map.of());
                    try {
                        Files.deleteIfExists(staged);
                    } catch (IOException ignored) {
                        // Best-effort cleanup after rejecting a corrupted audit chain.
                    }
                    return List.of(new RepositoryReplicationEvidence(RepositoryReplicationEventType.CONFLICT_DETECTED,
                            "repository-audit", conflict.metadata()));
                }
                try {
                    Files.createDirectories(staged.getParent());
                    Files.writeString(staged, line + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException exception) {
                    throw failure(MiddlewareErrorCode.INTERNAL, "failed to stage replicated audit chain", Map.of());
                }
                stagedBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                previousHash = currentHash;
                replicated++;
                pageRecords++;
            }
            cursor = page.nextCursor();
            if (pageRecords > 0) {
                this.publishAuditReplicationProgress(stagedCheckpoint, cursor, previousHash, replicated, pageNumber,
                        stagedBytes, this.auditStageDigest(staged));
            }
            if (page.end()) {
                break;
            }
            pageNumber++;
        }
        try {
            Files.createDirectories(targetAudit.getParent());
            if (replicated == 0) {
                Files.deleteIfExists(staged);
                Files.deleteIfExists(stagedCheckpoint);
                BoundedBundleIO.writeSmallFileAtomically(targetAudit, "", this.limits.maxMetadataBytes());
            } else {
                Files.move(staged, targetAudit, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.deleteIfExists(stagedCheckpoint);
            }
            return this.publishReplicatedAuditEvidence(targetAudit);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(staged);
                Files.deleteIfExists(stagedCheckpoint);
            } catch (IOException ignored) {
                // Best-effort cleanup after a failed audit-chain publication.
            }
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to replicate audit chain", Map.of());
        }
    }

    private Path replicatedAuditPath() {
        return this.config.target().repositoryRoot().resolve("replicated-audit")
                .resolve(this.config.peerReplica().value() + "-repository-events.jsonl");
    }

    private List<RepositoryReplicationEvidence> publishReplicatedAuditEvidence(final Path targetAudit) {
        List<RepositoryReplicationEvidence> output = new ArrayList<>();
        String previousHash = "";
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(targetAudit, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            while (line != null) {
                String currentHash = verifyAuditLine(line, previousHash);
                if (currentHash == null) {
                    throw failure(MiddlewareErrorCode.RECOVERY_FAILED,
                            "published replicated audit chain failed verification", Map.of());
                }
                RepositoryReplicationEvidence item = this.evidence(RepositoryReplicationEventType.AUDIT_CHAIN_RECORD_REPLICATED,
                        "repository-audit", Map.of("currentHash", currentHash, "line", Integer.toString(lineNumber)));
                output.add(item);
                this.append(RepositoryReplicationEventType.AUDIT_CHAIN_RECORD_REPLICATED, item.metadata());
                previousHash = currentHash;
                lineNumber++;
                line = reader.readLine();
            }
            return output;
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to publish replicated audit evidence", Map.of());
        }
    }

    private AuditReplicationProgress restoreAuditReplicationProgress(
            final Path staged,
            final Path checkpoint) {
        if (!Files.isRegularFile(checkpoint)) {
            this.deleteAuditReplicationStage(staged, checkpoint);
            return AuditReplicationProgress.empty();
        }
        try {
            Map<String, String> fields = StrictMetadataJson.readStringObject(checkpoint, this.limits,
                    AUDIT_REPLICATION_CHECKPOINT_SCHEMA_VERSION, AUDIT_REPLICATION_CHECKPOINT_REQUIRED_FIELDS,
                    AUDIT_REPLICATION_CHECKPOINT_FIELD_SET);
            String checksum = fields.get("checksum");
            if (!validSha256Hex(checksum) || !auditReplicationCheckpointChecksum(fields).equals(checksum)) {
                this.deleteAuditReplicationStage(staged, checkpoint);
                return AuditReplicationProgress.empty();
            }
            long cursor = strictNonNegativeLong(fields.get("cursor"), "AUDIT_REPLICATION_CHECKPOINT", "cursor");
            String previousHash = fields.get("previousHash");
            if (!previousHash.isBlank() && !validAuditHash(previousHash)) {
                this.deleteAuditReplicationStage(staged, checkpoint);
                return AuditReplicationProgress.empty();
            }
            int replicated = strictNonNegativeInt(fields.get("replicated"), "AUDIT_REPLICATION_CHECKPOINT", "replicated");
            int page = strictNonNegativeInt(fields.get("page"), "AUDIT_REPLICATION_CHECKPOINT", "page");
            long stagedBytes = strictNonNegativeLong(fields.get("stagedBytes"), "AUDIT_REPLICATION_CHECKPOINT", "stagedBytes");
            String stagedDigest = fields.get("stagedDigest");
            if (!validSha256Hex(stagedDigest)) {
                this.deleteAuditReplicationStage(staged, checkpoint);
                return AuditReplicationProgress.empty();
            }
            if ((replicated == 0 && stagedBytes != 0L) || (replicated > 0 && !Files.isRegularFile(staged))
                    || (Files.isRegularFile(staged) && Files.size(staged) != stagedBytes)) {
                this.deleteAuditReplicationStage(staged, checkpoint);
                return AuditReplicationProgress.empty();
            }
            if (replicated > 0) {
                StagedAuditValidation validation = this.validateStagedAudit(staged);
                if (validation.recordCount() != replicated || validation.byteCount() != stagedBytes
                        || !validation.previousHash().equals(previousHash) || !validation.digest().equals(stagedDigest)) {
                    this.deleteAuditReplicationStage(staged, checkpoint);
                    return AuditReplicationProgress.empty();
                }
            }
            return new AuditReplicationProgress(cursor, previousHash, replicated, page, stagedBytes);
        } catch (IOException | RuntimeException exception) {
            this.deleteAuditReplicationStage(staged, checkpoint);
            return AuditReplicationProgress.empty();
        }
    }

    private void publishAuditReplicationProgress(
            final Path checkpoint,
            final long cursor,
            final String previousHash,
            final int replicated,
            final int page,
            final long stagedBytes,
            final String stagedDigest) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", AUDIT_REPLICATION_CHECKPOINT_SCHEMA_VERSION);
        fields.put("cursor", Long.toString(cursor));
        fields.put("previousHash", previousHash);
        fields.put("replicated", Integer.toString(replicated));
        fields.put("page", Integer.toString(page));
        fields.put("stagedBytes", Long.toString(stagedBytes));
        fields.put("stagedDigest", stagedDigest);
        fields.put("checksum", auditReplicationCheckpointChecksum(fields));
        try {
            BoundedBundleIO.writeSmallFileAtomically(checkpoint, json(fields) + "\n", this.limits.maxMetadataBytes());
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to publish audit replication checkpoint", Map.of());
        }
    }

    private void deleteAuditReplicationStage(final Path staged, final Path checkpoint) {
        try {
            Files.deleteIfExists(staged);
            Files.deleteIfExists(checkpoint);
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to discard inconsistent audit replication stage", Map.of());
        }
    }

    private StagedAuditValidation validateStagedAudit(final Path staged) throws IOException {
        long maxBytes = this.maxAuditReplicationStageBytes();
        long byteCount = 0L;
        int recordCount = 0;
        String previousHash = "";
        try (BufferedReader reader = Files.newBufferedReader(staged, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            while (line != null) {
                long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                byteCount += lineBytes;
                if (byteCount > maxBytes || recordCount >= this.config.maxAuditReplicationBatchSize()) {
                    throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "staged audit replication exceeds bounds", Map.of());
                }
                String currentHash = verifyAuditLine(line, previousHash);
                if (currentHash == null) {
                    throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "staged audit replication hash chain mismatch", Map.of());
                }
                previousHash = currentHash;
                recordCount++;
                line = reader.readLine();
            }
        }
        String digest = this.fileSha256(staged, maxBytes);
        return new StagedAuditValidation(recordCount, byteCount, previousHash, digest);
    }

    private String fileSha256(final Path path, final long maxBytes) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[this.limits.ioBufferBytes()];
        long observed = 0L;
        try (InputStream input = Files.newInputStream(path)) {
            int read = input.read(buffer);
            while (read >= 0) {
                observed += read;
                if (observed > maxBytes) {
                    throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "staged audit replication exceeds byte limit", Map.of());
                }
                digest.update(buffer, 0, read);
                read = input.read(buffer);
            }
        }
        return hex(digest.digest());
    }

    private String auditStageDigest(final Path staged) {
        try {
            return this.fileSha256(staged, this.maxAuditReplicationStageBytes());
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to hash staged audit replication file", Map.of());
        }
    }

    private long maxAuditReplicationStageBytes() {
        long records = Math.max(1L, this.config.maxAuditReplicationBatchSize());
        long lineBytes = this.limits.maxAuditLineBytes() + 1L;
        if (records > Long.MAX_VALUE / lineBytes) {
            return Long.MAX_VALUE;
        }
        return records * lineBytes;
    }

    private RepositoryIndexSnapshot snapshot(final LocalApplicationRepository repository) {
        Map<String, String> entries = new LinkedHashMap<>();
        Path root = repository.repositoryRoot().resolve("artifacts");
        if (Files.isDirectory(root)) {
            try {
                List<Path> bundles = this.bundlePaths(root);
                J7mBundleReader reader = new J7mBundleReader();
                for (Path path : bundles) {
                    String digest = reader.sha256(path);
                    ApplicationCoordinate coordinate;
                    try {
                        coordinate = new ApplicationBundleVerifier().manifest(
                                new ApplicationBundle(path, ApplicationCoordinate.parse("unknown:unknown:0"), digest)).identity().coordinate();
                    } catch (RuntimeException exception) {
                        coordinate = coordinateFromArtifactPath(root, path);
                    }
                    entries.put(coordinate.toString(), digest);
                }
            } catch (IOException exception) {
                throw failure(MiddlewareErrorCode.INTERNAL, "failed to build repository index", Map.of());
            }
        }
        String digest = sha256(indexDigestText(entries).getBytes(StandardCharsets.UTF_8));
        return new RepositoryIndexSnapshot(entries.size(), digest, entries);
    }

    private RepositoryReplicationEvidence writeIndexSnapshot(final LocalApplicationRepository repository, final RepositoryIndexSnapshot snapshot) {
        if (snapshot.entries().size() > this.config.maxIndexDeltaBatchSize()) {
            throw failure(MiddlewareErrorCode.OVERLOADED, "index delta batch limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxIndexDeltaBatchSize())));
        }
        try {
            Path path = repository.repositoryRoot().resolve("replication-index").resolve("snapshot.json");
            Map<String, IndexEntry> entries = this.indexEntries(this.config.source());
            String encodedEntries = encodeIndexEntries(entries);
            String snapshotDigest = indexDigest(entries);
            long nextSequence = this.indexSequence + 1L;
            Map<String, String> values = new LinkedHashMap<>();
            values.put("schemaVersion", "1");
            values.put("sourceReplica", this.config.peerReplica().value());
            values.put("sequence", Long.toString(nextSequence));
            values.put("digest", snapshotDigest);
            values.put("entries", Integer.toString(snapshot.entries().size()));
            values.put("coordinates", String.join(",", entries.keySet()));
            values.put("artifactDigests", artifactDigestList(entries));
            values.put("indexEntries", encodedEntries);
            BoundedBundleIO.writeSmallFileAtomically(path, json(values) + "\n", this.limits.maxMetadataBytes());
            this.applyIndexSnapshot(nextSequence, snapshotDigest, encodedEntries);
            RepositoryReplicationEvidence item = this.evidence(RepositoryReplicationEventType.INDEX_SNAPSHOT_PUBLISHED,
                    "repository-index", values);
            this.append(RepositoryReplicationEventType.INDEX_SNAPSHOT_PUBLISHED, values);
            return item;
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to write repository index snapshot", Map.of());
        }
    }

    private Map<String, IndexEntry> indexEntries(final LocalApplicationRepository repository) {
        Map<String, IndexEntry> entries = new LinkedHashMap<>();
        Path root = repository.repositoryRoot().resolve("artifacts");
        if (!Files.isDirectory(root)) {
            return entries;
        }
        try {
            List<Path> bundles = this.bundlePaths(root);
            J7mBundleReader reader = new J7mBundleReader();
            ApplicationBundleVerifier verifier = new ApplicationBundleVerifier();
            for (Path path : bundles) {
                String digest = reader.sha256(path);
                ApplicationBundle manifestBundle = new ApplicationBundle(path, ApplicationCoordinate.parse("unknown:unknown:0"), digest);
                ApplicationCoordinate coordinate = verifier.manifest(manifestBundle).identity().coordinate();
                ApplicationBundle bundle = new ApplicationBundle(path, coordinate, digest);
                RepositoryArtifactDigest artifactDigest = this.digest(repository, coordinate);
                Map<String, String> verification = repository.verificationMetadata(coordinate);
                entries.put(coordinate.toString(), new IndexEntry(coordinate.toString(), artifactDigest.value(),
                        artifactDigest.sizeBytes(),
                        verification.getOrDefault("policy", repository.verificationPolicy(coordinate).name()),
                        verification.getOrDefault("signature", "unknown"),
                        reader.hasEntry(bundle, J7mBundleReader.SBOM_ENTRY) ? "present" : "missing",
                        reader.hasEntry(bundle, J7mBundleReader.PROVENANCE_ENTRY) ? "present" : "missing"));
            }
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to build repository index metadata", Map.of());
        }
        return entries;
    }

    private List<Path> bundlePaths(final Path root) throws IOException {
        List<Path> bundles = new ArrayList<>();
        int[] examined = new int[1];
        Files.walkFileTree(root, java.util.Set.of(), 8, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
                examined[0]++;
                if (examined[0] > config.maxIndexDeltaBatchSize()) {
                    throw failure(MiddlewareErrorCode.OVERLOADED, "repository index candidate scan limit exceeded",
                            Map.of("limit", Integer.toString(config.maxIndexDeltaBatchSize())));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                examined[0]++;
                if (examined[0] > config.maxIndexDeltaBatchSize()) {
                    throw failure(MiddlewareErrorCode.OVERLOADED, "repository index candidate scan limit exceeded",
                            Map.of("limit", Integer.toString(config.maxIndexDeltaBatchSize())));
                }
                if (file.toString().endsWith(".j7m")) {
                    if (bundles.size() == config.maxIndexDeltaBatchSize()) {
                        throw failure(MiddlewareErrorCode.OVERLOADED, "repository index candidate limit exceeded",
                                Map.of("limit", Integer.toString(config.maxIndexDeltaBatchSize())));
                    }
                    bundles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        bundles.sort(Comparator.comparing(Path::toString));
        return bundles;
    }

    private static String indexDigestText(final Map<String, String> entries) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (!first) {
                builder.append('\n');
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static String artifactDigestList(final Map<String, IndexEntry> entries) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (IndexEntry entry : entries.values()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.digest());
        }
        return builder.toString();
    }

    private void publishIndexDelta(
            final String operation,
            final ApplicationCoordinate coordinate,
            final String digest,
            final long sizeBytes) {
        long previous = this.indexSequence;
        long next = previous + 1L;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("previousSequence", Long.toString(previous));
        fields.put("nextSequence", Long.toString(next));
        fields.put("operation", operation);
        fields.put("coordinate", coordinate.toString());
        fields.put("digest", digest);
        fields.put("sizeBytes", Long.toString(sizeBytes));
        fields.put("policy", this.effectivePolicy(coordinate).name());
        this.applyIndexDelta(fields);
        this.append(RepositoryReplicationEventType.INDEX_DELTA_PUBLISHED, fields);
    }

    private void applyIndexSnapshot(
            final long sequence,
            final String digest,
            final String encodedEntries) {
        Map<String, IndexEntry> decoded = decodeIndexEntries(encodedEntries);
        String actualDigest = indexDigest(decoded);
        if (!digest.equals(actualDigest)) {
            this.conflict("INDEX_SNAPSHOT_DIGEST_MISMATCH", "repository-index",
                    Map.of("expectedDigest", digest, "actualDigest", actualDigest));
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository index snapshot digest mismatch",
                    Map.of("eventType", "INDEX_SNAPSHOT_PUBLISHED"));
        }
        this.indexEntries.clear();
        this.indexEntries.putAll(decoded);
        this.indexSequence = sequence;
    }

    private void applyIndexDelta(final Map<String, String> fields) {
        long previous = parseRequiredLong(fields, "previousSequence", "INDEX_DELTA_PUBLISHED");
        long next = parseRequiredLong(fields, "nextSequence", "INDEX_DELTA_PUBLISHED");
        if (previous != this.indexSequence || next != previous + 1L) {
            this.conflict("INDEX_DELTA_GAP", fields.getOrDefault("coordinate", "repository-index"),
                    Map.of("expectedPrevious", Long.toString(this.indexSequence), "actualPrevious", Long.toString(previous)));
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository index delta gap",
                    Map.of("eventType", "INDEX_DELTA_PUBLISHED"));
        }
        ApplicationCoordinate coordinate = parseCoordinate(fields.get("coordinate"), "INDEX_DELTA_PUBLISHED");
        String digest = requiredField(fields, "digest", "INDEX_DELTA_PUBLISHED");
        validateDigest(digest, "INDEX_DELTA_PUBLISHED");
        String operation = requiredField(fields, "operation", "INDEX_DELTA_PUBLISHED");
        if ("REMOVE".equals(operation)) {
            this.indexEntries.remove(coordinate.toString());
        } else {
            IndexEntry current = this.indexEntries.get(coordinate.toString());
            if (current != null && !"UPDATE".equals(operation) && !current.digest().equals(digest)) {
                this.conflict("INDEX_DELTA_CONFLICT", coordinate.toString(),
                        Map.of("existingDigest", current.digest(), "incomingDigest", digest));
                throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository index delta conflict",
                        Map.of("eventType", "INDEX_DELTA_PUBLISHED"));
            }
            long size = parseRequiredLong(fields, "sizeBytes", "INDEX_DELTA_PUBLISHED");
            this.indexEntries.put(coordinate.toString(), new IndexEntry(coordinate.toString(), digest, size,
                    fields.getOrDefault("policy", "DEVELOPMENT"),
                    fields.getOrDefault("signature", "unknown"),
                    fields.getOrDefault("sbom", "unknown"),
                    fields.getOrDefault("provenance", "unknown")));
        }
        this.indexSequence = next;
    }

    private static String encodeIndexEntries(final Map<String, IndexEntry> entries) {
        StringBuilder builder = new StringBuilder();
        entries.values().stream().sorted(Comparator.comparing(IndexEntry::coordinate)).forEach(entry -> {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(b64(entry.coordinate())).append('|')
                    .append(entry.digest()).append('|')
                    .append(entry.sizeBytes()).append('|')
                    .append(b64(entry.policy())).append('|')
                    .append(b64(entry.signature())).append('|')
                    .append(b64(entry.sbom())).append('|')
                    .append(b64(entry.provenance()));
        });
        return builder.toString();
    }

    private static Map<String, IndexEntry> decodeIndexEntries(final String encoded) {
        Map<String, IndexEntry> output = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return output;
        }
        for (String line : encoded.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 7) {
                throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository index entry",
                        Map.of("eventType", "INDEX_SNAPSHOT_PUBLISHED"));
            }
            String coordinate = ub64(parts[0]);
            String digest = parts[1];
            validateDigest(digest, "INDEX_SNAPSHOT_PUBLISHED");
            long size = strictLong(parts[2], "INDEX_SNAPSHOT_PUBLISHED", "sizeBytes");
            output.put(coordinate, new IndexEntry(coordinate, digest, size, ub64(parts[3]), ub64(parts[4]), ub64(parts[5]), ub64(parts[6])));
        }
        return output;
    }

    private static String indexDigest(final Map<String, IndexEntry> entries) {
        return sha256(encodeIndexEntries(entries).getBytes(StandardCharsets.UTF_8));
    }

    private RepositoryArtifactDigest digest(final LocalApplicationRepository repository, final ApplicationCoordinate coordinate) {
        ApplicationBundle bundle = repository.fetch(coordinate);
        long size;
        try {
            size = Files.size(bundle.path());
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to read artifact size", Map.of("coordinate", coordinate.toString()));
        }
        return new RepositoryArtifactDigest(coordinate, "SHA-256", bundle.sha256(), size);
    }

    private RepositoryArtifactDigest digestOrNull(final LocalApplicationRepository repository, final ApplicationCoordinate coordinate) {
        try {
            return this.digest(repository, coordinate);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static ApplicationCoordinate coordinateFromArtifactPath(final Path artifactRoot, final Path bundlePath) {
        Path relative = artifactRoot.relativize(bundlePath);
        int count = relative.getNameCount();
        if (count < 4) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to derive artifact coordinate", Map.of());
        }
        String name = relative.getName(count - 3).toString();
        String version = relative.getName(count - 2).toString();
        StringBuilder group = new StringBuilder();
        for (int index = 0; index < count - 3; index++) {
            if (!group.isEmpty()) {
                group.append('.');
            }
            group.append(relative.getName(index));
        }
        return ApplicationCoordinate.parse(group + ":" + name + ":" + version);
    }

    private void discardTargetArtifactForRepair(final ApplicationCoordinate coordinate) {
        RepositoryArtifactDigest source = this.digest(this.config.source(), coordinate);
        RepositoryArtifactDigest target = this.digestOrNull(this.config.target(), coordinate);
        if (target == null || target.value().equals(source.value())) {
            return;
        }
        try {
            Path artifact = this.config.target().fetch(coordinate).path();
            Files.deleteIfExists(artifact);
            Files.deleteIfExists(artifact.getParent().resolve("metadata.json"));
            Files.deleteIfExists(artifact.getParent().resolve("verification.json"));
        } catch (RuntimeException | IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to discard corrupted artifact for repair",
                    Map.of("coordinate", coordinate.toString()));
        }
    }

    private RepositoryReplicationConflict conflict(
            final String type,
            final String resource,
            final Map<String, String> metadata) {
        RepositoryReplicationConflict conflict = new RepositoryReplicationConflict(type, resource,
                RepositoryConflictResolution.REJECT, metadata);
        this.addConflict(conflict);
        this.append(RepositoryReplicationEventType.CONFLICT_DETECTED, Map.of(
                "conflictType", type, "resource", resource, "resolution", conflict.resolution().name()));
        return conflict;
    }

    private RepositoryReplicationEvidence evidence(
            final RepositoryReplicationEventType type,
            final String resource,
            final Map<String, String> metadata) {
        RepositoryReplicationEvidence item = new RepositoryReplicationEvidence(type, resource, metadata);
        this.addEvidence(item);
        return item;
    }

    private void addEvidence(final RepositoryReplicationEvidence item) {
        if (this.evidence.size() >= this.retainedStatusItems) {
            this.evidence.remove(0);
        }
        this.evidence.add(item);
    }

    private void addConflict(final RepositoryReplicationConflict conflict) {
        if (this.conflicts.size() >= this.retainedStatusItems) {
            this.conflicts.remove(0);
        }
        this.conflicts.add(conflict);
    }

    private void addReplicatedAuditHash(final String currentHash) {
        if (this.replicatedAuditHashes.size() >= this.retainedStatusItems) {
            this.replicatedAuditHashes.remove(0);
        }
        this.replicatedAuditHashes.add(currentHash);
    }

    private void recover() {
        this.log.recover(RecoveryOptions.defaults());
        long checkpointOffset = this.restoreCheckpoint();
        LogOffset startOffset = checkpointOffset >= 0L ? new LogOffset(checkpointOffset + 1L) : this.degradedRecoveryStartOffset();
        boolean checkpointTail = checkpointOffset >= 0L;
        long checkpointTailLimit = this.degradedRecoveryRecordLimit();
        long readCount = 0L;
        try (LogCursor cursor = this.log.openCursor(EVENT_STREAM, startOffset, new CursorOptions(true))) {
            Optional<LogRecord> next = cursor.next();
            while (next.isPresent()) {
                if (checkpointTail && readCount >= checkpointTailLimit) {
                    throw failure(MiddlewareErrorCode.RECOVERY_FAILED,
                            "repository replication checkpoint tail exceeds bounds",
                            Map.of("limit", Long.toString(checkpointTailLimit)));
                }
                RepositoryReplicationEvent event = this.codec.decode(next.get().payload());
                this.applyRecovered(event);
                this.lastAppliedLogOffset = startOffset.value() + readCount;
                readCount++;
                next = cursor.next();
            }
        }
        this.recoveryReadCount = readCount;
    }

    private void applyRecovered(final RepositoryReplicationEvent event) {
        switch (event.type()) {
            case ARTIFACT_TRANSFER_STARTED -> {
                String transferId = requiredField(event.fields(), "transferId", event.type().name());
                parseRequiredLong(event.fields(), "sizeBytes", event.type().name());
                validateDigest(requiredField(event.fields(), "digest", event.type().name()), event.type().name());
                parseCoordinate(event.fields().get("coordinate"), event.type().name());
                if (this.pendingTransfers.size() < this.config.maxPendingTransferTasks()) {
                    this.pendingTransfers.add(transferId);
                }
            }
            case ARTIFACT_TRANSFER_COMPLETED -> {
                this.artifactsTransferred++;
                this.bytesTransferred += parseRequiredLong(event.fields(), "sizeBytes", event.type().name());
                String transferId = event.fields().get("transferId");
                if (transferId != null && !transferId.isBlank()) {
                    this.pendingTransfers.remove(transferId);
                    this.addCompletedTransfer(transferId);
                }
                this.addEvidence(new RepositoryReplicationEvidence(event.type(),
                        event.fields().getOrDefault("coordinate", ""), event.fields()));
            }
            case ARTIFACT_CHUNK_TRANSFERRED -> {
                this.validateRecoveredChunkEvent(event.fields(), event.type().name());
            }
            case INDEX_SNAPSHOT_PUBLISHED -> {
                long sequence = parseRequiredLong(event.fields(), "sequence", event.type().name());
                String digest = requiredField(event.fields(), "digest", event.type().name());
                this.applyIndexSnapshot(sequence, digest, requiredField(event.fields(), "indexEntries", event.type().name()));
                this.addEvidence(new RepositoryReplicationEvidence(event.type(), "repository-index", event.fields()));
            }
            case INDEX_DELTA_PUBLISHED -> {
                this.applyIndexDelta(event.fields());
                this.addEvidence(new RepositoryReplicationEvidence(event.type(), event.fields().getOrDefault("coordinate", ""), event.fields()));
            }
            case CONFLICT_DETECTED -> this.addConflict(new RepositoryReplicationConflict(
                    event.fields().getOrDefault("conflictType", "UNKNOWN"),
                    event.fields().getOrDefault("resource", ""),
                    RepositoryConflictResolution.REJECT,
                    event.fields()));
            case PROMOTION_RECORDED, DEMOTION_RECORDED -> {
                parseCoordinate(event.fields().get("coordinate"), event.type().name());
                this.promotionState.put(event.fields().getOrDefault("environment", "dev"),
                        event.fields().get("coordinate") + "|" + event.type().name());
                this.addEvidence(new RepositoryReplicationEvidence(event.type(), event.fields().getOrDefault("coordinate", ""), event.fields()));
            }
            case ROLLBACK_RECORDED -> {
                parseCoordinate(event.fields().get("from"), event.type().name());
                parseCoordinate(event.fields().get("to"), event.type().name());
                this.rollbackState.put(event.fields().getOrDefault("environment", "dev"),
                        event.fields().get("from") + "|" + event.fields().get("to"));
                this.addEvidence(new RepositoryReplicationEvidence(event.type(), event.fields().getOrDefault("to", ""), event.fields()));
            }
            case AUDIT_CHAIN_RECORD_REPLICATED -> {
                String currentHash = requiredField(event.fields(), "currentHash", event.type().name());
                if (Files.isRegularFile(this.replicatedAuditPath())) {
                    this.addReplicatedAuditHash(currentHash);
                    this.addEvidence(new RepositoryReplicationEvidence(event.type(), "repository-audit", event.fields()));
                } else {
                    this.addConflict(new RepositoryReplicationConflict("AUDIT_REPLICATION_UNPUBLISHED",
                            "repository-audit", RepositoryConflictResolution.REJECT,
                            Map.of("state", "staged-only", "currentHash", currentHash)));
                }
            }
            case REPAIR_COMPLETED -> {
                this.repairsCompleted++;
                this.addEvidence(new RepositoryReplicationEvidence(event.type(), "repository-repair", event.fields()));
            }
            default -> {
                if (event.type() != RepositoryReplicationEventType.ARTIFACT_CHUNK_TRANSFERRED) {
                    this.addEvidence(new RepositoryReplicationEvidence(event.type(), event.fields().getOrDefault("coordinate", ""), event.fields()));
                }
            }
        }
    }

    private LogOffset append(final RepositoryReplicationEventType type, final Map<String, String> fields) {
        RepositoryReplicationEvent event = new RepositoryReplicationEvent(type, fields);
        LogRecord record = new LogRecord(LogRecordHeader.data(Map.of("domain", "repository-replication",
                "eventType", type.name(), "schemaVersion", RepositoryReplicationEventCodec.SCHEMA_VERSION)),
                this.codec.encode(event));
        LogAppendResult result = this.log.append(EVENT_STREAM, List.of(record), AppendOptions.defaults());
        this.lastAppliedLogOffset = result.lastOffset().value();
        this.publishCheckpoint();
        return result.lastOffset();
    }

    private void validateRecoveredChunkEvent(final Map<String, String> fields, final String eventType) {
        String transferId = requiredField(fields, "transferId", eventType);
        if (transferId.getBytes(StandardCharsets.UTF_8).length > this.limits.maxMetadataBytes()) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository replication transfer id exceeds bounds",
                    Map.of("eventType", eventType, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
        parseCoordinate(fields.get("coordinate"), eventType);
        validateDigest(requiredField(fields, "chunkDigest", eventType), eventType);
        int totalChunks = strictPositiveInt(requiredField(fields, "totalChunks", eventType), eventType, "totalChunks");
        if (totalChunks > this.limits.maxReplicationChunks()) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository replication chunk total exceeds bounds",
                    Map.of("eventType", eventType, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
        int sequence = strictNonNegativeInt(requiredField(fields, "sequence", eventType), eventType, "sequence");
        if (sequence >= totalChunks) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository replication chunk sequence exceeds total",
                    Map.of("eventType", eventType, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
        long bytes = strictNonNegativeLong(requiredField(fields, "bytes", eventType), eventType, "bytes");
        if (bytes > this.config.maxChunkBytes()) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository replication chunk size exceeds bounds",
                    Map.of("eventType", eventType, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
    }

    private Path checkpointPath() {
        return this.config.eventLogDirectory().resolve("repository-replication-checkpoint.json");
    }

    private long restoreCheckpoint() {
        Path path = this.checkpointPath();
        if (!Files.isRegularFile(path)) {
            return -1L;
        }
        try {
            Map<String, String> fields = StrictMetadataJson.readStringObject(path, this.limits, CHECKPOINT_SCHEMA_VERSION,
                    CHECKPOINT_REQUIRED_FIELDS, CHECKPOINT_FIELD_SET);
            String checksum = fields.get("checksum");
            if (!validSha256Hex(checksum) || !checkpointChecksum(fields).equals(checksum)) {
                return -1L;
            }
            long offset = strictLong(fields.get("lastOffset"), "REPLICATION_CHECKPOINT", "lastOffset");
            LogOffset currentOffset = this.currentStreamOffset();
            if (offset < 0L || offset == Long.MAX_VALUE || currentOffset == null || offset > currentOffset.value()) {
                return -1L;
            }
            this.indexSequence = strictNonNegativeLong(fields.get("indexSequence"), "REPLICATION_CHECKPOINT", "indexSequence");
            this.artifactsTransferred = strictNonNegativeLong(fields.get("artifactsTransferred"), "REPLICATION_CHECKPOINT", "artifactsTransferred");
            this.bytesTransferred = strictNonNegativeLong(fields.get("bytesTransferred"), "REPLICATION_CHECKPOINT", "bytesTransferred");
            this.repairsCompleted = strictNonNegativeLong(fields.get("repairsCompleted"), "REPLICATION_CHECKPOINT", "repairsCompleted");
            this.completedTransfers.clear();
            this.restoreCompletedTransfers(fields.get("completedTransfers"));
            this.pendingTransfers.clear();
            this.restorePendingTransfers(fields.get("pendingTransfers"));
            this.promotionState.clear();
            this.restoreStringMap(this.promotionState, fields.get("promotionState"), this.retainedStatusItems);
            this.rollbackState.clear();
            this.restoreStringMap(this.rollbackState, fields.get("rollbackState"), this.retainedStatusItems);
            this.replicatedAuditHashes.clear();
            this.restoreAuditHashes(fields.get("replicatedAuditHashes"));
            this.indexEntries.clear();
            this.restoreIndexEntries(fields.get("indexEntries"));
            this.restoreIndexEvidenceFromCheckpoint();
            this.lastAppliedLogOffset = offset;
            return offset;
        } catch (IOException | RuntimeException exception) {
            return -1L;
        }
    }

    private LogOffset degradedRecoveryStartOffset() {
        LogOffset currentOffset = this.currentStreamOffset();
        if (currentOffset == null) {
            return new LogOffset(0L);
        }
        this.recoveryDegraded = true;
        long limit = this.degradedRecoveryRecordLimit();
        long start = currentOffset.value() >= limit ? currentOffset.value() - limit + 1L : 0L;
        this.addConflict(new RepositoryReplicationConflict("REPLICATION_CHECKPOINT_DEGRADED",
                "repository-replication-checkpoint", RepositoryConflictResolution.REJECT,
                Map.of("mode", "bounded-tail", "limit", Long.toString(limit))));
        return new LogOffset(start);
    }

    private long degradedRecoveryRecordLimit() {
        return Math.max(1L, this.retainedStatusItems);
    }

    private LogOffset currentStreamOffset() {
        LogStatus status = this.log.status();
        return status.currentOffsets().get(EVENT_STREAM);
    }

    private void restoreCompletedTransfers(final String encoded) {
        this.completedTransfers.addAll(decodeStringList(encoded, this.retainedStatusItems));
    }

    private void restorePendingTransfers(final String encoded) {
        this.pendingTransfers.addAll(decodeStringList(encoded, this.config.maxPendingTransferTasks()));
    }

    private void restoreStringMap(final Map<String, String> target, final String encoded, final int limit) {
        target.putAll(decodeStringMap(encoded, limit));
    }

    private void restoreAuditHashes(final String encoded) {
        this.replicatedAuditHashes.addAll(decodeStringList(encoded, this.retainedStatusItems));
    }

    private void restoreIndexEntries(final String encoded) {
        this.indexEntries.putAll(decodeIndexEntries(encoded));
    }

    private void restoreIndexEvidenceFromCheckpoint() {
        if (this.indexEntries.isEmpty()) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", METADATA_SCHEMA_VERSION);
        fields.put("sourceReplica", this.config.peerReplica().value());
        fields.put("sequence", Long.toString(this.indexSequence));
        fields.put("digest", indexDigest(this.indexEntries));
        fields.put("entries", Integer.toString(this.indexEntries.size()));
        fields.put("coordinates", String.join(",", this.indexEntries.keySet()));
        fields.put("artifactDigests", artifactDigestList(this.indexEntries));
        fields.put("indexEntries", encodeIndexEntries(this.indexEntries));
        this.addEvidence(new RepositoryReplicationEvidence(RepositoryReplicationEventType.INDEX_SNAPSHOT_PUBLISHED,
                "repository-index", fields));
    }

    private void publishCheckpoint() {
        if (this.lastAppliedLogOffset < 0L) {
            return;
        }
        if (this.recoveryDegraded) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", CHECKPOINT_SCHEMA_VERSION);
        fields.put("lastOffset", Long.toString(this.lastAppliedLogOffset));
        fields.put("indexSequence", Long.toString(this.indexSequence));
        fields.put("artifactsTransferred", Long.toString(this.artifactsTransferred));
        fields.put("bytesTransferred", Long.toString(this.bytesTransferred));
        fields.put("repairsCompleted", Long.toString(this.repairsCompleted));
        fields.put("completedTransfers", encodeStringList(this.completedTransfers));
        fields.put("pendingTransfers", encodeStringList(this.pendingTransfers));
        fields.put("promotionState", encodeStringMap(this.promotionState));
        fields.put("rollbackState", encodeStringMap(this.rollbackState));
        fields.put("replicatedAuditHashes", encodeStringList(this.replicatedAuditHashes));
        fields.put("indexEntries", encodeIndexEntries(this.indexEntries));
        fields.put("checksum", checkpointChecksum(fields));
        try {
            BoundedBundleIO.writeSmallFileAtomically(this.checkpointPath(), json(fields) + "\n", this.limits.maxMetadataBytes());
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to publish repository replication checkpoint", Map.of());
        }
    }

    private static String checkpointChecksum(final Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        for (String key : CHECKPOINT_FIELDS) {
            if (!"checksum".equals(key)) {
                builder.append(key).append('=').append(fields.getOrDefault(key, "")).append('\n');
            }
        }
        return sha256(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String auditReplicationCheckpointChecksum(final Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        for (String key : AUDIT_REPLICATION_CHECKPOINT_FIELDS) {
            if (!"checksum".equals(key)) {
                builder.append(key).append('=').append(fields.getOrDefault(key, "")).append('\n');
            }
        }
        return sha256(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void addCompletedTransfer(final String transferId) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        if (this.completedTransfers.size() >= this.retainedStatusItems) {
            String eldest = this.completedTransfers.iterator().next();
            this.completedTransfers.remove(eldest);
        }
        this.completedTransfers.add(transferId);
    }

    private void ensureOpen() {
        if (this.closed.get()) {
            throw failure(MiddlewareErrorCode.UNAVAILABLE, "repository replication service is closed", Map.of());
        }
    }

    private List<RepositoryPromotionRecord> sourcePromotionRecords() {
        Path root = this.config.source().repositoryRoot().resolve("promotions");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            List<Path> files = new ArrayList<>();
            java.util.Iterator<Path> iterator = stream.iterator();
            int examined = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                examined++;
                if (examined > this.config.maxIndexDeltaBatchSize()) {
                    throw failure(MiddlewareErrorCode.OVERLOADED, "promotion state scan limit exceeded",
                            Map.of("limit", Integer.toString(this.config.maxIndexDeltaBatchSize())));
                }
                if (path.toString().endsWith(".json")) {
                    if (files.size() == this.config.maxIndexDeltaBatchSize()) {
                        throw failure(MiddlewareErrorCode.OVERLOADED, "promotion state candidate limit exceeded",
                                Map.of("limit", Integer.toString(this.config.maxIndexDeltaBatchSize())));
                    }
                    files.add(path);
                }
            }
            files.sort(Comparator.comparing(Path::toString));
            List<RepositoryPromotionRecord> records = new ArrayList<>(files.size());
            for (Path path : files) {
                Map<String, String> metadata = this.readPromotionMetadata(path);
                ApplicationCoordinate coordinate = parseCoordinate(metadata.get("coordinate"), "PROMOTION_RECORDED");
                String state = metadata.get("state");
                if (!"PROMOTED".equals(state) && !"DEMOTED".equals(state)) {
                    throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed promotion state metadata",
                            Map.of("errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
                }
                RepositoryArtifactDigest digest = this.digest(this.config.source(), coordinate);
                String fileName = path.getFileName().toString();
                String environment = fileName.substring(0, fileName.length() - ".json".length());
                records.add(new RepositoryPromotionRecord(environment, coordinate, state, digest.value()));
            }
            return List.copyOf(records);
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to list promotion state", Map.of());
        }
    }

    private List<RepositoryRollbackRecord> sourceRollbackRecords() {
        Path root = this.config.source().repositoryRoot().resolve("rollback");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            List<Path> files = new ArrayList<>();
            java.util.Iterator<Path> iterator = stream.iterator();
            int examined = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                examined++;
                if (examined > this.config.maxIndexDeltaBatchSize()) {
                    throw failure(MiddlewareErrorCode.OVERLOADED, "rollback state scan limit exceeded",
                            Map.of("limit", Integer.toString(this.config.maxIndexDeltaBatchSize())));
                }
                if (path.toString().endsWith(".json")) {
                    if (files.size() == this.config.maxIndexDeltaBatchSize()) {
                        throw failure(MiddlewareErrorCode.OVERLOADED, "rollback state candidate limit exceeded",
                                Map.of("limit", Integer.toString(this.config.maxIndexDeltaBatchSize())));
                    }
                    files.add(path);
                }
            }
            files.sort(Comparator.comparing(Path::toString));
            List<RepositoryRollbackRecord> records = new ArrayList<>(files.size());
            for (Path path : files) {
                Map<String, String> metadata = this.readRollbackMetadata(path);
                String environment = metadata.get("environment");
                if (environment.isBlank()) {
                    String fileName = path.getFileName().toString();
                    environment = fileName.substring(0, fileName.length() - ".json".length());
                }
                records.add(new RepositoryRollbackRecord(environment,
                        parseCoordinate(metadata.get("from"), "ROLLBACK_RECORDED"),
                        parseCoordinate(metadata.get("to"), "ROLLBACK_RECORDED"),
                        safeText(metadata.get("reason")),
                        java.time.Instant.EPOCH));
            }
            return List.copyOf(records);
        } catch (IOException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "failed to list rollback state", Map.of());
        }
    }

    private SignaturePolicy effectivePolicy(final ApplicationCoordinate coordinate) {
        return stronger(this.config.source().verificationPolicy(coordinate), this.config.targetVerificationPolicy());
    }

    private static SignaturePolicy stronger(final SignaturePolicy first, final SignaturePolicy second) {
        SignaturePolicy left = first == null ? SignaturePolicy.DEVELOPMENT : first;
        SignaturePolicy right = second == null ? SignaturePolicy.DEVELOPMENT : second;
        if (left == SignaturePolicy.STRICT_REGULATED || right == SignaturePolicy.STRICT_REGULATED) {
            return SignaturePolicy.STRICT_REGULATED;
        }
        if (left == SignaturePolicy.STRICT_PRODUCTION || right == SignaturePolicy.STRICT_PRODUCTION) {
            return SignaturePolicy.STRICT_PRODUCTION;
        }
        return SignaturePolicy.DEVELOPMENT;
    }

    private static long parseRequiredLong(
            final Map<String, String> fields,
            final String key,
            final String eventType) {
        return strictLong(requiredField(fields, key, eventType), eventType, key);
    }

    private static long strictLong(final String value, final String eventType, final String key) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication numeric field",
                    Map.of("eventType", eventType, "field", key, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
    }

    private static long strictNonNegativeLong(final String value, final String eventType, final String key) {
        long parsed = strictLong(value, eventType, key);
        if (parsed < 0L) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication numeric field",
                    Map.of("eventType", eventType, "field", key, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
        return parsed;
    }

    private static int strictNonNegativeInt(final String value, final String eventType, final String key) {
        long parsed = strictNonNegativeLong(value, eventType, key);
        if (parsed > Integer.MAX_VALUE) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication numeric field",
                    Map.of("eventType", eventType, "field", key, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
        return (int) parsed;
    }

    private static int strictPositiveInt(final String value, final String eventType, final String key) {
        int parsed = strictNonNegativeInt(value, eventType, key);
        if (parsed == 0) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication numeric field",
                    Map.of("eventType", eventType, "field", key, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
        return parsed;
    }

    private static String requiredField(
            final Map<String, String> fields,
            final String key,
            final String eventType) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "missing repository replication field",
                    Map.of("eventType", eventType, "field", key, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
        return value;
    }

    private static ApplicationCoordinate parseCoordinate(final String value, final String eventType) {
        try {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing coordinate");
            }
            return ApplicationCoordinate.parse(value);
        } catch (RuntimeException exception) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication coordinate",
                    Map.of("eventType", eventType, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
    }

    private static void validateDigest(final String value, final String eventType) {
        if (!validSha256Hex(value)) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication digest",
                    Map.of("eventType", eventType, "errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
    }

    private Map<String, String> readPromotionMetadata(final Path path) {
        try {
            return StrictMetadataJson.readStringObject(path, this.limits, METADATA_SCHEMA_VERSION,
                    PROMOTION_METADATA_FIELDS, PROMOTION_METADATA_FIELDS);
        } catch (IOException | IllegalArgumentException exception) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed promotion state metadata",
                    Map.of("errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
    }

    private Map<String, String> readRollbackMetadata(final Path path) {
        try {
            return StrictMetadataJson.readStringObject(path, this.limits, METADATA_SCHEMA_VERSION,
                    Set.of("schemaVersion", "from", "to"), ROLLBACK_METADATA_FIELDS);
        } catch (IOException | IllegalArgumentException exception) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed rollback state metadata",
                    Map.of("errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
    }

    private static RepositoryReplicationException failure(
            final MiddlewareErrorCode code,
            final String message,
            final Map<String, String> details) {
        return new RepositoryReplicationException(new RemoteError(code, message, details, null, null,
                code == MiddlewareErrorCode.OVERLOADED, code == MiddlewareErrorCode.INTERNAL));
    }

    private static String verifyAuditLine(final String line, final String previousHash) {
        Map<String, String> values = parseAuditLine(line);
        String linePreviousHash = values.get("previousHash");
        String currentHash = values.get("currentHash");
        if (!previousHash.equals(linePreviousHash) || !validAuditHash(currentHash)) {
            return null;
        }
        String canonical = auditCanonical(
                values.get("timestamp"),
                values.get("action"),
                values.get("coordinate"),
                values.get("result"),
                linePreviousHash);
        return sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(currentHash) ? currentHash : null;
    }

    private static Map<String, String> parseAuditLine(final String line) {
        try {
            return StrictMetadataJson.parseStringObject(line, ApplicationPlatformLimits.defaults(), null,
                    AUDIT_REQUIRED_METADATA_FIELDS, AUDIT_METADATA_FIELDS);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private static String auditCanonical(
            final String timestamp,
            final String action,
            final String coordinate,
            final String result,
            final String previousHash) {
        return timestamp + '\n' + action + '\n' + coordinate + '\n' + result + '\n' + previousHash;
    }

    private static boolean validAuditHash(final String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String safeText(final String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
    }

    private static String b64(final String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String ub64(final String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication encoded metadata",
                    Map.of("errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
        }
    }

    private static String encodeStringList(final Iterable<String> values) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (String value : values) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(b64(value));
        }
        return builder.toString();
    }

    private static List<String> decodeStringList(final String encoded, final int limit) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        int start = 0;
        while (start <= encoded.length()) {
            int end = encoded.indexOf(',', start);
            String part = end < 0 ? encoded.substring(start) : encoded.substring(start, end);
            if (!part.isBlank()) {
                if (values.size() >= limit) {
                    throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository replication checkpoint list exceeds limit",
                            Map.of("errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
                }
                values.add(ub64(part));
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return List.copyOf(values);
    }

    private static String encodeStringMap(final Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(';');
            }
            first = false;
            builder.append(b64(entry.getKey())).append('=').append(b64(entry.getValue()));
        }
        return builder.toString();
    }

    private static Map<String, String> decodeStringMap(final String encoded, final int limit) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        int start = 0;
        while (start <= encoded.length()) {
            int end = encoded.indexOf(';', start);
            String part = end < 0 ? encoded.substring(start) : encoded.substring(start, end);
            int separator = part.indexOf('=');
            if (separator <= 0 || separator == part.length() - 1) {
                throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "malformed repository replication checkpoint map",
                        Map.of("errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
            }
            if (values.size() >= limit) {
                throw failure(MiddlewareErrorCode.RECOVERY_FAILED, "repository replication checkpoint map exceeds limit",
                        Map.of("errorCode", MiddlewareErrorCode.RECOVERY_FAILED.name()));
            }
            values.put(ub64(part.substring(0, separator)), ub64(part.substring(separator + 1)));
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return Map.copyOf(values);
    }

    private static String json(final Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escape(entry.getKey())).append('"').append(':');
            builder.append('"').append(escape(entry.getValue())).append('"');
        }
        builder.append('}');
        return builder.toString();
    }

    private static String escape(final String value) {
        String source = value == null ? "" : value;
        return source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String sha256(final byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "SHA-256 unavailable", Map.of());
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, "SHA-256 unavailable", Map.of());
        }
    }

    private static String hex(final byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = alphabet[value >>> 4];
            output[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(output);
    }

    private record IndexEntry(
            String coordinate,
            String digest,
            long sizeBytes,
            String policy,
            String signature,
            String sbom,
            String provenance) {
    }

    private record AuditReplicationProgress(
            long cursor,
            String previousHash,
            int replicated,
            int page,
            long stagedBytes) {
        static AuditReplicationProgress empty() {
            return new AuditReplicationProgress(0L, "", 0, 0, 0L);
        }
    }

    private record StagedAuditValidation(
            int recordCount,
            long byteCount,
            String previousHash,
            String digest) {
    }
}
