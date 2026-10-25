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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.replication.RepositoryReplicationException;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryIndexReplicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesDigestAddressedIndexSnapshot() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "index", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(this.temporaryDirectory, source, target)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(java.util.List.of(bundle.coordinate()), true, false, false, false)).success());
        }
        String snapshot = Files.readString(target.repositoryRoot().resolve("replication-index").resolve("snapshot.json"));
        assertTrue(snapshot.contains("digest"));
        assertTrue(snapshot.contains("entries"));
        assertTrue(snapshot.contains(bundle.coordinate().toString()));
        assertTrue(snapshot.contains(bundle.sha256()));
        assertTrue(snapshot.contains("indexEntries"));
    }

    @Test
    void snapshotDigestChangesAndRecoveryReplaysIndexState() throws Exception {
        ApplicationBundle first = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-a"), "index-a", "1.0.0");
        ApplicationBundle second = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-b"), "index-b", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), first);
        source.publish(second, new org.j7m.middleware.application.api.RepositoryPublishOptions("test", false));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        Path events = this.temporaryDirectory.resolve("events");
        String firstSnapshot;
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(events, source, target)) {
            assertTrue(service.replicate(new RepositoryReplicationPlan(java.util.List.of(first.coordinate()), true, false, false, false)).success());
            firstSnapshot = Files.readString(target.repositoryRoot().resolve("replication-index").resolve("snapshot.json"));
            assertTrue(service.replicate(new RepositoryReplicationPlan(java.util.List.of(second.coordinate()), true, false, false, false)).success());
        }
        String secondSnapshot = Files.readString(target.repositoryRoot().resolve("replication-index").resolve("snapshot.json"));
        assertNotEquals(firstSnapshot, secondSnapshot);
        try (LocalRepositoryReplicationService recovered = RepositoryReplicationTestSupport.service(events, source, target)) {
            assertTrue(recovered.status().evidence().stream().anyMatch(evidence ->
                    evidence.eventType().name().equals("INDEX_SNAPSHOT_PUBLISHED")
                            && evidence.metadata().getOrDefault("coordinates", "").contains(first.coordinate().toString())
                            && evidence.metadata().getOrDefault("coordinates", "").contains(second.coordinate().toString())));
        }
    }

    @Test
    void malformedIndexDeltaNumericFieldFailsRecovery() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "bad-delta", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        Path badEvents = this.temporaryDirectory.resolve("bad-events");
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(badEvents.resolve("events")))) {
            log.append(new LogStreamId("repository-replication-events"), List.of(new LogRecord(
                    LogRecordHeader.data(Map.of()),
                    """
                    schemaVersion=MQ==
                    type=SU5ERVhfREVMVEFfUFVCTElTSEVE
                    previousSequence=bm90LWEtbnVtYmVy
                    nextSequence=MQ==
                    operation=QURE
                    coordinate=b3JnLmo3bS5yZXBsaWNhdGlvbjpiYWQtZGVsdGExLjAuMA==
                    digest=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
                    sizeBytes=MQ==
                    """.getBytes(StandardCharsets.UTF_8))),
                    AppendOptions.defaults());
        }
        RepositoryReplicationException exception = org.junit.jupiter.api.Assertions.assertThrows(RepositoryReplicationException.class,
                () -> RepositoryReplicationTestSupport.service(badEvents, source, target));
        org.junit.jupiter.api.Assertions.assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
    }
}
