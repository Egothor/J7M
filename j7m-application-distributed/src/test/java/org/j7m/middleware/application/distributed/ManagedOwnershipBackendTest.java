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
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

class ManagedOwnershipBackendTest {
    @Test void groupUsesTheSameNativeResourceForLeaseFenceAndReadAndInvalidTopologyIsRejected() throws Exception {
        var endpoint = new OwnershipEndpoint(); var backend = ManagedOwnershipBackend.group(endpoint); var session = backend.open(Instant.now().plusSeconds(1));
        var acquired = session.execute("ACQUIRE", "claim", List.of("lease", "owner", "4000", "0"), Instant.now().plusSeconds(1));
        assertEquals("OK", acquired.status()); var lease = session.lease("lease", Instant.now().plusSeconds(1));
        assertEquals(Long.parseLong(acquired.value()), lease.fence()); assertEquals(0, session.partitionEpoch());
        assertEquals("OK", session.execute("FENCED", "write", List.of("lease", Long.toString(lease.fence()), Long.toString(lease.leadershipEpoch()), "key", "value"), Instant.now().plusSeconds(1)).status());
        assertEquals("value", session.read("key", Instant.now().plusSeconds(1)).value()); assertEquals(1, endpoint.effects.get());
        assertThrows(NullPointerException.class, () -> ManagedOwnershipBackend.group(null));
        var topology = new PartitionAdminClient(new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", 9)), "admin", new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> ManagedOwnershipBackend.partition("p", "scope", topology, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ManagedOwnershipBackend.partition("p", "invalid scope", topology, Map.of()));
        assertEquals("ManagedOwnershipBackend[host-owned]", backend.toString());
    }
}
