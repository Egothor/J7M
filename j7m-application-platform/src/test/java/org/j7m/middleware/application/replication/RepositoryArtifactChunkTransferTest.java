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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.replication.RepositoryArtifactChunk;
import org.j7m.middleware.application.api.replication.RepositoryArtifactDigest;
import org.j7m.middleware.application.api.replication.RepositoryReplicaId;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryArtifactChunkTransferTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reconstructsChunkedTransferAndRejectsOversizedArtifact() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "chunked", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events"), 10L, 8, 10, 1, 10, 10, 10, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            org.j7m.middleware.application.api.replication.RepositoryReplicationException exception = assertThrows(
                    org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                    () -> service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())));
            assertEquals(MiddlewareErrorCode.OVERLOADED, exception.code());
        }

        LocalApplicationRepository secondTarget = new LocalApplicationRepository(this.temporaryDirectory.resolve("target2"));
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(this.temporaryDirectory.resolve("ok"), source, secondTarget)) {
            assertTrue(service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())).success());
            assertEquals(bundle.sha256(), secondTarget.fetch(bundle.coordinate()).sha256());
        }
    }

    @Test
    void assemblesOutOfOrderChunksAndRejectsMissingDuplicateOversizedAndCorruptChunks() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src2"), "chunks", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source2"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target3"));
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events2"), Long.MAX_VALUE, 7, 10, 10, 10, 10, 10, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            RepositoryArtifactDigest digest = service.advertiseDigest(bundle.coordinate());
            java.util.List<RepositoryArtifactChunk> chunks = service.createChunks(bundle.path(), digest, "transfer-test");
            java.util.List<RepositoryArtifactChunk> reversed = new java.util.ArrayList<>(chunks);
            java.util.Collections.reverse(reversed);
            assertArrayEquals(java.nio.file.Files.readAllBytes(bundle.path()), service.assembleChunks(reversed, digest));
            Path staged = service.stageChunks(reversed, digest);
            try {
                assertEquals(bundle.sha256(), sha256(Files.readAllBytes(staged)));
            } finally {
                Files.deleteIfExists(staged);
            }

            java.util.List<RepositoryArtifactChunk> missing = new java.util.ArrayList<>(chunks);
            missing.remove(0);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(missing, digest)).code());

            RepositoryArtifactChunk first = chunks.getFirst();
            RepositoryArtifactChunk conflictingDuplicate = new RepositoryArtifactChunk(first.transferId(), digest, first.sequence(),
                    first.totalChunks(), "bad", "bad".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.util.List<RepositoryArtifactChunk> duplicate = new java.util.ArrayList<>(chunks);
            duplicate.add(conflictingDuplicate);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(duplicate, digest)).code());

            RepositoryArtifactChunk oversized = new RepositoryArtifactChunk("oversized", digest, 0, 1, "",
                    new byte[config.maxChunkBytes() + 1]);
            assertEquals(MiddlewareErrorCode.OVERLOADED,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(oversized), digest)).code());

            RepositoryArtifactChunk corrupt = new RepositoryArtifactChunk(first.transferId(), digest, first.sequence(),
                    first.totalChunks(), first.chunkDigest(), "changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.util.List<RepositoryArtifactChunk> corrupted = new java.util.ArrayList<>(chunks);
            corrupted.set(0, corrupt);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(corrupted, digest)).code());

            RepositoryArtifactDigest oversizedDigest = new RepositoryArtifactDigest(digest.coordinate(), digest.algorithm(),
                    digest.value(), (long) Integer.MAX_VALUE + 1L);
            RepositoryArtifactChunk declaredOversized = new RepositoryArtifactChunk("declared-oversized", oversizedDigest, 0,
                    1, "", new byte[0]);
            assertEquals(MiddlewareErrorCode.OVERLOADED,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(declaredOversized), oversizedDigest)).code());
        }
    }

    @Test
    void validatesChunkDescriptorsBeforeAssemblyAllocation() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src4"), "descriptor", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source4"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target5"));
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events4"), Long.MAX_VALUE, 8, 10, 10, 10, 10, 10, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            RepositoryArtifactDigest digest = service.advertiseDigest(bundle.coordinate());
            byte[] firstBytes = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] secondBytes = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            RepositoryArtifactDigest twoChunkDigest = new RepositoryArtifactDigest(digest.coordinate(), "SHA-256",
                    sha256(concat(firstBytes, secondBytes)), firstBytes.length + secondBytes.length);
            RepositoryArtifactDigest singleDigest = new RepositoryArtifactDigest(digest.coordinate(), "SHA-256",
                    sha256(firstBytes), firstBytes.length);

            RepositoryArtifactChunk single = new RepositoryArtifactChunk("single", singleDigest, 0, 1,
                    sha256(firstBytes), firstBytes);
            assertArrayEquals(firstBytes, service.assembleChunks(java.util.List.of(single), singleDigest));

            assertEquals(MiddlewareErrorCode.OVERLOADED,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(new RepositoryArtifactChunk("too-many", digest,
                                    0, 8193, "", new byte[0])), digest)).code());

            RepositoryArtifactChunk countMismatch = new RepositoryArtifactChunk("count", twoChunkDigest, 0, 2, "", firstBytes);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(countMismatch), twoChunkDigest)).code());

            RepositoryArtifactChunk duplicateA = new RepositoryArtifactChunk("dup", twoChunkDigest, 0, 2, "", firstBytes);
            RepositoryArtifactChunk duplicateB = new RepositoryArtifactChunk("dup", twoChunkDigest, 0, 2, "", secondBytes);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(duplicateA, duplicateB), twoChunkDigest)).code());

            RepositoryArtifactChunk missingSequenceA = new RepositoryArtifactChunk("missing", twoChunkDigest, 0, 3, "", firstBytes);
            RepositoryArtifactChunk missingSequenceB = new RepositoryArtifactChunk("missing", twoChunkDigest, 2, 3, "", secondBytes);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(missingSequenceA, missingSequenceB), twoChunkDigest)).code());

            RepositoryArtifactDigest md5Digest = new RepositoryArtifactDigest(digest.coordinate(), "MD5", digest.value(), digest.sizeBytes());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(new RepositoryArtifactChunk("md5", md5Digest,
                                    0, 1, "", new byte[0])), md5Digest)).code());

            RepositoryArtifactDigest malformedDigest = new RepositoryArtifactDigest(digest.coordinate(), "SHA-256", "not-hex", 0L);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(new RepositoryArtifactChunk("bad-digest", malformedDigest,
                                    0, 1, "", new byte[0])), malformedDigest)).code());

            RepositoryArtifactChunk malformedChunkDigest = new RepositoryArtifactChunk("chunk-digest", digest, 0, 1,
                    "not-hex", new byte[0]);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(malformedChunkDigest), digest)).code());

            RepositoryArtifactDigest otherDigest = new RepositoryArtifactDigest(
                    org.j7m.middleware.application.api.ApplicationCoordinate.parse("org.j7m.test:other:1.0.0"),
                    digest.algorithm(), digest.value(), digest.sizeBytes());
            RepositoryArtifactChunk otherIdentity = new RepositoryArtifactChunk("identity", otherDigest, 0, 1, "", new byte[0]);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(otherIdentity), digest)).code());

            RepositoryArtifactChunk transferA = new RepositoryArtifactChunk("transfer-a", twoChunkDigest, 0, 2, "", firstBytes);
            RepositoryArtifactChunk transferB = new RepositoryArtifactChunk("transfer-b", twoChunkDigest, 1, 2, "", secondBytes);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.assembleChunks(java.util.List.of(transferA, transferB), twoChunkDigest)).code());

            java.util.List<RepositoryArtifactChunk> nullFirst = new java.util.ArrayList<>();
            nullFirst.add(null);
            nullFirst.add(transferB);
            org.j7m.middleware.application.api.replication.RepositoryReplicationException nullFirstFailure = assertThrows(
                    org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                    () -> service.assembleChunks(nullFirst, twoChunkDigest));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, nullFirstFailure.code());
            assertFalse(nullFirstFailure.getMessage().contains("NullPointerException"));

            java.util.List<RepositoryArtifactChunk> nullElement = new java.util.ArrayList<>();
            nullElement.add(transferA);
            nullElement.add(null);
            org.j7m.middleware.application.api.replication.RepositoryReplicationException nullElementFailure = assertThrows(
                    org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                    () -> service.assembleChunks(nullElement, twoChunkDigest));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, nullElementFailure.code());
            assertFalse(nullElementFailure.getMessage().contains("NullPointerException"));

            assertThrows(IllegalArgumentException.class, () -> new RepositoryArtifactChunk("bad", digest, -1, 1, "", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RepositoryArtifactChunk("bad", digest, 1, 1, "", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RepositoryArtifactChunk("bad", digest, 2, 1, "", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RepositoryArtifactChunk("bad", digest, 0, 0, "", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RepositoryArtifactChunk("bad", digest, 0, -1, "", new byte[0]));
        }
    }

    @Test
    void statusRetainsBoundedEvidence() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src3"), "status", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source3"), bundle);
        LocalApplicationRepository target = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("target4"), bundle);
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(this.temporaryDirectory.resolve("bounded"), source, target)) {
            for (int index = 0; index < 300; index++) {
                assertTrue(service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())).success());
            }
            assertTrue(service.status().evidence().size() <= 256);
        }
    }

    @Test
    void auditReplicationPagesBeyondRepositoryFirstPage() throws Exception {
        LocalApplicationRepository source = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-source"));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-target"));
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.test:audit:1.0.0");
        for (int index = 0; index < 260; index++) {
            source.demote(coordinate, "env-" + index);
        }
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("audit-events"), Long.MAX_VALUE, 8, 10, 10, 10, 300, 600,
                Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(List.of(), false, false, false, true)).success());
        }

        Path replicated = target.repositoryRoot().resolve("replicated-audit").resolve("b-repository-events.jsonl");
        assertEquals(260, Files.readAllLines(replicated, StandardCharsets.UTF_8).size());
    }

    @Test
    void auditReplicationDoesNotPrecheckBeyondConfiguredBatch() throws Exception {
        LocalApplicationRepository source = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-bounded-source"));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-bounded-target"));
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.test:audit-bounded:1.0.0");
        for (int index = 0; index < 260; index++) {
            source.demote(coordinate, "env-" + index);
        }
        corruptAuditResult(source.repositoryRoot().resolve("audit").resolve("repository-events.jsonl"), 259);
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("audit-bounded-events"), Long.MAX_VALUE, 8, 10, 10, 10, 10, 10,
                Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.replicate(new RepositoryReplicationPlan(List.of(), false, false, false, true))).code());
        }
    }

    @Test
    void auditReplicationResumesBoundedStagedPage() throws Exception {
        LocalApplicationRepository source = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-resume-source"));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-resume-target"));
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.test:audit-resume:1.0.0");
        for (int index = 0; index < 12; index++) {
            source.demote(coordinate, "env-" + index);
        }
        Path eventRoot = this.temporaryDirectory.resolve("audit-resume-events");
        RepositoryReplicationConfig firstPageOnly = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, eventRoot, Long.MAX_VALUE, 8, 10, 10, 10, 20, 5, Clock.systemUTC());
        Path replicated = target.repositoryRoot().resolve("replicated-audit").resolve("b-repository-events.jsonl");
        Path staged = replicated.resolveSibling(replicated.getFileName() + ".part");
        Path checkpoint = replicated.resolveSibling(replicated.getFileName() + ".checkpoint.json");
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(firstPageOnly)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.replicate(new RepositoryReplicationPlan(List.of(), false, false, false, true))).code());
        }
        assertFalse(Files.exists(replicated));
        assertTrue(Files.exists(staged));
        assertTrue(Files.exists(checkpoint));
        assertEquals(5, Files.readAllLines(staged, StandardCharsets.UTF_8).size());
        String checkpointText = Files.readString(checkpoint, StandardCharsets.UTF_8);
        assertTrue(checkpointText.contains("\"replicated\":\"5\""));

        RepositoryReplicationConfig resumable = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, eventRoot, Long.MAX_VALUE, 8, 10, 10, 10, 20, 20, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(resumable)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(List.of(), false, false, false, true)).success());
        }
        assertEquals(12, Files.readAllLines(replicated, StandardCharsets.UTF_8).size());
        assertFalse(Files.exists(staged));
        assertFalse(Files.exists(checkpoint));
    }

    @Test
    void auditReplicationRejectsSameSizeStagedCorruptionBeforeResume() throws Exception {
        LocalApplicationRepository source = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-stage-source"));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-stage-target"));
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.test:audit-stage:1.0.0");
        for (int index = 0; index < 12; index++) {
            source.demote(coordinate, "env-" + index);
        }
        Path eventRoot = this.temporaryDirectory.resolve("audit-stage-events");
        RepositoryReplicationConfig firstPageOnly = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, eventRoot, Long.MAX_VALUE, 8, 10, 10, 10, 20, 5, Clock.systemUTC());
        Path replicated = target.repositoryRoot().resolve("replicated-audit").resolve("b-repository-events.jsonl");
        Path staged = replicated.resolveSibling(replicated.getFileName() + ".part");
        Path checkpoint = replicated.resolveSibling(replicated.getFileName() + ".checkpoint.json");
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(firstPageOnly)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.replicate(new RepositoryReplicationPlan(List.of(), false, false, false, true))).code());
        }
        corruptCurrentHashWithoutChangingSize(staged);
        assertTrue(Files.exists(checkpoint));

        RepositoryReplicationConfig resumable = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, eventRoot, Long.MAX_VALUE, 8, 10, 10, 10, 20, 20, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(resumable)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(List.of(), false, false, false, true)).success());
        }
        assertEquals(12, Files.readAllLines(replicated, StandardCharsets.UTF_8).size());
        assertFalse(Files.exists(staged));
        assertFalse(Files.exists(checkpoint));
    }

    @Test
    void auditReplicationDetectsLaterPageHashMismatchWithinBoundedPaging() throws Exception {
        LocalApplicationRepository source = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-corrupt-source"));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("audit-corrupt-target"));
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.test:audit-corrupt:1.0.0");
        for (int index = 0; index < 260; index++) {
            source.demote(coordinate, "env-" + index);
        }
        corruptAuditResult(source.repositoryRoot().resolve("audit").resolve("repository-events.jsonl"), 258);
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("audit-corrupt-events"), Long.MAX_VALUE, 8, 10, 10, 10, 300, 300,
                Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            org.j7m.middleware.application.api.replication.RepositoryReplicationResult result =
                    service.replicate(new RepositoryReplicationPlan(List.of(), false, false, false, true));

            assertFalse(result.success());
            assertEquals("AUDIT_CHAIN_HASH_MISMATCH", result.conflicts().getFirst().conflictType());
        }
    }

    @Test
    void repositoryIndexScanCountsIrrelevantDirectories() throws Exception {
        LocalApplicationRepository source = new LocalApplicationRepository(this.temporaryDirectory.resolve("scan-source"));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("scan-target"));
        Path artifacts = source.repositoryRoot().resolve("artifacts").resolve("irrelevant");
        for (int index = 0; index < 4; index++) {
            Files.createDirectories(artifacts.resolve("noise-" + index));
        }
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("scan-events"), Long.MAX_VALUE, 8, 10, 10, 10, 3, 10,
                Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED,
                    assertThrows(org.j7m.middleware.application.api.replication.RepositoryReplicationException.class,
                            () -> service.replicate(new RepositoryReplicationPlan(List.of(), true, false, false, false))).code());
        }
    }

    private static void corruptAuditResult(final Path audit, final int lineIndex) throws Exception {
        List<String> lines = Files.readAllLines(audit, StandardCharsets.UTF_8);
        String line = lines.get(lineIndex);
        int resultStart = line.indexOf("\"result\":\"");
        int valueStart = resultStart + "\"result\":\"".length();
        int valueEnd = line.indexOf('"', valueStart);
        lines.set(lineIndex, line.substring(0, valueStart) + "tampered" + line.substring(valueEnd));
        Files.write(audit, lines, StandardCharsets.UTF_8);
    }

    private static void corruptCurrentHashWithoutChangingSize(final Path staged) throws Exception {
        String text = Files.readString(staged, StandardCharsets.UTF_8);
        int marker = text.indexOf("\"currentHash\":\"");
        int offset = marker + "\"currentHash\":\"".length();
        char current = text.charAt(offset);
        char replacement = current == 'a' ? 'b' : 'a';
        Files.writeString(staged, text.substring(0, offset) + replacement + text.substring(offset + 1),
                StandardCharsets.UTF_8);
    }

    private static byte[] concat(final byte[] left, final byte[] right) {
        byte[] output = new byte[left.length + right.length];
        System.arraycopy(left, 0, output, 0, left.length);
        System.arraycopy(right, 0, output, left.length, right.length);
        return output;
    }

    private static String sha256(final byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                int unsigned = value & 0xff;
                if (unsigned < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(unsigned));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
