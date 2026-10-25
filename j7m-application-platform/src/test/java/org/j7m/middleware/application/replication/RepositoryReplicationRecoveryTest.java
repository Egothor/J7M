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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.replication.RepositoryReplicationConflict;
import org.j7m.middleware.application.api.replication.RepositoryReplicationException;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.api.replication.RepositoryRepairPlan;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReplicationRecoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recoversCompletedTransfersAndRejectsMalformedEvents() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "recovery", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        Path eventRoot = this.temporaryDirectory.resolve("events");
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertTrue(service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())).success());
        }
        try (LocalRepositoryReplicationService recovered = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertTrue(recovered.status().metrics().artifactsTransferred() > 0L);
        }

        Path badEvents = this.temporaryDirectory.resolve("bad-events");
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(badEvents.resolve("events")))) {
            log.append(new LogStreamId("repository-replication-events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of()),
                    "schemaVersion=Mg==\ntype=Q09ORkxJQ1RfREVURUNURUQ=\nconflictType=WA==\n".getBytes(StandardCharsets.UTF_8))),
                    AppendOptions.defaults());
        }
        RepositoryReplicationException exception = assertThrows(RepositoryReplicationException.class,
                () -> RepositoryReplicationTestSupport.service(badEvents, source, target));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
    }

    @Test
    void missingCheckpointRecoversBoundedTailAndReportsDegraded() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-many"), "many-recovery", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-many"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-many"));
        Path eventRoot = this.temporaryDirectory.resolve("many-events");
        int eventCount = 300;
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(eventRoot.resolve("events")))) {
            LogStreamId stream = new LogStreamId("repository-replication-events");
            for (int index = 0; index < eventCount; index++) {
                log.append(stream, List.of(new LogRecord(LogRecordHeader.data(Map.of()), event(Map.of(
                        "schemaVersion", "1",
                        "type", "ARTIFACT_TRANSFER_COMPLETED",
                        "transferId", "transfer-" + index,
                        "coordinate", bundle.coordinate().toString(),
                        "digest", bundle.sha256(),
                        "sizeBytes", "1")))), AppendOptions.defaults());
            }
        }

        try (LocalRepositoryReplicationService recovered = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertEquals(ApplicationPlatformLimits.defaults().maxAuditEventsPerPage(), recovered.recoveryReadCount());
            assertEquals(ApplicationPlatformLimits.defaults().maxAuditEventsPerPage(), recovered.status().metrics().artifactsTransferred());
            assertNotEquals((long) eventCount, recovered.status().metrics().artifactsTransferred());
            assertDegradedCheckpoint(recovered);
            assertTrue(recovered.status().evidence().size() <= 256);
        }
    }

    @Test
    void validCheckpointReadsOnlyTailAndExactEndReadsNoHistory() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-checkpoint"),
                "checkpoint-recovery", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-checkpoint"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-checkpoint"));
        Path eventRoot = this.temporaryDirectory.resolve("checkpoint-events");
        int historicalEvents = 300;
        LogStreamId stream = new LogStreamId("repository-replication-events");
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(eventRoot.resolve("events")))) {
            for (int index = 0; index < historicalEvents; index++) {
                log.append(stream, List.of(new LogRecord(LogRecordHeader.data(Map.of()), event(Map.of(
                        "schemaVersion", "1",
                        "type", "ARTIFACT_TRANSFER_COMPLETED",
                        "transferId", "checkpoint-transfer-" + index,
                        "coordinate", bundle.coordinate().toString(),
                        "digest", bundle.sha256(),
                        "sizeBytes", "1")))), AppendOptions.defaults());
            }
        }
        try (LocalRepositoryReplicationService recovered = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertEquals(ApplicationPlatformLimits.defaults().maxAuditEventsPerPage(), recovered.recoveryReadCount());
            assertDegradedCheckpoint(recovered);
            assertFalse(Files.exists(eventRoot.resolve("events").resolve("repository-replication-checkpoint.json")));
            assertTrue(recovered.repair(new RepositoryRepairPlan(List.of(), true, true)).success());
        }
        assertTrue(Files.exists(eventRoot.resolve("events").resolve("repository-replication-checkpoint.json")));
        try (LocalRepositoryReplicationService exactEnd = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertEquals(0L, exactEnd.recoveryReadCount());
        }
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(eventRoot.resolve("events")))) {
            log.recover(org.j7m.middleware.api.durablelog.RecoveryOptions.defaults());
            log.append(stream, List.of(new LogRecord(LogRecordHeader.data(Map.of()), event(Map.of(
                    "schemaVersion", "1",
                    "type", "ARTIFACT_TRANSFER_COMPLETED",
                    "transferId", "checkpoint-tail",
                    "coordinate", bundle.coordinate().toString(),
                    "digest", bundle.sha256(),
                    "sizeBytes", "1")))), AppendOptions.defaults());
        }
        try (LocalRepositoryReplicationService tail = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertEquals(1L, tail.recoveryReadCount());
        }
    }

    @Test
    void validCheckpointWithOversizedTailFailsClosed() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-stale-checkpoint"),
                "stale-checkpoint", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-stale-checkpoint"),
                bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-stale-checkpoint"));
        Path eventRoot = this.temporaryDirectory.resolve("stale-checkpoint-events");
        LogStreamId stream = new LogStreamId("repository-replication-events");
        try (LocalRepositoryReplicationService ignored = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertEquals(0L, ignored.recoveryReadCount());
        }
        int oversizedTail = ApplicationPlatformLimits.defaults().maxAuditEventsPerPage() + 1;
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(eventRoot.resolve("events")))) {
            log.recover(org.j7m.middleware.api.durablelog.RecoveryOptions.defaults());
            for (int index = 0; index < oversizedTail; index++) {
                log.append(stream, List.of(new LogRecord(LogRecordHeader.data(Map.of()), event(Map.of(
                        "schemaVersion", "1",
                        "type", "ARTIFACT_TRANSFER_COMPLETED",
                        "transferId", "stale-checkpoint-tail-" + index,
                        "coordinate", bundle.coordinate().toString(),
                        "digest", bundle.sha256(),
                        "sizeBytes", "1")))), AppendOptions.defaults());
            }
        }

        RepositoryReplicationException exception = assertThrows(RepositoryReplicationException.class,
                () -> RepositoryReplicationTestSupport.service(eventRoot, source, target));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
    }

    @Test
    void unpublishedStagedAuditReplicationEventDoesNotRecoverAsCompleteEvidence() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-unpublished-audit"),
                "unpublished-audit", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-unpublished-audit"),
                bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-unpublished-audit"));
        Path eventRoot = this.temporaryDirectory.resolve("unpublished-audit-events");
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(eventRoot.resolve("events")))) {
            log.append(new LogStreamId("repository-replication-events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of()), event(Map.of(
                    "schemaVersion", "1",
                    "type", "AUDIT_CHAIN_RECORD_REPLICATED",
                    "currentHash", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "line", "0")))), AppendOptions.defaults());
        }

        try (LocalRepositoryReplicationService recovered = RepositoryReplicationTestSupport.service(eventRoot, source, target)) {
            assertFalse(recovered.status().evidence().stream()
                    .anyMatch(item -> item.eventType() == org.j7m.middleware.application.api.replication.RepositoryReplicationEventType
                            .AUDIT_CHAIN_RECORD_REPLICATED));
            assertTrue(recovered.status().conflicts().stream()
                    .anyMatch(conflict -> "AUDIT_REPLICATION_UNPUBLISHED".equals(conflict.conflictType())));
        }
    }

    @Test
    void strictCheckpointParserRejectsMalformedCheckpointShapesSafely() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-strict"),
                "strict-checkpoint", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-strict"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-strict"));

        assertMalformedCheckpointDegrades(source, target, "duplicate-schema", """
                {"schemaVersion":"j7m.repository.replication-checkpoint/v1","schemaVersion":"j7m.repository.replication-checkpoint/v1","lastOffset":"0","indexSequence":"0","artifactsTransferred":"0","bytesTransferred":"0","repairsCompleted":"0","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
        assertMalformedCheckpointDegrades(source, target, "wrong-type", """
                {"schemaVersion":"j7m.repository.replication-checkpoint/v1","lastOffset":0,"indexSequence":"0","artifactsTransferred":"0","bytesTransferred":"0","repairsCompleted":"0","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
        assertMalformedCheckpointDegrades(source, target, "trailing-data", """
                {"schemaVersion":"j7m.repository.replication-checkpoint/v1","lastOffset":"0","indexSequence":"0","artifactsTransferred":"0","bytesTransferred":"0","repairsCompleted":"0","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"} true
                """);
        assertMalformedCheckpointDegrades(source, target, "unsupported-schema", """
                {"schemaVersion":"j7m.repository.replication-checkpoint/v2","lastOffset":"0","indexSequence":"0","artifactsTransferred":"0","bytesTransferred":"0","repairsCompleted":"0","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
    }

    @Test
    void rejectsUnknownTypeMissingFieldMalformedDigestAndMalformedCoordinate() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src2"), "semantic-recovery", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source2"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target2"));

        assertRecoveryFailed(source, target, "unknown-type", event(Map.of("schemaVersion", "1", "type", "NOT_A_REAL_EVENT")));
        assertRecoveryFailed(source, target, "missing-field", event(Map.of("schemaVersion", "1", "type", "ARTIFACT_TRANSFER_COMPLETED",
                "coordinate", bundle.coordinate().toString(), "digest", bundle.sha256())));
        assertRecoveryFailed(source, target, "bad-digest", event(Map.of("schemaVersion", "1", "type", "INDEX_DELTA_PUBLISHED",
                "previousSequence", "0", "nextSequence", "1", "operation", "ADD", "coordinate", bundle.coordinate().toString(),
                "digest", "not-a-digest", "sizeBytes", "1")));
        assertRecoveryFailed(source, target, "bad-coordinate", event(Map.of("schemaVersion", "1", "type", "INDEX_DELTA_PUBLISHED",
                "previousSequence", "0", "nextSequence", "1", "operation", "ADD", "coordinate", "not-a-coordinate",
                "digest", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "sizeBytes", "1")));
        assertRecoveryFailed(source, target, "chunk-missing-transfer", event(Map.of("schemaVersion", "1", "type", "ARTIFACT_CHUNK_TRANSFERRED",
                "coordinate", bundle.coordinate().toString(), "sequence", "0", "totalChunks", "1",
                "chunkDigest", bundle.sha256(), "bytes", "1")));
        assertRecoveryFailed(source, target, "chunk-bad-coordinate", event(Map.of("schemaVersion", "1", "type", "ARTIFACT_CHUNK_TRANSFERRED",
                "transferId", "transfer-bad-coordinate", "coordinate", "not-a-coordinate", "sequence", "0", "totalChunks", "1",
                "chunkDigest", bundle.sha256(), "bytes", "1")));
        assertRecoveryFailed(source, target, "chunk-bad-digest", event(Map.of("schemaVersion", "1", "type", "ARTIFACT_CHUNK_TRANSFERRED",
                "transferId", "transfer-bad-digest", "coordinate", bundle.coordinate().toString(), "sequence", "0", "totalChunks", "1",
                "chunkDigest", "not-a-digest", "bytes", "1")));
        assertRecoveryFailed(source, target, "chunk-sequence-over-total", event(Map.of("schemaVersion", "1", "type", "ARTIFACT_CHUNK_TRANSFERRED",
                "transferId", "transfer-bad-sequence", "coordinate", bundle.coordinate().toString(), "sequence", "1", "totalChunks", "1",
                "chunkDigest", bundle.sha256(), "bytes", "1")));
        assertRecoveryFailed(source, target, "chunk-negative-bytes", event(Map.of("schemaVersion", "1", "type", "ARTIFACT_CHUNK_TRANSFERRED",
                "transferId", "transfer-negative-bytes", "coordinate", bundle.coordinate().toString(), "sequence", "0", "totalChunks", "1",
                "chunkDigest", bundle.sha256(), "bytes", "-1")));
        assertRecoveryFailed(source, target, "chunk-too-large", event(Map.of("schemaVersion", "1", "type", "ARTIFACT_CHUNK_TRANSFERRED",
                "transferId", "transfer-too-large", "coordinate", bundle.coordinate().toString(), "sequence", "0", "totalChunks", "1",
                "chunkDigest", bundle.sha256(), "bytes", Integer.toString(1024 * 1024 + 1))));
    }

    private void assertRecoveryFailed(
            final LocalApplicationRepository source,
            final LocalApplicationRepository target,
            final String name,
            final byte[] payload) {
        Path root = this.temporaryDirectory.resolve(name);
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(root.resolve("events")))) {
            log.append(new LogStreamId("repository-replication-events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of()), payload)), AppendOptions.defaults());
        }
        RepositoryReplicationException exception = assertThrows(RepositoryReplicationException.class,
                () -> RepositoryReplicationTestSupport.service(root, source, target));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
    }

    private void assertMalformedCheckpointDegrades(
            final LocalApplicationRepository source,
            final LocalApplicationRepository target,
            final String name,
            final String checkpoint) throws Exception {
        Path root = this.temporaryDirectory.resolve(name);
        Path eventDirectory = root.resolve("events");
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(eventDirectory))) {
            log.append(new LogStreamId("repository-replication-events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of()), event(Map.of(
                    "schemaVersion", "1",
                    "type", "ARTIFACT_TRANSFER_COMPLETED",
                    "transferId", name + "-transfer",
                    "coordinate", RepositoryReplicationTestSupport.coordinate("strict-checkpoint", "1.0.0").toString(),
                    "digest", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "sizeBytes", "1")))), AppendOptions.defaults());
        }
        Files.createDirectories(eventDirectory);
        Files.writeString(eventDirectory.resolve("repository-replication-checkpoint.json"), checkpoint, StandardCharsets.UTF_8);

        try (LocalRepositoryReplicationService recovered = RepositoryReplicationTestSupport.service(root, source, target)) {
            assertEquals(1L, recovered.recoveryReadCount());
            assertDegradedCheckpoint(recovered);
        }
    }

    private void assertDegradedCheckpoint(final LocalRepositoryReplicationService service) {
        RepositoryReplicationConflict conflict = service.status().conflicts().getFirst();
        assertEquals("REPLICATION_CHECKPOINT_DEGRADED", conflict.conflictType());
        assertEquals("repository-replication-checkpoint", conflict.resource());
        assertEquals("bounded-tail", conflict.metadata().get("mode"));
        String rendered = conflict.resource() + conflict.metadata();
        assertFalse(rendered.contains(this.temporaryDirectory.toString()));
        assertFalse(rendered.contains("schemaVersion"));
        assertFalse(rendered.contains("lastOffset"));
        assertFalse(rendered.contains("checksum"));
    }

    private static byte[] event(final Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> builder.append(key).append('=')
                .append(java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))).append('\n'));
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
