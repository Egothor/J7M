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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.replication.RepositoryRepairPlan;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReconciliationRepairTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void plansMissingArtifactRepairAndReverifies() throws Exception {
        ApplicationBundle bundle = RepositoryReplicationTestSupport.bundle(this.temporaryDirectory.resolve("src"), "repair", "1.0.0");
        LocalApplicationRepository source = RepositoryReplicationTestSupport.published(this.temporaryDirectory.resolve("source"), bundle);
        LocalApplicationRepository target = new LocalApplicationRepository(this.temporaryDirectory.resolve("target"));
        try (LocalRepositoryReplicationService service = RepositoryReplicationTestSupport.service(this.temporaryDirectory, source, target)) {
            assertTrue(service.reconcile().missingArtifacts().contains(bundle.coordinate().toString()));
            assertTrue(service.repair(new RepositoryRepairPlan(java.util.List.of(bundle.coordinate()), true, true)).success());
            assertEquals(bundle.sha256(), target.fetch(bundle.coordinate()).sha256());
            java.nio.file.Files.writeString(target.fetch(bundle.coordinate()).path(), "corrupted-local-artifact");
            assertTrue(service.reconcile().corruptedArtifacts().contains(bundle.coordinate().toString()));
            assertTrue(service.repair(new RepositoryRepairPlan(java.util.List.of(bundle.coordinate()), false, false)).success());
            org.junit.jupiter.api.Assertions.assertArrayEquals(java.nio.file.Files.readAllBytes(bundle.path()),
                    java.nio.file.Files.readAllBytes(target.fetch(bundle.coordinate()).path()));
        }
    }
}
