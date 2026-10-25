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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.replication.RepositoryReplicaId;
import org.j7m.middleware.application.api.replication.RepositoryReplicationException;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReplicationBackpressureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsPendingTransferAndArtifactLimitsWithExactCode() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "pressure", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        RepositoryReplicationConfig config = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events"), Long.MAX_VALUE, 1024, 1, 1, 1, 1, 1, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(config)) {
            RepositoryReplicationException exception = assertThrows(RepositoryReplicationException.class,
                    () -> service.replicate(new RepositoryReplicationPlan(java.util.List.of(bundle.coordinate(), bundle.coordinate()), false, false, false, false)));
            assertEquals(MiddlewareErrorCode.OVERLOADED, exception.code());
        }
    }

    @Test
    void rejectsInFlightReconciliationIndexAndAuditLimitsWithExactCode() throws Exception {
        ApplicationBundle first = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-a"), "limit-a", "1.0.0");
        ApplicationBundle second = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src-b"), "limit-b", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source2"), first);
        source.publish(second, new org.j7m.middleware.application.api.RepositoryPublishOptions("test", false));
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target2"));

        RepositoryReplicationConfig inFlight = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events-inflight"), Long.MAX_VALUE, 1024, 10, 1, 10, 10, 10, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(inFlight)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(RepositoryReplicationException.class,
                    () -> service.replicate(new RepositoryReplicationPlan(java.util.List.of(first.coordinate(), second.coordinate()), false, false, false, false))).code());
        }

        RepositoryReplicationConfig reconciliation = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events-reconcile"), Long.MAX_VALUE, 1024, 10, 10, 1, 10, 10, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(reconciliation)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(RepositoryReplicationException.class, service::reconcile).code());
        }

        RepositoryReplicationConfig index = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events-index"), Long.MAX_VALUE, 1024, 10, 10, 10, 1, 10, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(index)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(RepositoryReplicationException.class,
                    () -> service.replicate(new RepositoryReplicationPlan(java.util.List.of(), true, false, false, false))).code());
        }

        RepositoryReplicationConfig audit = new RepositoryReplicationConfig(new RepositoryReplicaId("a"), new RepositoryReplicaId("b"),
                source, target, this.temporaryDirectory.resolve("events-audit"), Long.MAX_VALUE, 1024, 10, 10, 10, 10, 1, Clock.systemUTC());
        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(audit)) {
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(RepositoryReplicationException.class,
                    () -> service.replicate(new RepositoryReplicationPlan(java.util.List.of(), false, false, false, true))).code());
        }
    }
}
