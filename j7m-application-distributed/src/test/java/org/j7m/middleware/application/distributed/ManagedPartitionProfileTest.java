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

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.consensus.NetworkConsensusConfig;
import org.j7m.middleware.distributed.PartitionServer;
import org.j7m.middleware.distributed.ReplicatedResourceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedPartitionProfileTest {
    @TempDir Path directory;
    private NetworkConsensusConfig consensus(int frame, int snapshot) {
        return new NetworkConsensusConfig("a", Map.of("a", new InetSocketAddress("127.0.0.1", 12345)), Set.of("a"),
                directory, new byte[32], Duration.ofMillis(500), Duration.ofMillis(20), frame, snapshot, null, "group");
    }
    private ManagedPartitionProfile profile(NetworkConsensusConfig config, String scope, int capacity,
            Map<String, PartitionServer.Binding> bindings, byte[] authority) {
        return new ManagedPartitionProfile(config, "partition", 1, scope, capacity,
                new InetSocketAddress("127.0.0.1", 0), bindings, authority,
                () -> new ReplicatedResourceClient.ClockMeasurement(0, 0, 0, true), null);
    }
    @Test void explicitNativeBoundsAndAuthenticatedFixedScopeAreValidatedWithoutOpeningStorage() {
        var bindings = Map.of("client", new PartitionServer.Binding("tenant", new byte[32], false, false));
        byte[] key = new byte[32];
        var profile = profile(consensus(512 * 1024, 128 * 1024 * 1024), "tenant", 10000, bindings, key);
        key[0] = 9; byte[] copy = profile.authorityKey(); copy[0] = 8;
        assertEquals(0, profile.authorityKey()[0]);
        assertEquals("partition", profile.partition()); assertEquals(1, profile.initialEpoch());
        assertEquals("tenant", profile.resourceScope()); assertEquals(10000, profile.maximumRequests());
        assertEquals(Set.of("a"), profile.consensus().initialMembers());
        assertTrue(profile.serviceAddress().getAddress().isLoopbackAddress());
        assertEquals(bindings, profile.principals()); assertNull(profile.serviceTls());
        assertTrue(profile.clock().sample().monotonicStable());
        assertEquals("ManagedPartitionProfile[trusted,redacted]", profile.toString());
        assertThrows(IllegalArgumentException.class, () -> profile(consensus(512 * 1024, 128 * 1024 * 1024), "other", 1, bindings, key));
        assertThrows(IllegalArgumentException.class, () -> profile(consensus(4096, 128 * 1024 * 1024), "tenant", 1, bindings, key));
        assertThrows(IllegalArgumentException.class, () -> profile(consensus(512 * 1024, 1024 * 1024), "tenant", 1, bindings, key));
        assertThrows(IllegalArgumentException.class, () -> profile(consensus(512 * 1024, 128 * 1024 * 1024), "tenant", 0, bindings, key));
        assertThrows(IllegalArgumentException.class, () -> profile(consensus(512 * 1024, 128 * 1024 * 1024), "tenant", 1, Map.of(), key));
        assertThrows(IllegalArgumentException.class, () -> profile(consensus(512 * 1024, 128 * 1024 * 1024), "tenant", 1,
                Map.of("first", new PartitionServer.Binding("tenant", new byte[32], false, false),
                        "second", new PartitionServer.Binding("tenant", new byte[32], true, true)), key));
        assertThrows(IllegalArgumentException.class, () -> profile(consensus(512 * 1024, 128 * 1024 * 1024), "tenant", 1, bindings, new byte[31]));
    }
}
