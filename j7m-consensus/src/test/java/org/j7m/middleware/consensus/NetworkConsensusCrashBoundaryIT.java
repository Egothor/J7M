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

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Force-kills actual JVMs paused at exact storage/publication boundaries.
 */
@Timeout(60)
class NetworkConsensusCrashBoundaryIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"before-wal-force", "after-wal-force", "before-commit-force", "after-commit-force", "before-append-response"})
    void leaderCrashAtDurabilityBoundariesAndLostResponseRetryApplyExactlyOnce(String boundary) throws Exception {
        try (var cluster = new NetworkConsensusNodeIT.Cluster(directory, 3)) {
            String leader = cluster.leader();
            assertEquals("ARMED", cluster.call(leader, "ARM " + boundary));
            CompletableFuture<String> unresolved = cluster.nodes.get(leader).ask("APPEND stable-request 1");
            assertEquals(boundary, cluster.nodes.get(leader).faults.poll(10, TimeUnit.SECONDS));
            assertFalse(unresolved.isDone(), "no response escapes the armed boundary");
            cluster.kill(leader);
            String replacement = cluster.leader();
            String index = cluster.call(replacement, "APPEND stable-request 1");
            assertEquals(index, cluster.call(replacement, "APPEND stable-request 1"));
            cluster.start(leader); cluster.awaitCount(1);
            for (String id : cluster.nodes.keySet()) assertTrue(cluster.state(id).startsWith("1,1,"));
            retain(boundary, "unresolved-response;retry-index=" + index + ";replicas=3;count=1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-snapshot-force", "after-snapshot-force", "before-snapshot-publish", "after-snapshot-publish", "after-snapshot-directory-force", "before-snapshot-state-restore"})
    void snapshotInstallationCrashAtEveryPublicationBoundaryRestoresOldOrNewValidState(String boundary) throws Exception {
        try (var cluster = new NetworkConsensusNodeIT.Cluster(directory, 3)) {
            String leader = cluster.leader();
            String replica = List.of("a", "b", "c").stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow();
            cluster.kill(replica);
            List<CompletableFuture<String>> operations = new ArrayList<>();
            for (int i = 0; i < 32; i++) operations.add(cluster.nodes.get(leader).ask("APPEND snapshot" + i + " 1"));
            for (var operation : operations) operation.get(10, TimeUnit.SECONDS);
            cluster.call(leader, "SNAPSHOT");
            cluster.isolate(replica); cluster.start(replica); cluster.call(replica, "ARM " + boundary); cluster.heal();
            assertEquals(boundary, cluster.nodes.get(replica).faults.poll(10, TimeUnit.SECONDS));
            cluster.kill(replica); cluster.isolate(replica); cluster.start(replica);
            String recovered = cluster.state(replica);
            assertTrue(recovered.startsWith("0,0,") || recovered.startsWith("32,32,"), recovered);
            cluster.heal(); cluster.awaitCount(32);
            assertEquals(cluster.state(leader), cluster.state(replica));
            retain(boundary, "recovered=" + recovered + ";after-catchup=" + cluster.state(replica));
        }
    }
    private static void retain(String boundary, String evidence) throws Exception {
        Path path = Path.of("build/reports/network-consensus/crash-" + boundary + ".txt"); Files.createDirectories(path.getParent()); Files.writeString(path, evidence + "\n");
    }
}
