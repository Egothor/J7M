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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Native three-voter resource quorum plus two independent application JVMs.
 */
class ManagedOwnershipProcessIT {
    @TempDir Path directory;
    @Test void renewalNetworkLossAndWorkerKillCannotLetOldOwnerOverwriteNewHigherFence() throws Exception {
        var fixture = new ManagedEventSubscriptionProcessIT(); fixture.directory = directory;
        try (var backends = fixture.new Backends(false); var blackhole = new ServerSocket(0);
                var old = fixture.new Child("old-owner", OwnershipWorkerProcess.class.getName(), backends.ports(), Integer.toString(blackhole.getLocalPort()));
                var next = fixture.new Child("next-owner", OwnershipWorkerProcess.class.getName(), backends.ports(), Integer.toString(blackhole.getLocalPort()))) {
            assertEquals(3, backends.nodes.size());
            assertEquals("STARTED", old.command("START")); String entered = old.read(); assertTrue(entered.startsWith("ENTERED "), entered);
            long initialFence = Long.parseLong(entered.split(" ")[1]);
            assertEquals("BLOCKED", old.command("BLOCK_RENEW"));
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!old.command("STATUS").equals("STATUS true 1")) {
                assertTrue(System.nanoTime() < limit, "Renewal timeout must revoke while actual USER remains held"); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            String namespace = OwnershipData.hash("inventory", "alice", OwnershipData.hash(String.class.getName(), "input"));
            String leaseKey = "ownership.lease." + namespace;
            var previous = backends.source.lease(leaseKey, Instant.now().plusSeconds(2)); assertNotNull(previous);
            assertTrue(previous.fence() >= initialFence);
            limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (backends.source.lease(leaseKey, Instant.now().plusSeconds(2)) != null) {
                assertTrue(System.nanoTime() < limit, "Native committed time must expire the abandoned lease"); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            assertEquals("STARTED", next.command("START")); String replacement = next.read(); assertTrue(replacement.startsWith("ENTERED "), replacement);
            assertTrue(Long.parseLong(replacement.split(" ")[1]) > previous.fence());
            assertTrue(next.command("WRITE new-effect good").startsWith("WROTE "));
            assertTrue(old.command("WRITE old-effect forbidden").startsWith("DENIED "));
            String target = "ownership.data." + namespace + "." + OwnershipData.hash("key");
            assertEquals("CONFLICT", backends.source.execute("FENCED", "raw-old-fence", List.of(leaseKey,
                    Long.toString(previous.fence()), Long.toString(previous.leadershipEpoch()), target, "forbidden"), Instant.now().plusSeconds(2)).status());
            old.kill();
            assertTrue(next.command("WRITE after-kill still-good").startsWith("WROTE "));
            assertEquals(OwnershipData.encode("still-good"), backends.source.read(target, Instant.now().plusSeconds(2)).value());
            assertEquals("FINISHED 0", next.command("FINISH"));
        }
    }
}
