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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.replication.RepositoryPromotionRecord;
import org.j7m.middleware.application.api.replication.RepositoryReplicationException;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.api.replication.RepositoryRollbackRecord;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryPromotionRollbackReplicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void replicatesPromotionDemotionRollbackAndRejectsMissingPromotionTarget() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "promoted", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(this.temporaryDirectory, source, target)) {
            assertFalse(service.replicatePromotion(new RepositoryPromotionRecord("prod", bundle.coordinate(), "PROMOTED", bundle.sha256())).success());
            assertTrue(service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())).success());
            assertTrue(service.replicatePromotion(new RepositoryPromotionRecord("prod", bundle.coordinate(), "PROMOTED", bundle.sha256())).success());
            assertTrue(service.replicatePromotion(new RepositoryPromotionRecord("prod", bundle.coordinate(), "DEMOTED", bundle.sha256())).success());
            assertTrue(service.replicateRollback(new RepositoryRollbackRecord("prod", bundle.coordinate(), bundle.coordinate(), "safe rollback", Instant.EPOCH)).success());
        }
        assertTrue(Files.exists(target.repositoryRoot().resolve("promotions").resolve("prod.json")));
        assertTrue(Files.exists(target.repositoryRoot().resolve("rollback").resolve("prod.json")));
    }

    @Test
    void replicationPlanHonorsPromotionAndRollbackFlags() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-plan"), "planned", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-plan"), bundle);
        source.promote(bundle.coordinate(), "prod");
        Path rollback = source.repositoryRoot().resolve("rollback").resolve("prod.json");
        Files.createDirectories(rollback.getParent());
        Files.writeString(rollback, "{\"schemaVersion\":\"1\",\"environment\":\"prod\",\"from\":\"" + bundle.coordinate()
                + "\",\"to\":\"" + bundle.coordinate() + "\",\"reason\":\"planned\"}\n");

        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-plan"));
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(this.temporaryDirectory.resolve("plan-false"), source, target)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(java.util.List.of(bundle.coordinate()), false, false, false, false)).success());
        }
        assertFalse(Files.exists(target.repositoryRoot().resolve("promotions").resolve("prod.json")));
        assertFalse(Files.exists(target.repositoryRoot().resolve("rollback").resolve("prod.json")));

        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(this.temporaryDirectory.resolve("plan-true"), source, target)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(java.util.List.of(), false, true, true, false)).success());
        }
        assertTrue(Files.exists(target.repositoryRoot().resolve("promotions").resolve("prod.json")));
        assertTrue(Files.exists(target.repositoryRoot().resolve("rollback").resolve("prod.json")));
    }

    @Test
    void promotionMetadataParsingIsStrictBoundedAndRedacted() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-metadata"), "metadata", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-metadata"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-metadata"));
        Path promotion = source.repositoryRoot().resolve("promotions").resolve("prod.json");
        Files.createDirectories(promotion.getParent());

        assertPromotionMetadataRejected(source, target, promotion, "{\"schemaVersion\":\"1\",\"coordinate\":\""
                + bundle.coordinate() + "\",\"coordinate\":\"" + bundle.coordinate() + "\",\"state\":\"PROMOTED\"}\n");
        assertPromotionMetadataRejected(source, target, promotion, "[]\n");
        assertPromotionMetadataRejected(source, target, promotion, "{\"schemaVersion\":\"1\",\"coordinate\":{\"value\":\""
                + bundle.coordinate() + "\"},\"state\":\"PROMOTED\"}\n");
        assertPromotionMetadataRejected(source, target, promotion, "{\"schemaVersion\":\"1\",\"coordinate\":\""
                + bundle.coordinate() + "\",\"state\":\"PROMOTED\",\"secret\":\"PRIVATE\"}\n");
        assertPromotionMetadataRejected(source, target, promotion, "{\"coordinate\":\"" + bundle.coordinate()
                + "\",\"state\":\"PROMOTED\"}\n");
        assertPromotionMetadataRejected(source, target, promotion, "{\"schemaVersion\":\"2\",\"coordinate\":\""
                + bundle.coordinate() + "\",\"state\":\"PROMOTED\"}\n");
        assertPromotionMetadataRejected(source, target, promotion, "{\"schemaVersion\":\"1\",\"coordinate\":\"not-a-coordinate\","
                + "\"state\":\"PROMOTED\"}\n");
        assertPromotionMetadataRejected(source, target, promotion, "{\"schemaVersion\":\"1\",\"coordinate\":\""
                + bundle.coordinate() + "\",\"state\":\"PROMOTED\"}\n{}\n");
        Files.write(promotion, new byte[] {(byte) 0xc3, (byte) 0x28});
        RepositoryReplicationException malformedUtf8 = assertThrows(RepositoryReplicationException.class,
                () -> replicatePromotionPlan(source, target));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, malformedUtf8.code());
        assertFalse(malformedUtf8.toString().contains("PRIVATE"));
    }

    @Test
    void rollbackMetadataAllowsEmbeddedFieldLikeStringOnlyAsStringContent() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-rollback-metadata"),
                "rollback-metadata", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source-rollback-metadata"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target-rollback-metadata"));
        Path rollback = source.repositoryRoot().resolve("rollback").resolve("prod.json");
        Files.createDirectories(rollback.getParent());
        Files.writeString(rollback, "{\"schemaVersion\":\"1\",\"environment\":\"prod\",\"from\":\"" + bundle.coordinate()
                + "\",\"to\":\"" + bundle.coordinate()
                + "\",\"reason\":\"contains \\\\\\\"currentHash\\\\\\\":\\\\\\\"not-a-field\\\\\\\" text\"}\n",
                StandardCharsets.UTF_8);

        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(
                this.temporaryDirectory.resolve("rollback-embedded"), source, target)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(java.util.List.of(bundle.coordinate()),
                    false, false, true, false)).success());
        }
    }

    private void assertPromotionMetadataRejected(
            final LocalApplicationRepository source,
            final LocalApplicationRepository target,
            final Path promotion,
            final String metadata) throws Exception {
        Files.writeString(promotion, metadata, StandardCharsets.UTF_8);
        RepositoryReplicationException exception = assertThrows(RepositoryReplicationException.class,
                () -> replicatePromotionPlan(source, target));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
        assertFalse(exception.toString().contains("PRIVATE"));
    }

    private static void replicatePromotionPlan(
            final LocalApplicationRepository source,
            final LocalApplicationRepository target) {
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(
                RepositoryReplicationConfig.defaults(source, target, source.repositoryRoot().resolve("metadata-events")))) {
            service.replicate(new RepositoryReplicationPlan(java.util.List.of(), false, true, false, false));
        }
    }
}
