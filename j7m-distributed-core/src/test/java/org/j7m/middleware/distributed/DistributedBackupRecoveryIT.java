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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.j7m.middleware.consensus.ConsensusBackup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributedBackupRecoveryIT {
    @TempDir Path directory;
    @Test void threeJvmBackupRestoresPreparedLocksEventCursorAndApplicationDedupe() throws Exception {
        try (var cluster = new AcceptanceCluster(directory.resolve("map"), "map", 3)) {
            var map = cluster.map(); var first = map.execute("BATCH", "first", List.of("base", "0", "one"), AcceptanceCluster.deadline()); assertEquals("OK", first.status());
            var events = new EventSubscriptions(map); var view = events.snapshotAndTail(AcceptanceCluster.deadline()); assertEquals("OK", events.subscribe("subscribe", "consumer", "", view.cursor(), 86400000, AcceptanceCluster.deadline()).status());
            map.execute("PREPARE", "prepare", List.of("transaction", "WRITE", "locked", "prepared-value"), AcceptanceCluster.deadline());
            var before = events.poll("consumer", 10, AcceptanceCluster.deadline()); assertEquals(1, before.events().size()); events.ack("ack", "consumer", before.through(), 86400000, AcceptanceCluster.deadline());
            map.execute("BATCH", "tail", List.of("tail", "0", "two"), AcceptanceCluster.deadline()); var unacknowledged = events.poll("consumer", 10, AcceptanceCluster.deadline()).events().getFirst().cursor();
            var metadata = cluster.backup(directory.resolve("backup-map")); assertEquals(3, metadata.members().size()); assertTrue(metadata.requestIdentities() >= 5);
            cluster.restore(directory.resolve("backup-map"), metadata.index()); map = cluster.map(); events = new EventSubscriptions(map);
            assertEquals(List.of("transaction"), map.prepared(AcceptanceCluster.deadline())); assertEquals("PREPARED", map.transaction("transaction", AcceptanceCluster.deadline()).status());
            assertEquals(first, map.execute("BATCH", "first", List.of("base", "0", "one"), AcceptanceCluster.deadline())); assertEquals("one", map.read("base", AcceptanceCluster.deadline()).value());
            assertEquals("CONFLICT", map.execute("BATCH", "blocked", List.of("locked", "0", "bad"), AcceptanceCluster.deadline()).status());
            var resumed = events.poll("consumer", 10, AcceptanceCluster.deadline()); assertEquals(unacknowledged, resumed.events().getFirst().cursor());
            assertEquals("COMMITTED", map.execute("COMMIT", "decision", List.of("transaction", "1"), AcceptanceCluster.deadline()).status()); assertEquals("prepared-value", map.read("locked", AcceptanceCluster.deadline()).value());
            cluster.restartAll(); assertEquals("prepared-value", cluster.map().read("locked", AcceptanceCluster.deadline()).value()); assertTrue(cluster.map().prepared(AcceptanceCluster.deadline()).isEmpty());
        }
    }
    @Test void fiveJvmBackupRestoresQueueMembershipDeliveryFenceAndAcknowledgedIdentity() throws Exception {
        try (var cluster = new AcceptanceCluster(directory.resolve("queue"), "queue", 5)) {
            var queue = cluster.queue(); assertEquals("OK\t1", queue.join("join", "workers", "worker", 86400000, AcceptanceCluster.deadline()));
            String first = queue.send("send-first", "q", "first", new byte[]{1}, 3, AcceptanceCluster.deadline()); String delivery = queue.claim("claim-first", "q", "workers", "worker", 1, 86400000, AcceptanceCluster.deadline());
            assertTrue(delivery.startsWith("MESSAGE\tfirst\t")); assertEquals("ACKED", queue.ack("ack-first", "q", "workers", "worker", 1, "first", Long.parseLong(delivery.split("\t")[4]), AcceptanceCluster.deadline()));
            queue.send("send-second", "q", "second", new byte[]{2}, 3, AcceptanceCluster.deadline()); String live = queue.claim("claim-second", "q", "workers", "worker", 1, 86400000, AcceptanceCluster.deadline());
            var metadata = cluster.backup(directory.resolve("backup-queue")); assertEquals(5, metadata.members().size()); assertEquals(metadata, ConsensusBackup.inspect(directory.resolve("backup-queue"), AcceptanceNodeProcess.schema("queue")));
            cluster.restore(directory.resolve("backup-queue"), metadata.index()); queue = cluster.queue();
            assertEquals(first, queue.send("send-first", "q", "first", new byte[]{1}, 3, AcceptanceCluster.deadline())); assertEquals("EMPTY", queue.claim("blocked-delivery", "q", "workers", "worker", 1, 86400000, AcceptanceCluster.deadline()));
            assertEquals("ACKED", queue.ack("ack-second", "q", "workers", "worker", 1, "second", Long.parseLong(live.split("\t")[4]), AcceptanceCluster.deadline()));
            cluster.restartAll(); assertEquals("EMPTY", cluster.queue().claim("no-redelivery", "q", "workers", "worker", 1, 86400000, AcceptanceCluster.deadline()));
        }
    }
}
