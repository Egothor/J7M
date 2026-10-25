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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.replication.RepositoryPromotionRecord;
import org.j7m.middleware.application.api.replication.RepositoryReplicaId;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.api.replication.RepositoryRollbackRecord;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SignaturePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReplicationVerificationPolicyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void strictPolicyRejectsInvalidSignatureDuringReplication() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "strict", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        try (LocalRepositoryReplicationService service = strictService(source, target, SignaturePolicy.STRICT_PRODUCTION)) {
            var result = service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate()));
            assertFalse(result.success());
            assertTrue(result.conflicts().stream().anyMatch(conflict -> conflict.conflictType().equals("ARTIFACT_VERIFICATION_REJECTED")));
        }
    }

    @Test
    void regulatedPolicyRejectsMissingSbomOrProvenance() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src2"), "regulated", "1.0.0");
        ApplicationBundle missingSbom = withoutEntry(bundle, J7mBundleReader.SBOM_ENTRY, this.temporaryDirectory.resolve("missing-sbom.j7m"));
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source2"), bundle);
        Files.copy(missingSbom.path(), source.fetch(bundle.coordinate()).path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target2"));
        try (LocalRepositoryReplicationService service = strictService(source, target, SignaturePolicy.STRICT_REGULATED)) {
            assertFalse(service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())).success());
        }

        ApplicationBundle missingProvenance = withoutEntry(bundle, J7mBundleReader.PROVENANCE_ENTRY, this.temporaryDirectory.resolve("missing-provenance.j7m"));
        LocalApplicationRepository source2 = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source3"), bundle);
        Files.copy(missingProvenance.path(), source2.fetch(bundle.coordinate()).path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        LocalApplicationRepository target2 = new LocalApplicationRepository(this.temporaryDirectory.resolve("target3"));
        try (LocalRepositoryReplicationService service = strictService(source2, target2, SignaturePolicy.STRICT_REGULATED)) {
            assertFalse(service.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())).success());
        }
    }

    @Test
    void promotionAndRollbackRejectTargetsNotVerifiedForEffectivePolicy() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src4"), "policy-gate", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source4"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target4"));
        try (LocalRepositoryReplicationService development = RepositoryReplicationTestSupport.service(this.temporaryDirectory.resolve("dev"), source, target)) {
            assertTrue(development.replicate(RepositoryReplicationPlan.artifact(bundle.coordinate())).success());
        }
        try (LocalRepositoryReplicationService strict = strictService(source, target, SignaturePolicy.STRICT_PRODUCTION)) {
            assertFalse(strict.replicatePromotion(new RepositoryPromotionRecord("prod", bundle.coordinate(), "PROMOTED", bundle.sha256())).success());
            assertFalse(strict.replicateRollback(new RepositoryRollbackRecord("prod", bundle.coordinate(), bundle.coordinate(),
                    "rollback", java.time.Instant.EPOCH)).success());
        }
    }

    private LocalRepositoryReplicationService strictService(
            final LocalApplicationRepository source,
            final LocalApplicationRepository target,
            final SignaturePolicy policy) {
        return new LocalRepositoryReplicationService(new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events-" + policy.name()), Long.MAX_VALUE, 1024, 100, 100, 100, 100, 100,
                policy, Clock.systemUTC()));
    }

    private static ApplicationBundle withoutEntry(
            final ApplicationBundle bundle,
            final String excluded,
            final Path output) throws Exception {
        try (ZipFile input = new ZipFile(bundle.path().toFile());
                ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            java.util.Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals(excluded)) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0L);
                zip.putNextEntry(copy);
                zip.write(input.getInputStream(entry).readAllBytes());
                zip.closeEntry();
            }
        }
        return new ApplicationBundle(output, bundle.coordinate(), new J7mBundleReader().sha256(output));
    }
}
